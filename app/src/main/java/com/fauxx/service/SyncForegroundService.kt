package com.fauxx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.fauxx.R
import com.fauxx.sync.SealedChannel
import com.fauxx.sync.data.PairedPeerRepository
import com.fauxx.sync.discovery.MulticastLockHolder
import com.fauxx.sync.discovery.NsdDiscovery
import com.fauxx.sync.pairing.PairingManager
import com.fauxx.sync.transport.SyncListener
import com.fauxx.sync.transport.TcpClient
import com.fauxx.sync.wire.SyncConstants
import com.fauxx.targeting.layer3.PersonaRotationLayer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetSocketAddress
import javax.inject.Inject

/**
 * The user-initiated encrypted LAN sync session (E13 #178). While the user has sync enabled on the
 * Sync screen, this `specialUse` foreground service holds the [MulticastLockHolder], advertises and
 * browses over [NsdDiscovery], runs the inbound [SyncListener], and feeds resolved peer addresses
 * into the [TcpClient] routing table. Torn down on disable. Never auto-started at boot.
 *
 * The type is `specialUse`, not `dataSync`: Android 15+ caps `dataSync` at 6 hours per 24-hour
 * window and kills any service that outlives it, which is what crashed users in #248/#249. It is
 * also the wrong category on its own terms, since `dataSync` describes moving the user's data
 * to and from the CLOUD, while this never leaves the local network. This mirrors the engine's move
 * to `specialUse` for the same cap (#64/#65).
 *
 * A long-lived inbound `ServerSocket` cannot run in the background on modern Android (Doze, FGS
 * background-start restrictions), so sync is a foreground session bounded to the user's presence,
 * not an always-on daemon. This mirrors the desktop's explicit `serve --lan-sync` start/stop.
 */
@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject lateinit var sealedChannel: SealedChannel
    @Inject lateinit var pairingManager: PairingManager
    @Inject lateinit var nsdDiscovery: NsdDiscovery
    @Inject lateinit var syncListener: SyncListener
    @Inject lateinit var multicastLock: MulticastLockHolder
    @Inject lateinit var tcpClient: TcpClient
    @Inject lateinit var pairedPeerRepository: PairedPeerRepository
    @Inject lateinit var personaRotationLayer: PersonaRotationLayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        isRunning = true
        if (!started) {
            started = true
            startSession()
        }
        // Never auto-restart from the background: a background FGS start would throw on modern
        // Android, and sync is only meaningful while the user is present.
        return START_NOT_STICKY
    }

    /**
     * The FGS type is NOT passed explicitly: the manifest declaration is the source of truth, which
     * is the same thing [PhantomForegroundService] does for its own `specialUse` service.
     *
     * This matters below API 34 (`specialUse` was added in Android 14). On API 29-33 the platform
     * checks that an explicitly requested type is a subset of the manifest-declared types, and an
     * older parser that does not recognize `specialUse` leaves that set empty — so passing
     * FOREGROUND_SERVICE_TYPE_SPECIAL_USE there can throw. minSdk is 26, so those devices are in
     * range. Omitting the type lets each platform version use whatever it parsed.
     */
    private fun startInForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Backstop for the platform foreground-service time limit (#248, #249).
     *
     * Those crashes were `ForegroundServiceDidNotStopInTimeException` on this service while it was
     * typed `dataSync`, which Android 15+ caps at 6 hours per 24-hour window: the system calls
     * `onTimeout`, and an app that does not stop within a few seconds is killed. The service is now
     * `specialUse`, which carries no such cap (the same reason the engine moved to it in #64/#65),
     * so this should never fire. It stays as a cheap guarantee that a capped type can never again
     * turn into a crash — if the type is ever changed back, or a future Android caps `specialUse`,
     * sync stops cleanly instead of taking the process down.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        Timber.i("LAN sync: stopping, the platform foreground-service time limit was reached")
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.i("LAN sync: stopping, the platform time limit was reached (type=$fgsType)")
        stopSelf()
    }

    private fun startSession() {
        multicastLock.acquire()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
        scope.launch {
            try {
                val pk = sealedChannel.publicKey
                val pkB64 = com.fauxx.sync.wire.PublicKeyCodec.encode(pk)
                val fingerprint = sealedChannel.fingerprint()
                val name = pairingManager.deviceName()
                nsdDiscovery.advertise(name, SyncConstants.DEFAULT_SYNC_PORT, pkB64, fingerprint)
                // Auto-adopt a received persona as the active one (#234) so paired devices converge
                // to a single coherent persona, rather than the receiver silently keeping its own.
                syncListener.start(scope, SyncConstants.DEFAULT_SYNC_PORT) { persona, _ ->
                    personaRotationLayer.adoptSyncedPersona(persona)
                }
                // Feed resolved peers that match a paired key into the routing table.
                nsdDiscovery.discoveredPeers.collectLatest { discovered ->
                    val paired = pairedPeerRepository.getAll().map { it.publicKey }.toHashSet()
                    for (peer in discovered) {
                        val key = peer.publicKey ?: continue
                        if (key !in paired) continue
                        val addr = peer.addresses.firstNotNullOfOrNull { parseAddress(it) } ?: continue
                        tcpClient.setRoute(key, addr)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "LAN sync: session startup failed")
            }
        }
    }

    private fun onNetworkChanged() {
        // Treat every resolved IP as ephemeral: drop routes, recycle the multicast lock, and
        // re-advertise/re-browse so the route table refreshes for the new L2 segment.
        scope.launch {
            runCatching {
                tcpClient.clearRoutes()
                multicastLock.release()
                multicastLock.acquire()
                nsdDiscovery.restart()
            }
        }
    }

    private fun stopSession() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        runCatching { syncListener.stop() }
        runCatching { nsdDiscovery.stop() }
        runCatching { tcpClient.clearRoutes() }
        runCatching { multicastLock.release() }
    }

    override fun onDestroy() {
        isRunning = false
        stopSession()
        scope.cancel()
        started = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = tapIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fauxx LAN sync")
            .setContentText("Discoverable for decoy persona sync on this network")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(0, "Stop", stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LAN sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active while syncing decoy personas with paired devices on your local network"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun parseAddress(hostPort: String): InetSocketAddress? {
        val idx = hostPort.lastIndexOf(':')
        if (idx <= 0) return null
        val host = hostPort.substring(0, idx)
        val port = hostPort.substring(idx + 1).toIntOrNull() ?: return null
        return try {
            InetSocketAddress(host, port).takeUnless { it.isUnresolved }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val CHANNEL_ID = "fauxx_sync"
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.fauxx.sync.action.STOP"

        /**
         * True while the sync session is foregrounded (#213). The Sync screen's toggle defaults to
         * false and was only ever mutated by the toggle itself, so re-entering the screen showed a
         * stale OFF even with a session running. [SyncViewModel] seeds its state from this flag.
         * Process-scoped: a fresh process correctly reads false.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        @androidx.annotation.VisibleForTesting
        internal fun setRunningForTest(value: Boolean) {
            isRunning = value
        }

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SyncForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

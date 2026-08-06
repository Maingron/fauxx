package com.fauxx

import com.fauxx.network.UserAgentPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI regression guard for issue #274: the bundled User-Agent pool must contain only strings a
 * real human's browser would send.
 *
 * The pool previously shipped a Googlebot-suffixed Pixel UA, `facebookexternalhit`, `bingbot`,
 * and two `HeadlessChrome` strings. Every one of those is a self-defeating tell: Fauxx's whole
 * thesis is that a decoy must be indistinguishable from a real user, and a request announcing
 * itself as a crawler or an automation binary is trivially filtered out of a broker's profile,
 * which is the opposite of the intended effect.
 *
 * The Googlebot entry was the worst of them. It contained both `Android` and `Chrome/` and none
 * of [UserAgentPool.isChromiumAndroid]'s exclusions, so it passed that filter and was eligible
 * on the WebView search path — the most visible traffic Fauxx generates.
 *
 * Reads `src/main/assets` directly as a plain JVM unit test (no AssetManager), matching the
 * other corpus audits such as [CrawlUrlsCorpusAuditTest].
 */
class UserAgentCorpusAuditTest {

    private val uaFile = File("src/main/assets/user_agents.json")
    private val listType = object : TypeToken<List<String>>() {}.type

    /**
     * Tokens that mark a UA as a bot, crawler, or headless automation build. Matched
     * case-insensitively against the whole string, so a token appended to an otherwise
     * plausible browser UA (the shape the Googlebot entry had) is still caught.
     */
    private val forbiddenTokens = listOf(
        "bot/", "bot;", "spider", "crawler", "HeadlessChrome", "PhantomJS",
        "facebookexternalhit", "Slurp", "AdsBot", "APIs-Google", "Mediapartners",
        "python-requests", "curl/", "wget", "okhttp", "Scrapy", "HeadlessFirefox",
    )

    private fun agents(): List<String> {
        assertTrue(
            "user_agents.json missing at ${uaFile.absolutePath} (cwd=${File(".").absolutePath}); " +
                "run from the app module root.",
            uaFile.exists()
        )
        return Gson().fromJson(uaFile.readText(), listType)
    }

    @Test
    fun `no bundled User-Agent advertises a bot, crawler, or headless build`() {
        val agents = agents()
        assertTrue("User-Agent pool is empty", agents.isNotEmpty())

        val violations = agents.mapIndexedNotNull { i, ua ->
            val hit = forbiddenTokens.firstOrNull { ua.contains(it, ignoreCase = true) }
            hit?.let { "[$i] contains \"$it\": $ua" }
        }

        assertEquals(
            buildString {
                append("Bundled User-Agents advertise a bot/crawler/headless client. A decoy that ")
                append("identifies itself as automation is filtered straight out of the broker ")
                append("profile, defeating the purpose of generating it. Remove these entries ")
                append("from user_agents.json (#274):\n")
                violations.forEach { appendLine("  $it") }
            },
            0,
            violations.size
        )
    }

    /**
     * The WebView path is the narrower risk: [UserAgentPool.isChromiumAndroid] gates it, and a
     * bot token appended to an Android Chrome UA slips through that gate untouched. Asserting it
     * here keeps the guard honest even if the general audit above is ever relaxed.
     */
    @Test
    fun `no Chromium-Android eligible User-Agent carries a bot token`() {
        val eligible = agents().filter { UserAgentPool.isChromiumAndroid(it) }
        assertTrue("No Chromium-Android UAs found; the WebView path would fall back", eligible.isNotEmpty())

        val violations = eligible.filter { ua ->
            forbiddenTokens.any { ua.contains(it, ignoreCase = true) }
        }
        assertEquals(
            "WebView-eligible User-Agents must never advertise a bot: $violations",
            0,
            violations.size
        )
    }

    @Test
    fun `bundled User-Agents are unique and non-blank`() {
        val agents = agents()
        val blank = agents.withIndex().filter { it.value.isBlank() }.map { "[${it.index}] blank" }
        assertEquals("Blank User-Agent entries: $blank", 0, blank.size)

        val dupes = agents.groupBy { it }.filter { it.value.size > 1 }.keys
        assertEquals("Duplicate User-Agent entries dilute the pool: $dupes", 0, dupes.size)
    }
}

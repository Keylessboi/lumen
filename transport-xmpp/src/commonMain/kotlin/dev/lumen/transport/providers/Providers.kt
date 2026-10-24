package dev.lumen.transport.providers

/**
 * Embedded provider list — M4. Build-time pinned snapshot of the XMPP
 * Providers project's Category A feed
 * (`https://data.xmpp.net/providers/v2/providers-A.json`): servers that
 * allow sign-up from inside an app (XEP-0077) and are free of charge.
 *
 * docs/providers.md §2: the list MUST be a build-time snapshot, never
 * fetched at runtime — a runtime fetch makes whoever serves it a
 * silently-trusted third party. Staleness is handled by re-running the
 * filter, not by auto-update.
 *
 * ## What changed in the 2026-10-23 snapshot
 *
 * The previous snapshot kept only CAPTCHA-free servers, because the app
 * could not show a CAPTCHA. It now can (XEP-0158, like monocles chat), so
 * every Category A server with a top client-to-server rating and no email
 * requirement is offered. CAPTCHA-free servers still sort first.
 * `jabber.fr` left Category A upstream and is dropped.
 *
 * MAM retention (docs/providers.md §5) is listed but no longer filters:
 * sync rides PEP pubsub, not MAM, so the MAM window does not bound how long
 * a device may stay offline. The node's `max_items` does.
 *
 * Only what the picker shows ships; the rest was checked at snapshot time.
 */
object Providers {

    data class Provider(
        val jid: String,
        /** 0 = suggested (no CAPTCHA, long-running), 1 = community. */
        val tier: Int,
        /** ISO country codes of the servers. */
        val locations: List<String>,
        /** Sign-up asks the user to read a CAPTCHA image. */
        val captcha: Boolean,
        /** The operator offers some way to reset a forgotten password. */
        val passwordReset: Boolean,
        /** Year the service started, from the feed's `since`. */
        val since: Int,
        /** People who can keep it running (`busFactor`); -1 = not stated. */
        val busFactor: Int,
    )

    /** Snapshot date of the upstream feed this list was filtered from. */
    const val SNAPSHOT_DATE: String = "2026-10-23"

    /**
     * Whether nodes are assumed transient until proven otherwise. Measured
     * live on 2026-08-12: some servers refuse persistence/max_items on PEP
     * nodes. The transport now applies each setting separately and walks
     * max_items down until one is accepted.
     */
    const val KNOWN_TRANSIENT_NODES: Boolean = true

    val all: List<Provider> = listOf(
        Provider("yax.im", 0, listOf("de"), captcha = false, passwordReset = false, since = 2013, busFactor = 3),
        Provider("jabber.de", 0, listOf("de"), captcha = false, passwordReset = false, since = 2001, busFactor = 3),
        Provider("xmpp.party", 0, listOf("us"), captcha = false, passwordReset = false, since = 2018, busFactor = 1),
        Provider("jabber.vg", 1, listOf("pe"), captcha = false, passwordReset = false, since = 2025, busFactor = -1),
        Provider("chat.between-us.online", 1, listOf("us"), captcha = false, passwordReset = true, since = 2024, busFactor = 1),
        Provider("lain.rocks", 1, listOf("cz"), captcha = false, passwordReset = true, since = 2025, busFactor = 2),
        Provider("hookipa.net", 1, listOf("de"), captcha = true, passwordReset = false, since = 2008, busFactor = 2),
        Provider("jabjab.de", 1, listOf("de"), captcha = true, passwordReset = true, since = 2005, busFactor = -1),
        Provider("magicbroccoli.de", 1, listOf("de"), captcha = true, passwordReset = true, since = 2014, busFactor = -1),
        Provider("chalec.org", 1, listOf("fr"), captcha = true, passwordReset = false, since = 2022, busFactor = 3),
        Provider("openim.nl", 1, listOf("de"), captcha = true, passwordReset = true, since = 2024, busFactor = -1),
        Provider("07f.de", 1, listOf("de"), captcha = true, passwordReset = true, since = 2021, busFactor = 1),
        Provider("chatterboxtown.us", 1, listOf("us"), captcha = true, passwordReset = true, since = 2020, busFactor = -1),
        Provider("og.im", 1, listOf("dk"), captcha = true, passwordReset = false, since = 2019, busFactor = 1),
        Provider("projectsegfau.lt", 1, listOf("in", "ch"), captcha = true, passwordReset = true, since = 2023, busFactor = 2),
    )

    /** Plain-language location: "Germany", "India and Switzerland". */
    fun describeLocations(codes: List<String>): String =
        codes.map { COUNTRY_NAMES[it] ?: it.uppercase() }.let { names ->
            when (names.size) {
                0 -> "Location not stated"
                1 -> names.single()
                else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
            }
        }

    private val COUNTRY_NAMES = mapOf(
        "de" to "Germany", "us" to "United States", "fr" to "France", "pe" to "Peru", "cz" to "Czechia",
        "dk" to "Denmark", "in" to "India", "ch" to "Switzerland", "nl" to "Netherlands",
    )
}

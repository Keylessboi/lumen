package dev.lumen.transport.providers

/**
 * Embedded provider list — M4. Build-time pinned snapshot from the XMPP
 * Providers project's Category A feed (`https://data.xmpp.net/providers/v2/providers-A.json`),
 * filtered by the vetting policy in `docs/providers.md`:
 *
 *   - `inBandRegistration` true (XEP-0077 signup in-app)
 *   - NO captcha (`inBandRegistrationCaptchaRequired` false)
 *   - NO email requirement (`inBandRegistrationEmailAddressRequired` false)
 *
 * docs/providers.md §2: the list MUST be a build-time snapshot, never
 * fetched at runtime — a runtime fetch makes whoever serves it a
 * silently-trusted third party. Staleness is handled by a human re-run of
 * the same filter, not by auto-update.
 *
 * The five entries below are every Category A provider that passed the
 * filter as of the snapshot date. Only `jid` ships in the binary; the
 * vetting fields were checked at snapshot time.
 */
object Providers {

    data class Provider(
        val jid: String,
        /** Recommended → community ordering; 0 is recommended-first. */
        val tier: Int,
    )

    /** Snapshot date of the upstream feed this list was filtered from. */
    const val SNAPSHOT_DATE: String = "2026-08-12"

    /**
     * Whether the provider retains pubsub node history. Measured live on
     * 2026-08-12: jabber.fr rejects persistence config on PEP nodes
     * ("max_items: out of bounds"), so only the latest item survives — a
     * device offline past that horizon would miss intermediate records.
     * yax.im rate-limits registrations per IP (also measured live) but its
     * pubsub posture is unverified. The sync engine must assume the worst
     * (transient nodes) until a provider is proven persistent.
     */
    const val KNOWN_TRANSIENT_NODES: Boolean = true

    val all: List<Provider> = listOf(
        Provider("yax.im", tier = 0),              // busFactor 3 — the most durable of the five
        Provider("jabber.fr", tier = 0),           // busFactor 2, French community, long-lived; transient nodes (measured)
        Provider("chat.between-us.online", tier = 1),
        Provider("xmpp.party", tier = 1),
        Provider("jabber.vg", tier = 1),
    )
}
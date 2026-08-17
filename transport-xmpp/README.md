// XMPP transport module — OWNER: Agent A.
// XMPP client (stream, SASL, PEP pubsub), IBR (XEP-0077),
// MAM (XEP-0313), embedded curated provider list.
//
// One implementation, three configurations:
//   (a) public provider via in-app IBR
//   (b) self-hosted server (advanced, manual credentials)
//   (c) local-only (transport not configured, sync never runs)
//
// Read the Two-Agent Execution Contract in docs/plan.md before touching
// this directory. Agent B: do not modify files here.

## Source sets

The module targets both desktop JVM and Android. Smack 4.4.8 is pure
Java and runs on both JVM and Android (Dalvik/ART), so the XMPP client
lives in `jvmMain` — an intermediate source set shared by both targets.

- `commonMain`: `Providers.kt` (embedded provider list), `SyncTransport` seam
- `jvmMain`: `XmppTransport.kt` (the Smack-based XMPP client)
- `desktopMain`: (empty — inherits jvmMain)
- `androidMain`: (empty — inherits jvmMain)

### Android support

Android support uses Option A: the same Smack XMPP client on Android.
Smack is pure Java with no native dependencies. The `companion object`
init block in `XmppTransport.kt` handles manual Smack initialization
(MiniDnsResolver, Base64 encoder) — this works on Android without the
`smack-android` artifact because:

- `java.util.Base64` is available on API 26+ (matching our minSdk)
- MiniDns is pure Java and works on Dalvik/ART
- All Smack XML parser and DNS resolver JARs are pure Java

No platform-specific `expect`/`actual` declarations are needed — the
same `XmppTransport` class compiles and runs on both targets.

### Known limitations

- `desktopTest/XmppRoundTripHarness.kt` is a manual live harness
  (not a `@Test`), so `./gradlew :transport-xmpp:desktopTest` fails
  with "no tests discovered" — this is by design.

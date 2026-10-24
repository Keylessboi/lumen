// XMPP transport module — OWNER: Agent A.
// XMPP client (stream, SASL, PEP pubsub), in-app sign-up (XEP-0077 with
// XEP-0158 CAPTCHAs), OMEMO 2-style device list node, embedded curated
// provider list. Shared by desktop and Android (src/jvmMain); only the XML
// parser differs per platform (StAX on desktop, XmlPullParser on Android).
//
// Live checks (manual, need network; src/desktopTest):
//   RegistrationFormProbe — fetches every provider's sign-up form, creates nothing
//   XmppRoundTripHarness  — creates and deletes a throwaway account
//
// One implementation, three configurations:
//   (a) public provider via in-app IBR
//   (b) self-hosted server (advanced, manual credentials)
//   (c) local-only (transport not configured, sync never runs)
//
// Read the Two-Agent Execution Contract in docs/plan.md before touching
// this directory. Agent B: do not modify files here.

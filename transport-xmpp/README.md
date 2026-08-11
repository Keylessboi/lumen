// XMPP transport module — OWNER: Agent A.
// XMPP client (stream, SASL, PEP pubsub), IBR (XEP-0077),
// MAM (XEP-0313), embedded curated provider list.
//
// One implementation, three configurations:
//   (a) public provider via in-app IBR
//   (b) self-hosted server (advanced, manual credentials)
//   (c) local-only (transport unconfigured — sync never runs)
//
// See docs/plan.md Two-Agent Execution Contract before touching this
// directory if you are Agent B.

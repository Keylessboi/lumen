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

# Lumen v1 — E2EE Design and Threat Model

**Owner:** Agent B (`docs/plan.md`, Two-Agent Execution Contract)
**Status:** Draft. Normative for the `EncryptedPayload` wire envelope, which **freezes at M4 (G3)**.
**Implements:** the `E2EE` and `Keychain` seams in `core/src/commonMain/kotlin/dev/lumen/core/crypto/E2EE.kt` (Agent A's zone, frozen at M1).

This document is the normative spec for what Lumen encrypts, what it does not, and what the sync server can still learn. Where it disagrees with marketing copy, this document wins.

---

## 1. Scope

v1 uses **X25519 + XSalsa20-Poly1305** (libsodium `crypto_box`), per adjudicated decision **B** in `docs/plan.md`. OMEMO 2 is the hard-pinned first post-MVP milestone; the `E2EE` interface exists so that swap is mechanical.

**In scope:** confidentiality and integrity of sync record payloads in transit and at rest on the server.

**Explicitly out of scope for v1**, and this must stay out of the marketing copy:

- **Forward secrecy.** Device keys are long-lived. An attacker who obtains a device private key can decrypt all previously captured ciphertext. OMEMO 2 fixes this; v1 does not.
- **Post-compromise security.** There is no ratchet. A compromised key stays compromised until the user manually resets the device identity.
- **Metadata privacy.** See §4. This is the largest honest gap.
- **Deniability.** Payloads authenticate the sender to the recipient.

The locked product claim is *"content encrypted; the server sees when and how much you sync."* That claim is accurate for this design. Anything stronger is not.

---

## 2. Adversaries

| # | Adversary | Capability assumed | v1 outcome |
|---|---|---|---|
| A1 | **Sync server operator** (public XMPP provider) | Reads/stores all traffic, MAM archive, indefinite retention | Cannot read usage content. Learns all of §4. |
| A2 | **Network attacker** | Passive capture; active MITM if TLS is broken/misissued | Cannot read content even with TLS fully broken — E2EE is independent of transport security |
| A3 | **Malicious/compromised provider** | A1 + can reorder, drop, replay, or inject records | Cannot forge content (Poly1305). **Can** drop or reorder — see §6 |
| A4 | **Device thief (locked device)** | Physical possession, no unlock credential | Keys at rest protected by hardware; see §5 |
| A5 | **Device thief (unlocked device)** | Full app access | **Total compromise.** No defense in v1, none claimed |
| A6 | **Cloud backup exfiltration** | Android auto-backup to Google Drive | Mitigated: `allowBackup=false` (locked decision, enforced in `app-android/build.gradle.kts`) |
| A7 | **Other apps on device** | Standard sandbox, no root | App-private storage; keys never leave the keystore as plaintext (desktop: see §5.3) |

A5 is deliberate. A screen-time tracker on an unlocked device is readable by whoever holds it, and pretending otherwise would be dishonest.

---

## 3. What travels

Only `SyncRecord.payload` is encrypted. Per `docs/plan.md` the synced record kinds are `EVENT`, `ROLLUP`, and `SETTING`.

**Never synced at all**, regardless of encryption:

- `FocusEvent.titleHash` — window titles never leave the device in any form. This is a hard rule, not a default. Titles are the highest-sensitivity field Lumen touches (document names, URLs, chat partners) and they have no cross-device use case.
- Raw `events` beyond the ~30-day prune horizon.

`SyncRecord.deviceId` and `SyncRecord.seq` travel **in the clear** — they are routing and dedupe metadata. `RecordKind` also travels in the clear.

---

## 4. Metadata the server learns (unavoidable in v1)

This section exists so nobody has to reverse-engineer it later:

1. **Your JID** — identity on the provider, plus registration IP and timestamps.
2. **Device count and device IDs** — stable UUIDs, so devices are linkable across the account's lifetime.
3. **Sync timing** — every publish, to the second. Because collectors publish on activity, sync timing is a coarse **activity oracle**: it leaks roughly when you are at your computer, and therefore sleep and work patterns.
4. **Volume** — record counts and ciphertext sizes. Number of distinct apps used per day is approximable from record counts, since one rollup record ≈ one app-day.
5. **`RecordKind`** — settings changes are distinguishable from usage data.

Mitigations deliberately **not** taken in v1, with reasons:

- *Padding ciphertext to fixed size* — cheap, and it would blunt (4). Worth reconsidering before M4 freeze; noted as an open question in §8.
- *Batching/jitter on publish* — would blunt (3) but harms the "sync feels instant" goal and complicates the engine. Deferred.
- *Rotating device IDs* — breaks append-merge reconciliation, which is keyed on `(device_id, seq)`. Structurally impossible without changing the frozen data model.

---

## 5. Key management

### 5.1 Hierarchy

```
device identity keypair  (X25519, long-lived, per install)
        │
        ├── used directly in crypto_box for record payloads
        │
        └── public key published to the account's other devices
            (via a SETTING record, verified out-of-band by fingerprint)
```

There is no separate account-level key in v1. Devices talk peer-to-peer through the server.

### 5.2 Android — verified constraint

**The Android Keystore cannot hold an X25519 key.** Verified directly against `platforms/android-35/android.jar`; `android.security.keystore.KeyProperties` exposes exactly:

```
KEY_ALGORITHM_3DES, KEY_ALGORITHM_AES, KEY_ALGORITHM_EC,
KEY_ALGORITHM_HMAC_SHA1/224/256/384/512, KEY_ALGORITHM_RSA
```

`KEY_ALGORITHM_EC` covers the NIST curves (P-256 et al.), and `PURPOSE_AGREE_KEY` enables hardware ECDH — but only for those curves. There is no `XDH` / Curve25519 algorithm at API 35.

This directly contradicts the `KeyPairRef` docstring in the frozen contract:

> `/** Opaque handle; the private key never materializes in app memory. */`

On Android that is **not achievable with X25519**. See §8, issue 1 — this needs resolving before M1 freezes `E2EE.kt`.

**v1 approach (pending that decision):** *hardware-wrapped at rest.*

1. Generate a non-exportable **AES-256-GCM** key in the Android Keystore (`setUserAuthenticationRequired(false)`, StrongBox when `FEATURE_STRONGBOX_KEYSTORE` is present).
2. Generate the X25519 keypair in software (libsodium).
3. Store the X25519 private key encrypted under the Keystore AES key, in app-private storage.
4. Unwrap into memory only for the duration of a `crypto_box` operation; zero the buffer after.

**Honest statement of what this buys:** the private key is protected **at rest** by hardware. It is *not* protected **in use** — it exists in app process memory during every encrypt/decrypt. This defeats A4 and A6. It does not defeat A5, and it does not defeat an attacker with live process memory access.

### 5.3 Desktop, Linux — Agent A's implementation

Secret Service / libsecret. Note for the record: libsecret protects at rest under the login keyring, and on a typical desktop the keyring is unlocked for the whole session. The desktop at-rest guarantee is therefore **weaker** than Android's, not stronger. `docs/plan.md`'s "hardware keystore (Android) / OS keyring (desktop)" phrasing reads as if these were equivalent; they are not.

### 5.4 Desktop, macOS — implemented

`MacosKeychain`, in the login keychain as a generic password under service `dev.lumen.macos`, account `device-key`, holding `base64(public):base64(private)`. The keypair is X25519, generated by the JDK's own XDH provider and stored as **raw 32-byte little-endian keys** (RFC 7748) rather than DER — `devices.public_key_x25519` is a bare BLOB, and the libsodium/OMEMO world this has to interoperate with speaks raw keys.

**Via `/usr/bin/security`, not the framework.** Keychain Services needs AppKit/Security through a JNI bridge and `app-macos` does not have one. The command is the same keychain.

**The private key never appears in a command line.** `security add-generic-password -w <secret>` is the obvious call and it is wrong: process arguments are readable by every process of the same user, so writing the key would publish it to the process table. Apple's own usage text says so — *"Use of the -p or -w options is insecure. Specify -w as the last option to be prompted."* So `-w` is passed last with no value and the secret goes over the process's stdin (twice — the prompt asks for confirmation). There is a test that writes a key and then greps `ps -axww` for it.

**Honest statement of what this buys,** in the same terms as §5.2 and §5.3: the login keychain is unlocked for the whole session, so this defends a powered-off Mac (A4) and **not** an attacker at an unlocked one (A5). It is the same guarantee as the Linux keyring, not the Android one. No hardware binding is claimed; the Secure Enclave holds P-256 keys only and cannot hold an X25519 key any more than the Android Keystore can.

**A damaged entry is not replaced.** Regenerating on a read failure would turn "something is wrong with your key" into "you are now a different device", silently orphaning every record written under the old identity. It fails loudly and leaves the entry for a human.

**Exports carry it.** `ExportPayload.deviceKeys` was empty for as long as this did not exist, so a restore brought back history but not the identity it was recorded under. When the keychain cannot be reached the field stays empty rather than carrying an invented key — an empty list makes the restore visibly incomplete; a placeholder makes it look complete and is a lie.

### 5.5 Fingerprint verification

`E2EE.identityFingerprint()` returns a stable, human-comparable encoding of the device public key. v1 surfaces it as a string plus a QR code in device settings. **Verification is advisory in v1** — Lumen does not block sync on unverified devices, because a first-run blocker violates the locked "<60s first run" constraint. Unverified devices are shown with a visible unverified marker.

Consequence, stated plainly: a malicious provider that injects a rogue device public key into the account can read subsequent records, unless the user checks fingerprints. This is the standard TOFU weakness and the reason OMEMO 2 is post-MVP #1.

---

## 6. Envelope format (normative — freezes at M4)

Wire form of `EncryptedPayload`:

| Field | Type | Cleartext? | Notes |
|---|---|---|---|
| `version` | `Int` | yes | `1` for this spec. Recipients MUST reject unknown versions rather than best-effort parse. |
| `senderDeviceId` | `DeviceId` | yes | Recipient looks up the sender's public key |
| `nonce` | `ByteArray(24)` | yes | libsodium `crypto_box` nonce. MUST be 24 random bytes from a CSPRNG, never a counter. |
| `ciphertext` | `ByteArray` | — | `crypto_box_easy(plaintext, nonce, recipientPk, senderSk)`; includes the 16-byte Poly1305 tag |

**Rules:**

- A record that fails authentication MUST be discarded and surfaced as an integrity warning, never silently skipped. Silent skip is how sync corruption becomes invisible data loss.
- `version` mismatch is a hard failure, not a downgrade.
- Nonce reuse under the same keypair is catastrophic for XSalsa20. 24 random bytes makes collision negligible; no counter scheme is used precisely to avoid state-tracking bugs across reinstalls.

**Replay and ordering are NOT solved by the envelope.** A3 can replay or reorder ciphertext freely. Defense lives in the `SyncIntegrity` hash-chain seam (Agent A) plus `(deviceId, seq)` dedupe. The envelope only guarantees a record was authored by the claimed device and not modified.

---

## 7. Export / migrate (M5)

The Argon2id encrypted export (decision E) is the recovery path for provider death **and** for OMEMO 2's new-device problem. Requirements:

- Argon2id, not PBKDF2 or scrypt. Parameters recorded in the file header so future readers do not need to guess.
- Interactive-grade parameters, tuned on real hardware at M5 — a phone and a laptop have very different budgets, and the export must be openable on both.
- The export contains **decrypted history plus device private keys**. It is the most sensitive artifact Lumen ever produces and the UI must say so at the moment of creation.
- Passphrase strength is the user's; the UI shows an honest strength estimate and does not fabricate a security guarantee.

---

## 8. Open contract issues — must resolve before M1 freeze

**Issue 1 — `KeyPairRef` overpromises on Android.**
The docstring guarantees the private key never materializes in app memory. §5.2 shows that is unachievable with X25519 on Android. Options:

- **(a)** Keep X25519; amend the docstring to an at-rest guarantee. *Recommended* — OMEMO 2 is built on Curve25519, so switching the curve now creates a migration problem at exactly the milestone we have hard-pinned next.
- **(b)** Switch the KEM to P-256 ECDH via `KEY_ALGORITHM_EC` + `PURPOSE_AGREE_KEY`. True in-use hardware protection on Android, but diverges from OMEMO 2 and imports NIST-curve baggage a privacy-focused FOSS project will be asked about.
- **(c)** Platform-divergent curves. Rejected: doubles the crypto surface for the least trustworthy reason.

**Issue 2 — the envelope is single-recipient; the product is multi-device.**
`EncryptedPayload` carries exactly one `nonce` + one `ciphertext`. With N devices, every record must be encrypted N−1 times and published N−1 times. At N=2 (the overwhelmingly common case) this is fine. At N=4 it is a 3× bandwidth and storage multiplier, and the server's per-record metadata (§4.4) gets proportionally richer.

OMEMO's approach — encrypt the payload once under a random symmetric key, then wrap that key per recipient device — needs a `Map<DeviceId, ByteArray>` of wrapped keys in the envelope. Adding that field **after** the M4 freeze is a wire-format break.

Recommendation: add the field now as optional, even if v1 only ever populates the single-recipient path. Costs nothing at v1 scale and removes a forced wire break at OMEMO 2.

**Issue 3 — ciphertext padding.**
Cheap to add before freeze, impossible to add after without a version bump. See §4.

---

## 9. Verification at G3

`docs/plan.md` requires *"2-device E2E via real provider, ciphertext-only server, no loss/dup"*. The ciphertext-only check is Agent B's `tools/sync-test-server` and must assert, mechanically:

- No plaintext `app_key` string from a known-app fixture list appears anywhere in the server's stored bytes.
- No window title from the fixture set appears in stored bytes.
- Stored payload bytes differ from the plaintext for every record.
- The MAM archive, not just the live stream, is checked — MAM is where the long-lived copy lives.

A visual inspection of one packet does not satisfy G3.

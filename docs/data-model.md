# Lumen Data Model (FROZEN at M1)

Owner: Agent A. Binding for both agents. Changes require a tag-bump PR
reviewed by both agents. Schema migrations only add.

## Standard unit & layering

```
raw events (local, ~30d)  ->  1-min buckets (local, ~6mo)  ->  app-day rollups (forever)
```

- **Raw events** are the real record on the device. Pruned after ~30
  days.
- **1-min buckets** are the standard level of stored detail. Android's
  UsageStats keeps event detail for days only, then aggregates. The
  platform forces this model; it is not a compromise.
- **App-day rollups** are the sync/API unit. Kept forever. Tiny.

## Reconciliation (TWO strategies — never one)

| Data | Strategy | Rule |
|---|---|---|
| events / buckets / rollups | **append-merge** | remove duplicates by (device_id, monotonic seq); immutable; sum across devices |
| settings / limits / overrides | **LWW + UTC-day** | last write wins per field, tiebreak (device_id, seq); UTC day boundary |

**NO CRDT. NO last-write-wins by clock time for events.** A device with
a wrong clock must never silently overwrite data. Server order (pubsub
item ID / MAM archive ID) is the ordering authority; gaps and jumps
show a sync-integrity warning instead of silent convergence.

## IDs

- **DeviceId**: UUID v4, generated per install. Device origin on every
  record.
- **AppKey**: the standard app identity — Linux: desktop-file id /
  WM_CLASS / exec name. Android: package name. It links everything.

## SQLite schema (binding)

```sql
CREATE TABLE devices (
  device_id        TEXT PRIMARY KEY,      -- uuid v4
  display_name     TEXT NOT NULL,
  public_key_x25519 BLOB NOT NULL,
  created_at_ms    INTEGER NOT NULL,
  last_seen_ms     INTEGER
);

CREATE TABLE events (                     -- pruned ~30 days
  seq              INTEGER PRIMARY KEY,   -- per-device monotonic
  device_id        TEXT NOT NULL REFERENCES devices(device_id),
  app_key          TEXT NOT NULL,
  title_hash       TEXT,                  -- truncated/hashed, NEVER synced
  started_at_ms    INTEGER NOT NULL,
  duration_ms      INTEGER NOT NULL,
  category         TEXT,                  -- denormalized snapshot
  sync_state       INTEGER NOT NULL DEFAULT 0,  -- 0 local, 1 acked, 2 conflict
  UNIQUE(device_id, seq)
);
CREATE INDEX idx_events_device_seq ON events(device_id, seq);

CREATE TABLE buckets (                    -- pruned ~6 months
  device_id   TEXT NOT NULL,
  bucket_ts   INTEGER NOT NULL,           -- UTC minute boundary (ms)
  app_key     TEXT NOT NULL,
  active_ms   INTEGER NOT NULL,
  PRIMARY KEY (device_id, bucket_ts, app_key)
);

CREATE TABLE rollups (                    -- kept forever
  device_id  TEXT NOT NULL,
  day_utc    TEXT NOT NULL,               -- 'YYYY-MM-DD' UTC day
  app_key    TEXT NOT NULL,
  total_ms   INTEGER NOT NULL,
  category   TEXT,
  PRIMARY KEY (device_id, day_utc, app_key)
);

CREATE TABLE settings (                   -- LWW + UTC-day reconciliation
  key            TEXT PRIMARY KEY,
  value          BLOB NOT NULL,
  updated_at_ms  INTEGER NOT NULL,
  updated_day_utc TEXT NOT NULL,
  device_id      TEXT NOT NULL            -- last writer
);

CREATE TABLE category_registry (
  app_key  TEXT PRIMARY KEY,
  category TEXT NOT NULL,
  source   TEXT NOT NULL                  -- 'registry' | 'manual'
);

CREATE TABLE manual_overrides (
  app_key      TEXT PRIMARY KEY,
  category     TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  sticky       INTEGER NOT NULL DEFAULT 1 -- never auto-reclassified
);

CREATE TABLE sync_watermark (
  device_id     TEXT PRIMARY KEY,
  last_acked_seq INTEGER NOT NULL
);
```

## Sync record envelope (sent over the network, E2EE-encrypted payload)

```kotlin
data class SyncRecord(
    val deviceId: DeviceId,
    val seq: Long,          // per-device monotonic
    val kind: RecordKind,   // EVENT | ROLLUP | SETTING
    val payload: ByteArray, // E2EE ciphertext (docs/e2ee.md)
)
```

Remove duplicates on receipt by (deviceId, seq). Rollback detection
with an optional hash chain (SyncIntegrity interface,
core/sync/SyncTransport.kt).

## Day boundary

UTC only. `UtcDay.dayOf(epochMs)` returns `YYYY-MM-DD`. Two devices in
different time zones must agree on what day a rollup belongs to.
Device-local midnight is a bug.

## Retention summary

| Layer | Retention | Owner of pruning |
|---|---|---|
| events | ~30 days | Agent A (core) |
| buckets | ~6 months | Agent A (core) |
| rollups | forever | — |

## Cross-device totals

Home screen sums rollups across devices. Per-device minutes are
independent (phone time + desktop time = day total). Overlapping
sessions are fine — they are per-device by design.

# Stage 8 — WorkManager-Backed Durable Sync Retry (integration design)

Status: PINNED 2026-09-06 (approved). Scope of this artifact: the exact scheduling,
deduplication, constraint, backoff, and coordinator-coexistence policy for adding
WorkManager on top of the completed Stage 7B-3 sync system. No code in this round
beyond the approved dependency.

## 1. Problem being closed

Stage 7B-3 guarantees failed cloud syncs are never lost (`SyncStateStore` markers)
and are retried — but only while the app process is alive (app start,
connectivity-regained callback, next local change). Gap: the user goes offline,
performs a SOS/contacts change, and the process dies before connectivity returns;
the markers then wait for the next app open. Stage 8 adds OS-guaranteed execution:
persistent background work that survives process death and reboot and runs when a
network constraint is met — **with the app never opened**.

Acceptance scenario: airplane-mode SOS → close app → restore network →
`sos_alerts/{uid}/{alertId}` appears in RTDB without the app being foregrounded.

## 2. Role separation (invariants preserved)

| Layer | Role | Changes in Stage 8 |
|---|---|---|
| `SyncStateStore` / `SharedPreferencesSyncStateStore` | WHAT must sync — the sole durable persistence (per-UID markers) | **None. Read-only consumer.** |
| `SyncingContactsRepository` / `SyncingSosRepository` | Markers on push outcome; `retryPendingBackup()` / `retryPendingBackups()` execution | **None in v1.** |
| `SyncRetryCoordinator` | The ONLY executor/serializer of retry runs (tryLock + coalesce) | Gains `ensureQueued()` (enqueue helper); execution path unchanged |
| WorkManager | WHEN to attempt — guaranteed, constraint-aware scheduling | New: `work/` package with one worker |
| Firebase data sources / payloads / paths | One-way local→cloud writes | **None.** |

Routing data (`route_graph_{regionId}`, legacy `route_graph`) remains local-only —
never synced. Local→cloud only; no cloud→local; no conflict resolution. Local-first
and local-never-fails rules untouched. 218/218 JVM baseline preserved.

## 3. Work unit, enqueue, and deduplication policy (EXACT)

- **One unique work unit**: `OneTimeWorkRequest` for `SyncWorker` (new
  `work/SyncWorker.kt`), unique work name **`"georescux-sync-pending-retry"`**,
  enqueued via `WorkManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)`.
- **Deduplication = `ExistingWorkPolicy.KEEP`**: while the work is enqueued or
  running, further enqueues are no-ops; once it completes, the next enqueue starts
  fresh. Combined with the coordinator's `tryLock`, there is at most **one queued
  and one executing** retry job process-wide. `REPLACE` is rejected (it would cancel
  an in-flight retry); `APPEND` is rejected (chains duplicate runs).
- **Worker body = one trigger**: `syncRetryCoordinator.retryPendingNow()`. The worker
  is a *scheduler*, never an executor — every retry run (app start, connectivity,
  WorkManager) flows through the same coordinator mutex/coalescing. Therefore the
  7B-3 guarantees ("multiple events cannot cause uncontrolled overlapping sync runs",
  "a retry in progress is not duplicated") hold globally.
- **Worker result contract**: the worker ALWAYS returns `Result.success()`. All
  failure containment lives in the retry methods (offline/rules errors → `false` →
  markers stay pending). The worker never returns `retry()` — no WorkManager-level
  retry loops; backoff is effectively dormant. If the process dies mid-run,
  WorkManager re-runs the (unique, idempotent) work on next constraint satisfaction —
  safe by design.
- **Signed-out / nothing pending**: the run is a vacuous success — no cloud work, no
  state mutation, zero cost.

## 4. Constraint and backoff (EXACT)

- **Constraint**: `NetworkType.CONNECTED` (any usable network; validated connectivity
  is not required — the same pragmatic posture as the 7B-3 connectivity callback;
  denied-rule failures remain contained and marked pending).
- **Backoff**: `BackoffPolicy.EXPONENTIAL`, initial delay **30 seconds** (WorkManager
  default). Only relevant to WorkManager-level re-runs after process death; the
  worker's success-always contract means normal flow never exercises backoff.
- **No periodic work in v1.** Rationale: app-start and connectivity triggers already
  cover regular use; periodic sweeps add battery cost without closing an extra gap
  (see §5). Revisit only if real-device testing shows a need.

## 5. Enqueue points (v1) and the documented residual gap

Enqueue happens ONLY from the coordinator (no decorator changes in v1):

1. `SyncRetryCoordinator.start()` — ensures the unique work exists for the whole
   session (KEEP makes this idempotent).
2. Every `retryPendingNow()` trigger path calls `ensureQueued()` before dispatching
   (app-start run, connectivity-regained, future triggers).

Coverage analysis:
- **Offline case (headline acceptance scenario)**: marker set while offline → the
  work enqueued at session start is still pending (constraint unmet) → app killed →
  WorkManager runs it when connectivity returns. CLOSED.
- **Online-but-failing case** (rules/5xx while online): the queued work may already
  have completed before the marker appeared; if the app is then killed, the marker
  waits for the next app open. **RESIDUAL GAP, accepted for v1** — markers are
  durable, so nothing is lost. Optional follow-up (separate approved item):
  enqueue-on-marker hooks inside the two decorators (`setSosAlertPending(true)` /
  `setContactBackupPending(true)` call `ensureQueued()`), which closes this gap
  completely at the cost of touching the decorators.

## 6. Coordinator coexistence (EXACT)

- `SyncRetryCoordinator` keeps its existing API and semantics (tryLock +
  `retryAgain` coalescing, per-channel contained calls, CancellationException
  rethrown). `start()`/`stop()` behavior unchanged; the connectivity monitor,
  app-start trigger, and local-change-triggered pushes are NOT removed.
- The worker calls `retryPendingNow()` exactly like any other trigger. If a run is
  already in progress (app start run, connectivity run, local-change run), the
  worker's trigger coalesces into the running job's single follow-up instead of
  stacking. WorkManager-side dedupe (unique KEEP) prevents a second queued worker.
- `stop()` (instrumented-only teardown) does NOT cancel WorkManager work —
  WorkManager work is process-independent by definition.

## 7. Dependency and initialization (EXACT)

- Dependency: **`androidx.work:work-runtime-ktx`** (the only new dependency,
  approved). Version pinned in `gradle/libs.versions.toml` (`androidx-work`), used
  from `app/build.gradle.kts` via the version catalog. WorkManager 2.10.x requires
  compileSdk 35 (satisfied); minSdk 24 supported.
- Initialization: WorkManager **default initialization** (its merged-manifest
  `androidx.startup` provider). No custom `Configuration.Provider`, no manifest
  edits, no on-demand init in v1.
- As-built 2026-09-06: dependency added (`androidx.work:work-runtime-ktx:2.10.0` via
  the version catalog); `data/sync/SyncWorkScheduler.kt` (JVM-testable abstraction) +
  `work/SyncWorker.kt` (trigger-only, always success, unique name
  `georescux-sync-pending-retry`, JVM-testable `triggerPendingRetry` seam) +
  `work/WorkManagerSyncScheduler.kt` (KEEP / CONNECTED / EXPONENTIAL 30 s) +
  `SyncRetryCoordinator.ensureQueued()` (called by `start()` and every
  `retryPendingNow()` trigger) + `AppContainer` wiring + coordinator/worker JVM tests.

## 8. Out of scope (unchanged from 7B-3 constraints)

- No WorkManager `PeriodicWorkRequest`, no Foreground service, no notifications,
  no cloud→local restore, no multi-device merge, no sync-status UI, no Firebase
  schema/path changes, no routing sync, no additional dependencies beyond the one
  approved artifact, no removal of existing triggers.

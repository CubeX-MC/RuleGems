# RuleGems Release Hardening Plan

## Objective

Prepare RuleGems for a public stable release without weakening its core
guarantees:

- A configured gem instance is unique and cannot be silently duplicated,
  deleted, or orphaned.
- A failed load or save never looks like an empty installation.
- Player, entity, and world access obeys Paper and Folia ownership rules.
- Shared plugin state remains consistent when different regions execute events
  and commands in parallel.
- Economy and optional-integration claims do not exceed what the underlying APIs
  can guarantee.
- The release artifact, metadata, documentation, and tested compatibility
  matrix describe the same product.

Risk class: **R4 release-critical**.

## Implementation Record (2026-07-26)

Local implementation is complete for the Spigot/Paper release candidate. The
automated gate passes and the version is now `1.1.0`, but publication remains
blocked on the real-server gates listed at the end of this file.

Completed:

- fail-closed YAML/SQLite loading, staged semantic validation, atomic YAML
  replacement, backup/recovery, versioned newest-only saves, and diagnostics;
- immutable block-position identity, UUID holder identity, locked placement /
  holder transitions, concurrent nested collections, atomic allowance
  consumption, and reload/scatter serialization;
- stable `plugin.yml` base command fallback;
- QuickShop-Hikari 6.2.0.11 startup contract validation and fail-closed health
  gate;
- built-in Vault transfer disabled by default, account-pair serialization,
  balance recheck, and verified compensation when explicitly enabled;
- SQLite JDBC upgrade, dependency locks, SHA-256 dependency verification,
  JaCoCo baseline, OSV CI scan, and release-JAR package audit;
- Folia metadata and unconditional support documentation removed.

Intentionally not claimed as complete:

- Folia ownership safety. This release candidate targets Spigot/Paper only.
- Crash-recoverable Vault transfer journaling. Keep
  `economy.transfer_directives_enabled: false` in production.
- Live QuickShop money/inventory/shop invariance, platform startup, Windows /
  Linux locked-store, rollback, and 24-hour soak tests. These require real test
  servers and remain publication gates.

## Non-Negotiable Architecture Rules

1. Global state stores UUIDs, immutable block positions, and serializable values;
   it does not retain mutable Bukkit objects such as `Player` or `Location`.
2. Player and entity API calls run through the owning entity scheduler.
3. World and block API calls run through the owning region scheduler.
4. Global mutations are atomic and exposed through transaction methods; callers
   do not mutate manager-owned maps directly.
5. Bukkit, Vault, LuckPerms, and QuickShop APIs are never called while a global
   state lock is held.
6. Reload is staged: parse and validate a replacement snapshot first, then swap
   it in one commit. A failed reload preserves the active state.
7. Storage and required optional-integration failures are fail-closed and visible
   through startup logs and `/rg doctor`.
8. Existing YAML data remains readable unless a separately documented migration
   is introduced.
9. `folia-supported: true` may remain only after the real multi-region gate
   passes.

## Phase 0 - Freeze the Baseline

Status: completed enough to begin hardening.

- Preserve the current full-build evidence and artifact checksum.
- Keep the existing 407 passing tests as the regression baseline.
- Add fault-injection tests before each risky implementation slice.
- Keep current user changes and avoid unrelated formatting churn.

Exit criteria:

- The current bugs can be reproduced deterministically by tests or a documented
  runtime scenario.
- Each later phase has a narrow targeted test command before the full build.

## Phase 1 - Fail-Closed Persistence

Status: **automated implementation complete; cross-platform failure injection
remains a manual release gate**.

Implemented in the current hardening branch:

- explicit `SUCCESS` / `NOT_FOUND` / `FAILURE` load results and observable save
  results;
- strict YAML and SQLite payload parsing, SQLite transactions and busy timeout;
- atomic YAML replacement with a last-known-good `.bak`;
- side-effect-free schema and invariant validation before active state teardown;
- failed startup/reload preservation, failed-save doctor reporting, newest-only
  versioned save acceptance, and emergency recovery YAML for synchronous
  failures;
- regression tests for missing/corrupt storage, backup recovery, invalid
  semantic data, active-state preservation, save retry, and diagnostics.

Completed during hardening:

- storage payloads are captured into a detached immutable staging snapshot
  before active gem state is cleared;
- deterministic scheduler-controlled tests prove stale asynchronous save tasks
  cannot overwrite the newest revision;
- locked/unwritable stores and emergency recovery remain explicit Windows and
  Linux runtime scenarios because unit mocks cannot establish OS-level behavior.

Primary files:

- `src/main/java/org/cubexmc/storage/StorageProvider.kt`
- `src/main/java/org/cubexmc/storage/YamlStorageProvider.kt`
- `src/main/java/org/cubexmc/storage/SqliteStorageProvider.kt`
- `src/main/java/org/cubexmc/manager/ConfigManager.kt`
- `src/main/java/org/cubexmc/manager/GemManager.kt`
- `src/main/java/org/cubexmc/RuleGems.kt`

Implementation:

1. Replace ambiguous storage reads with explicit `Success`, `NotFound`, and
   `Failure` results. Only `NotFound` represents a new installation.
2. Make saves return success/failure instead of swallowing exceptions.
3. Parse gem data into a staging snapshot. Validate UUIDs, configured keys,
   coordinates, holders, and count invariants before replacing active state.
4. Never clear active state or call `ensureConfiguredGemsPresent()` after a
   failed read.
5. On startup failure, fail plugin enablement before new gem UUIDs are created.
6. On reload failure, retain the active state and report failure to the command
   sender.
7. Keep a last-known-good YAML backup. Write through a same-directory temporary
   file, flush it, and replace the target atomically where supported.
8. Configure SQLite busy timeout and use explicit transactions. Treat schema,
   query, payload, and integrity failures as load failures.
9. Introduce a single-writer save coordinator using immutable, versioned
   snapshots. Coalescing may discard an older snapshot, never a newer state.
10. Flush synchronously on disable; if the primary save fails, write a clearly
    named emergency recovery snapshot and log an ERROR.

Tests:

- Missing YAML/SQLite storage produces an empty new-install result.
- Invalid YAML and invalid SQLite payload return failure.
- Locked/unwritable storage never produces new UUIDs.
- Failed reload leaves the current state unchanged.
- Failed save is observable by the caller and doctor report.
- Backup recovery loads the last known good state without silently overwriting
  the damaged primary file.
- Concurrent save requests persist the newest state version.

Exit criteria:

- No storage exception can be converted into an empty successful load.
- No failed load can reach configured-gem generation.
- Existing YAML and SQLite happy-path tests still pass.

## Phase 2 - Atomic Shared State

Status: **completed for the Spigot/Paper release scope**. Manager maps retained
for legacy internal/test compatibility use concurrent collections; new code
must use manager transaction methods. A future Folia support effort should
finish removing those mutable compatibility views before restoring the claim.

Proposed additions:

- `state/RuleGemsStateStore.kt`
- `state/GemStateSnapshot.kt`
- `state/BlockPosition.kt`
- `operation/GlobalOperationCoordinator.kt`

Implementation:

1. Replace mutable `Location` keys with immutable world UUID plus integer block
   coordinates.
2. Replace retained `Player` holders with player UUIDs.
3. Move gem ownership, allowance, redemption, and appointment data behind a
   state-store transaction boundary.
4. Add atomic operations such as:
   - held-to-placed transition;
   - placed-to-held transition;
   - allowance consume/refund;
   - appoint/dismiss;
   - immutable persistence snapshot.
5. Remove externally exposed mutable maps.
6. Keep Bukkit and provider side effects outside state locks.
7. Make save snapshots use the same consistency boundary as mutations.

Tests:

- Thirty-two concurrent consumers cannot use a finite allowance more times than
  configured.
- Concurrent appoint/dismiss/save operations do not corrupt collections.
- Every snapshot preserves location/holder mutual exclusion and UUID uniqueness.

Exit criteria:

- No global mutable `HashMap` or `HashSet` is accessed concurrently without an
  explicit ownership or lock policy.
- A state snapshot represents one coherent state version.

## Phase 3 - Folia Ownership And Global Operations

Status: **release decision complete: Folia support is removed**. Reload and
scatter are serialized by `GlobalOperationCoordinator`; full entity/region
fan-out remains required before Folia can be claimed again.

Primary files:

- `utils/SchedulerUtil.kt`
- `manager/GemManager.kt`
- `manager/GemScatterService.kt`
- `manager/GemPermissionManager.kt`
- `manager/PowerStructureManager.kt`
- `features/appoint/AppointFeature.kt`
- entity, inventory, lifecycle, and command listeners

Implementation:

1. Expose explicit scheduling operations:
   - entity-owned task;
   - location-owned task;
   - global task;
   - asynchronous IO task.
2. Remove `Bukkit.isPrimaryThread()` as a serialization guarantee.
3. Convert online-player loops to UUID snapshots followed by per-entity tasks.
4. Convert block, display, placement, and cleanup work to per-location tasks.
5. Serialize reload and scatter through `GlobalOperationCoordinator`.
6. Implement reload/scatter as asynchronous fan-out/fan-in workflows:
   - mark the global operation active;
   - schedule player and location work on owners;
   - collect completion results;
   - commit global state once;
   - save and report completion.
7. Reject or defer conflicting state-changing commands while a global operation
   is active.
8. Route permission attachments, inventory edits, titles, sounds, effects, and
   navigation updates through entity ownership.

Tests and runtime gates:

- Two players at least 1,000 chunks apart concurrently drop, die, disconnect,
  consume allowances, appoint, and save.
- Player-initiated and console-initiated reload/scatter both complete.
- Repeated runs produce no ownership exception, duplicate allowance, permission
  residue, or gem-count drift.

Exit criteria:

- Real Folia multi-region smoke passes before `folia-supported: true` is shipped.
- Otherwise the release removes the Folia metadata and documentation claim.

## Phase 4 - Economy Transfer Integrity

Status: **safe release boundary complete**. Built-in transfer is disabled by
default and must remain disabled for production. Pair locking, balance recheck,
response verification, and compensation exist for explicit test use. The
journal/recovery-command design below is deferred and blocks any future claim
that built-in Vault transfers are crash-transactional.

Implementation:

1. Remove the word "atomic" from Vault transfer claims. Vault exposes separate
   withdraw and deposit calls, not a cross-account transaction.
2. Keep built-in `transfer:` disabled by default until the compensated transfer
   implementation is ready.
3. Serialize Vault calls on the global scheduler.
4. Lock normalized account pairs in deterministic order and recheck balance
   under the lock.
5. Verify withdrawal, deposit, and rollback results.
6. Persist a transfer journal with states:
   `PREPARED`, `WITHDRAWN`, `DEPOSITED`, `ROLLBACK_PENDING`, `ROLLED_BACK`.
7. Surface unresolved transactions through `/rg doctor` and an administrator
   recovery command.
8. Prefer removing built-in transfer support when the configured economy plugin
   already provides its own transactional command.

Tests:

- withdrawal failure;
- deposit failure with successful rollback;
- deposit failure with failed rollback;
- restart at every journal state;
- concurrent transfers from the same account.

Exit criteria:

- No failed money movement is unrecorded.
- No success is reported before both account operations are confirmed.

## Phase 5 - Stable Command Entry Point

Status: **completed for the base command compatibility path**.

Implementation:

1. Declare `/rulegems` and `/rg` in `plugin.yml`.
2. Introduce a framework-neutral `CommandRouter`.
3. Use Bukkit `CommandExecutor` and `TabCompleter` as the required compatibility
   path.
4. Keep Cloud only as an optional Brigadier enhancement, or remove it if it
   cannot initialize across the supported matrix.
5. Route Cloud and Bukkit adapters through the same handlers and permission
   checks.
6. Remove reliance on NMS class names and private command-map fields.

Tests:

- every subcommand under player/console and permitted/denied combinations;
- feature-enabled and feature-disabled behavior;
- tab suggestions do not expose unusable admin actions;
- Paper 26.1.2 startup without command bootstrap errors.

Exit criteria:

- Base commands work without Cloud.
- Supported Paper versions start with no RuleGems command ERROR.

## Phase 6 - QuickShop-Hikari Contract And Health

Status: **adapter and automated contract gate complete; real transaction-path
smoke remains a publication gate**.

Initial certified target: QuickShop-Hikari `6.2.0.11`.

Implementation:

1. Validate required event classes, cancellation methods, phases, shop access,
   and item access at startup.
2. Track integration health as `ABSENT`, `ACTIVE`, `UNSUPPORTED`, or `FAILED`.
3. Report installed-but-inactive QuickShop protection as `/rg doctor` ERROR and
   a release-blocking startup condition.
4. Keep purchases, sales, and shop creation blocked before money or items move.
5. Maintain compatibility through the isolated adapter; do not raise the
   Spigot 1.16.5 compile baseline solely for QuickShop.

Runtime tests:

- create a gem shop;
- sell a gem to a buying shop;
- buy a gem from an existing shop;
- repeat with a nested carrier;
- verify buyer/seller balance, both inventories, shop inventory, gem UUID, and
  ownership before and after.

Exit criteria:

- All three trade paths are cancelled with no state change.
- A failed hook cannot be mistaken for healthy protection.

## Phase 7 - Dependency, Documentation, And Release Governance

Status: **implementation complete; version prepared as 1.1.0, with publication
still deferred until the manual gates pass**.

Implementation:

1. Upgrade SQLite JDBC after persistence tests pass.
2. Enable Gradle dependency locking and dependency verification.
3. Add an OSV dependency scan to CI.
4. Add Kotlin static analysis and a measured coverage baseline without blocking
   the first safety patch on unrelated legacy findings.
5. Run `/rg doctor` only after the permission backend is initialized.
6. Synchronize README files, the server-ready guide, project profile, metadata,
   changelog, compatibility matrix, storage recovery instructions, and rollback
   notes.
7. Prepare plugin version `1.1.0` for the storage/threading architecture
   change, while keeping publication blocked until the release gates pass.

## Release Verification Matrix

| Platform or integration | Required verification |
|---|---|
| Spigot 1.16.5 / Java 17 | commands, lifecycle, YAML |
| Paper 1.19.4+ | both presentation backends |
| Paper 26.1.2 / Java 21 | full feature and command smoke |
| Current Folia plus 1.21.4 | real two-region concurrency |
| YAML | corrupt, unwritable, backup, rollback |
| SQLite | lock, corrupt payload, migration, concurrent save |
| Vault/CMI | every transfer failure stage |
| QuickShop-Hikari 6.2.0.11 | create, buy, sell |

## Final Release Gates

All gates are mandatory:

- Full clean build and all automated tests pass.
- Supported-platform startup has no RuleGems ERROR.
- Failed storage cannot create a new gem UUID.
- Real Folia multi-region testing has no ownership or state-consistency error,
  or Folia support is removed from the release claim.
- QuickShop create/buy/sell tests leave money, inventory, shop, UUID, and
  ownership unchanged.
- A 24-hour test-server run shows stable gem counts, permissions, allowances,
  appointments, and persistence.
- Upgrade, backup, downgrade, and data rollback are rehearsed and documented.

Current local evidence:

- 437 tests passed, 0 failed, 0 skipped.
- Detekt strict-baseline gate passed; 654 pre-existing maintainability findings
  are recorded so newly introduced findings fail the gate.
- JaCoCo baseline: 5,074 / 13,008 lines (39.01%).
- Shaded artifact:
  `build/libs/RuleGems-1.1.0.jar`, 7,949,578 bytes,
  SHA-256
  `495782FBC371B3305C9A28E45E2F3B5237510D3562142CEF3CBBB496DD671EBA`.
- OSV Scanner 2.3.8 scanned 136 locked packages and found no untriaged finding
  after 17 ID-specific, time-limited build/test/compileOnly exceptions. CI
  verifies those package trees remain absent from the shaded artifact.

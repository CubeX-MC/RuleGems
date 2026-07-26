# Changelog

## Unreleased

- **Compatibility claim correction**: remove `folia-supported: true` and the
  unconditional Folia support claim until the documented real two-region gate
  has passed.
- **Stable command fallback**: declare `/rulegems` and `/rg` in `plugin.yml`
  and attach the Bukkit compatibility executor without reflective command-map
  registration when Cloud cannot initialize.
- **QuickShop health gate**: validate the 6.2.0.11 event, cancellation, phase,
  shop, and item-access contracts at startup; an installed QuickShop instance
  without active purchase/sale/create protection now blocks RuleGems startup
  instead of degrading silently.
- **Economy safety boundary**: built-in `transfer:` directives are disabled by
  default. When explicitly enabled, transfers are serialized per normalized
  account pair, balances are rechecked under the lock, and deposit rollback is
  verified; documentation no longer calls Vault's separate calls atomic.
- **Dependency governance**: upgrade SQLite JDBC to 3.53.2.0, lock resolved
  RuleGems dependency versions, verify resolved artifact SHA-256 checksums, add
  a strict Detekt legacy baseline, JaCoCo XML/HTML coverage reports, and a
  scheduled/PR OSV vulnerability scan plus release-JAR package audit.
- **Global operation safety**: serialize reload and scatter, reject a second
  global rebuild while one is active, and run doctor diagnostics only after the
  permission backend is selected.
- **Shared-state safety**: use immutable block-position identity and player UUID
  holder identity, lock holder/location transitions and snapshots, use
  concurrent nested allowance/permission collections, and atomically consume
  finite allowances.
- **Fail-closed persistence**: distinguish missing data from failed reads, abort
  startup/reload on unreadable or semantically invalid gem state, and preserve
  the active runtime state on reload failure.
- **Crash-safe YAML storage**: validate and flush same-directory temporary
  files, replace the primary atomically where supported, and maintain
  `data/gems.yml.bak` as the last-known-good copy.
- **SQLite storage safety**: use explicit transactions, rollback failures, a
  busy timeout, and strict validation of the stored YAML payload.
- **Save recovery**: reject stale save revisions, expose the latest storage
  failure through `/rg doctor`, block reload after a failed synchronous save,
  and write `data/recovery/gems-emergency-<timestamp>.yml` if the primary
  synchronous save fails.
- **QuickShop-Hikari safety**: cancel gem purchases, sales, and shop creation
  through optional pre-transaction hooks, before money or items move.
- **Container input safety**: block off-hand swaps into external inventories.
- **Nested custody safety**: remove gems recursively from shulker boxes and
  bundles during logout, death, duplicate cleanup, and foreign-inventory
  recovery while preserving unrelated carrier contents where the server API
  supports rewriting them.
- **Folia drop safety**: deduplicate custody recovery by dropped-item entity so
  `ItemSpawnEvent` and `PlayerDropItemEvent` cannot schedule two placements.
- **World-border fail-safe**: refuse random placement bounds when the configured
  range and vanilla world border do not intersect.
- **Navigation security**: project compass targets onto player-relative bearing
  waypoints so client packets no longer contain a gem's absolute coordinates.
- **Navigation lifecycle**: refresh relative waypoints safely, clear stale or
  picked-up targets, and retain cleanup for indefinite sessions.
- **Folia safety**: deliver gem intel from the recipient's entity scheduler and
  validate the player is still online before reading location or sending chat.
- **Scatter safety**: never create anonymous gem instances when no gem
  definitions are configured.
- **Stable scatter identity**: reuse existing gem UUIDs by type and configured
  count while still resetting locations, holders, grants, and allowances.
- **Proximity presentation**: add the opt-in `proximity_display` backend with
  reveal/hide hysteresis, display pickup, and in-place `/rg reload` switching
  to and from traditional blocks without changing gem coordinates or UUIDs.
- **Presentation compatibility**: use per-player-hidden `BlockDisplay` entities
  on Minecraft 1.19.4+ and a non-persistent ArmorStand fallback on older servers.
- **Stable gem escape flow**: replace per-gem long-range timers with one global
  cycle that selects old or clustered gems and searches same-world distance
  bands before moving them.
- **Escape recovery**: after configurable failed local rounds, or repeated local
  escapes without pickup, re-scatter only that gem globally while preserving its
  UUID and invalidating earlier intel after the move succeeds.
- **Escape configuration compatibility**: retain `gem_escape.min_interval` and
  `max_interval` as the global-cycle bounds, and add configurable minimum
  unmoved duration, selection weighting, local distance bands, retry delay,
  attempts, failure threshold, and no-pickup threshold. Existing files are
  merged with the new defaults; review interval values because their scheduling
  semantics changed from per-gem timers to a server-wide cycle.

## 1.0.9 (2026-07-10)

- **Economy**: resolve Vault transfers through named accounts first, with
  fallback player-account resolution and rollback on failed deposits.
- **Gem intel**: add the optional `intel.yml` feature that periodically leaks
  fuzzy, per-axis gem location rumors to eligible players.
- **Navigation**: make compass guidance range-limited and time-limited, then
  restore the player's original compass target when guidance expires.
- **Appointments**: allow appointed role powers to be toggled independently in
  the power GUI; keep cycle checks scoped to the same appointment set.
- **Gem placement**: unplace an old gem block when the same gem is moved to a
  different block.
- **Compatibility**: update cloud command dependencies to `2.0.0-beta.17` and
  include the new intel feature config in default config migration.

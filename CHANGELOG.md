# Changelog

## Unreleased

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

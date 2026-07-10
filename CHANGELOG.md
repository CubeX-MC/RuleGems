# Changelog

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

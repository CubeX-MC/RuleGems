# RuleGems

[中文](README.md) | English<br>
[Discord](https://discord.com/invite/7tJeSZPZgv) | [QQ频道](https://pd.qq.com/s/1n3hpe4e7?b=9)

A lightweight plugin that passes player power around through collectible "rule gems" on Spigot and Paper.

## Installation
1. Put the JAR into the `plugins` folder
2. Start the server to generate configs
3. Adjust `config.yml` and files under `gems/`, `powers/`, and `features/` as needed

### Framework update and rollback

Stop the server, back up the old jar and the entire RuleGems folder, including
`data/`, `lang/` and any SQLite file configured outside that folder. Replace only
the deployment jar and restart normally; do not hot-swap this relocation update.
Gem UUIDs, counters, permissions and YAML/SQLite formats are unchanged. Missing defaults
are merged using the existing backup policy; deleting configs or regenerating gems is unnecessary.
Limited-command cooldowns still clear on restart as before; uses do not refill automatically.
Revoke-rule cooldowns and pending transfer guards remain durable.

Run `/rg doctor`, check ownership, appointments and remaining uses, then test small
bank transfers against the actual economy plugin. To roll back, stop the server and
restore the matching jar and data backup. Resolve pending transfers before downgrading:
older jars do not enforce the new guards. Reconcile current economy balances separately;
restoring RuleGems files does not undo economy transactions. Language version 3 changes only
known obsolete bundled transfer messages; check custom messages for outdated refund promises. See [IMPROVE_PLAN](IMPROVE_PLAN.md)
for this improvement run's automated verification and outstanding live-server checks.

## Server-Ready Setup
- Opening checklist, smoke tests, and production notes live in [server-ready-guide.md](docs/server-ready-guide.md).
- Copyable gameplay packs live under [presets/](presets/); the first one is `kingdom-power`.
- Presets are not loaded automatically. Copy them into `plugins/RuleGems/`, then run `/rg reload` and `/rg doctor`.
- If you use `permission_groups` or collection-threshold groups, install LuckPerms; the Bukkit fallback has no persistent group model.

## Commands
- All `/rulegems ...` commands have the alias `/rg ...` (see `aliases: [rg]` in plugin.yml)
- Running `/rulegems` or `/rg` with no arguments opens the main GUI for players and shows help for the console
- `/rulegems place <gemId> [x|~ y|~ z|~]` Place a specific gem instance at the given coordinates; omitting coordinates is equivalent to `~ ~ ~` and uses your current location
- `/rulegems tp <gemId>` Teleport to the current location of the gem instance
- `/rulegems revoke <player>` Force clear all gem-granted permissions and allowances from a player (admin intervention). If `inventory_grants` is enabled and the player still holds gems, permissions will be re-issued on the next inventory recalculation.
- `/rulegems revoke-power list` Show configured revoke-power rules
- `/rulegems revoke-power <rule> <player> <power>` Use a configured revoke rule to counter a player's redeemed gem power. Rules usually require `/rg revoke-power confirm`; use `/rg revoke-power cancel` to abandon the pending action.
- `/rulegems transfer-review list [page]` List pending transfer operations (also the default with no subcommand)
- `/rulegems transfer-review resolve <operation UUID> <note>` Record reconciliation and release its retry guard; no balance or use changes
- `/rulegems reload` Reload configuration files
- `/rulegems rulers` List current power holders
- `/rulegems gems` Show the status of every gem instance
- `/rulegems gui` Open the GUI interface
- `/rulegems scatter` Collect and scatter every gem; existing UUIDs are preserved by gem type by default while holders, grants, and allowances are reset
- `/rulegems redeem` Redeem the gem held in main hand
- `/rulegems redeemall` Redeem all gem types once the player has at least one of each
- `/rulegems history [page] [player]` View paged history records, optionally filtered by player
- `/rulegems setaltar <gemKey>` Set the altar location for a gem at your current position
- `/rulegems removealtar <gemKey>` Remove the altar location for a gem
- `/rulegems appoint <perm_set> <player>` Appoint a player to a permission set
- `/rulegems dismiss <perm_set> <player>` Dismiss a player's appointment
- `/rulegems appointees [perm_set]` View list of appointees

## Permissions
- `rulegems.admin` Admin commands (default OP; includes both reconciliation permissions)
- `rulegems.transfer.review` View pending operations (default OP)
- `rulegems.transfer.resolve` Confirm reconciliation and release guards (default OP; not implied by review)
- `rulegems.redeem` Redeem single (default true)
- `rulegems.redeemall` Redeem all (default true)
- `rulegems.rulers` View current holder (default true)
- `rulegems.gems` View gem list (default true)
- `rulegems.help` View command help (default true)
- `rulegems.navigate` Use compass to navigate to the nearest gem (default false)
- `rulegems.rule` Allows all gem powers when RuleGate is enabled (default false)
- `rulegems.rule.<gemKey>` Allows one specific gem power when RuleGate is enabled (default false)
- `rulegems.revoke` Use configured revoke-power rules (default false)
- `rulegems.revoke.admin` Reserved admin permission for revoke-power rule management (default OP)
- `rulegems.appoint.<perm_set>` Appoint other players to the specified permission set

## Compatibility
- Servers: Spigot / Paper 1.16+. This build does not declare Folia support; do not deploy it to a production Folia server until the real two-region concurrency gate passes.
- Optional integrations: LuckPerms / Vault (permission backends) and
  QuickShop-Hikari 6.2.0.11 (shop protection adapter)
- If QuickShop-Hikari is detected but the purchase, sale, and shop-creation
  protection hooks cannot all be registered, RuleGems refuses to start instead
  of allowing unprotected gem trades.

## Mechanics
Each gem type can grant permissions, Vault groups and limited-use commands. Every gem instance is unique and permanently exists somewhere on the server (either placed in the world or held in a player inventory). Five application modes can be combined:

1. **inventory_grants** – breaking a gem block puts the gem into the player inventory and immediately grants the corresponding permissions and limited commands. When the gem leaves the inventory (logout, death, placement, etc.) these perks are removed. Limited-command usage attaches to the most recent holder: a returning holder keeps remaining uses; a new holder inherits the remaining uses once the old owner no longer owns that gem type.
2. **redeem_enabled** – `/rg redeem` while holding a gem consumes that specific instance (it respawns elsewhere) and grants its rewards. Ownership tracking is per gem instance (UUID). Permissions and allowances are only revoked once the previous owner no longer owns any instance of that gem type. Mutual exclusions are respected during redemption.
3. **full_set_grants_all** – once a player has at least one instance of every gem type, `/rg redeemall` grants every gem reward (ignoring mutual exclusions) plus extra perks defined under `redeem_all`. The previous full-set holder keeps everything until another player successfully `redeemall`s.
4. **place_redeem_enabled** – placing a gem near its configured altar redeems that gem directly.
5. **hold_to_redeem_enabled** – redeem by holding right-click for the configured duration.

## Features & Configuration Notes
- Every gem instance has its own UUID; use `/rulegems place <gemId> ...` for precise placement.
- Scatter reuses existing UUIDs by `gem key` and configured `count`: increasing a count creates only the missing UUIDs, while decreasing a count or removing a type retires surplus UUIDs. Locations, holders, grants, and command allowances are still reset.
- Help links: `links.documentation`, `links.discord`, and `links.qq` appear in the `/rg help` footer and startup log. The bundled documentation link now points to `https://github.com/CubeX-MC/RuleGems`; upgrades migrate only the former official default and preserve custom server URLs.
- Bundled gem and power examples are starter templates only. Existing `gems/` and `powers/` files are not repopulated with removed example definitions on reload.
- `gems.<key>.count` defines how many instances of a gem type should exist; full-set checks only require at least one per key.
- `gems.<key>.mutual_exclusive` declares mutually exclusive types (applies to `inventory_grants` and `redeem_enabled`; ignored for `redeem_all`).
- `gems.<key>.command_allows` supports both map form and list form. `time_limit: -1` means unlimited uses. Command executors support `console:` for console dispatch, `player:` for running as the player without elevation, and `player-op:` for temporary OP when `allow_op_escalation: true`. Extras granted by `redeem_all` live under root `redeem_all.command_allows` with the same syntax, counted under a synthetic `ALL` key. The same command label may have different effects in different sources; execution resolves the held instance, redeemed instance, appointment, or `redeem_all` source first, then uses that source's command config, cooldown, and remaining-use counter.
- Permissions and groups are granted on a per-type counter: 0→1 grants, 1→0 revokes. Limited commands follow the same counters.
- Root `redeem_all` supports extra perks: `broadcast`, `titles`, `sound`, `permissions`, `permission_groups`, and `command_allows` (same syntax as above, applied when `redeemall` succeeds).
- Root `gem_collect_thresholds` can grant groups by the number of distinct redeemed gem types, for example `2: noble` and `4: lord`; groups are revoked when the player falls below the threshold.
- Per-gem `redeem_requirements` can raise the cost of dangerous powers:
  - `requires_held` requires held gem ingredients without consuming them and supports `{ gem, amount }`.
  - `requires_redeemed` requires redeemed gem ownership and supports `{ gem, amount }`.
  - `consumes` removes and respawns matched held gem instances after the main redeem succeeds and the redeem event is not cancelled.
  - `any_of` defines multiple equivalent recipes and uses the first satisfiable recipe in order.
  - `requires_any` and `requires_count` + `requires_count_from` remain as legacy either/or and at-least-N gates.
  - `allow_redeem_all` defaults to `false` for configured requirements so `/rg redeemall` cannot bypass them accidentally.
- Config upgrades: startup or reload backs up legacy syntax to `backups/config-optimization-<unique-id>/` before reading it with coarse compatibility and warnings. Migrate `template`, root-level implicit power fields, `vault_group` / `vault_groups` / `permission_group`, and old requirement forms to `base`, `permission_groups`, and recipe/ingredient syntax; future versions may remove compatibility.
- Permission backends are selected automatically in LuckPerms → Vault → Bukkit order; group adds/removals are routed through the active provider.
- Storage: `storage.type: yaml` uses `data/gems.yml` and maintains `data/gems.yml.bak` as the last-known-good write. `storage.type: sqlite` uses the database configured by `storage.sqlite.file`, preserves the existing data shape, and imports `data/gems.yml` when an empty database is first initialized. Corrupt or unreadable data is never treated as a new installation and cannot trigger new UUID generation: startup fails, while reload preserves the active runtime state. If a synchronous primary save fails, RuleGems attempts `data/recovery/gems-emergency-<timestamp>.yml` and reports the failure through `/rg doctor`.
- Economy transfers: `transfer:` remains disabled by default via
  `economy.transfer_directives_enabled: false`. RuleGems serializes
  transfers against the same provider. Only an explicit rejected deposit is refunded;
  exceptions, null responses and failed refunds require manual balance reconciliation.
  A committed transfer followed by another command failure retains the consumed use and cooldown.
  Uncertain/partial operations block retries from the same player/source in `data/transfer-operations.yml`,
  surviving reload and restart. Vault is not an atomic transaction or automatic crash recovery service.
- Accounts: player names, UUID / `uuid:<UUID>`, `name:<account>` for Vault string
  accounts, and `bank:<bank>` where supported. Ordinary names resolve via online/cache
  players, existing Vault named accounts, then trusted profile lookup; no full offline-player
  scan occurs. Use `name:cubex_bank` for explicit named-account routing and verify both
  balances with a small transfer on the actual economy provider.
- Installation remains unchanged: RuleGems runs independently, without CubeXLib.
  Stop the server before replacing the jar for this update.
- Reload: require feature stores to pass their save barrier and save gems synchronously, then validate config, languages and storage before
  publishing new config. Named failures stop later stages. RuleGems menus close on
  reload; the command executor and its active cooldowns remain. Runtime failures in
  world operations or other plugins are not a cross-component atomic rollback.
- Power gate: `features/rule.yml` is disabled by default. When enabled, `rulegems.rule` allows all gem powers and `rulegems.rule.<gemKey>` allows one specific gem. This is useful during testing when only trusted players should be able to activate powers.

### Reconciliation and data failures

Use `/rg transfer-review list` to inspect operation IDs, players, status and resolved transfer arguments.
Check both balances and the economy provider's records before correcting money through that provider.
Then run `/rg transfer-review resolve <UUID> <note>`: allowances must save successfully before the
acknowledgement is archived under `data/transfer-reviews/` and the guard is released. It does not replay
commands, refund money, refill uses or clear cooldowns. Do not delete the journal to bypass review.

Unreadable rule/revoke configuration and feature data abort loading; they do not silently disable gates
or clear cooldowns. Appointments and revoke cooldowns retain their existing YAML formats and save before
publishing changes. Failed writes do not report success. History queries retain only the requested page
in memory, but still scan records to count them. Durable transfer protection adds synchronous disk writes;
unit tests are not a production TPS or economy-provider latency benchmark. Hot-swapping external providers
is not supported; stop the server for upgrades.

### Limited-command argument constraints

List entries in `command_allows` accept `args` and an optional `usage`, including gem, appointment,
and `redeem_all` commands. For example, configure `/cxfine <player> <amount>` in the owner's power template:

```yaml
command_allows:
  - command: /cxfine
    usage: '/cxfine <player> <amount>'
    args:
      arg1: {type: string, required: true, suggestions: online_players}
      arg2:
        type: number
        min: 0.01
        max: 10000
        suggestions: [50, 100, 500, 1000, 5000, 10000]
    execute:
      - 'transfer:%arg1% name:cubex_bank %arg2%'
    time_limit: 5
    cooldown: 7200
```

For the appointment template, change `arg2.max` to `500` and `time_limit` to `3`.
Both have a 120-minute cooldown. Constraints follow the resolved power source along with its counter
and cooldown. `time_limit` is a use allowance, not a replenishing charge pool; this feature does not
change the existing counter or cooldown lifecycle.

| Setting | Behavior |
|---|---|
| `args.arg1`, `args.arg2`, … | Validate the corresponding input position; unconfigured and extra arguments remain unrestricted |
| `type: string` | Default type; checks presence only, not whether a player exists |
| `type: number` | Plain decimal such as `25` or `0.01`; rejects NaN, Infinity, exponent notation, hexadecimal and suffixes; maximum 128 characters |
| `type: integer` | Integer text such as `25`; rejects `25.0` |
| `required: true` | Required by default; `false` skips missing inputs but still validates supplied values |
| `min` / `max` | Optional inclusive bounds for numeric types; excess values are rejected, never clamped |
| `suggestions: online_players` | String arguments only; suggests online players visible to the sender through Bukkit `canSee` |
| `suggestions: [50, 100, 500]` | Fixed candidates, deduplicated in configured order; numeric candidates are filtered by the argument type and `min/max` |
| `usage` | Usage shown on input errors, rendered as plain text; defaults to the command name |

Validation runs before placeholder substitution, the entire command chain, counter consumption, and
cooldown updates. Invalid input produces a reason and usage without executing any action. It applies
to `console:`, `player:`, `player-op:`, and `transfer:` commands. Malformed constraints (unknown types,
misspelled `max`, reversed bounds, etc.) log a warning and block execution rather than silently dropping
the restriction. Configurations without `args` retain their behavior. New language keys are merged
without overwriting existing translations.

Suggestions match the typed prefix (player names ignore case), with at most 50 results per request.
The appointment's `max: 500` automatically filters `1000`, `5000`, and `10000` from the same list.
Completion resolves the currently executable power source; exhausted or disabled powers provide no
RuleGems candidates. Requests never consume uses or start cooldowns. Static amounts are filtered at
configuration load; player names use the online list only, without offline-player scans, database
queries, or balance lookups.

Omitting `suggestions` adds no argument completion; `suggestions: []` explicitly supplies no candidates.
Custom proxies and native-command Bukkit completion events share these rules. Explicitly configured
candidates replace existing results, even when empty; unconfigured arguments retain native results.
RuleGems does not inherit completion from other commands in `execute`. Suggestions are hints, not an
allowlist: users may still type other valid amounts or offline names, subject to execution validation.
Unknown completion sources or malformed structures log a configuration error and block the command.

**Keep money amounts required.** Constraints validate player input, not configured defaults in
`%argN|default%`, fixed amounts in templates, or the total of several transfers in a chain. This is not
a global economy-account limit. The example still requires Vault, an economy plugin, and explicit
`economy.transfer_directives_enabled` opt-in. Transfer compensation risks remain; this is not an atomic
transaction. Put transfers before success broadcasts.

After upgrading, edit powers and run `/rg reload`; existing counters are not automatically refilled.
**Disable dynamic-amount commands relying on these constraints before rolling back to an older jar**
that ignores `args`, or their limits will no longer be enforced.
When rolling back to a version that supports `args` but not `suggestions`, remove `suggestions` first;
otherwise its strict parser will block the command.

### Gem Presentation Modes

`gem_presentation.mode` in `config.yml` selects the presentation backend:

- `block` (default) uses a traditional world block and provides the broadest compatibility.
- `proximity_display` keeps the logical location as air, reveals the gem only after a player enters `reveal_range`, and hides it after the player leaves `hide_range`. The two thresholds prevent boundary flicker. Left-click the display to pick up the gem.

Run `/rg reload` after changing the mode to switch in place without scattering or changing gem coordinates and UUIDs; switching back to `block` restores the traditional blocks directly. Minecraft 1.19.4+ uses per-player-hidden `BlockDisplay` entities. Older versions use a non-persistent ArmorStand compatibility backend, which can only control whether an entity exists near any player and therefore provides weaker protection against entity-radar clients.

### Gem Escape

`gem_escape.enabled` is disabled by default. When enabled, one global cycle runs at a random interval between `min_interval` and `max_interval`, moving at most one eligible placed gem per cycle. A gem becomes eligible after it remains unmoved for `minimum_unmoved_duration`; dense clusters receive the configurable `selection.cluster_*` weighting.

Escape first searches a same-world distance band defined by `local_move.min_distance` and `max_distance`. Failed rounds expand outward by `distance_growth` and retry after `retry_delay`. The old location remains active until a safe destination is validated, and the gem keeps its UUID, type, and instance state. After the configurable `local_move.max_failed_rounds` (three by default), or after `max_local_escapes_without_pickup` successful local escapes without a pickup, only that gem is globally re-scattered inside `random_place_range`; other gems are not reset. When `broadcast` is enabled, the fallback message explicitly invalidates earlier intel, while compass navigation continues following the same UUID at its new location.

Existing configuration keys remain valid, but `min_interval` and `max_interval` now mean the interval between server-wide escape cycles instead of a separate delay for every gem. Review these values when upgrading; set `gem_escape.enabled: false` for a quick rollback.

## Extended Features

### Gem Navigation (Navigate)
Players with the `rulegems.navigate` permission can right-click a compass to navigate to the nearest gem.
- Config file: `features/navigate.yml`
- When enabled, right-clicking a compass shows the direction and distance to the nearest placed gem
- The compass receives a player-relative bearing waypoint instead of the gem's absolute coordinates; guidance is cleared as soon as the gem is picked up or otherwise becomes unavailable

### Revoke Power
`features/revoke.yml` is disabled by default. When enabled, operators can configure one gem as a countermeasure that revokes specific redeemed gem powers from a target player.

```yaml
enabled: true
confirm_timeout: 30
rules:
  judgment:
    display_name: "&cJudgment Gem"
    trigger_gem: judgment
    target_powers:
      - territory
      - jailer
    require_held: true
    consume_gem: false
    cooldown: 3600
    confirm_required: true
    broadcast: true
    allow_offline_target: true
```

- `trigger_gem` is the gem key required to start the revoke.
- `target_powers` currently matches redeemed gem keys whose powers may be revoked.
- `require_held` requires the actor to hold the trigger gem; `consume_gem` consumes and respawns it after a successful revoke.
- `cooldown` is tracked per player and rule in `data/revokes.yml`.
- `confirm_required` sends a destructive-action confirmation with target, power, consume, and broadcast details.
- `allow_offline_target` allows cleanup of recorded redeemed powers for offline targets.

### Appointment System (Appoint)
Allows rulers to delegate permissions to other players, forming a power hierarchy.

#### Core Concepts
- **Permission Set**: A predefined set of permissions, limited commands, and optional inheritance
- **Appoint**: Rulers grant permission sets to other players
- **Cascade Revoke**: When an appointer loses their permission, all their appointees are also revoked (configurable)
- **Conditions**: Permission sets can have activation conditions (time/world) that determine when they are active

#### Configuration
`features/appoint.yml` only configures `enabled`, `cascade_revoke` and
`condition_refresh_interval`. Positions come from `appoints` in loaded gem power structures,
not from `features/appoint.yml.permission_sets`. For example, place this in a file under
`powers/` and reference it from a gem with `power: ruler`:

```yaml
ruler:
  appoints:
    knight:
      display_name: "<gold>Knight"
      max_count: 3
      power:
        permissions: ["example.permission1"]
        command_allows:
          - command: "/kit warrior"
            time_limit: 3
        conditions:
          time: {enabled: true, type: day}
          worlds: {enabled: true, mode: whitelist, list: [world]}
```

Nested positions belong under the position's `power.appoints`; RuleGems grants the corresponding
appointment permissions. State remains in `data/appoints.yml`. The legacy
`features/appoint_data.yml` is validated before migration; migration failure does not create an empty store.

#### Condition System
Permission sets can have activation conditions. When conditions are not met, the permissions and commands are temporarily disabled:

**Time Condition** (`conditions.time`):
- `always`: Always active (default)
- `day`: Day only (0-12000 ticks)
- `night`: Night only (12000-24000 ticks)
- `custom`: Custom time range (specify `from` and `to`)

**World Condition** (`conditions.worlds`):
- `whitelist`: Only active in specified worlds
- `blacklist`: Active in all worlds except specified ones

Condition refresh triggers:
1. Immediately when player changes world
2. Periodically based on `condition_refresh_interval` (for time conditions)

#### Power Hierarchy Example
```
King (gem holder, has rulegems.appoint.duke)
└── Duke (appointed, has rulegems.appoint.knight via nested power.appoints)
    └── Knight (appointed)
```
When King loses the gem → Duke is cascade revoked → Knight is also cascade revoked

#### GUI Support
Click on any ruler in the Rulers GUI to view all players they have appointed with detailed information.

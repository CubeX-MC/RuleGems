# RuleGems

[中文](README.md) | English<br>
[Discord](https://discord.com/invite/7tJeSZPZgv) | [QQ频道](https://pd.qq.com/s/1n3hpe4e7?b=9)

A lightweight plugin that passes player power around through collectible "rule gems". Folia-supported.

## Installation
1. Put the JAR into the `plugins` folder
2. Start the server to generate configs
3. Adjust `config.yml` and files under `gems/`, `powers/`, and `features/` as needed

## Commands
- All `/rulegems ...` commands have the alias `/rg ...` (see `aliases: [rg]` in plugin.yml)
- `/rulegems place <gemId> <x|~> <y|~> <z|~>` Place a specific gem instance at the given coordinates
- `/rulegems tp <gemId>` Teleport to the current location of the gem instance
- `/rulegems revoke <player>` Force clear all gem-granted permissions and allowances from a player (admin intervention). If `inventory_grants` is enabled and the player still holds gems, permissions will be re-issued on the next inventory recalculation.
- `/rulegems reload` Reload configuration files
- `/rulegems rulers` List current power holders
- `/rulegems gems` Show the status of every gem instance
- `/rulegems gui` Open the GUI interface
- `/rulegems scatter` Collect every gem and scatter them randomly again
- `/rulegems redeem` Redeem the gem held in main hand
- `/rulegems redeemall` Redeem all gem types once the player has at least one of each
- `/rulegems history [lines] [player]` View recent history records, optionally filtered by player
- `/rulegems setaltar <gemKey>` Set the altar location for a gem at your current position
- `/rulegems removealtar <gemKey>` Remove the altar location for a gem
- `/rulegems appoint <perm_set> <player>` Appoint a player to a permission set
- `/rulegems dismiss <perm_set> <player>` Dismiss a player's appointment
- `/rulegems appointees [perm_set]` View list of appointees

## Permissions
- `rulegems.admin` Admin commands (default OP)
- `rulegems.redeem` Redeem single (default true)
- `rulegems.redeemall` Redeem all (default true)
- `rulegems.rulers` View current holder (default true)
- `rulegems.gems` View gem list (default true)
- `rulegems.help` View command help (default true)
- `rulegems.navigate` Use compass to navigate to the nearest gem (default false)
- `rulegems.appoint.<perm_set>` Appoint other players to the specified permission set

## Compatibility
- Servers: Spigot / Paper 1.16+; fully Folia compatible
- Optional dependency: Vault (permission backend)

## Mechanics
Each gem type can grant permissions, Vault groups and limited-use commands. Every gem instance is unique and permanently exists somewhere on the server (either placed in the world or held in a player inventory). Five application modes can be combined:

1. **inventory_grants** – breaking a gem block puts the gem into the player inventory and immediately grants the corresponding permissions and limited commands. When the gem leaves the inventory (logout, death, placement, etc.) these perks are removed. Limited-command usage attaches to the most recent holder: a returning holder keeps remaining uses; a new holder inherits the remaining uses once the old owner no longer owns that gem type.
2. **redeem_enabled** – `/rg redeem` while holding a gem consumes that specific instance (it respawns elsewhere) and grants its rewards. Ownership tracking is per gem instance (UUID). Permissions and allowances are only revoked once the previous owner no longer owns any instance of that gem type. Mutual exclusions are respected during redemption.
3. **full_set_grants_all** – once a player has at least one instance of every gem type, `/rg redeemall` grants every gem reward (ignoring mutual exclusions) plus extra perks defined under `redeem_all`. The previous full-set holder keeps everything until another player successfully `redeemall`s.
4. **place_redeem_enabled** – placing a gem near its configured altar redeems that gem directly.
5. **hold_to_redeem_enabled** – redeem by holding right-click for the configured duration.

## Features & Configuration Notes
- Every gem instance has its own UUID; use `/rulegems place <gemId> ...` for precise placement.
- `gems.<key>.count` defines how many instances of a gem type should exist; full-set checks only require at least one per key.
- `gems.<key>.mutual_exclusive` declares mutually exclusive types (applies to `inventory_grants` and `redeem_enabled`; ignored for `redeem_all`).
- `gems.<key>.command_allows` supports both map form and list form. `time_limit: -1` means unlimited uses. Extras granted by `redeem_all` live under root `redeem_all.command_allows` with the same syntax, counted under a synthetic `ALL` key.
- Permissions and groups are granted on a per-type counter: 0→1 grants, 1→0 revokes. Limited commands follow the same counters.
- Root `redeem_all` supports extra perks: `broadcast`, `titles`, `sound`, `permissions`, and `command_allows` (same syntax as above, applied when `redeemall` succeeds).
- When combining with Vault, group adds/removals are routed through the configured permission provider.

## Extended Features

### Gem Navigation (Navigate)
Players with the `rulegems.navigate` permission can right-click a compass to navigate to the nearest gem.
- Config file: `features/navigate.yml`
- When enabled, right-clicking a compass shows the direction and distance to the nearest gem

### Appointment System (Appoint)
Allows rulers to delegate permissions to other players, forming a power hierarchy.

#### Core Concepts
- **Permission Set**: A predefined set of permissions, limited commands, and optional inheritance
- **Appoint**: Rulers grant permission sets to other players
- **Cascade Revoke**: When an appointer loses their permission, all their appointees are also revoked (configurable)
- **Conditions**: Permission sets can have activation conditions (time/world) that determine when they are active

#### Configuration
`features/appoint.yml` defines permission sets:
```yaml
enabled: true
cascade_revoke: true  # Cascade revocation
condition_refresh_interval: 30  # Condition refresh interval (seconds), set to 0 to disable

permission_sets:
  knight:  # Permission set key, corresponds to rulegems.appoint.knight
    display_name: "&6Knight"
    description: "Basic combat permissions"
    max_appointments: 3  # Max appointees per appointer, -1 for unlimited
    permissions:
      - example.permission1
    command_allows:  # Limited commands (same syntax as gems)
      - command: "/kit warrior"
        time_limit: 3
    delegate_permissions:  # Permissions that can be further delegated
      - rulegems.appoint.squire
    inherits:  # Inherit from other permission sets
      - squire
    on_appoint:  # Commands executed on appointment
      - "console: broadcast %player% appointed %target% as Knight"
    on_revoke:  # Commands executed on dismissal
      - "console: broadcast %target%'s Knight title was revoked"
    
    # Conditions (optional)
    conditions:
      time:
        enabled: true
        type: day  # always / day / night / custom
        from: 0    # Custom time range (only for type=custom)
        to: 12000  # Minecraft time: 0=sunrise, 6000=noon, 12000=sunset
      worlds:
        enabled: true
        mode: whitelist  # whitelist / blacklist
        list:
          - world
          - world_nether
```

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
└── Duke (appointed, has rulegems.appoint.knight via delegate_permissions)
    └── Knight (appointed)
```
When King loses the gem → Duke is cascade revoked → Knight is also cascade revoked

#### GUI Support
Click on any ruler in the Rulers GUI to view all players they have appointed with detailed information.

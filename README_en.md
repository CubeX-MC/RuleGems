# RuleGems

[中文](README.md) | English<br>
[Discord](https://discord.com/invite/7tJeSZPZgv) | [QQ频道](https://pd.qq.com/s/1n3hpe4e7?b=9)

A lightweight plugin that passes player power around through collectible "rule gems". Folia-supported.

## Installation
1. Put the JAR into the `plugins` folder
2. Start the server to generate configs
3. Adjust `config.yml` / `rulegems.yml` as needed

## Commands
- All `/rulegems ...` commands have the alias `/rg ...` (see `aliases: [rg]` in plugin.yml)
- `/rulegems place <gemId> <x|~> <y|~> <z|~>` Place a specific gem instance at the given coordinates
- `/rulegems tp <gemId>` Teleport to the current location of the gem instance
- `/rulegems revoke <player>` Force clear all gem-granted permissions and allowances from a player (admin intervention). If `inventory_grants` is enabled and the player still holds gems, permissions will be re-issued on the next inventory recalculation.
- `/rulegems reload` Reload configuration files
- `/rulegems rulers` List current power holders
- `/rulegems gems` Show the status of every gem instance
- `/rulegems scatter` Collect every gem and scatter them randomly again
- `/rulegems redeem` Redeem the gem held in main hand
- `/rulegems redeemall` Redeem all gem types once the player has at least one of each
- `/rulegems history [lines] [player]` View recent history records, optionally filtered by player

## Permissions
- `rulegems.admin` Admin commands (default OP)
- `rulegems.redeem` Redeem single (default true)
- `rulegems.redeemall` Redeem all (default true)
- `rulegems.rulers` View current holder (default true)

## Compatibility
- Servers: Spigot / Paper 1.16+; fully Folia compatible
- Optional dependency: Vault (permission backend)

## Mechanics
Each gem type can grant permissions, Vault groups and limited-use commands. Every gem instance is unique and permanently exists somewhere on the server (either placed in the world or held in a player inventory). Three application modes can be combined:

1. **inventory_grants** – breaking a gem block puts the gem into the player inventory and immediately grants the corresponding permissions and limited commands. When the gem leaves the inventory (logout, death, placement, etc.) these perks are removed. Limited-command usage attaches to the most recent holder: a returning holder keeps remaining uses; a new holder inherits the remaining uses once the old owner no longer owns that gem type.
2. **redeem_enabled** – `/rg redeem` while holding a gem consumes that specific instance (it respawns elsewhere) and grants its rewards. Ownership tracking is per gem instance (UUID). Permissions and allowances are only revoked once the previous owner no longer owns any instance of that gem type. Mutual exclusions are respected during redemption.
3. **full_set_grants_all** – once a player has at least one instance of every gem type, `/rg redeemall` grants every gem reward (ignoring mutual exclusions) plus extra perks defined under `redeem_all`. The previous full-set holder keeps everything until another player successfully `redeemall`s.

## Features & Configuration Notes
- Every gem instance has its own UUID; use `/rulegems place <gemId> ...` for precise placement.
- `gems.<key>.count` defines how many instances of a gem type should exist; full-set checks only require at least one per key.
- `gems.<key>.mutual_exclusive` declares mutually exclusive types (applies to `inventory_grants` and `redeem_enabled`; ignored for `redeem_all`).
- `gems.<key>.command_allows` supports both map form and list form. `time_limit: -1` means unlimited uses. Extras granted by `redeem_all` live under root `redeem_all.command_allows` with the same syntax, counted under a synthetic `ALL` key.
- Permissions and groups are granted on a per-type counter: 0→1 grants, 1→0 revokes. Limited commands follow the same counters.
- Root `redeem_all` supports extra perks: `broadcast`, `titles`, `sound`, `permissions`, and `command_allows` (same syntax as above, applied when `redeemall` succeeds).
- When combining with Vault, group adds/removals are routed through the configured permission provider.

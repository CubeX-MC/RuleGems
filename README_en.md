# RuleGems

[中文](README.md) | English<br>
[Discord](https://discord.com/invite/7tJeSZPZgv) | [QQ频道](https://pd.qq.com/s/1n3hpe4e7?b=9)

A lightweight plugin that transfers player power through collecting "gems". Folia-friendly and multi-version support.

## Installation
1. Put the JAR into the `plugins` folder
2. Start the server to generate configs
3. Adjust `config.yml` / `rulegems.yml` as needed

## Commands
- All `/rulegems ...` commands can be used with the alias `/rg ...` (see `aliases: [rg]` in plugin.yml)
- `/rulegems place <gemId> <x|~> <y|~> <z|~>` Place a gem instance at coordinates
- `/rulegems tp <gemId>` Teleport to specified gem location
- `/rulegems revoke <player>` Force revoke all gem permissions from a player (admin intervention)
- `/rulegems reload` Reload configuration
- `/rulegems rulers` Show current power holders
- `/rulegems gems` Show gem status
- `/rulegems scatter` Scatter all gems randomly
- `/rulegems redeem` Redeem single gem while holding it in main hand
- `/rulegems redeemall` Redeem all at once when complete
- `/rulegems history [lines] [player]` View gem history records

## Permissions
- `rulegems.admin` Admin commands (default OP)
- `rulegems.redeem` Redeem single (default true)
- `rulegems.redeemall` Redeem all (default true)
- `rulegems.rulers` View current holder (default true)

## Compatibility
- Server: Spigot/Paper 1.16+; Folia compatible
- Optional: Vault (permission backend)

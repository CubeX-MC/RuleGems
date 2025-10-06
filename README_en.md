# RuleGems

[中文](README.md)

A lightweight plugin that transfers player power through collecting "gems". Folia-friendly and multi-version support.

## Installation
1. Put the JAR into the `plugins` folder
2. Start the server to generate configs
3. Adjust `config.yml` / `rulegems.yml` as needed

## Commands
- All `/rulegems ...` commands can be used with the alias `/rg ...` (see `aliases: [rg]` in plugin.yml)
- `/rulegems place <x> <y> <z>` Place a gem
- `/rulegems revoke` Revoke current power holder
- `/rulegems reload` Reload configuration
- `/rulegems rulers` Show current power holder
- `/rulegems gems` Show gem status
- `/rulegems scatter` Scatter all gems randomly
- `/rulegems redeem <key|name>` Redeem a single gem
- `/rulegems redeemall` Redeem all at once when complete

## Permissions
- `rulegems.admin` Admin commands (default OP)
- `rulegems.redeem` Redeem single (default true)
- `rulegems.redeemall` Redeem all (default true)
- `rulegems.rulers` View current holder (default true)

## Compatibility
- Server: Spigot/Paper 1.16+; Folia compatible
- Optional: Vault (permission backend)

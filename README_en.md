# RulerGem

[中文](README.md)

A lightweight plugin that transfers player power through collecting "gems". Folia-friendly and multi-version support.

## Installation
1. Put the JAR into the `plugins` folder
2. Start the server to generate configs
3. Adjust `config.yml` / `rulergem.yml` as needed

## Commands
- All `/rulergem ...` commands can be used with the alias `/rg ...` (see `aliases: [rg]` in plugin.yml)
- `/rulergem place <x> <y> <z>` Place a gem
- `/rulergem revoke` Revoke current power holder
- `/rulergem reload` Reload configuration
- `/rulergem rulers` Show current power holder
- `/rulergem gems` Show gem status
- `/rulergem scatter` Scatter all gems randomly
- `/rulergem redeem <key|name>` Redeem a single gem
- `/rulergem redeemall` Redeem all at once when complete

## Permissions
- `rulergem.admin` Admin commands (default OP)
- `rulergem.redeem` Redeem single (default true)
- `rulergem.redeemall` Redeem all (default OP)
- `rulergem.rulers` View current holder (default true)

## Compatibility
- Server: Spigot/Paper 1.16+; Folia compatible
- Optional: Vault (permission backend)

## License
MIT

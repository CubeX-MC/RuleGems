# PowerGem

[中文](README.md)

A lightweight plugin that transfers player power through collecting "gems". Folia-friendly and multi-version support.

## Installation
1. Put the JAR into the `plugins` folder
2. Start the server to generate configs
3. Adjust `config.yml` / `powergem.yml` as needed

## Commands
- All `/powergem ...` commands can be used with the alias `/pg ...` (see `aliases: [pg]` in plugin.yml)
- `/powergem place <x> <y> <z>` Place a gem
- `/powergem revoke` Revoke current power holder
- `/powergem reload` Reload configuration
- `/powergem powerplayer` Show current power holder
- `/powergem gems` Show gem status
- `/powergem scatter` Scatter all gems randomly
- `/powergem redeem <key|name>` Redeem a single gem
- `/powergem redeemall` Redeem all at once when complete

## Permissions
- `powergem.admin` Admin commands (default OP)
- `powergem.redeem` Redeem single (default true)
- `powergem.redeemall` Redeem all (default OP)
- `powergem.powerplayer` View current holder (default true)

## Compatibility
- Server: Spigot/Paper 1.16+; Folia compatible
- Optional: Vault (permission backend)

## License
MIT

# PowerGem

[中文](README_zh.md)

A Minecraft plugin that implements a power gem system for server management.

This plugin introduces an innovative way to manage server permissions through a gem collection mechanism, allowing server administrators to delegate power to players through gameplay rather than direct permission assignment. This reduces management overhead and creates an engaging way to select server moderators.

## Description

PowerGem is an experimental plugin that adds special power gems to your Minecraft server. These gems can be collected, placed, and used to grant special permissions to players who collect all of them.

## Core Concept

The plugin implements a unique "rule by collection" system:
- Server managers no longer need to manually assign moderator permissions
- Players compete fairly to collect gems
- Power is distributed through gameplay mechanics
- Automatic power transition when gems are scattered
- Built-in anti-abuse mechanisms

## Features

- Configurable number of power gems
- Customizable gem appearance and effects
- Automatic gem scattering system
- Permission-based power system
- Anti-abuse mechanisms (gems can't be stored in containers)
- Persistent storage of gem locations and power player data

## Commands

- `/powergem place <x> <y> <z>` - Place a gem at specific coordinates
- `/powergem revoke` - Revoke power from current power holder
- `/powergem reload` - Reload plugin configuration
- `/powergem powerplayer` - Show current power holder
- `/powergem gems` - Display gem status and locations
- `/powergem scatter` - Scatter all gems randomly
- `/powergem help` - Show help information

## Configuration

### config.yml
```yaml
required_count: 5                # Total number of gems in the server
gem_material: RED_STAINED_GLASS # Material type for gems
gem_name: "&c权力宝石"          # Gem display name (supports color codes)
gem_particle: FLASH             # Particle effect for gems
gem_sound: AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE # Sound effect

# Location restrictions
use_required_locations: false    # Whether gems must be placed in specific locations
required_locations:             # Required placement area (if enabled)
  world: world
  center:
    x: 0
    y: 70
    z: 0
  radius: 5

# Random placement settings
random_place_range:             # Area for random gem scattering
  world: world
  corner1: {x: -100, y: 160, z: -100}
  corner2: {x: 100, y: 30, z: 100}

# Event configurations
revoke_power:                   # Actions when power is revoked
  commands:
    - "broadcast &c%player% 的权力宝石被强制回收！"
  sound: ENTITY_ENDERMAN_TELEPORT
  particle: FLAME

gem_union_execute:              # Actions when all gems are collected
  commands:
    - "give %player% diamond 5"
  sound: ENTITY_ENDERMAN_TELEPORT
  particle: FLAME

gem_scatter_execute:            # Actions when gems are scattered
  commands:
    - "broadcast &c%player% 的权力宝石散落了！"
  sound: ENTITY_ENDERMAN_TELEPORT
```

## Installation

1. Download the plugin JAR file
2. Place it in your server's `plugins` folder
3. Restart the server or load the plugin
4. Configure the plugin in `config.yml`

## Permissions

- `op` - Required for all PowerGem commands

## Dependencies

- Spigot/Paper 1.16+

## Building

The project uses Java and requires the Spigot API. Build using your preferred Java IDE or build system.

## License

This plugin is provided as-is with no warranty. Feel free to modify and distribute according to your needs.

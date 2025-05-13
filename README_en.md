# PowerGems

[中文](README.md)

This is an innovative server management plugin that implements player self-governance through a gem collection mechanism, allowing servers to select managers through gameplay rather than direct permission assignment. This approach both reduces the pressure on server administrators and creates an interesting way to select managers.

## Description

PowerGem is an experimental plugin that adds special power gems to your Minecraft server. These gems can be collected and placed, and when a player collects all the gems, they gain specified permissions (and power) on the server.

## Core Concept

The plugin implements a unique "collection equals power" system:
- Server administrators no longer need to manually assign management permissions
- Players compete fairly to collect gems
- Power is naturally distributed through game mechanics
- When gems are scattered, power transfers automatically
- Built-in anti-abuse mechanisms

## Features

- Configurable number of gems
- Customizable gem appearance and effects
- Automatic gem distribution system
- Permission-based power system
- Anti-abuse mechanisms (gems cannot be stored in containers)
- Persistent storage of gem locations and power holder data
- Supports Folia server

## Commands

- `/powergem place <x> <y> <z>` - Place a gem at specified coordinates
- `/powergem revoke` - Revoke current power holder's permissions
- `/powergem reload` - Reload plugin configuration
- `/powergem powerplayer` - Display current power holder
- `/powergem gems` - Show gem status and locations
- `/powergem scatter` - Randomly scatter all gems
- `/powergem help` - Display help information

## Configuration

### config.yml
```yaml
required_count: 5                # Total number of gems in the server
gem_material: RED_STAINED_GLASS # Gem material type
gem_name: "&cPower Gem"         # Gem display name (supports color codes)
gem_particle: FLASH             # Gem particle effect
gem_sound: AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE # Sound effect

# Position restrictions
use_required_locations: false    # Whether gems must be placed in specific locations
required_locations:             # Required placement area (if enabled)
  world: world
  center:
    x: 0
    y: 70
    z: 0
  radius: 5

# Random placement settings
random_place_range:             # Area for randomly scattering gems
  world: world
  corner1: {x: -100, y: 160, z: -100}
  corner2: {x: 100, y: 30, z: 100}

# Event configuration
revoke_power:                   # Actions when power is revoked
  commands:
    - "broadcast &c%player%'s power gems have been forcibly reclaimed!"
  sound: ENTITY_ENDERMAN_TELEPORT
  particle: FLAME

gem_union_execute:              # Actions when all gems are collected
  commands:
    - "give %player% diamond 5"
  sound: ENTITY_ENDERMAN_TELEPORT
  particle: FLAME

gem_scatter_execute:            # Actions when gems are scattered
  commands:
    - "broadcast &c%player%'s power gems have scattered!"
  sound: ENTITY_ENDERMAN_TELEPORT
```

## Installation

1. Download the plugin JAR file
2. Place it in your server's `plugins` folder
3. Restart the server or load the plugin
4. Configure the plugin in `config.yml`

## Permissions

- `op` - OP permission required to use all PowerGem commands

## Dependencies

- Requires Spigot/Paper 1.16 or higher
- Supports Folia server

## Building

The project is developed in Java and requires the Spigot API and Folia API. Use your preferred Java IDE or build system to build it.

## License

This plugin is provided as-is without any warranty. You are free to modify and distribute it as needed.

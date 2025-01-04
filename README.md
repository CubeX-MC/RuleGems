# 权力宝石

[English](README_en.md)

这是一个创新的服务器管理插件，通过宝石收集机制实现玩家自治，让服务器通过游戏玩法而不是直接分配权限来选择管理者。这种方式既减轻了服务器管理员的压力，又创造了一种有趣的管理者选拔方式。

## 描述

PowerGem 是一个实验性的插件，它为您的 Minecraft 服务器添加了特殊的权力宝石。这些宝石可以被收集和放置，当玩家收集齐所有宝石时，就会获得服务器指定的权限（及权力）。

## 核心理念

插件实现了独特的"收集即权力"系统：
- 服务器管理员不再需要手动分配管理权限
- 玩家通过公平竞争来收集宝石
- 权力通过游戏机制自然分配
- 当宝石被散布时，权力自动转移
- 内置防滥用机制

## 功能特性

- 可配置的宝石数量
- 可自定义的宝石外观和效果
- 自动的宝石散布系统
- 基于权限的权力系统
- 防滥用机制（宝石不能存储在容器中）
- 持久化存储宝石位置和权力者数据

## 命令

- `/powergem place <x> <y> <z>` - 在指定坐标放置宝石
- `/powergem revoke` - 收回当前权力持有者的权限
- `/powergem reload` - 重新加载插件配置
- `/powergem powerplayer` - 显示当前的权力持有者
- `/powergem gems` - 显示宝石状态和位置
- `/powergem scatter` - 随机散布所有宝石
- `/powergem help` - 显示帮助信息

## 配置

### config.yml
```yaml
required_count: 5                # 服务器中的宝石总数
gem_material: RED_STAINED_GLASS # 宝石的材质类型
gem_name: "&c权力宝石"          # 宝石显示名称（支持颜色代码）
gem_particle: FLASH             # 宝石的粒子效果
gem_sound: AMBIENT_UNDERWATER_LOOP_ADDITIONS_ULTRA_RARE # 音效

# 位置限制
use_required_locations: false    # 是否必须在特定位置放置宝石
required_locations:             # 必需放置区域（如果启用）
  world: world
  center:
    x: 0
    y: 70
    z: 0
  radius: 5

# 随机放置设置
random_place_range:             # 随机散布宝石的区域
  world: world
  corner1: {x: -100, y: 160, z: -100}
  corner2: {x: 100, y: 30, z: 100}

# 事件配置
revoke_power:                   # 收回权力时的动作
  commands:
    - "broadcast &c%player% 的权力宝石被强制回收！"
  sound: ENTITY_ENDERMAN_TELEPORT
  particle: FLAME

gem_union_execute:              # 收集齐所有宝石时的动作
  commands:
    - "give %player% diamond 5"
  sound: ENTITY_ENDERMAN_TELEPORT
  particle: FLAME

gem_scatter_execute:            # 宝石散布时的动作
  commands:
    - "broadcast &c%player% 的权力宝石散落了！"
  sound: ENTITY_ENDERMAN_TELEPORT
```

## 安装

1. 下载插件 JAR 文件
2. 将其放入服务器的 `plugins` 文件夹
3. 重启服务器或加载插件
4. 在 `config.yml` 中配置插件

## 权限

- `op` - 需要 OP 权限才能使用所有 PowerGem 命令

## 依赖

- 需要 Spigot/Paper 1.16 或更高版本

## 构建

项目使用 Java 开发，需要 Spigot API。使用您喜欢的 Java IDE 或构建系统进行构建。

## 许可

本插件按原样提供，不提供任何保证。您可以根据需要自由修改和分发。 
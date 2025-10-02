# RulerGem

[English](README_en.md)

一个用“宝石收集”来流转玩家权限的轻量插件，支持 Folia 与多版本。

## 安装
1. 将 JAR 放入 `plugins` 目录
2. 启动服务器自动生成配置
3. 在 `config.yml` / `rulergem.yml` 中按需调整

## 命令
- 所有 `/rulergem ...` 命令均可用别名 `/rg ...`（见 plugin.yml 的 `aliases: [rg]`）
- `/rulergem place <x> <y> <z>` 放置宝石
- `/rulergem revoke` 收回当前权力持有者
- `/rulergem reload` 重载配置
- `/rulergem powerplayer` 查看当前权力持有者
- `/rulergem gems` 查看宝石状态
- `/rulergem scatter` 随机散布全部宝石
- `/rulergem redeem <键|名字>` 兑换单颗宝石
- `/rulergem redeemall` 集齐后一次性兑换

## 权限
- `rulergem.admin` 管理指令（默认 OP）
- `rulergem.redeem` 兑换单颗（默认 true）
- `rulergem.redeemall` 兑换全部（默认 OP）
- `rulergem.powerplayer` 查看当前持有者（默认 true）

## 兼容性
- 服务器：Spigot/Paper 1.16+；Folia 兼容
- 可选依赖：Vault（权限后端）

## 许可
MIT
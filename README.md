# PowerGem

[English](README_en.md)

一个用“宝石收集”来流转玩家权限的轻量插件，支持 Folia 与多版本。

## 安装
1. 将 JAR 放入 `plugins` 目录
2. 启动服务器自动生成配置
3. 在 `config.yml` / `powergem.yml` 中按需调整

## 命令
- 所有 `/powergem ...` 命令均可用别名 `/pg ...`（见 plugin.yml 的 `aliases: [pg]`）
- `/powergem place <x> <y> <z>` 放置宝石
- `/powergem revoke` 收回当前权力持有者
- `/powergem reload` 重载配置
- `/powergem powerplayer` 查看当前权力持有者
- `/powergem gems` 查看宝石状态
- `/powergem scatter` 随机散布全部宝石
- `/powergem redeem <键|名字>` 兑换单颗宝石
- `/powergem redeemall` 集齐后一次性兑换

## 权限
- `powergem.admin` 管理指令（默认 OP）
- `powergem.redeem` 兑换单颗（默认 true）
- `powergem.redeemall` 兑换全部（默认 OP）
- `powergem.powerplayer` 查看当前持有者（默认 true）

## 兼容性
- 服务器：Spigot/Paper 1.16+；Folia 兼容
- 可选依赖：Vault（权限后端）

## 许可
MIT
# PowerGem

一个通过“宝石”来流转管理权限的轻量玩法插件，支持多版本与 Folia。

## 安装
1. 将 JAR 放入 `plugins` 目录
2. 启动服务器自动生成配置
3. 在 `config.yml`/`powergems.yml` 中按需调整

## 命令
- `/powergem place <x> <y> <z>` 放置宝石
- `/powergem revoke` 收回当前权力持有者
- `/powergem reload` 重载配置
- `/powergem powerplayer` 查看当前权力持有者
- `/powergem gems` 查看宝石状态
- `/powergem scatter` 随机散布全部宝石
- `/powergem redeem <key|名字>` 兑换单颗宝石
- `/powergem redeemall` 集齐后一次性兑换

## 权限
- `powergem.admin` 管理类子命令（默认 OP）
- `powergem.redeem` 兑换单颗（默认 true）
- `powergem.redeemall` 兑换全部（默认 OP）
- `powergem.powerplayer` 查看当前持有者（默认 true）

## 依赖与兼容
- 服务器：Spigot/Paper 1.16+；Folia 自动适配
- 可选：Vault（权限后端）

## 许可
MIT
# RuleGems 改进计划

> 基于代码审计报告（2026-02-08）制定，当前综合评分 **7.5/10**。
> 前序改进（Phase 1–3 + 4.1/4.2）已全部完成，下文仅列出待执行项。

---

## 代码现状概览

| 指标 | 数值 |
|------|------|
| 总 Java 行数 | ~15,400（含 613 行测试） |
| 最大文件 | AppointFeature 1018 行 |
| GemManager（门面） | 746 行 |
| 子管理器合计 | State 737 + Allowance 555 + Permission 841 + Placement 690 |
| 测试 | 220 tests / 6 test classes / 0 failures |
| 空 catch 块 | 55 |
| e.printStackTrace() | 4 |

---

## Phase 5 — Folia 完整兼容 & 错误处理（中工作量/高影响）

### 5.1 AppointFeature 中 `Bukkit.dispatchCommand()` 迁移至 SchedulerUtil
- **文件**: `AppointFeature.java#L671`, `#L676`, `#L817`, `#L821`
- **问题**: 4 处直接调用 `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ...)` 未通过 SchedulerUtil 包装
- **风险**: Folia 环境下可能在错误的区域线程执行，导致崩溃
- **修复**: 包装为 `SchedulerUtil.globalRun(plugin, () -> Bukkit.dispatchCommand(...), 0, -1)`

### 5.2 清理 55 个空 catch 块
- **涉及文件**: GemPermissionManager(17) / GemPlacementManager(6) / GemStateManager(5) / GemManager(5) / PowerStructureManager(5) / GUIManager(5) / GemConsumeListener(4) / 其他(8)
- **问题**: `catch (Exception ignored) {}` 吞没所有异常，导致问题难以排查
- **修复**: 至少添加 `plugin.getLogger().fine("xxx failed: " + e.getMessage())`；对关键路径（数据加载、权限操作）使用 `warning` 级别

### 5.3 消除 `e.printStackTrace()`
- **文件**: `ConfigManager.java#L147`, `GemDefinitionParser.java#L244`, `CustomCommandExecutor.java#L44`, `#L72`
- **修复**: 替换为 `plugin.getLogger().log(Level.SEVERE, "描述", e)`

### 5.4 GemPermissionManager `loadData()` UUID 容错
- **文件**: `GemPermissionManager.java#L169`
- **问题**: `UUID.fromString(playerUuidStr)` 无 try/catch，配置损坏时整个加载方法中断
- **修复**: 添加 try/catch + warning 日志，跳过损坏条目继续加载

---

## Phase 6 — 国际化完善（低工作量/中影响）

### 6.1 用户可见硬编码中文字符串迁移
迁移以下硬编码中文至语言文件：

| 文件 | 行号 | 字符串 | 建议键名 |
|------|------|--------|----------|
| GemStateManager.java | L722 | `"没有更多信息"` | `gui.no_more_info` |
| GemDefinitionParser.java | L260 | `"&c权力宝石"` | `gem.default_name` |
| GemPermissionManager.java | L469 | `"归属切换：失去最后一件该类型宝石"` | `history.ownership_lost` |
| GemPermissionManager.java | L524 | `"未知(离线)"` | `player.unknown_offline` |
| GemPermissionManager.java | L526 | `"归属切换：失去最后一件该类型宝石（离线撤销）"` | `history.ownership_lost_offline` |
| GemPermissionManager.java | L769 | `"管理员强制撤销"` / `"全部宝石权限"` | `history.admin_revoke` / `history.all_permissions` |

### 6.2 Logger 消息双语化
- **涉及**: ~30+ 处 `plugin.getLogger().warning/info("中文...")` 分布在 8+ 文件
- **方案**: 统一改为英文日志（Minecraft 服务端日志惯例为英文），或通过 `LanguageManager.logMessage()` 走国际化路径

### 6.3 调试日志级别修正
- **文件**: `CustomCommandExecutor.java#L40, #L124-L141`
- **问题**: 6+ 条 `logger.info("[调试]...")` 在生产环境输出过多
- **修复**: 改为 `logger.fine()` 或 `logger.config()`

---

## Phase 7 — 线程安全加固（中工作量/高影响）

### 7.1 关键共享 Map 改用 ConcurrentHashMap
项目明确支持 Folia（多区域线程），且使用了 `runTaskLaterAsynchronously()`，以下字段有并发访问风险：

| 文件 | 字段 | 建议 |
|------|------|------|
| CustomCommandExecutor | `playerCooldowns` | → `ConcurrentHashMap` |
| GemStateManager | `locationToGemUuid`, `gemUuidToHolder`, `playerNameCache` | → `ConcurrentHashMap` |
| GemPermissionManager | `pendingPermRevokes` 等 4 个 pending map | → `ConcurrentHashMap` |
| GemPlacementManager | `gemEscapeTasks` | → `ConcurrentHashMap` |

### 7.2 合并 GemPermissionManager 中 4 个 pending revoke Map
- **文件**: `GemPermissionManager.java#L55-L61`
- **问题**: `pendingPermRevokes` / `pendingGroupRevokes` / `pendingKeyRevokes` / `pendingEffectRevokes` 结构和逻辑高度重复
- **方案**: 合并为 `Map<UUID, PendingRevoke>` 其中 `PendingRevoke` 包含 `Set<String> permissions, groups, keys, effects`

---

## Phase 8 — 测试覆盖提升（中工作量/高长期收益）

当前测试覆盖率：613/15399 = **4%**，仅覆盖 model 包。

### 8.1 `GemAllowanceManager` 单元测试
- 测试命令限次的 grant/consume/refund 逻辑
- 测试 held vs redeemed 实例的隔离
- 测试 `getAvailableCommandLabels()` 聚合逻辑

### 8.2 `GemPermissionManager` 单元测试
- 测试 redeem→权限授予→owner key count 递增
- 测试离线撤销队列（queueOfflineRevokes → applyPendingRevokesIfAny）
- 测试全套拥有者判定

### 8.3 `GemStateManager` 单元测试
- 测试 UUID↔Location↔Holder 映射一致性
- 测试 `isRuleGem()` 物品识别
- 测试 `loadData()` 兼容旧格式

### 8.4 集成测试框架
- 引入 MockBukkit 3.x 至 pom.xml
- 创建基础集成测试：插件加载 → 配置解析 → 宝石生命周期

---

## Phase 9 — 性能改进（低工作量/中影响）

### 9.1 `HistoryLogger` 异步反向读取
- **文件**: `HistoryLogger.java`
- **问题**: 日志读取在主线程执行，文件大时卡顿
- **修复**: 使用异步任务读取后回调主线程显示

### 9.2 `saveAllowedData()` 脏标记 + 定时批量写入
- **问题**: 每次消耗命令限次都触发全量保存
- **修复**: 引入 `dirty` 标记，定时（如 60 秒）批量写入

### 9.3 `GemAllowanceManager` 反向索引
- **问题**: `getAvailableCommandLabels()` 需遍历多层 Map
- **修复**: 维护 `playerUuid → Set<label>` 反向索引，在 grant/revoke 时同步更新

---

## Phase 10 — 代码整洁（低工作量/低影响）

### 10.1 拆分长方法
以下方法超过 100 行，建议提取子方法：

| 方法 | 文件 | 行数 | 建议 |
|------|------|------|------|
| `buildGemDefinitionFromMap()` | GemDefinitionParser | ~140 | 按属性组拆分（基础属性/视觉效果/权力结构） |
| `parsePowerStructure()` | GemDefinitionParser | ~135 | 按子节点拆分（permissions/effects/commands/conditions） |
| `onCommand()` | RuleGemsCommand | ~118 | 各子命令提取为独立方法 |
| `cascadeRevokeForPermSet()` | AppointFeature | ~103 | 提取 revoke 单个被任命者的逻辑 |

### 10.2 GemManager 门面方法精简
- **文件**: `GemManager.java` 底部 ~50 行纯委托方法
- **方案**: 使消费者（GUI、Listener）直接注入子管理器，逐步减少 GemManager 的中转角色

---

## 进度追踪

| 步骤 | 状态 | 说明 |
|------|------|------|
| 5.1 AppointFeature dispatchCommand | ✅ | 4 处已包装至 SchedulerUtil |
| 5.2 清理空 catch 块 | ✅ | 55 处已添加日志 |
| 5.3 消除 e.printStackTrace() | ✅ | 4 处已替换为 plugin.getLogger().log() |
| 5.4 loadData UUID 容错 | ✅ | GemPermissionManager#L169 已添加 try/catch |
| 6.1 硬编码中文迁移 | ✅ | 6 处已迁移至 lang/*.yml |
| 6.2 Logger 消息双语化 | ✅ | 43+ 处已改为英文日志 |
| 6.3 调试日志级别修正 | ✅ | CustomCommandExecutor 7 处 info→fine |
| 7.1 ConcurrentHashMap | ✅ | 4 个文件 9 个字段已迁移至 ConcurrentHashMap |
| 7.2 合并 pending revoke Map | ✅ | 新增 PendingRevoke 模型，4 个 Map 合并为 1 个 |
| 8.1 AllowanceManager 测试 | ✅ | 54 tests：consume/refund/query/dirty flag/reassign/label cache |
| 8.2 PermissionManager 测试 | ✅ | 46 tests：ownerKeyCount/offline revoke queue/rulers/markRedeemed/revokeAll |
| 8.3 StateManager 测试 | ✅ | 60 tests：mapping/isRuleGem/getGemUUID/findDef/resolve/counts/cache |
| 8.4 集成测试框架 | ⏸️ | MockBukkit 需 Paper API 1.20+，与当前 Spigot 1.16.5 不兼容；已用 Mockito mockStatic 替代 |
| 9.1 HistoryLogger 异步读取 | ✅ | RuleGemsCommand 中 handleHistoryCommand 使用 SchedulerUtil.asyncRun 异步读取文件 |
| 9.2 脏标记批量写入 | ✅ | GemAllowanceManager 新增 dirty flag，60 秒定时 flushIfDirty |
| 9.3 反向索引 | ✅ | GemAllowanceManager 新增 labelIndexCache 惰性反向索引 |
| 10.1 拆分长方法 | ✅ | buildGemDefinitionFromMap 提取 7 个子方法；cascadeRevokeForPermSet 提取 2 个子方法 |
| 10.2 门面方法精简 | ✅ | 新增子管理器 getter；CommandAllowanceListener 直接注入 GemAllowanceManager；删除 19 个死/冗余门面方法 |

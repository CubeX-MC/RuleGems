package org.cubexmc.features.appoint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
// Removed unused import: PotionEffectType

import org.cubexmc.RuleGems;
import org.cubexmc.features.Feature;
import org.cubexmc.model.AppointDefinition;
// Removed unused import: EffectConfig
import org.cubexmc.model.GemDefinition;
import org.cubexmc.model.PowerStructure;

/**
 * 委任功能
 * 允许拥有特定权限的玩家任命其他玩家获得权限集
 */
public class AppointFeature extends Feature {

    private static final String PERMISSION_PREFIX = "rulegems.appoint.";
    
    private final Map<String, AppointDefinition> appointDefinitions = new HashMap<>();
    
    // 任命数据: permSetKey -> appointeeUuid -> Appointment
    private final Map<String, Map<UUID, Appointment>> appointments = new HashMap<>();
    
    // 权限附件: playerUuid -> PermissionAttachment
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    
    // 级联撤销（连坐制）
    private boolean cascadeRevoke = true;
    
    // 配置文件
    private File configFile;
    private YamlConfiguration config;
    
    // 数据文件
    private File dataFile;
    private YamlConfiguration data;
    
    // 定时任务ID
    private int refreshTaskId = -1;
    
    // 条件刷新间隔（秒）
    private int conditionRefreshInterval = 30;

    public AppointFeature(RuleGems plugin) {
        super(plugin, PERMISSION_PREFIX + "*");
    }

    @Override
    public void initialize() {
        // 初始化配置文件
        initConfigFile();
        // 加载数据
        loadData();
        // 为在线玩家恢复权限
        restoreOnlinePlayersPermissions();
        // 启动条件刷新任务
        startConditionRefreshTask();
    }

    @Override
    public void shutdown() {
        // 停止定时任务
        stopConditionRefreshTask();
        // 保存数据
        saveData();
        // 使用 PowerStructureManager 清除所有 appoint 命名空间的权限
        org.cubexmc.manager.PowerStructureManager psm = plugin.getPowerStructureManager();
        if (psm != null) {
            psm.clearAllInNamespace("appoint");
        }
        // 兼容：移除旧的权限附件
        for (PermissionAttachment attachment : attachments.values()) {
            try {
                attachment.remove();
            } catch (Exception ignored) {}
        }
        attachments.clear();
    }

    @Override
    public void reload() {
        // 停止旧的定时任务
        stopConditionRefreshTask();
        // 重新加载配置
        initConfigFile();
        // 重新加载数据
        loadData();
        // 重新应用权限
        restoreOnlinePlayersPermissions();
        // 重新启动条件刷新任务
        startConditionRefreshTask();
    }

    /**
     * 初始化配置文件
     */
    private void initConfigFile() {
        // 确保 features 文件夹存在
        File featuresFolder = new File(plugin.getDataFolder(), "features");
        if (!featuresFolder.exists()) {
            featuresFolder.mkdirs();
        }
        
        // 配置文件
        configFile = new File(featuresFolder, "appoint.yml");
        if (!configFile.exists()) {
            plugin.saveResource("features/appoint.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        
        // 数据文件 - 统一放到 data 文件夹
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        dataFile = new File(dataFolder, "appoints.yml");
        
        // 迁移旧数据文件
        File oldDataFile = new File(featuresFolder, "appoint_data.yml");
        if (oldDataFile.exists() && !dataFile.exists()) {
            try {
                java.nio.file.Files.move(oldDataFile.toPath(), dataFile.toPath());
                plugin.getLogger().info("Migrated appoint_data.yml to data/appoints.yml");
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to migrate appoint_data.yml: " + e.getMessage());
            }
        }
        
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create data/appoints.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        
        // 加载配置
        loadConfig();
    }

    /**
     * 加载配置
     */
    private void loadConfig() {
        appointDefinitions.clear();
        
        this.enabled = config.getBoolean("enabled", true);
        this.cascadeRevoke = config.getBoolean("cascade_revoke", true);
        this.conditionRefreshInterval = config.getInt("condition_refresh_interval", 30);
        
        // 从所有已加载的 Gems 中注册 AppointDefinition
        registerAppointDefinitionsFromGems();
        
        plugin.getLogger().info("Loaded " + appointDefinitions.size() + " appoint definitions.");
    }
    
    /**
     * 从 Gems 中注册 AppointDefinition
     */
    private void registerAppointDefinitionsFromGems() {
        List<GemDefinition> gems = plugin.getConfigManager().getGemDefinitions();
        if (gems == null) return;
        
        for (GemDefinition gem : gems) {
            if (gem.getPowerStructure() != null) {
                registerAppointsRecursively(gem.getPowerStructure());
            }
        }
        
        // 同时也从 Power Templates 中注册，以防有些模板未被 Gem 使用但被引用
        // ConfigManager 没有直接暴露 templates map，但我们可以通过 parsePowerStructure 间接访问
        // 或者给 ConfigManager 加一个 getter。这里暂时只从 Gems 注册。
        // 如果用户希望定义全局可用的 Appoint，应该在 appoint.yml 定义，或者确保至少有一个 Gem 使用了该模板。
    }
    
    private void registerAppointsRecursively(PowerStructure power) {
        if (power == null || power.getAppoints() == null) return;
        
        for (Map.Entry<String, AppointDefinition> entry : power.getAppoints().entrySet()) {
            String key = entry.getKey();
            AppointDefinition def = entry.getValue();
            
            // 注册定义
            if (!appointDefinitions.containsKey(key)) {
                appointDefinitions.put(key, def);
            }
            
            // 递归注册下级
            if (def.getPowerStructure() != null) {
                registerAppointsRecursively(def.getPowerStructure());
            }
        }
    }

    /**
     * 加载任命数据
     */
    private void loadData() {
        appointments.clear();
        
        ConfigurationSection appointmentsSection = data.getConfigurationSection("appointments");
        if (appointmentsSection == null) return;
        
        for (String permSetKey : appointmentsSection.getKeys(false)) {
            ConfigurationSection setSection = appointmentsSection.getConfigurationSection(permSetKey);
            if (setSection == null) continue;
            
            Map<UUID, Appointment> setAppointments = new HashMap<>();
            for (String uuidStr : setSection.getKeys(false)) {
                try {
                    UUID appointeeUuid = UUID.fromString(uuidStr);
                    ConfigurationSection appointmentSection = setSection.getConfigurationSection(uuidStr);
                    if (appointmentSection == null) continue;
                    
                    String appointerStr = appointmentSection.getString("appointed_by");
                    UUID appointerUuid = appointerStr != null ? UUID.fromString(appointerStr) : null;
                    long appointedAt = appointmentSection.getLong("appointed_at", System.currentTimeMillis());
                    
                    Appointment appointment = new Appointment(appointeeUuid, permSetKey, appointerUuid, appointedAt);
                    setAppointments.put(appointeeUuid, appointment);
                } catch (IllegalArgumentException ignored) {}
            }
            appointments.put(permSetKey, setAppointments);
        }
    }

    /**
     * 保存任命数据
     */
    public void saveData() {
        data.set("appointments", null);
        
        for (Map.Entry<String, Map<UUID, Appointment>> entry : appointments.entrySet()) {
            String permSetKey = entry.getKey();
            for (Map.Entry<UUID, Appointment> appointEntry : entry.getValue().entrySet()) {
                Appointment appointment = appointEntry.getValue();
                String path = "appointments." + permSetKey + "." + appointment.getAppointeeUuid().toString();
                if (appointment.getAppointerUuid() != null) {
                    data.set(path + ".appointed_by", appointment.getAppointerUuid().toString());
                }
                data.set(path + ".appointed_at", appointment.getAppointedAt());
            }
        }
        
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save appoint data: " + e.getMessage());
        }
    }

    /**
     * 为在线玩家恢复权限
     */
    private void restoreOnlinePlayersPermissions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyPermissions(player);
        }
    }

    /**
     * 任命玩家
     */
    public boolean appoint(Player appointer, Player appointee, String permSetKey) {
        if (!enabled) return false;
        
        AppointDefinition def = appointDefinitions.get(permSetKey);
        if (def == null) return false;
        
        // 检查任命者权限
        if (!appointer.hasPermission(PERMISSION_PREFIX + permSetKey) && !appointer.hasPermission("rulegems.admin")) {
            // 还要检查任命者是否拥有包含此 appoint 的 Power
            // 这需要反向查找：appointer 拥有的 PowerStructure 中是否包含 key 为 permSetKey 的 appoint
            // 目前简化处理：只要有 rulegems.appoint.<key> 权限即可
            // 或者，如果 appointer 是通过 Gem 获得权力的，GemManager 会给予他相应的权限吗？
            // 目前 GemManager 还没有自动给予 appoint 权限的逻辑。
            // 我们需要修改 GemManager 或者在这里放宽检查。
            // 理想情况下，拥有 Gem -> 拥有 Power -> 拥有 Appoint 权利。
            // 我们可以检查 appointer 是否拥有任何包含此 appoint 的 Gem。
            // 但为了性能，最好还是依赖权限节点。
            // 建议：当玩家获得 Gem 时，GemManager 应该给予他所有下级 appoint 的权限节点。
            return false;
        }
        
        // 检查是否已被任命
        if (isAppointed(appointee.getUniqueId(), permSetKey)) {
            return false;
        }
        
        // 检查任命数量限制
        if (def.getMaxAppointments() > 0) {
            int currentCount = getAppointmentCountBy(appointer.getUniqueId(), permSetKey);
            if (currentCount >= def.getMaxAppointments()) {
                return false;
            }
        }
        
        // 创建任命记录
        Appointment appointment = new Appointment(
            appointee.getUniqueId(),
            permSetKey,
            appointer.getUniqueId(),
            System.currentTimeMillis()
        );
        
        appointments.computeIfAbsent(permSetKey, k -> new HashMap<>())
                   .put(appointee.getUniqueId(), appointment);
        
        // 应用权限
        applyPermissions(appointee);
        
        // 执行任命命令
        executeCommands(def.getOnAppoint(), appointer, appointee, permSetKey);
        
        // 播放音效
        playSound(appointee, def.getAppointSound());
        
        // 保存数据
        saveData();
        
        return true;
    }

    /**
     * 撤销任命
     */
    public boolean dismiss(Player dismisser, UUID appointeeUuid, String permSetKey) {
        if (!enabled) return false;
        
        AppointDefinition def = appointDefinitions.get(permSetKey);
        if (def == null) return false;
        
        // 检查是否有权限撤销
        Map<UUID, Appointment> setAppointments = appointments.get(permSetKey);
        if (setAppointments == null) return false;
        
        Appointment appointment = setAppointments.get(appointeeUuid);
        if (appointment == null) return false;
        
        boolean canDismiss = dismisser.hasPermission("rulegems.admin") ||
                            (appointment.getAppointerUuid() != null && 
                             appointment.getAppointerUuid().equals(dismisser.getUniqueId()));
        
        if (!canDismiss) return false;
        
        // 移除任命记录
        setAppointments.remove(appointeeUuid);
        
        // 移除权限
        Player appointee = Bukkit.getPlayer(appointeeUuid);
        if (appointee != null) {
            applyPermissions(appointee);
            
            // 执行撤销命令
            executeCommands(def.getOnRevoke(), dismisser, appointee, permSetKey);
            
            // 播放音效
            playSound(appointee, def.getRevokeSound());
        }
        
        // 保存数据
        saveData();
        
        return true;
    }

    /**
     * 检查玩家是否被任命了某个权限集
     */
    public boolean isAppointed(UUID playerUuid, String permSetKey) {
        Map<UUID, Appointment> setAppointments = appointments.get(permSetKey);
        return setAppointments != null && setAppointments.containsKey(playerUuid);
    }

    /**
     * 获取某人任命的数量
     */
    public int getAppointmentCountBy(UUID appointerUuid, String permSetKey) {
        Map<UUID, Appointment> setAppointments = appointments.get(permSetKey);
        if (setAppointments == null) return 0;
        
        int count = 0;
        for (Appointment appointment : setAppointments.values()) {
            if (appointerUuid.equals(appointment.getAppointerUuid())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取玩家的所有任命
     */
    public List<Appointment> getPlayerAppointments(UUID playerUuid) {
        List<Appointment> result = new ArrayList<>();
        for (Map<UUID, Appointment> setAppointments : appointments.values()) {
            Appointment appointment = setAppointments.get(playerUuid);
            if (appointment != null) {
                result.add(appointment);
            }
        }
        return result;
    }

    /**
     * 获取权限集的所有被任命者
     */
    public List<Appointment> getAppointees(String permSetKey) {
        Map<UUID, Appointment> setAppointments = appointments.get(permSetKey);
        if (setAppointments == null) return new ArrayList<>();
        return new ArrayList<>(setAppointments.values());
    }

    /**
     * 应用权限和效果给玩家
     * 使用 PowerStructureManager 统一管理
     */
    public void applyPermissions(Player player) {
        org.cubexmc.manager.PowerStructureManager psm = plugin.getPowerStructureManager();
        
        // 先清除该玩家在 appoint 命名空间下的所有权限
        if (psm != null) {
            psm.clearNamespace(player, "appoint");
        }
        
        // 兼容：移除旧的权限附件
        PermissionAttachment oldAttachment = attachments.remove(player.getUniqueId());
        if (oldAttachment != null) {
            try {
                oldAttachment.remove();
            } catch (Exception ignored) {}
        }
        
        // 为每个任命应用对应的 PowerStructure
        Set<String> processedSets = new HashSet<>();
        for (Appointment appointment : getPlayerAppointments(player.getUniqueId())) {
            applyAppointmentPowers(appointment.getPermSetKey(), player, processedSets);
        }
        
        player.recalculatePermissions();
    }
    
    /**
     * 递归应用任命的 PowerStructure（处理继承）
     */
    private void applyAppointmentPowers(String permSetKey, Player player, Set<String> processedSets) {
        if (processedSets.contains(permSetKey)) return;
        processedSets.add(permSetKey);
        
        AppointDefinition def = appointDefinitions.get(permSetKey);
        if (def == null) return;
        
        PowerStructure power = def.getPowerStructure();
        if (power == null) return;
        
        // 使用 PowerStructureManager 应用（会自动检查条件）
        org.cubexmc.manager.PowerStructureManager psm = plugin.getPowerStructureManager();
        if (psm != null) {
            // 创建一个扩展的 PowerStructure，包含自动授予的下级任命权限
            PowerStructure extendedPower = new PowerStructure();
            extendedPower.setPermissions(new ArrayList<>(power.getPermissions()));
            extendedPower.setVaultGroups(power.getVaultGroups());
            extendedPower.setEffects(power.getEffects());
            extendedPower.setAllowedCommands(power.getAllowedCommands());
            extendedPower.setCondition(power.getCondition());
            
            // 添加自动授予的下级任命权限
            if (power.getAppoints() != null) {
                List<String> perms = extendedPower.getPermissions();
                for (String appointKey : power.getAppoints().keySet()) {
                    String appointPerm = PERMISSION_PREFIX + appointKey;
                    if (!perms.contains(appointPerm)) {
                        perms.add(appointPerm);
                    }
                }
            }
            
            psm.applyStructure(player, extendedPower, "appoint", permSetKey, true);
        }
    }
    
    /**
     * 应用委任的药水效果（保留用于兼容）
     */
    private void applyAppointEffects(Player player, List<org.cubexmc.model.EffectConfig> effects) {
        if (effects == null || effects.isEmpty()) return;
        for (org.cubexmc.model.EffectConfig effect : effects) {
            if (effect != null) {
                effect.apply(player);
            }
        }
    }
    
    /**
     * 移除所有由委任应用的药水效果（保留用于兼容）
     */
    private void removeAllAppointEffects(Player player) {
        // 遍历所有可能的委任定义，移除它们的效果
        for (AppointDefinition def : appointDefinitions.values()) {
            PowerStructure power = def.getPowerStructure();
            if (power != null && power.getEffects() != null) {
                for (org.cubexmc.model.EffectConfig effect : power.getEffects()) {
                    if (effect != null) {
                        effect.remove(player);
                    }
                }
            }
        }
    }

    /**
     * 递归收集权限和效果（处理继承，带条件检查）
     */
    private void collectPowersWithCondition(String permSetKey, Player player, 
            Set<String> permissions, List<org.cubexmc.model.EffectConfig> effects, Set<String> processedSets) {
        if (processedSets.contains(permSetKey)) return;
        processedSets.add(permSetKey);
        
        AppointDefinition def = appointDefinitions.get(permSetKey);
        if (def == null) return;
        
        PowerStructure power = def.getPowerStructure();
        if (power == null) return;
        
        // 检查条件是否满足
        org.cubexmc.model.PowerCondition condition = power.getCondition();
        if (condition != null && !condition.checkConditions(player)) {
            // 条件不满足，跳过此权限集
            return;
        }
        
        // 添加本权限集的权限
        permissions.addAll(power.getPermissions());
        
        // 添加本权限集的药水效果
        if (power.getEffects() != null) {
            effects.addAll(power.getEffects());
        }
        
        // 自动授予下级职位的任命权限
        if (power.getAppoints() != null) {
            for (String appointKey : power.getAppoints().keySet()) {
                permissions.add(PERMISSION_PREFIX + appointKey);
            }
        }
    }
    
    // 保留旧方法名的兼容别名
    private void collectPermissionsWithCondition(String permSetKey, Player player, Set<String> permissions, Set<String> processedSets) {
        List<org.cubexmc.model.EffectConfig> dummyEffects = new ArrayList<>();
        collectPowersWithCondition(permSetKey, player, permissions, dummyEffects, processedSets);
    }

    /**
     * 执行命令
     */
    private void executeCommands(List<String> commands, Player appointer, Player target, String permSetKey) {
        if (commands == null || commands.isEmpty()) return;
        
        AppointDefinition def = appointDefinitions.get(permSetKey);
        String displayName = def != null ? 
            ChatColor.translateAlternateColorCodes('&', def.getDisplayName()) : permSetKey;
        
        for (String cmd : commands) {
            String processed = cmd
                .replace("%player%", appointer.getName())
                .replace("%target%", target.getName())
                .replace("%perm_set%", displayName);
            
            if (processed.startsWith("console: ")) {
                String consoleCmd = processed.substring(9);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd);
            } else if (processed.startsWith("player: ")) {
                String playerCmd = processed.substring(8);
                appointer.performCommand(playerCmd);
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
            }
        }
    }

    /**
     * 播放音效
     */
    private void playSound(Player player, String soundName) {
        if (soundName == null || soundName.isEmpty()) return;
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {}
    }

    /**
     * 玩家加入时恢复权限
     */
    public void onPlayerJoin(Player player) {
        applyPermissions(player);
    }

    /**
     * 玩家退出时清理
     */
    public void onPlayerQuit(Player player) {
        // 使用 PowerStructureManager 清理
        org.cubexmc.manager.PowerStructureManager psm = plugin.getPowerStructureManager();
        if (psm != null) {
            psm.clearNamespace(player, "appoint");
        }
        
        // 兼容：移除旧的权限附件
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            try {
                attachment.remove();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 当玩家失去某个 appoint 权限时调用（级联撤销检查）
     * 这个方法应该在权限被撤销后调用
     * @param appointerUuid 失去权限的玩家 UUID
     * @param permSetKey 失去的权限集 key（可为 null 表示检查所有权限集）
     */
    public void onAppointerLostPermission(UUID appointerUuid, String permSetKey) {
        if (!enabled || !cascadeRevoke) return;
        
        Player appointer = Bukkit.getPlayer(appointerUuid);
        
        // 如果指定了权限集，只检查该权限集
        if (permSetKey != null) {
            cascadeRevokeForPermSet(appointerUuid, appointer, permSetKey);
        } else {
            // 检查所有权限集
            for (String key : appointDefinitions.keySet()) {
                cascadeRevokeForPermSet(appointerUuid, appointer, key);
            }
        }
    }

    /**
     * 对特定权限集执行级联撤销
     */
    private void cascadeRevokeForPermSet(UUID appointerUuid, Player appointer, String permSetKey) {
        // 检查该任命者是否仍有权限
        boolean stillHasPermission;
        if (appointer != null && appointer.isOnline()) {
            stillHasPermission = appointer.hasPermission(PERMISSION_PREFIX + permSetKey) 
                              || appointer.hasPermission("rulegems.admin");
        } else {
            // 离线玩家，假设已失去权限（因为被撤销了才会调用此方法）
            stillHasPermission = false;
        }
        
        if (stillHasPermission) return;
        
        // 获取该任命者任命的所有人
        Map<UUID, Appointment> setAppointments = appointments.get(permSetKey);
        if (setAppointments == null) return;
        
        List<UUID> toRevoke = new ArrayList<>();
        for (Appointment appointment : setAppointments.values()) {
            if (appointerUuid.equals(appointment.getAppointerUuid())) {
                toRevoke.add(appointment.getAppointeeUuid());
            }
        }
        
        if (toRevoke.isEmpty()) return;
        
        AppointDefinition def = appointDefinitions.get(permSetKey);
        
        // 执行级联撤销
        for (UUID appointeeUuid : toRevoke) {
            setAppointments.remove(appointeeUuid);
            
            Player appointee = Bukkit.getPlayer(appointeeUuid);
            if (appointee != null && appointee.isOnline()) {
                applyPermissions(appointee);
                
                // 执行撤销命令（使用控制台作为执行者）
                if (def != null && def.getOnRevoke() != null) {
                    for (String cmd : def.getOnRevoke()) {
                        String processed = cmd
                            .replace("%player%", appointer != null ? appointer.getName() : "SYSTEM")
                            .replace("%target%", appointee.getName())
                            .replace("%perm_set%", def.getDisplayName() != null ? 
                                ChatColor.translateAlternateColorCodes('&', def.getDisplayName()) : permSetKey);
                        
                        if (processed.startsWith("console: ")) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed.substring(9));
                        } else if (processed.startsWith("player: ")) {
                            // 跳过玩家命令，因为任命者可能不在线
                        } else {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
                        }
                    }
                }
                
                // 播放音效
                if (def != null) {
                    playSound(appointee, def.getRevokeSound());
                }
            }
            
            // 递归：如果被撤销的人也有任命权限，他任命的人也应被级联撤销
            // 使用延迟执行避免递归过深
            final UUID finalAppointeeUuid = appointeeUuid;
            Bukkit.getScheduler().runTask(plugin, () -> {
                onAppointerLostPermission(finalAppointeeUuid, null);
            });
        }
        
        // 保存数据
        saveData();
        
        plugin.getLogger().info("Cascade revoked " + toRevoke.size() + " appointments for perm set '" + permSetKey + "' due to appointer losing permission.");
    }

    /**
     * 检查并执行所有必要的级联撤销
     * 用于定期检查或在特定事件后调用
     */
    public void checkAllCascadeRevocations() {
        if (!enabled || !cascadeRevoke) return;
        
        Set<UUID> allAppointers = new HashSet<>();
        for (Map<UUID, Appointment> setAppointments : appointments.values()) {
            for (Appointment appointment : setAppointments.values()) {
                if (appointment.getAppointerUuid() != null) {
                    allAppointers.add(appointment.getAppointerUuid());
                }
            }
        }
        
        for (UUID appointerUuid : allAppointers) {
            onAppointerLostPermission(appointerUuid, null);
        }
    }

    // Getters
    public Map<String, AppointDefinition> getAppointDefinitions() {
        return new HashMap<>(appointDefinitions);
    }

    public AppointDefinition getAppointDefinition(String key) {
        return appointDefinitions.get(key);
    }
    
    public boolean isCascadeRevoke() {
        return cascadeRevoke;
    }
    
    /**
     * 获取某个任命者任命的所有人
     * @param appointerUuid 任命者 UUID
     * @return 任命列表
     */
    public List<Appointment> getAppointmentsByAppointer(UUID appointerUuid) {
        List<Appointment> result = new ArrayList<>();
        for (Map<UUID, Appointment> setAppointments : appointments.values()) {
            for (Appointment appointment : setAppointments.values()) {
                if (appointerUuid.equals(appointment.getAppointerUuid())) {
                    result.add(appointment);
                }
            }
        }
        return result;
    }
    
    /**
     * 获取某个任命者在特定权限集中任命的所有人
     * @param appointerUuid 任命者 UUID
     * @param permSetKey 权限集 key
     * @return 任命列表
     */
    public List<Appointment> getAppointmentsByAppointer(UUID appointerUuid, String permSetKey) {
        List<Appointment> result = new ArrayList<>();
        Map<UUID, Appointment> setAppointments = appointments.get(permSetKey);
        if (setAppointments == null) return result;
        
        for (Appointment appointment : setAppointments.values()) {
            if (appointerUuid.equals(appointment.getAppointerUuid())) {
                result.add(appointment);
            }
        }
        return result;
    }
    
    /**
     * 启动条件刷新任务
     * 定期检查所有在线玩家的条件并刷新权限
     */
    private void startConditionRefreshTask() {
        if (!enabled || conditionRefreshInterval <= 0) return;
        
        // 检查是否有任何权限集配置了条件
        boolean hasConditions = appointDefinitions.values().stream()
            .anyMatch(def -> def.getPowerStructure() != null && 
                             def.getPowerStructure().getCondition() != null && 
                             def.getPowerStructure().getCondition().hasAnyCondition());
        
        if (!hasConditions) {
            plugin.getLogger().info("No permission conditions configured, skipping refresh task.");
            return;
        }
        
        long intervalTicks = conditionRefreshInterval * 20L;
        refreshTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                // 检查玩家是否有任命
                if (!getPlayerAppointments(player.getUniqueId()).isEmpty()) {
                    applyPermissions(player);
                }
            }
        }, intervalTicks, intervalTicks).getTaskId();
        
        plugin.getLogger().info("Started condition refresh task (interval: " + conditionRefreshInterval + "s)");
    }
    
    /**
     * 停止条件刷新任务
     */
    private void stopConditionRefreshTask() {
        if (refreshTaskId != -1) {
            Bukkit.getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
    }
    
    /**
     * 玩家切换世界时刷新权限
     * 应该由外部监听器调用
     */
    public void onPlayerChangeWorld(Player player) {
        if (!enabled) return;
        
        // 检查玩家是否有任命
        if (!getPlayerAppointments(player.getUniqueId()).isEmpty()) {
            applyPermissions(player);
        }
    }
    
    /**
     * 检查玩家是否满足某个权限集的条件
     * @param player 玩家
     * @param permSetKey 权限集 key
     * @return 是否满足条件
     */
    public boolean checkCondition(Player player, String permSetKey) {
        AppointDefinition def = appointDefinitions.get(permSetKey);
        if (def == null) return false;
        
        PowerStructure power = def.getPowerStructure();
        if (power == null) return true;
        
        org.cubexmc.model.PowerCondition condition = power.getCondition();
        if (condition == null) return true;
        
        return condition.checkConditions(player);
    }
}

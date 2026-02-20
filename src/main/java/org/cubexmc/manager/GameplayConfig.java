package org.cubexmc.manager;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.cubexmc.model.ExecuteConfig;
import org.cubexmc.model.PowerStructure;

import static org.cubexmc.utils.ConfigParseUtils.*;

/**
 * 游戏玩法配置 — 存储从 config.yml 读取的所有运行时游戏设置。
 * <p>
 * 长期存活对象；{@link #loadFrom} 会原地刷新所有字段，
 * 因此 reload 后持有引用的消费者自动看到新值。
 */
public class GameplayConfig {

    // ==================== 授权策略 ====================
    private boolean inventoryGrantsEnabled;
    private boolean redeemEnabled;
    private boolean fullSetGrantsAllEnabled;

    // ==================== Redeem 广播 ====================
    private boolean broadcastRedeemTitle = true;

    // ==================== Redeem-All ====================
    private List<String> redeemAllTitle = Collections.emptyList();
    private Boolean redeemAllBroadcast = null; // null => inherit global
    private String redeemAllSound = "ENTITY_ENDER_DRAGON_GROWL";
    private PowerStructure redeemAllPowerStructure = new PowerStructure();

    // ==================== 放置 / 散落 ====================
    private ExecuteConfig gemScatterExecute;
    private Location randomPlaceCorner1;
    private Location randomPlaceCorner2;

    // ==================== 宝石逃逸 ====================
    private boolean gemEscapeEnabled;
    private long gemEscapeMinIntervalTicks;
    private long gemEscapeMaxIntervalTicks;
    private boolean gemEscapeBroadcast;
    private String gemEscapeSound;
    private String gemEscapeParticle;

    // ==================== 放置兑换（祭坛模式） ====================
    private boolean placeRedeemEnabled;
    private int placeRedeemRadius;
    private String placeRedeemSound;
    private String placeRedeemParticle;
    private boolean placeRedeemBeaconBeam;
    private int placeRedeemBeaconDuration;

    // ==================== 长按右键兑换 ====================
    private boolean holdToRedeemEnabled;
    private boolean sneakToRedeem;
    private int holdToRedeemDurationTicks;

    // ==================== 安全 ====================
    private boolean opEscalationAllowed;

    // ==================== 加载 ====================

    /**
     * 从 config.yml 读取并刷新所有 gameplay 字段。
     *
     * @param config     主配置文件
     * @param parser     GemDefinitionParser（用于 parsePowerStructure）
     * @param lang       LanguageManager（目前未直接使用，保留扩展性）
     * @param logger     日志记录器
     * @param cornerLoader 辅助函数：(ConfigurationSection, String, World) → Location
     */
    public void loadFrom(FileConfiguration config, GemDefinitionParser parser,
                         LanguageManager lang, Logger logger,
                         LocationLoader cornerLoader) {

        // 授权策略
        ConfigurationSection gp = config.getConfigurationSection("grant_policy");
        this.inventoryGrantsEnabled = gp != null && gp.getBoolean("inventory_grants", false);
        this.redeemEnabled = gp == null || gp.getBoolean("redeem_enabled", true);
        this.fullSetGrantsAllEnabled = gp == null || gp.getBoolean("full_set_grants_all", true);
        this.placeRedeemEnabled = gp != null && gp.getBoolean("place_redeem_enabled", false);
        this.holdToRedeemEnabled = gp != null
                && (gp.getBoolean("hold_to_redeem_enabled", gp.getBoolean("hold_to_redeem", true)));

        // hold_to_redeem 配置块
        ConfigurationSection htr = config.getConfigurationSection("hold_to_redeem");
        if (htr != null) {
            this.sneakToRedeem = htr.getBoolean("sneak_to_redeem", true);
            double durationSeconds = htr.getDouble("duration", 3.0);
            this.holdToRedeemDurationTicks = (int) (durationSeconds * 20);
        } else {
            this.sneakToRedeem = gp == null || gp.getBoolean("sneak_to_redeem", true);
            this.holdToRedeemDurationTicks = 60; // 默认3秒
        }

        // 全局开关
        ConfigurationSection toggles = config.getConfigurationSection("toggles");
        if (toggles != null) {
            this.broadcastRedeemTitle = toggles.getBoolean("broadcast_redeem_title", true);
        }

        // redeem_all
        ConfigurationSection ra = config.getConfigurationSection("redeem_all");
        if (ra != null) {
            Object titlesObj = ra.get("titles");
            this.redeemAllTitle = toStringList(titlesObj);
            if (ra.isSet("broadcast")) {
                this.redeemAllBroadcast = ra.getBoolean("broadcast");
            } else {
                this.redeemAllBroadcast = null;
            }
            String s = stringOf(ra.get("sound"));
            if (s != null && !s.isEmpty())
                this.redeemAllSound = s;

            this.redeemAllPowerStructure = parser.parsePowerStructure(ra);
        } else {
            this.redeemAllTitle = Collections.emptyList();
            this.redeemAllBroadcast = null;
            this.redeemAllPowerStructure = new PowerStructure();
        }

        // 散落效果
        this.gemScatterExecute = new ExecuteConfig(
                config.getStringList("gem_scatter_execute.commands"),
                config.getString("gem_scatter_execute.sound"),
                config.getString("gem_scatter_execute.particle"));

        // 随机放置范围
        ConfigurationSection randomPlaceRange = config.getConfigurationSection("random_place_range");
        if (randomPlaceRange != null && cornerLoader != null) {
            String worldName = randomPlaceRange.getString("world");
            if (worldName != null) {
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
                if (world != null) {
                    this.randomPlaceCorner1 = cornerLoader.load(randomPlaceRange, "corner1", world);
                    this.randomPlaceCorner2 = cornerLoader.load(randomPlaceRange, "corner2", world);
                }
            }
        }

        // 宝石逃逸配置
        ConfigurationSection escapeSection = config.getConfigurationSection("gem_escape");
        if (escapeSection != null) {
            this.gemEscapeEnabled = escapeSection.getBoolean("enabled", false);
            this.gemEscapeMinIntervalTicks = parseTimeToTicks(escapeSection.getString("min_interval", "30m"), logger);
            this.gemEscapeMaxIntervalTicks = parseTimeToTicks(escapeSection.getString("max_interval", "2h"), logger);
            this.gemEscapeBroadcast = escapeSection.getBoolean("broadcast", true);
            this.gemEscapeSound = escapeSection.getString("sound", "ENTITY_ENDERMAN_TELEPORT");
            this.gemEscapeParticle = escapeSection.getString("particle", "PORTAL");
        } else {
            this.gemEscapeEnabled = false;
            this.gemEscapeMinIntervalTicks = 30 * 60 * 20L;
            this.gemEscapeMaxIntervalTicks = 2 * 60 * 60 * 20L;
            this.gemEscapeBroadcast = true;
            this.gemEscapeSound = "ENTITY_ENDERMAN_TELEPORT";
            this.gemEscapeParticle = "PORTAL";
        }
        // 确保 min <= max
        if (this.gemEscapeMinIntervalTicks > this.gemEscapeMaxIntervalTicks) {
            long tmp = this.gemEscapeMinIntervalTicks;
            this.gemEscapeMinIntervalTicks = this.gemEscapeMaxIntervalTicks;
            this.gemEscapeMaxIntervalTicks = tmp;
        }
        // 确保最小间隔至少 1 秒
        if (this.gemEscapeMinIntervalTicks < 20L) {
            this.gemEscapeMinIntervalTicks = 20L;
        }

        // 放置兑换（祭坛模式）全局设置
        ConfigurationSection prSection = config.getConfigurationSection("place_redeem");
        if (prSection != null) {
            this.placeRedeemRadius = prSection.getInt("radius", 1);
            this.placeRedeemSound = prSection.getString("sound", "BLOCK_BEACON_ACTIVATE");
            this.placeRedeemParticle = prSection.getString("particle", "TOTEM");
            this.placeRedeemBeaconBeam = prSection.getBoolean("beacon_beam", true);
            this.placeRedeemBeaconDuration = prSection.getInt("beacon_beam_duration", 5);
        } else {
            this.placeRedeemRadius = 1;
            this.placeRedeemSound = "BLOCK_BEACON_ACTIVATE";
            this.placeRedeemParticle = "TOTEM";
            this.placeRedeemBeaconBeam = true;
            this.placeRedeemBeaconDuration = 5;
        }

        // 安全配置
        this.opEscalationAllowed = config.getBoolean("allow_op_escalation", false);
    }

    // ==================== Getters ====================

    public boolean isInventoryGrantsEnabled() { return inventoryGrantsEnabled; }
    public boolean isRedeemEnabled() { return redeemEnabled; }
    public boolean isFullSetGrantsAllEnabled() { return fullSetGrantsAllEnabled; }
    public boolean isBroadcastRedeemTitle() { return broadcastRedeemTitle; }

    public List<String> getRedeemAllTitle() { return redeemAllTitle; }
    public Boolean getRedeemAllBroadcastOverride() { return redeemAllBroadcast; }
    public String getRedeemAllSound() { return redeemAllSound; }
    public PowerStructure getRedeemAllPowerStructure() { return redeemAllPowerStructure; }

    public ExecuteConfig getGemScatterExecute() { return gemScatterExecute; }
    public Location getRandomPlaceCorner1() { return randomPlaceCorner1; }
    public Location getRandomPlaceCorner2() { return randomPlaceCorner2; }

    public boolean isGemEscapeEnabled() { return gemEscapeEnabled; }
    public long getGemEscapeMinIntervalTicks() { return gemEscapeMinIntervalTicks; }
    public long getGemEscapeMaxIntervalTicks() { return gemEscapeMaxIntervalTicks; }
    public boolean isGemEscapeBroadcast() { return gemEscapeBroadcast; }
    public String getGemEscapeSound() { return gemEscapeSound; }
    public String getGemEscapeParticle() { return gemEscapeParticle; }

    public boolean isPlaceRedeemEnabled() { return placeRedeemEnabled; }
    public int getPlaceRedeemRadius() { return placeRedeemRadius; }
    public String getPlaceRedeemSound() { return placeRedeemSound; }
    public String getPlaceRedeemParticle() { return placeRedeemParticle; }
    public boolean isPlaceRedeemBeaconBeam() { return placeRedeemBeaconBeam; }
    public int getPlaceRedeemBeaconDuration() { return placeRedeemBeaconDuration; }

    public boolean isHoldToRedeemEnabled() { return holdToRedeemEnabled; }
    public boolean isSneakToRedeem() { return sneakToRedeem; }
    public int getHoldToRedeemDurationTicks() { return holdToRedeemDurationTicks; }

    public boolean isOpEscalationAllowed() { return opEscalationAllowed; }

    // ==================== 辅助接口 ====================

    /**
     * 函数式接口，用于从 ConfigurationSection 加载 Location。
     * 由 ConfigManager 提供实现，避免 GameplayConfig 依赖 RuleGems。
     */
    @FunctionalInterface
    public interface LocationLoader {
        Location load(ConfigurationSection section, String path, org.bukkit.World world);
    }
}

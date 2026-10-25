package alphahu.tdm.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局玩法设置，对应 {@code config.yml}。
 *
 * <p>这里只放「跨场地通用的默认值」。每个场地可以在 {@code arenas.yml} 里
 * 覆盖其中一部分（见 {@link alphahu.tdm.arena.TdmArena#resolveRules(TdmSettings)}）——
 * 需要按场地调的通常只有击杀目标、时间上限这类节奏参数，
 * 而复活延时、友伤、HUD 样式这些在整台服务器上保持一致更省心。
 *
 * <p>字段全部设为不可变：加载后不再改动，重载时整体换一个新实例。
 * 这样比赛进行中读到的设置永远是自洽的一整套，不会出现
 * 「读到一半配置被换了，一半新一半旧」的错乱。
 *
 * @author AlphaHu
 */
public final class TdmSettings {

    // ---- match ----
    public final int targetKills;
    public final int timeLimitSeconds;
    public final int countdownSeconds;
    public final int endDelaySeconds;
    public final boolean autoRestart;
    public final int minPlayers;
    public final boolean autoBalance;

    // ---- respawn ----
    public final int respawnDelaySeconds;
    public final double protectionSeconds;
    public final boolean breakProtectionOnAttack;
    public final boolean dropItems;
    /** {@code random} 或 {@code first}。 */
    public final String spawnMode;

    // ---- combat ----
    public final boolean friendlyFire;
    public final boolean overrideDeathMessage;
    public final String killMessage;
    public final String deathMessageNoKiller;
    public final int killstreakBroadcast;
    public final boolean preventItemDrop;

    // ---- hud ----
    public final String killMessageFormat;
    public final int killMessageDurationMs;
    public final String respawnBarTitle;
    public final String respawnBarColor;
    public final String respawnBarStyle;
    public final String protectionBarTitle;
    public final String protectionBarColor;
    public final String protectionBarStyle;
    public final boolean matchBarEnabled;
    public final String matchBarFormat;
    public final String matchBarColor;
    public final String matchBarStyle;
    public final boolean scoreboardEnabled;
    public final String scoreboardTitle;
    public final List<String> scoreboardLines;

    // ---- loadout ----
    public final boolean clearInventory;
    public final double health;
    public final double maxHealth;
    /** 开局默认装备：直接写商店里的商品 id，发放时复用购买那套落点逻辑。 */
    public final List<String> defaultItemsCommon;
    public final List<String> defaultItemsT;
    public final List<String> defaultItemsCt;
    public final List<String> commonItems;
    public final List<String> tItems;
    public final List<String> ctItems;

    // ---- economy ----
    public final boolean economyEnabled;
    public final int economyStartMoney;
    public final int economyKillReward;
    public final int economyKillstreakBonus;
    public final int economyKillstreakBonusMax;
    public final int economyMaxMoney;

    // ---- shop ----
    public final boolean shopEnabled;
    public final String shopSource;
    public final String shopWbdFile;
    public final String shopLocalFile;
    public final int shopFallbackSlot;

    /**
     * 是否给商店发出的物品打 PDC 标记。
     *
     * <p>{@code true}：物品带上「本组商品」标记，再买同组的会**自动清掉旧的** ——
     * 玩家把旧枪挪到背包别的格子也照样清得掉，不至于攒一背包枪。
     *
     * <p>{@code false}：物品一个字节都不改，纯粹原样发放。代价是只能覆盖目标槽位，
     * 玩家把旧物品挪走之后再买，旧的会留在别处。
     */
    public final boolean shopTagGrantedItems;

    public final Map<String, Integer> shopSlotOverrides;
    public final Map<String, Integer> shopMaterialSlots;

    // ---- reports ----
    public final boolean reportsEnabled;
    public final String reportsFolder;
    public final int reportsKeep;
    public final boolean reportsIncludeRanked;
    public final boolean reportsIncludePlayers;

    // ---- shop 购买区 ----
    public final boolean shopBuyZoneRequired;

    // ---- 队友名牌 ----
    public final boolean teammateNameTag;
    public final String teammateNameTagPrefix;

    // ---- 队伍皮肤 ----
    public final boolean skinEnabled;
    public final String skinT;
    public final String skinCt;
    public final String skinApplyCommand;
    public final String skinRestoreCommand;

    // ---- 队伍队服 ----
    public final boolean uniformEnabled;
    public final String uniformSlot;
    public final String uniformTColor;
    public final String uniformCtColor;

    // ---- integration ----
    public final boolean wbdAddon;
    public final boolean useWbdAddonHud;
    public final boolean warnArenaConflict;
    public final boolean autoDisableInWbd;
    public final String wbdArenasFile;

    private TdmSettings(FileConfiguration c) {
        this.targetKills = Math.max(0, c.getInt("match.target-kills", 50));
        this.timeLimitSeconds = Math.max(0, c.getInt("match.time-limit-seconds", 900));
        this.countdownSeconds = Math.max(0, c.getInt("match.countdown-seconds", 10));
        this.endDelaySeconds = Math.max(1, c.getInt("match.end-delay-seconds", 30));
        this.autoRestart = c.getBoolean("match.auto-restart", false);
        this.minPlayers = Math.max(1, c.getInt("match.min-players", 2));
        this.autoBalance = c.getBoolean("match.auto-balance", true);

        this.respawnDelaySeconds = Math.max(0, c.getInt("respawn.delay-seconds", 3));
        this.protectionSeconds = Math.max(0.0, c.getDouble("respawn.protection-seconds", 2.0));
        this.breakProtectionOnAttack = c.getBoolean("respawn.break-protection-on-attack", true);
        this.dropItems = c.getBoolean("respawn.drop-items", false);
        this.spawnMode = c.getString("respawn.spawn-mode", "random");

        this.friendlyFire = c.getBoolean("combat.friendly-fire", false);
        this.overrideDeathMessage = c.getBoolean("combat.override-death-message", true);
        this.killMessage = c.getString("combat.kill-message",
                "&c%killer% &7[&e%weapon%&7] &f%victim%");
        this.deathMessageNoKiller = c.getString("combat.death-message-no-killer",
                "&f%victim% &7阵亡");
        this.killstreakBroadcast = Math.max(0, c.getInt("combat.killstreak-broadcast", 5));
        this.preventItemDrop = c.getBoolean("combat.prevent-item-drop", true);

        this.killMessageFormat = c.getString("hud.kill-message-format",
                "&e[%weapon%] &f%victim% &a+%money%");
        this.killMessageDurationMs = Math.max(250,
                c.getInt("hud.kill-message-duration-ms", 2000));
        this.respawnBarTitle = c.getString("hud.respawn-bar-title", "&c%seconds% &7秒后复活");
        this.respawnBarColor = c.getString("hud.respawn-bar-color", "RED");
        this.respawnBarStyle = c.getString("hud.respawn-bar-style", "SOLID");
        this.protectionBarTitle = c.getString("hud.protection-bar-title",
                "&b无敌 &f%seconds% &b秒");
        this.protectionBarColor = c.getString("hud.protection-bar-color", "BLUE");
        this.protectionBarStyle = c.getString("hud.protection-bar-style", "SOLID");
        this.matchBarEnabled = c.getBoolean("hud.match-bar-enabled", true);
        this.matchBarFormat = c.getString("hud.match-bar-format",
                "&e%time% &7| &cT &f%t_score% &7- &bCT &f%ct_score%");
        this.matchBarColor = c.getString("hud.match-bar-color", "YELLOW");
        this.matchBarStyle = c.getString("hud.match-bar-style", "SOLID");
        this.scoreboardEnabled = c.getBoolean("hud.scoreboard-enabled", true);
        this.scoreboardTitle = c.getString("hud.scoreboard-title", "&6&l团队死斗");
        this.scoreboardLines = c.getStringList("hud.scoreboard-lines");

        this.clearInventory = c.getBoolean("loadout.clear-inventory", true);
        this.health = Math.max(1.0, c.getDouble("loadout.health", 20.0));
        // 0 表示不改动最大血量属性
        this.maxHealth = Math.max(0.0, c.getDouble("loadout.max-health", 20.0));
        this.defaultItemsCommon = c.getStringList("loadout.default-items.common");
        this.defaultItemsT = c.getStringList("loadout.default-items.t");
        this.defaultItemsCt = c.getStringList("loadout.default-items.ct");
        this.commonItems = c.getStringList("loadout.common");
        this.tItems = c.getStringList("loadout.t");
        this.ctItems = c.getStringList("loadout.ct");

        this.wbdAddon = c.getBoolean("integration.wbdaddon", true);
        this.useWbdAddonHud = c.getBoolean("integration.use-wbdaddon-hud", true);
        this.warnArenaConflict = c.getBoolean("integration.warn-arena-conflict", true);
        this.autoDisableInWbd = c.getBoolean("integration.auto-disable-in-wbd", true);
        this.wbdArenasFile = c.getString("integration.wbd-arenas-file",
                "plugins/WarZBombDefuse/arenas.yml");

        this.shopEnabled = c.getBoolean("shop.enabled", true);
        this.economyEnabled = c.getBoolean("economy.enabled", true);
        this.economyStartMoney = Math.max(0, c.getInt("economy.start-money", 800));
        this.economyKillReward = Math.max(0, c.getInt("economy.kill-reward", 300));
        this.economyKillstreakBonus = Math.max(0, c.getInt("economy.killstreak-bonus", 100));
        this.economyKillstreakBonusMax = Math.max(0, c.getInt("economy.killstreak-bonus-max", 500));
        this.economyMaxMoney = Math.max(1, c.getInt("economy.max-money", 16000));

        this.shopSource = c.getString("shop.source", "wbd");
        this.shopTagGrantedItems = c.getBoolean("shop.tag-granted-items", true);

        this.shopWbdFile = c.getString("shop.wbd-file", "plugins/WarZBombDefuse/shop.yml");
        this.shopLocalFile = c.getString("shop.local-file", "shop.yml");
        this.shopFallbackSlot = Math.max(0, Math.min(35, c.getInt("shop.fallback-slot", 0)));

        Map<String, Integer> overrides = new HashMap<>();
        ConfigurationSection overrideSection = c.getConfigurationSection("shop.slot-overrides");
        if (overrideSection != null) {
            for (String id : overrideSection.getKeys(false)) {
                int slot = overrideSection.getInt(id, -1);
                if (slot >= 0 && slot < 36) {
                    overrides.put(id, slot);
                }
            }
        }
        this.shopSlotOverrides = Collections.unmodifiableMap(overrides);

        Map<String, Integer> materialSlots = new HashMap<>();
        ConfigurationSection materialSection = c.getConfigurationSection("shop.material-slots");
        if (materialSection != null) {
            for (String material : materialSection.getKeys(false)) {
                int slot = materialSection.getInt(material, -1);
                if (slot >= 0 && slot < 36) {
                    materialSlots.put(material.toUpperCase(java.util.Locale.ROOT), slot);
                }
            }
        }
        this.shopMaterialSlots = Collections.unmodifiableMap(materialSlots);

        this.reportsEnabled = c.getBoolean("reports.enabled", true);
        // 目录名不合法（含路径分隔符）会让战报散落到数据目录之外，
        // 这里只做最基本的收束：去掉斜杠与开头的点
        this.reportsFolder = sanitizeFolder(c.getString("reports.folder", "reports"));
        this.reportsKeep = Math.max(0, c.getInt("reports.keep", 500));
        this.reportsIncludeRanked = c.getBoolean("reports.include-ranked", true);
        this.reportsIncludePlayers = c.getBoolean("reports.include-players", true);

        this.shopBuyZoneRequired = c.getBoolean("shop.buy-zone-required", false);

        this.teammateNameTag = c.getBoolean("teammate.name-tag", true);
        this.teammateNameTagPrefix = c.getString("teammate.name-tag-prefix", "&a[队友] &f");

        this.skinEnabled = c.getBoolean("teammate.skin.enabled", false);
        this.skinT = c.getString("teammate.skin.t-skin", "");
        this.skinCt = c.getString("teammate.skin.ct-skin", "");
        this.skinApplyCommand = c.getString("teammate.skin.apply-command",
                "skin set %skin% %player%");
        this.skinRestoreCommand = c.getString("teammate.skin.restore-command",
                "skin undo %player%");

        // 默认关闭：WBDAddon 1.2.1 起自带「队伍护甲」模块，功能重叠；
        // 两边同时开会在同一个盔甲槽上互相覆盖，导致物品无限复制
        this.uniformEnabled = c.getBoolean("teammate.uniform.enabled", false);
        this.uniformSlot = c.getString("teammate.uniform.slot", "HELMET");
        this.uniformTColor = c.getString("teammate.uniform.t-color", "#C0392B");
        this.uniformCtColor = c.getString("teammate.uniform.ct-color", "#2E86DE");
    }

    /** 把目录名收束成一个安全的值，避免 ../ 之类把它带出数据目录。 */
    private static String sanitizeFolder(String raw) {
        if (raw == null || raw.isBlank()) {
            return "reports";
        }
        String cleaned = raw.replace('\\', '/').replace("..", "").replace("/", "");
        cleaned = cleaned.replaceAll("^[.\\s]+", "").trim();
        return cleaned.isEmpty() ? "reports" : cleaned;
    }

    /** 从已加载的配置对象读出一整套设置。 */
    public static TdmSettings load(FileConfiguration config) {
        return new TdmSettings(config);
    }

    /** 该模式需要多少击杀才能获胜（0 表示不设目标）。 */
    public boolean hasKillTarget() {
        return targetKills > 0;
    }

    /** 该模式是否有时间上限（0 表示不限时）。 */
    public boolean hasTimeLimit() {
        return timeLimitSeconds > 0;
    }
}

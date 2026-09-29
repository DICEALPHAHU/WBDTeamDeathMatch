package alphahu.tdm.integration;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.game.TdmPlayer;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.api.CombatStats;
import alphahu.wbdaddon.api.WbdAddonApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * WBDAddon 对接的**唯一**实现类。
 *
 * <h2>这个类为什么必须孤立存在</h2>
 * 它是整个插件里唯一 {@code import alphahu.wbdaddon.*} 的地方，而且**绝不允许**
 * 被 {@code AddonBridge} 之外的任何代码直接引用（类型、字段、返回值都不行）。
 *
 * <p>原因：WBDAddon 是可选依赖，而 JVM 在加载一个类时就要解析它的接口与父类。
 * 只要主流程里有任何一个类 {@code implements AddonMode}，那么在没装 WBDAddon
 * 的服务器上，那个类一被加载就会抛 {@code NoClassDefFoundError}，
 * 插件连 {@code onEnable} 都跑不完 —— 「没装也能用」的承诺就此作废。
 *
 * <p>所以加载路径被设计成两段：
 * <ol>
 *   <li>{@link AddonBridge} 先用 {@code Class.forName} 确认 WBDAddon 在场、
 *       且它的 api 包确实存在（拦住旧版本）</li>
 *   <li>确认之后才用 {@code Class.forName} 加载本类 —— 此时 {@code AddonMode}
 *       一定能解析，这个类也就一定能安全加载</li>
 * </ol>
 *
 * <p>换句话说：<b>本类的加载本身，就是「WBDAddon 可用」的证明。</b>
 * 因此构造函数里不做任何探测，直接取服务、注册模式。
 *
 * @author AlphaHu
 */
public final class WbdAddonModeBridge implements ModeBridge, AddonMode {

    /** 模式标识。WBDAddon 的 {@code /wbdaddon modes} 会显示它。 */
    public static final String MODE_ID = "team-deathmatch";

    private final WBDTeamDeathmatch plugin;

    private WbdAddonApi api;
    private boolean registered;

    /**
     * WBDAddon 是否真的在替我们渲染 ActionBar。
     *
     * <p>注册成功不等于 HUD 有人发：match-status 模块可能被禁用，
     * 或者它的顶栏开关被服主关掉了。这个值由 {@link #refreshHudRelay()}
     * 实地探测后写入，而不是照着配置想当然。
     */
    private boolean hudRelayed;

    /** 伤害账本的适配器，惰性创建。 */
    private DamageLedger ledger;

    public WbdAddonModeBridge(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 建立与拆除
    // ------------------------------------------------------------------

    /**
     * 取服务并注册本模式。
     *
     * @return 服务不在场（例如 WBDAddon 已加载但尚未发布服务）时返回 false
     */
    public boolean register() {
        RegisteredServiceProvider<WbdAddonApi> provider =
                Bukkit.getServicesManager().getRegistration(WbdAddonApi.class);
        if (provider == null) {
            return false;
        }

        this.api = provider.getProvider();
        this.api.registerMode(this);
        this.registered = true;
        refreshHudRelay();
        checkApiVersion();
        return true;
    }

    /** WBDAddon 报告的 API 版本；读不到时为 null。 */
    private String apiVersion;

    /**
     * 检查对方的 API 版本够不够用。
     *
     * <p>这不是形式主义：队友名牌前缀与隐藏敌人名牌是 API 1.2 才加的，
     * 装 1.1 的 WBDAddon 时它们**不会报任何错**，只是不生效 ——
     * 玩家看到的是一堆头顶没有标记的敌人，然后来问你插件是不是坏了。
     * 静默失效比崩溃难查得多，所以这里主动说出来。
     */
    private void checkApiVersion() {
        try {
            apiVersion = api.getApiVersion();
        } catch (Throwable t) {
            apiVersion = null;
        }
        if (!plugin.getSettings().teammateNameTag) {
            return;
        }
        if (apiVersion == null || compareVersion(apiVersion, "1.2") < 0) {
            plugin.getLogger().warning("WBDAddon 的 API 版本是 "
                    + (apiVersion == null ? "未知" : apiVersion)
                    + "，低于队友名牌功能所需的 1.2："
                    + "「[队友] 前缀」和「隐藏敌人名牌」不会生效。请升级 WBDAddon。");
        }
    }

    /** 比较形如 {@code "1.2"} 的版本号；a 小于 b 返回负数。 */
    private static int compareVersion(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int x = i < left.length ? parseInt(left[i]) : 0;
            int y = i < right.length ? parseInt(right[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void unregister() {
        if (registered && api != null) {
            api.unregisterMode(MODE_ID);
        }
        registered = false;
        hudRelayed = false;
        // 保留 api 引用：注销之后仍要用它取伤害账本做击杀归属
    }

    /**
     * 重新探测 HUD 代渲染是否可用。
     *
     * <p>WBDAddon 重载模块后状态会变，本插件重载时也要跟着重新判断，
     * 否则会出现「HUD 交给了对方，但对方其实已经不发」的空窗。
     */
    public void refreshHudRelay() {
        if (!registered || api == null || !plugin.getSettings().useWbdAddonHud) {
            hudRelayed = false;
            return;
        }
        try {
            hudRelayed = api.isHudRelayAvailable();
        } catch (Throwable t) {
            // 老版本 WBDAddon 没有这个方法时会抛 NoSuchMethodError，
            // 这时按「不能代渲染」处理，本插件自己发，功能不受影响
            hudRelayed = false;
        }
    }

    // ------------------------------------------------------------------
    // ModeBridge
    // ------------------------------------------------------------------

    @Override
    public boolean isActive() {
        return registered;
    }

    @Override
    public boolean isHudRelayed() {
        return hudRelayed;
    }

    @Override
    public String resolveWeaponName(Player killer) {
        if (api != null) {
            try {
                return api.resolveWeaponName(killer);
            } catch (Throwable ignored) {
                // 落到下面的兜底实现
            }
        }
        return fallbackWeaponName(killer);
    }

    @Override
    public DamageLedger damageLedger() {
        if (api == null) {
            return null;
        }
        if (ledger != null) {
            return ledger;
        }
        CombatStats stats;
        try {
            stats = api.getCombatStats();
        } catch (Throwable t) {
            return null;
        }
        if (stats == null) {
            // kill-reports 模块没启用，对方没有伤害记录能力
            return null;
        }
        // 这里刻意不提供 reset()：DamageTracker 是 WBDAddon 与爆破模式共享的实例，
        // 死斗去清空它会把正在进行的爆破回合统计一并抹掉
        ledger = stats::getDamageSources;
        return ledger;
    }

    /**
     * 没有 WBDAddon 时的武器名兜底。
     *
     * <p>能力上确实弱一档：拿不到 TacZ 的 GunId，只能读物品显示名或材质名。
     * 但对于没装 TacZ 的服务器，这个结果已经够用了。
     */
    private String fallbackWeaponName(Player killer) {
        if (killer == null) {
            return "未知";
        }
        var item = killer.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return "拳头";
        }
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return org.bukkit.ChatColor.stripColor(meta.getDisplayName());
        }
        return item.getType().name().toLowerCase().replace("_", " ");
    }

    // ------------------------------------------------------------------
    // AddonMode 实现
    // ------------------------------------------------------------------

    @Override
    public String getModeId() {
        return MODE_ID;
    }

    @Override
    public String getDisplayName() {
        return "团队死斗（WBDTeamDeathmatch）";
    }

    @Override
    public boolean isParticipating(Player player) {
        return match(player) != null;
    }

    /**
     * 死斗里没有纯旁观者：加入的人一定分到了阵营。
     * 阵亡等待复活的玩家仍然是参赛者，只是暂时不存活。
     */
    @Override
    public boolean isPlaying(Player player) {
        return match(player) != null;
    }

    @Override
    public boolean isAlive(Player player) {
        TdmMatch match = match(player);
        return match != null && match.isActive(player);
    }

    @Override
    public String getTeamId(Player player) {
        TdmMatch match = match(player);
        if (match == null) {
            return null;
        }
        TdmPlayer record = match.getPlayer(player);
        return record == null ? null : record.team().id();
    }

    @Override
    public String getArenaId(Player player) {
        TdmMatch match = match(player);
        return match == null ? null : match.getArena().getId();
    }

    @Override
    public String getArenaName(Player player) {
        TdmMatch match = match(player);
        return match == null ? null : match.getArena().getName();
    }

    /**
     * 顶部状态行。
     *
     * <p>返回 null 表示「这一刻没什么可显示的」（例如比赛还没开始），
     * WBDAddon 会据此跳过这个玩家，而不是把他的 ActionBar 刷成空白。
     */
    @Override
    public String hudLine(Player player) {
        if (!hudRelayed) {
            return null;
        }
        TdmMatch match = match(player);
        return match == null ? null : match.hudLine(player);
    }

    /**
     * 死斗玩家是否挂「防第三人称」遮挡面。
     *
     * <p>这里<b>覆盖</b>掉 {@link AddonMode} 的默认实现（默认对所有模式生效），
     * 默认返回 false。
     *
     * <p>理由是那个遮挡面的性质决定的：它是一块挂在玩家身边、只对本人可见的
     * 巨大黑平面。要让第三人称失去偷看价值，它就必须待在你自己的视野里 ——
     * 于是低头时会看到黑块，贴身缠斗时它挡住的是你正要打的那个人。
     *
     * <p>这个代价在节奏慢、需要防隔墙偷看的爆破模式里是划算的；而死斗以
     * 近距离交火为主，它挡住的多半是敌人，弊大于利。
     * 需要的话可以用 {@code integration.antithirdcam-in-tdm} 打开。
     */
    @Override
    public boolean shouldBlockThirdPerson(Player player) {
        if (!plugin.getSettings().antithirdcamInTdm) {
            return false;
        }
        return isPlaying(player) && isAlive(player);
    }

    /**
     * 队友头顶名牌的前缀。
     *
     * <p>前缀由 WBDAddon 写进原版队伍，而队伍是按「模式 + 阵营」划分的，
     * 所以它天然只加在队友头上。但仅靠前缀还不够 —— 原版队伍的前缀是
     * 「所有人的客户端都收得到」的，必须配合下面的
     * {@link #hideEnemyNameTags()} 才能保证敌人那边不会也顶着一个「[队友]」。
     */
    @Override
    public String nameTagPrefix(Player player) {
        return plugin.getSettings().teammateNameTag
                ? plugin.getSettings().teammateNameTagPrefix
                : null;
    }

    /**
     * 隐藏敌人的头顶名牌。
     *
     * <p>这是死斗里最基本的信息控制。敌人头上要是明明白白写着名字，
     * 「辨认敌我」这件事就不存在了，而它本来就是团队射击的一部分 ——
     * 尤其在没有小地图标记的情况下，名牌是最容易被滥用的信息来源。
     *
     * <p>实现走原版队伍的 {@code NAME_TAG_VISIBILITY}，不需要发包插件，
     * 因此在 Arclight 这类混合端上同样有效。
     */
    @Override
    public boolean hideEnemyNameTags() {
        return plugin.getSettings().teammateNameTag;
    }

    private TdmMatch match(Player player) {
        return plugin.getMatchManager() == null
                ? null
                : plugin.getMatchManager().getMatch(player);
    }
}

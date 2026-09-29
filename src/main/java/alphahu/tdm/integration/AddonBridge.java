package alphahu.tdm.integration;

import alphahu.tdm.WBDTeamDeathmatch;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 与 WBDAddon 的对接层：负责**探测**对方是否可用，并在可用时建立连接。
 *
 * <h2>本类最关键的约束</h2>
 * <b>这里不允许出现任何 {@code alphahu.wbdaddon.*} 的类型引用</b>——
 * 不能 import、不能当字段、不能当返回值、不能当参数。全部通过
 * {@link ModeBridge} 这层中立接口与之交互。
 *
 * <p>之所以如此严格，是因为踩过一次真实的坑：早先的版本让本类直接
 * {@code implements AddonMode}，结果在没装 WBDAddon 的服务器上，
 * JVM 加载本类时就必须解析那个接口，直接抛
 * {@code NoClassDefFoundError: alphahu/wbdaddon/api/AddonMode}，
 * 插件连 {@code onEnable} 都跑不完。代码里写的
 * 「取不到服务就降级」那段容错**根本执行不到**——
 * 备选分支放在一个加载阶段就会崩的类里，等于不存在。
 *
 * <p>现在的加载路径分三步，只有全部通过才会去加载真正引用对方的
 * {@link WbdAddonModeBridge}：
 * <ol>
 *   <li>WBDAddon 插件在不在、启用没有（只碰 {@link Plugin}，不碰它的类）</li>
 *   <li>它有没有 {@code alphahu.wbdaddon.api} 包（拦住 1.1.0 之前的旧版本）</li>
 *   <li>确认无误后才 {@code Class.forName} 加载实现类</li>
 * </ol>
 * 任何一步不通过，都只是少几个附加功能，死斗本体照常运行。
 *
 * @author AlphaHu
 */
public final class AddonBridge {

    /** WBDAddon 对外服务接口的全名，用于探测它的版本。 */
    private static final String API_CLASS = "alphahu.wbdaddon.api.WbdAddonApi";

    /**
     * 实现类的全名。
     *
     * <p>刻意用字符串而不是 {@code WbdAddonModeBridge.class}：后者会让本类在
     * 常量池里持有对实现类的引用，JVM 解析时常数池的解析虽然通常也是惰性的，
     * 但用字符串能百分百保证本类加载时不会连带触发实现类的加载。
     */
    private static final String IMPL_CLASS = "alphahu.tdm.integration.WbdAddonModeBridge";

    private final WBDTeamDeathmatch plugin;

    /** 已建立的对接，null 表示不可用（没装、版本旧、或注册失败）。 */
    private ModeBridge bridge;

    public AddonBridge(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 建立与拆除
    // ------------------------------------------------------------------

    /**
     * 探测并建立对接。可重复调用（重载配置时用），旧连接会先被拆掉。
     */
    public void register() {
        unregister();

        if (!plugin.getSettings().wbdAddon) {
            plugin.getLogger().info("配置中已关闭 WBDAddon 集成，死斗将独立运行。");
            return;
        }

        // 第 1 步：插件在不在。这里只碰 Plugin 接口，不会触发对方任何类的加载
        Plugin wbdAddon = Bukkit.getPluginManager().getPlugin("WBDAddon");
        if (wbdAddon == null || !wbdAddon.isEnabled()) {
            plugin.getLogger().info("未检测到可用的 WBDAddon，"
                    + "饥饿锁定 / 防第三人称 / JourneyMap 桥接等附加功能不可用，"
                    + "死斗本身不受影响。");
            return;
        }

        // 第 2 步：它有没有 api 包。
        // initialize=false 只检查类存在与否，不触发静态初始化；
        // 用对方的 ClassLoader 查询，不依赖本插件自己的可见性配置
        if (!hasApiClass(wbdAddon)) {
            plugin.getLogger().warning("检测到 WBDAddon，但它不提供 " + API_CLASS + "。");
            plugin.getLogger().warning("这通常是版本过旧（需要 1.1.0 或更高）。"
                    + "请更新 WBDAddon 以启用饥饿锁定、防第三人称、JourneyMap 桥接等附加功能。");
            return;
        }

        // 第 3 步：到这一步才能安全加载实现类——AddonMode 一定解析得到
        ModeBridge created = instantiateBridge();
        if (created == null) {
            return;
        }
        if (!created.isActive()) {
            plugin.getLogger().info("WBDAddon 在场，但它的服务尚未发布，"
                    + "本次未能接入（附加功能暂不可用）。");
            return;
        }

        this.bridge = created;
        reportConnected();
    }

    /** 探测对方的 api 包是否存在。 */
    private boolean hasApiClass(Plugin wbdAddon) {
        try {
            Class.forName(API_CLASS, false, wbdAddon.getClass().getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 加载并实例化实现类。任何失败都降级为「集成不可用」，绝不冒泡。 */
    private ModeBridge instantiateBridge() {
        try {
            Class<?> implClass = Class.forName(IMPL_CLASS);
            Object instance = implClass
                    .getConstructor(WBDTeamDeathmatch.class)
                    .newInstance(plugin);

            if (!(instance instanceof ModeBridge modeBridge)) {
                plugin.getLogger().warning("对接实现类的类型不符，已跳过 WBDAddon 集成。");
                return null;
            }
            if (!tryRegister(modeBridge)) {
                return null;
            }
            return modeBridge;
        } catch (Throwable t) {
            plugin.getLogger().warning("接入 WBDAddon 时出错，将以独立模式运行：" + t);
            return null;
        }
    }

    /**
     * 反射调用实现类的 {@code register()}。
     *
     * <p>用反射而不是接口方法：注册是「建立连接」的一次性动作，
     * 不属于 {@link ModeBridge} 的运行时职责，没必要把它暴露给主流程。
     */
    private boolean tryRegister(ModeBridge modeBridge) {
        try {
            modeBridge.getClass().getMethod("register").invoke(modeBridge);
            return modeBridge.isActive();
        } catch (Throwable t) {
            plugin.getLogger().warning("向 WBDAddon 注册模式失败：" + t);
            return false;
        }
    }

    private void reportConnected() {
        plugin.getLogger().info("已接入 WBDAddon，"
                + "饥饿锁定 / 防第三人称 / JourneyMap 桥接等附加功能可用。");
        plugin.getLogger().info("WBDAddon 提供的伤害账本："
                + (damageLedger() != null ? "可用" : "不可用（kill-reports 模块未启用）"));
        plugin.getLogger().info("顶部状态行："
                + (isHudRelayed() ? "由 WBDAddon 的 match-status 渲染"
                                  : "由本插件自行渲染"));
    }

    /** 断开对接。 */
    public void unregister() {
        if (bridge != null) {
            try {
                bridge.unregister();
            } catch (Throwable t) {
                plugin.getLogger().warning("断开 WBDAddon 集成时出错：" + t);
            }
            bridge = null;
        }
    }

    /** 重新探测 HUD 代渲染是否可用（重载时调用）。 */
    public void refreshHudRelay() {
        if (bridge == null) {
            return;
        }
        try {
            bridge.getClass().getMethod("refreshHudRelay").invoke(bridge);
        } catch (Throwable ignored) {
            // 探测失败就维持现状，不影响比赛
        }
    }

    // ------------------------------------------------------------------
    // 供主流程使用的能力查询
    // ------------------------------------------------------------------

    /** 对接是否已建立。 */
    public boolean isActive() {
        return bridge != null;
    }

    /** HUD 是否确实由 WBDAddon 渲染。 */
    public boolean isHudRelayed() {
        return bridge != null && bridge.isHudRelayed();
    }

    /** 解析武器显示名；对接不可用时走本插件自己的简化实现。 */
    public String resolveWeaponName(Player killer) {
        if (bridge == null) {
            return fallbackWeaponName(killer);
        }
        try {
            return bridge.resolveWeaponName(killer);
        } catch (Throwable t) {
            return fallbackWeaponName(killer);
        }
    }

    /** 伤害账本；不可用时返回 null，调用方必须处理空值。 */
    public DamageLedger damageLedger() {
        if (bridge == null) {
            return null;
        }
        try {
            return bridge.damageLedger();
        } catch (Throwable t) {
            return null;
        }
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
}

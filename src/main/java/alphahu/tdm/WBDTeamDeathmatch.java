package alphahu.tdm;

import alphahu.tdm.arena.TdmArena;
import alphahu.tdm.combat.CombatListener;
import alphahu.tdm.command.TdmCommand;
import alphahu.tdm.config.TdmSettings;
import alphahu.tdm.game.LoadoutService;
import alphahu.tdm.game.MatchManager;
import alphahu.tdm.game.SkinService;
import alphahu.tdm.hud.MatchHud;
import alphahu.tdm.integration.AddonBridge;
import alphahu.tdm.integration.DamageLedger;
import alphahu.tdm.message.Messages;
import alphahu.tdm.ranked.RankedService;
import alphahu.tdm.report.ReportListener;
import alphahu.tdm.report.ReportService;
import alphahu.tdm.shop.ShopEditor;
import alphahu.tdm.shop.ShopListener;
import alphahu.tdm.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * WBDTeamDeathmatch 主类。
 *
 * <p>团队死斗模式：无限复活、限时击杀赛。设计上与 WarZBombDefuse 的爆破模式
 * 完全平行——它有自己的场地、自己的比赛循环、自己的计分，
 * 不借用 WBD 的 {@code ArenaSession}（那个类是 final 的回合状态机，
 * 塞不进一个没有回合的玩法）。
 *
 * <p>两边唯一的交集是通过 WBDAddon 的 {@code WbdAddonApi}：把死斗玩家注册成
 * 一个「模式」，附加组件便自动把饥饿锁定、JourneyMap 桥接
 * 这些功能一并覆盖到死斗玩家身上，不必在死斗里重写一遍。
 *
 * @author AlphaHu
 */
public final class WBDTeamDeathmatch extends JavaPlugin {

    private static WBDTeamDeathmatch instance;

    private TdmSettings settings;
    private MatchManager matchManager;
    private MatchHud hud;
    private LoadoutService loadout;
    private ShopService shop;
    private ShopEditor shopEditor;
    private AddonBridge addonBridge;
    private Messages messages;
    private RankedService ranked;
    private ReportService reports;
    private SkinService skins;

    /** 比赛主循环任务。 */
    private BukkitTask tickTask;

    /** 赛季到期检查任务。 */
    private BukkitTask seasonTask;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        printBanner();

        // 依赖检查放在任何初始化之前。
        // 缺 WarZBombDefuse 时直接拒绝启动 —— 本插件没有一样数据格式是自己
        // 定义的（场地、商店、物品数据全用它的），硬撑着跑只会留下一个
        // 「命令存在但什么都不会发生」的插件，比干脆不加载更难查。
        if (!checkDependencies()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        instance = this;

        // 整个初始化过程都包在 try 里。
        //
        // 这不是形式主义：Bukkit 会**先**根据 plugin.yml 把命令对象注册好，
        // **然后**才调用 onEnable。所以只要 onEnable 中途抛了异常，
        // 命令依然"存在"于命令表里，但 executor 永远不会被挂上——
        // 玩家执行 /tdm 系列命令时，服务端只会吐出 plugin.yml 里的 usage，
        // 也就是干巴巴一句「/tdm help」，看不出任何异常迹象。
        //
        // 把异常显式打出来并主动禁用自己，是让这类故障从「猜」变成「读日志」的关键。
        try {
            init();
        } catch (Throwable t) {
            getLogger().severe("=================================================");
            getLogger().severe("WBDTeamDeathmatch 启用失败，插件将被禁用。");
            getLogger().severe("错误：" + t);
            getLogger().severe("提示：/tdm 命令此时不可用，执行它只会显示「/tdm help」。");
            getLogger().severe("=================================================");
            t.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    /**
     * 检查两个依赖，决定能不能启动、以及要不要降级。
     *
     * <p><b>WarZBombDefuse 是硬依赖。</b>竞技场坐标格式、商店配置格式、
     * 商品里内嵌的 Bukkit 序列化物品数据，全都沿用它的那一套，
     * 本插件没有一样是自己定义的。plugin.yml 里已经写了 {@code depend}，
     * 正常情况下服务端根本不会走到这里；这一段是给
     * 「装了但加载失败、或被别的插件连带禁用」准备的 —— 那种情况
     * Bukkit 不会拦，得自己拦。
     *
     * <p><b>WBDAddon 是软依赖。</b>它只影响附加功能，缺了就逐条列出来，
     * 死斗本体照常运行。注意这里列的是「本插件视角下会失去什么」，
     * 具体探测与降级由 {@link AddonBridge} 负责执行。
     *
     * @return 是否允许继续启动
     */
    private boolean checkDependencies() {
        Plugin wbd = getServer().getPluginManager().getPlugin("WarZBombDefuse");
        if (wbd == null || !wbd.isEnabled()) {
            getLogger().severe("=================================================");
            getLogger().severe(" 缺少硬依赖：WarZBombDefuse");
            getLogger().severe("");
            getLogger().severe(" 本插件复用它的竞技场格式、商店格式与物品数据，");
            getLogger().severe(" 没有它连一份场地都读不出来，已停止加载。");
            getLogger().severe("");
            getLogger().severe(" 请把 WarZBombDefuse 放进 plugins/ 后重启服务器。");
            getLogger().severe("=================================================");
            return false;
        }
        getLogger().info("依赖检查：WarZBombDefuse "
                + wbd.getDescription().getVersion() + " 已就绪。");

        Plugin addon = getServer().getPluginManager().getPlugin("WBDAddon");
        if (addon == null || !addon.isEnabled()) {
            getLogger().warning("依赖检查：未检测到可用的 WBDAddon，以下附加功能不可用 ——");
            getLogger().warning("    · 竞技场饥饿锁定（arena-food）");
            getLogger().warning("    · JourneyMap 队伍桥接");
            getLogger().warning("    · 击杀播报的武器名解析、回合伤害统计");
            getLogger().warning("    · 顶部状态行交给 WBDAddon 代渲染");
            getLogger().warning("  死斗本体（场地、复活、计分、商店、经济）不受影响。");
            return true;
        }
        getLogger().info("依赖检查：WBDAddon "
                + addon.getDescription().getVersion() + " 已就绪，附加功能已启用。");
        return true;
    }

    private void init() {
        // 1. 配置。arenas.yml 与 shop.yml 只在缺失时生成，绝不覆盖服主已经配好的内容
        saveDefaultConfig();
        saveResource("arenas.yml", false);
        // 这份 shop.yml 与 WarZBombDefuse 内置的那份一样是**空商店**（items 为空），
        // 刻意不带任何武器：插件不该替服主决定卖什么枪、更不该拿某台服务器调好的
        // 那份当默认。要商品就把自己的配置放进来，或让 shop.source 指向 wbd。
        saveResource("shop.yml", false);
        // 段位与消息文案。两者都在缺失时才生成，同样不覆盖服主改过的版本
        saveResource("season.yml", false);
        saveResource("messages.yml", false);
        reloadConfig();
        this.settings = TdmSettings.load(getConfig());

        // 2. 表现层、出生装备与商店
        this.hud = new MatchHud(this);
        this.loadout = new LoadoutService(this, settings, getLogger());
        this.shop = new ShopService(this);
        loadShop();

        // 2.5 消息文案与段位。段位要在比赛开始前装好 —— 公告一开场就会
        //     去问段位显示名，那时它必须已经能回答（哪怕是「未定级」）
        this.messages = new Messages(this);
        messages.load("messages.yml", "messages.yml");
        this.ranked = new RankedService(this);
        ranked.load(loadSeasonConfig());

        // 战报服务。它没有需要预热的资源，但要在比赛开始前就位 ——
        // 第一场比赛结束时就会用到它
        this.reports = new ReportService(this);

        // 3. 场地与比赛
        this.matchManager = new MatchManager(this);
        matchManager.loadArenas();

        // 4. 接入 WBDAddon（软依赖，取不到就降级）
        this.addonBridge = new AddonBridge(this);

        // 4.5 队伍皮肤（软依赖，靠命令模板接皮肤插件；没装就什么都不做）
        this.skins = new SkinService(this);
        skins.checkPrerequisites();

        // 5. 监听战斗事件与商店交互
        Bukkit.getPluginManager().registerEvents(new CombatListener(this, matchManager), this);
        Bukkit.getPluginManager().registerEvents(new ShopListener(this), this);
        // 崩溃重启后的皮肤补救要在玩家进服时就能响应
        Bukkit.getPluginManager().registerEvents(skins, this);
        this.shopEditor = new ShopEditor(this);
        Bukkit.getPluginManager().registerEvents(shopEditor, this);
        // 战报界面的点击与拖拽拦截
        Bukkit.getPluginManager().registerEvents(new ReportListener(this), this);

        // 6. 注册命令
        registerCommand();

        // 7. 对接放在最后：AddonBridge 注册后，WBDAddon 的定时任务就会开始
        //    向我们要 hudLine，此时 matchManager 必须已经能正常回答查询
        addonBridge.register();

        // 8. 比赛主循环。每 tick 推进一次：复活排期、状态切换、边界限制
        //    都靠它，因此频率不能再低——晚一 tick 玩家就多等 50 毫秒
        this.tickTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> matchManager.tick(), 20L, 1L);

        // 9. 赛季到期检查。五分钟一次足够 —— 赛季以「天」为单位，
        //    查得再勤也只是白白读写一次数据库
        this.seasonTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> ranked.tickSeason(), 20L * 60, 20L * 300);

        getLogger().info("WBDTeamDeathmatch 已加载。");
    }

    /** 读取 season.yml。缺失时由 init() 先释放资源，再进到这里。 */
    private FileConfiguration loadSeasonConfig() {
        File file = new File(getDataFolder(), "season.yml");
        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * 读取商店配置。
     *
     * <p>默认直接复用 WarZBombDefuse 的那份 —— 里面往往有几十件商品，
     * 还内嵌了枪械（TacZ 等）的完整物品数据，是编辑器生成的，重写一遍不现实。
     * 读不到时自动退回本插件自己的 shop.yml，让死斗至少还能跑起来。
     */
    private void loadShop() {
        String source = settings.shopSource;
        File file;

        if ("wbd".equalsIgnoreCase(source)) {
            file = resolveServerFile(settings.shopWbdFile);
            if (file == null || !file.exists()) {
                getLogger().warning("找不到 WBD 的商店配置（" + settings.shopWbdFile
                        + "），已回退到本插件自己的 " + settings.shopLocalFile + "。");
                file = new File(getDataFolder(), settings.shopLocalFile);
            }
        } else {
            file = new File(getDataFolder(), settings.shopLocalFile);
        }

        // 旧版本曾经把一份「某台服务器调好的商品」释放成 shop-tdm.yml 并设为默认。
        // 那等于插件替服主预设了卖什么枪，已经去掉了；但老配置里的 local-file
        // 还指着它，于是插件升级后行为不会自动变回来 —— 这里点一句，免得
        // 服主以为「默认商店怎么突然带武器了」。
        if (file != null && "shop-tdm.yml".equalsIgnoreCase(file.getName())) {
            getLogger().warning("shop.local-file 指向 shop-tdm.yml —— 那是旧版本释放的示例商店"
                    + "（里面带武器，不是插件默认）。插件已不再自带该文件。"
                    + "想用空商店请把 local-file 改回 shop.yml；想复用爆破模式的商品"
                    + "请把 source 改成 wbd。");
        }

        shop.load(file);
    }

    /**
     * 解析「相对服务端根目录」的路径。
     *
     * <p>服主可能写 {@code plugins/WarZBombDefuse/shop.yml}，也可能只写
     * {@code WarZBombDefuse/shop.yml}。两种都试一次，省掉一轮
     * 「为什么找不到文件」的来回。
     */
    public File resolveServerFile(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        File pluginsDir = getDataFolder().getParentFile();
        File serverRoot = pluginsDir == null ? null : pluginsDir.getParentFile();

        if (serverRoot != null) {
            File direct = new File(serverRoot, configured);
            if (direct.exists()) {
                return direct;
            }
        }
        if (pluginsDir != null) {
            File relative = new File(pluginsDir,
                    configured.replaceFirst("^[/\\\\]?plugins[/\\\\]", ""));
            if (relative.exists()) {
                return relative;
            }
        }
        return serverRoot == null ? null : new File(serverRoot, configured);
    }

    /**
     * 挂上 /tdm 的命令执行器。
     *
     * <p>取不到命令对象时**必须出声**。以前这里是个静默的 {@code if (cmd != null)}，
     * 结果是命令表里有 /tdm、却没有执行器，玩家敲任何 /tdm 子命令都只看到
     * 「/tdm help」，控制台干干净净——最难查的那种故障。
     */
    private void registerCommand() {
        TdmCommand executor = new TdmCommand(this);
        var cmd = getCommand("tdm");

        if (cmd == null) {
            getLogger().severe("找不到 /tdm 命令对象，本插件的命令将不可用。");
            getLogger().severe("常见原因：另一个插件已经注册了 /tdm，把本插件挤掉了。");
            getLogger().severe("可以改用本插件的别名 /teamdeathmatch 试试；");
            getLogger().severe("若别名也不通，请把上面几行连同完整启动日志一起反馈。");
            return;
        }

        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
        getLogger().info("/tdm 命令已就绪（别名：" + String.join("、", cmd.getAliases()) + "）。");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (seasonTask != null) {
            seasonTask.cancel();
            seasonTask = null;
        }

        // 先注销模式，再清场：反过来的话，WBDAddon 的定时任务可能在清场
        // 过程中通过 API 读到已经半拆解的比赛对象
        if (addonBridge != null) {
            addonBridge.unregister();
        }

        if (matchManager != null) {
            matchManager.shutdown();
        }

        // 把还穿着队伍皮肤的人还原。必须排在清场之后：清场会走 leave()，
        // 那条路径已经把皮肤还原过了，这里只是兜底（例如清场途中抛了异常）
        if (skins != null) {
            skins.restoreAll();
        }

        if (hud != null) {
            hud.shutdown();
        }

        // 段位放在最后收尾：它会先把脏档案写回数据库再关闭连接。
        // 顺序反了的话，关服那一刻刚好结算完的那一局会丢分
        if (ranked != null) {
            ranked.shutdown();
        }

        instance = null;
        getLogger().info("WBDTeamDeathmatch 已卸载。");
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /**
     * 重载配置与竞技场，并重启所有比赛。
     *
     * <p>会先把场上的人还原送走，因此不适合在比赛正打到一半时随手执行。
     */
    public void reloadAll() {
        reloadConfig();
        this.settings = TdmSettings.load(getConfig());
        this.loadout = new LoadoutService(this, settings, getLogger());
        loadShop();

        // 消息文案与段位设置也跟着重载。漏掉这两行的后果是「改了
        // messages.yml 或 season.yml 必须重启才生效」，而 /tdm reload
        // 看上去明明成功了 —— 这种半吊子重载比不支持重载更让人困惑
        this.messages.load("messages.yml", "messages.yml");
        this.ranked.reloadSettings(loadSeasonConfig());

        // 场地重建会顺带把场上的人送出去
        matchManager.loadArenas();

        // 集成开关可能被改了，重新走一遍注册流程
        addonBridge.unregister();
        addonBridge.register();

        getLogger().info("配置、竞技场与商店已重载。");
    }

    // ------------------------------------------------------------------
    // 供其他组件使用的门面
    // ------------------------------------------------------------------

    public static WBDTeamDeathmatch getInstance() {
        return instance;
    }

    public TdmSettings getSettings() {
        return settings;
    }

    public MatchManager getMatchManager() {
        return matchManager;
    }

    public MatchHud getHud() {
        return hud;
    }

    public LoadoutService getLoadout() {
        return loadout;
    }

    public ShopService getShop() {
        return shop;
    }

    public ShopEditor getShopEditor() {
        return shopEditor;
    }

    public AddonBridge getAddonBridge() {
        return addonBridge;
    }

    /** 消息文案服务，用于公告与结算展示。 */
    public Messages getMessages() {
        return messages;
    }

    /**
     * 段位服务。
     *
     * <p>永远非 null（即使数据库不可用也会有一个「不可用」的实例），
     * 调用方不必到处判空 —— 那样迟早会漏掉一处，然后 NPE。
     * 是否真的结算由 {@link RankedService#available()} 决定。
     */
    public RankedService getRanked() {
        return ranked;
    }

    /** 战报服务。 */
    public ReportService getReports() {
        return reports;
    }

    /** 队伍皮肤服务。 */
    public SkinService getSkins() {
        return skins;
    }

    /**
     * 伤害账本，不可用时返回 null。
     *
     * <p>死斗用它来校正 TacZ 环境下的击杀归属：那种场景里
     * {@code Player#getKiller()} 经常是空的，只能靠「谁打的血最多」来推断。
     *
     * <p>返回的是本插件自己的 {@link DamageLedger} 而不是 WBDAddon 的类型 ——
     * 后者会让这个类在没装 WBDAddon 的服务器上加载失败。
     */
    public DamageLedger getDamageLedger() {
        return addonBridge == null ? null : addonBridge.damageLedger();
    }

    /**
     * 当前 ActionBar 是否由 WBDAddon 负责发送。
     *
     * <p>HUD 只能有一个发送者，否则两段文字会交替闪现。
     */
    public boolean isAddonHudActive() {
        return addonBridge != null && addonBridge.isHudRelayed();
    }

    /** 解析武器显示名，优先用 WBDAddon 的实现（能认 TacZ 枪械）。 */
    public String resolveWeaponName(Player killer) {
        return addonBridge == null ? "未知" : addonBridge.resolveWeaponName(killer);
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    private void printBanner() {
        String[] lines = {
                "&b========================================",
                "&b  WBDTeamDeathmatch &ev" + pluginVersion(),
                "&b  构建：&e" + buildStamp(),
                "&b  作者：&dAlphaHu",
                "&b  团队死斗：无限复活 / 限时击杀赛",
                "&b  可接入 WBDAddon 复用饥饿锁定与 JourneyMap 桥接",
                "&b  输入 &e/tdm help &b查看用法",
                "&b========================================"
        };
        for (String line : lines) {
            Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    @SuppressWarnings("deprecation")
    private String pluginVersion() {
        return getDescription().getVersion();
    }

    /** 读取打包时生成的 git.properties，让日志能对应到具体提交。 */
    private String buildStamp() {
        try (InputStream in = getResource("git.properties")) {
            if (in == null) {
                return "未知（非打包产物）";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("git.commit.id.abbrev", "?")
                    + "  " + props.getProperty("git.build.time", "?");
        } catch (IOException e) {
            return "未知";
        }
    }

    /** 供命令层复用的一次性提示：把所有场地列成一张可读的表。 */
    public String describeArenas() {
        StringBuilder sb = new StringBuilder();
        for (TdmArena arena : matchManager.getArenas()) {
            sb.append(arena.getId())
                    .append(arena.isEnabled() ? "（启用）" : "（未启用）");
            if (!arena.isValid()) {
                sb.append(" ⚠配置不完整");
            }
            sb.append(' ');
        }
        return sb.toString();
    }
}

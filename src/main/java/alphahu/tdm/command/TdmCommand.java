package alphahu.tdm.command;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.arena.ArenaLoader;
import alphahu.tdm.arena.TdmArena;
import alphahu.tdm.game.MatchState;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.game.TdmTeam;
import alphahu.tdm.message.Messages;
import alphahu.tdm.ranked.MatchRecord;
import alphahu.tdm.ranked.RankedProfile;
import alphahu.tdm.ranked.RankedService;
import alphahu.tdm.report.ReportMenu;
import alphahu.tdm.util.ItemCodec;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /tdm} 主命令。
 *
 * <p>子命令一览：
 * <ul>
 *   <li>{@code /tdm help} —— 帮助</li>
 *   <li>{@code /tdm list} —— 列出场地与状态</li>
 *   <li>{@code /tdm join [场地] [t|ct]} —— 加入比赛</li>
 *   <li>{@code /tdm leave} —— 离开比赛</li>
 *   <li>{@code /tdm status [场地]} —— 查看比赛详情</li>
 *   <li>{@code /tdm start|stop [场地]} —— 管理员强制开始/结束</li>
 *   <li>{@code /tdm reload} —— 重载配置与场地</li>
 *   <li>{@code /tdm import <wbd场地id> [新id]} —— 从 WarZBombDefuse 导入坐标</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class TdmCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "help", "list", "join", "leave", "status", "shop",
            "shopedit", "shopids", "enable", "disable", "start", "stop", "reload", "import");

    private final WBDTeamDeathmatch plugin;

    public TdmCommand(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help" -> sendHelp(sender);
            case "list" -> sendList(sender);
            case "join" -> handleJoin(sender, args);
            case "leave" -> handleLeave(sender);
            case "status" -> sendStatus(sender, args);
            case "shop" -> handleShop(sender, args);
            case "shopids" -> handleShopIds(sender);
            case "shopedit" -> handleShopEdit(sender, args);
            case "enable" -> handleToggle(sender, args, true);
            case "disable" -> handleToggle(sender, args, false);
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender, args);
            case "reload" -> handleReload(sender);
            case "import" -> handleImport(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "exportitem" -> handleExportItem(sender);
            case "rank" -> handleRank(sender, args);
            case "matchrp" -> handleMatchReport(sender, args);
            case "season" -> handleSeason(sender, args);
            default -> sender.sendMessage(color("&c未知子命令。使用 /tdm help 查看帮助。"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 帮助与列表
    // ------------------------------------------------------------------

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&6====== WBDTeamDeathmatch 帮助 ======"));
        sender.sendMessage(color("&e/tdm list &7- 查看所有死斗场地"));
        sender.sendMessage(color("&e/tdm join [场地] [t|ct] &7- 加入比赛（可指定阵营）"));
        sender.sendMessage(color("&e/tdm leave &7- 离开比赛"));
        sender.sendMessage(color("&e/tdm shop &7- 打开装备商店"));
        sender.sendMessage(color("&e/tdm status [场地] &7- 查看比赛详情"));
        sender.sendMessage(color("&e/tdm rank [玩家] &7- 查询段位与本赛季战绩"));
        sender.sendMessage(color("&e/tdm matchrp [玩家] &7- 查看最近比赛战报（点击图标展开）"));
        if (sender.hasPermission("tdm.admin")) {
            sender.sendMessage(color("&e/tdm shopedit [T|CT] [页] &7- 可视化编辑商店商品"));
            sender.sendMessage(color("&e/tdm shop adopt &7- 采用 WarZBombDefuse 的商店配置"));
            sender.sendMessage(color("&e/tdm enable|disable <场地id> &7- 启用 / 停用场地"));
            sender.sendMessage(color("&e/tdm import <wbd场地id> [新id] &7- 从 WarZBombDefuse 导入坐标"));
            sender.sendMessage(color("&e/tdm delete <场地id> &7- 删除一个死斗场地（不动 WBD 那边）"));
            sender.sendMessage(color("&e/tdm exportitem &7- 把手持物品导出成能贴进 config.yml 的物品数据"));
            sender.sendMessage(color("&e/tdm start|stop [场地] &7- 强制开始 / 结束比赛"));
            sender.sendMessage(color("&e/tdm season [reset] &7- 查看赛季剩余天数 / 手动软重置"));
            sender.sendMessage(color("&e/tdm reload &7- 重载配置与竞技场"));
            sender.sendMessage(color("&e/tdm shopids &7- 列出商品 id 与推断类别（贴进 item-categories.yml）"));
        }
        sender.sendMessage(color("&6==================================="));
    }

    private void sendList(CommandSender sender) {
        sender.sendMessage(color("&6====== 死斗场地 ======"));
        if (plugin.getMatchManager().getArenas().isEmpty()) {
            sender.sendMessage(color("&c没有配置任何场地。"));
            sender.sendMessage(color("&7编辑 &fplugins/WBDTeamDeathmatch/arenas.yml&7，"
                    + "或用 &f/tdm import <wbd场地id> &7从 WarZBombDefuse 导入。"));
            sender.sendMessage(color("&6====================="));
            return;
        }

        for (TdmMatch match : plugin.getMatchManager().getMatches()) {
            TdmArena arena = match.getArena();
            String flag = arena.isEnabled() ? "&a启用" : "&c未启用";
            String warn = arena.isValid() ? "" : " &c⚠配置不完整";
            sender.sendMessage(color("&e" + arena.getId() + " &7(" + arena.getName() + ") " + flag
                    + " &7状态 " + stateText(match.getState())
                    + " &7人数 &f" + match.size() + warn));
        }
        sender.sendMessage(color("&6====================="));
    }

    private void sendStatus(CommandSender sender, String[] args) {
        TdmMatch match = resolveMatch(sender, args, 1);
        if (match == null) return;

        TdmArena arena = match.getArena();
        sender.sendMessage(color("&6====== " + arena.getName() + " ======"));
        sender.sendMessage(color("&7状态：&f" + stateText(match.getState())
                + " &7剩余 &f" + match.timeText(System.currentTimeMillis())));
        sender.sendMessage(color("&cT &f" + match.getScore(TdmTeam.T)
                + " &7- &bCT &f" + match.getScore(TdmTeam.CT)
                + " &7（目标 &f" + match.getRules().targetKills() + "&7）"));
        sender.sendMessage(color("&7存活 &f" + match.aliveCount() + "&7/&f" + match.size()
                + " &7| T &f" + match.teamSize(TdmTeam.T)
                + " &7| CT &f" + match.teamSize(TdmTeam.CT)));
        if (match.getWinner() != null) {
            sender.sendMessage(color("&7胜者：&f" + match.getWinner().displayName()));
        }
        for (var record : match.getPlayers()) {
            sender.sendMessage(color("&8- " + record.team().color() + record.name()
                    + " &7击杀 &f" + record.kills()
                    + " &7死亡 &f" + record.deaths()
                    + (record.isAlive() ? "" : " &7(等待复活 " + record.respawnSecondsLeft(System.currentTimeMillis()) + "s)")));
        }
        sender.sendMessage(color("&6====================="));
    }

    private String stateText(MatchState state) {
        return switch (state) {
            case WAITING -> "&7等待中";
            case COUNTDOWN -> "&e倒计时";
            case LIVE -> "&a进行中";
            case ENDED -> "&6结算中";
        };
    }

    // ------------------------------------------------------------------
    // 玩家操作
    // ------------------------------------------------------------------

    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c该命令只能由玩家执行。"));
            return;
        }
        if (!player.hasPermission("tdm.use")) {
            player.sendMessage(color("&c你没有权限加入比赛。"));
            return;
        }

        // 已在比赛中就不必再走一遍
        TdmMatch current = plugin.getMatchManager().getMatch(player);
        if (current != null) {
            player.sendMessage(color("&c你已在场地 " + current.getArena().getName() + " 的比赛中，"
                    + "用 /tdm leave 退出。"));
            return;
        }

        TdmMatch target;
        if (args.length >= 2) {
            target = plugin.getMatchManager().getMatch(args[1]);
            if (target == null) {
                player.sendMessage(color("&c找不到场地：" + args[1] + "。用 /tdm list 查看可用场地。"));
                return;
            }
        } else {
            target = plugin.getMatchManager().pickForJoin();
            if (target == null) {
                player.sendMessage(color("&c当前没有可用的死斗场地。"));
                return;
            }
        }

        TdmTeam requested = args.length >= 3 ? TdmTeam.fromInput(args[2]) : null;
        if (args.length >= 3 && requested == null) {
            player.sendMessage(color("&c无法识别的阵营：" + args[2] + "，请输入 t 或 ct。"));
            return;
        }
        if (requested != null && !player.hasPermission("tdm.use")) {
            requested = null;
        }

        String error = target.join(player, requested);
        if (error != null) {
            player.sendMessage(color(error));
        } else {
            player.sendMessage(color("&a已加入 &e" + target.getArena().getName() + "&a 的死斗比赛。"));
        }
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c该命令只能由玩家执行。"));
            return;
        }
        TdmMatch match = plugin.getMatchManager().getMatch(player);
        if (match == null) {
            player.sendMessage(color("&c你不在任何死斗比赛中。"));
            return;
        }
        match.leave(player, true);
        player.sendMessage(color("&a已离开比赛，你的位置与背包已还原。"));
    }

    /** 打开装备商店；带 {@code adopt} 参数时改为「采用 WBD 的商店配置」。 */
    private void handleShop(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("adopt")) {
            handleShopAdopt(sender);
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c该命令只能由玩家执行。"));
            return;
        }
        if (!plugin.getSettings().shopEnabled) {
            player.sendMessage(color("&c本服未启用装备商店。"));
            return;
        }
        TdmMatch match = plugin.getMatchManager().getMatch(player);
        if (match == null || !match.isActive(player)) {
            player.sendMessage(color("&c只有比赛中的参赛玩家才能使用商店。"));
            return;
        }
        // 与右键商店物品走同一道购买区检查。这条命令是绕过限制最省事的入口 ——
        // 只在右键那条路径上做限制，等于把限制挂在了一扇没锁的门上
        if (!match.canPurchase(player)) {
            player.sendMessage(plugin.getMessages().get("shop.outside-buy-zone"));
            return;
        }
        plugin.getShop().open(player);
    }

    /**
     * 打开可视化商店编辑器。
     *
     * <p>用法：{@code /tdm shopedit} 编辑公共商城，{@code /tdm shopedit T} 编辑 T 方，
     * 末尾可再加页码。
     */
    /**
     * {@code /tdm shopids}：把商店里的商品 id 连同推断出的类别打出来，
     * 方便直接粘进 item-categories.yml 的 ids 段。
     */
    private void handleShopIds(CommandSender sender) {
        var items = plugin.getShop().allItems();
        if (items.isEmpty()) {
            sender.sendMessage(color("&c商店里没有商品。"));
            return;
        }
        sender.sendMessage(color("&e共 " + items.size() + " 件商品（类别是按材质推断的，按需改）："));
        sender.sendMessage(color("&8ids:"));
        for (var item : items) {
            var categories = plugin.getShop().getCategories();
            var category = categories.classify(item);
            String key = category == alphahu.tdm.shop.LoadoutCategories.Category.NONE
                    ? "?" : category.key();
            String gunId = item.give() == null ? null : categories.gunId(item.give());
            sender.sendMessage(color("&8  " + item.id() + ": &f" + key
                    + (gunId == null ? "" : " &8(" + gunId + ")")));
        }
        sender.sendMessage(color("&7粘进 plugins/WBDTeamDeathmatch/item-categories.yml 后 &f/tdm reload&7。"));
    }

    private void handleShopEdit(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c编辑器需要图形界面，只能由玩家执行。"));
            return;
        }

        String teamId = null;
        int page = 1;
        if (args.length >= 2) {
            String raw = args[1];
            if (raw.equalsIgnoreCase("t")) {
                teamId = "T";
            } else if (raw.equalsIgnoreCase("ct")) {
                teamId = "CT";
            } else if (raw.equalsIgnoreCase("any") || raw.equalsIgnoreCase("common")) {
                teamId = null;
            } else {
                Integer parsed = parsePage(raw);
                if (parsed == null) {
                    player.sendMessage(color("&c无法识别「" + raw
                            + "」。用法：/tdm shopedit [T|CT] [页码]"));
                    return;
                }
                page = parsed;
            }
        }
        if (args.length >= 3) {
            Integer parsed = parsePage(args[2]);
            if (parsed == null) {
                player.sendMessage(color("&c页码必须是数字。"));
                return;
            }
            page = parsed;
        }

        plugin.getShopEditor().open(player, teamId, page);
    }

    private Integer parsePage(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 采用 WarZBombDefuse 的商店配置：复制一份到本插件目录，改成独立维护。
     *
     * <p>为什么是「复制」而不是「继续共用」：两边的需求本来就不同 ——
     * 死斗不比经济、没有回合、拆弹器之类的东西也用不上。共用一份意味着
     * 每次为死斗调整都要顾虑爆破模式，早晚会互相牵制。复制出来之后两边独立，
     * 想同步时手动再复制一次即可。
     */
    private void handleShopAdopt(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }

        File source = resolveWbdShopFile();
        if (source == null || !source.exists()) {
            sender.sendMessage(color("&c找不到 WarZBombDefuse 的商店配置。"));
            sender.sendMessage(color("&7请确认路径，或修改 config.yml 里的 "
                    + "&fshop.wbd-file&7。"));
            return;
        }

        File target = new File(plugin.getDataFolder(),
                plugin.getSettings().shopLocalFile);
        try {
            Files.copy(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            sender.sendMessage(color("&c复制失败：" + e.getMessage()));
            plugin.getLogger().warning("复制商店配置失败：" + e);
            return;
        }

        boolean switched = switchShopSourceToLocal();

        plugin.reloadAll();

        sender.sendMessage(color("&a已把 WBD 的商店配置复制到 &f" + target.getName() + "&a。"));
        if (switched) {
            sender.sendMessage(color("&a并已把 config.yml 的 &fshop.source &a改成 &flocal&a，"
                    + "之后两边独立维护。"));
        } else {
            sender.sendMessage(color("&e自动改 config.yml 失败，请手动把 "
                    + "&fshop.source &e改成 &flocal&e，否则仍会读 WBD 那份。"));
        }
        sender.sendMessage(color("&7改动商品用 &f/tdm shopedit&7，"
                + "它会直接编辑这份副本。"));
    }

    /**
     * 把 config.yml 里的 {@code shop.source} 从 wbd 改成 local。
     *
     * <p>用逐行文本替换而不是 YamlConfiguration 读写：那份 config.yml 里有大量
     * 说明注释，用 YAML 读写会全部丢光 —— 改一行配置却赔上整个文件的注释，
     * 这笔账怎么算都不划算。
     */
    private boolean switchShopSourceToLocal() {
        File config = new File(plugin.getDataFolder(), "config.yml");
        if (!config.exists()) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(config.toPath(), StandardCharsets.UTF_8);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "^(\\s*source:\\s*)[\"']?wbd[\"']?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).matches()) {
                    lines.set(i, lines.get(i).replaceAll("(?i)wbd", "local"));
                    Files.write(config.toPath(), lines, StandardCharsets.UTF_8);
                    return true;
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("改写 config.yml 的 shop.source 失败：" + e);
        }
        return false;
    }

    /** 定位 WarZBombDefuse 的商店配置。 */
    private File resolveWbdShopFile() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File serverRoot = pluginsDir == null ? null : pluginsDir.getParentFile();
        String configured = plugin.getSettings().shopWbdFile;

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

    // ------------------------------------------------------------------
    // 管理员操作
    // ------------------------------------------------------------------

    private void handleStart(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        TdmMatch match = resolveMatch(sender, args, 1);
        if (match == null) return;
        String error = match.forceStart();
        sender.sendMessage(color(error != null ? error : "&a已强制开始比赛。"));
    }

    /**
     * 启用 / 停用一个场地。
     *
     * <p>启用时还会顺带处理 WarZBombDefuse 侧的同名场地 —— 见
     * {@link #disableInWbd(String)}，那里解释了为什么这件事非做不可。
     */
    private void handleToggle(CommandSender sender, String[] args, boolean enable) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(color("&c用法：/tdm " + (enable ? "enable" : "disable") + " <场地id>"));
            return;
        }

        String arenaId = args[1];
        TdmArena arena = plugin.getMatchManager().getArena(arenaId);
        if (arena == null) {
            sender.sendMessage(color("&c找不到场地：" + arenaId + "。用 /tdm list 查看。"));
            return;
        }

        // 启用前先挡住明显配错的场地：缺出生点的场子一旦开起来，
        // 玩家会在复活的那一刻被传送到 null 位置，比直接拒绝更糟
        if (enable && !arena.isValid()) {
            sender.sendMessage(color("&c场地 " + arenaId + " 配置不完整，无法启用："));
            for (String problem : arena.validationProblems()) {
                sender.sendMessage(color("&7- " + problem));
            }
            return;
        }

        File file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!ArenaLoader.setEnabled(file, arenaId, enable)) {
            sender.sendMessage(color("&c写入 arenas.yml 失败：文件不存在或没有这个场地。"));
            return;
        }

        // WBD 侧的清理放在重载之前：先让它把场地让出来，再让死斗接手，
        // 中间那几毫秒不会有玩家正好进场。
        // 用 resolveWbdArenaId() 而不是 arenaId：导入时可能改过 id，
        // 死斗这边叫 tdm_dust2，WBD 那边还叫 dust2
        String wbdResult = enable ? disableInWbd(arenaId, arena.resolveWbdArenaId()) : null;

        plugin.reloadAll();

        sender.sendMessage(color("&a场地 &e" + arenaId + " &a已" + (enable ? "启用" : "停用") + "。"));
        if (wbdResult != null) {
            sender.sendMessage(color(wbdResult));
        }
        if (enable) {
            sender.sendMessage(color("&7玩家可以执行 &f/tdm join " + arenaId + " &7加入了。"));
        }
    }

    /**
     * 在 WarZBombDefuse 侧禁用同名场地。
     *
     * <p>两个模式抢同一个场地会互相打架：WBD 会在这个场子里推回合、发炸弹、
     * 判全灭、把玩家强制变旁观，而死斗在搞无限复活。症状是「玩家莫名变成旁观者」
     * 「刚复活又被传走」，而且很难联想到是两个插件在抢场地。
     *
     * <p><b>为什么走 WBD 的命令而不是直接改它的 arenas.yml</b>：
     * <ol>
     *   <li>直接改文件会抹掉 WBD 配置里大量的说明注释 —— 服主打开发现注释全没了，
     *       这本身就是个事故</li>
     *   <li>更要命的是改了也没用：WBD 的启用状态加载在内存里（ArenaManager 持有
     *       对应的 ArenaSession），磁盘上改成 disabled 但没人通知它，
     *       那个场子照样推回合。冲突一点没解决，只是更难发现了</li>
     * </ol>
     * 调它的命令则由它自己同步持久化与运行时状态，格式也由它自己维护。
     *
     * @param arenaId   死斗这边的场地 id，仅用于提示文案
     * @param wbdArenaId 该场地在 WBD 侧的 id（导入时改过名的话两者不同）
     * @return 一句可直接发给用户的处理结果
     */
    private String disableInWbd(String arenaId, String wbdArenaId) {
        if (!plugin.getSettings().autoDisableInWbd) {
            return "&7配置已关闭自动禁用，请自行确认 WBD 侧的同名场地是关的。";
        }

        var wbd = org.bukkit.Bukkit.getPluginManager().getPlugin("WarZBombDefuse");
        if (wbd == null || !wbd.isEnabled()) {
            return "&7未检测到 WarZBombDefuse，无需处理 WBD 侧。";
        }

        File wbdFile = resolveWbdArenasFile();
        if (wbdFile == null || !wbdFile.exists()) {
            return "&7读不到 WBD 的 arenas.yml，请自行确认该场地在 WBD 侧是关闭的。";
        }

        String path = "arenas." + wbdArenaId;
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(wbdFile);

        if (!yaml.isConfigurationSection(path)) {
            return "&7WBD 里没有场地 " + wbdArenaId + "，无需处理。";
        }
        if (!yaml.getBoolean(path + ".enabled", false)) {
            return "&7WBD 侧的场地 " + wbdArenaId + " 本来就是关闭的。";
        }

        // 走它的命令，让 WBD 自己禁用并保存
        org.bukkit.Bukkit.dispatchCommand(
                org.bukkit.Bukkit.getConsoleSender(), "wbd disable " + wbdArenaId);

        // 复核结果：命令可能因为权限、版本差异或场地正被占用而没有生效。
        // 只报告「已执行」而不验证，服主会以为没事了，实际的冲突还在
        org.bukkit.configuration.file.YamlConfiguration after =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(wbdFile);
        if (!after.getBoolean(path + ".enabled", false)) {
            return "&aWBD 侧的场地 " + wbdArenaId + " 已自动禁用。";
        }
        return "&c尝试在 WBD 侧自动禁用失败，请手动执行 &f/wbd disable " + wbdArenaId
                + "&c，否则两个模式会抢这个场地。";
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        TdmMatch match = resolveMatch(sender, args, 1);
        if (match == null) return;
        String error = match.forceStop();
        sender.sendMessage(color(error != null ? error : "&a已强制结束比赛。"));
    }

    private void handleReload(CommandSender sender) {
        if (!requireAdmin(sender)) return;
        try {
            plugin.reloadAll();
            sender.sendMessage(color("&a配置与竞技场已重载。"));
        } catch (Exception e) {
            sender.sendMessage(color("&c重载失败：" + e.getMessage()));
            plugin.getLogger().warning("重载时出错：" + e);
        }
    }

    /**
     * 从 WarZBombDefuse 的 arenas.yml 导入一个场地的坐标。
     *
     * <p>导入出来的场地一律是未启用状态，服主核对坐标后再手动打开。
     * 这里做的是「省去手抄坐标」，不是「替你确认坐标对不对」——
     * 一个配错的出生点会让人直接掉进虚空，这种事必须有人看一眼。
     */
    private void handleImport(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(color("&c用法：/tdm import <wbd场地id> [新的场地id]"));
            return;
        }

        String sourceId = args[1];
        String targetId = args.length >= 3 ? args[2] : sourceId;

        File source = resolveWbdArenasFile();
        if (source == null || !source.exists()) {
            sender.sendMessage(color("&c找不到 WarZBombDefuse 的竞技场文件。"));
            sender.sendMessage(color("&7请确认路径，或修改 config.yml 里的 "
                    + "&fintegration.wbd-arenas-file&7。"));
            return;
        }

        List<String> problems = new ArrayList<>();
        TdmArena imported = ArenaLoader.readArena(sourceId, targetId, source, problems);
        if (imported == null) {
            sender.sendMessage(color("&c导入失败："));
            for (String problem : problems) {
                sender.sendMessage(color("&7- " + problem));
            }
            sender.sendMessage(color("&7可用 &f/wbd list &7查看 WarZBombDefuse 里的场地 id。"));
            return;
        }

        // 显示名：WBD 那边配了 name 就用它，并加后缀区分两个插件。
        //
        // 没配 name 时 readArena 会把名字回退成 id，这时候**不能**加后缀 ——
        // 「tdm_ship（死斗）」比 id 更难听，而且会让运行时「从 WBD 补名字」
        // 失效（那一步只在名字还等于 id 时才动手）
        String wbdName = imported.getName();
        boolean namedByWbd = wbdName != null && !wbdName.isBlank() && !wbdName.equals(sourceId);
        if (namedByWbd) {
            String suffix = "（死斗）";
            String baseName = wbdName.endsWith(suffix)
                    ? wbdName.substring(0, wbdName.length() - suffix.length())
                    : wbdName;
            imported.setName(baseName + suffix);
        }
        // 记下来源 id：导入时可能改了名，之后要拿它去 WBD 侧禁用真正该禁的那个场地，
        // 也要靠它去 WBD 的 arenas.yml 里读场地显示名
        imported.setSourceArena(sourceId);

        File target = new File(plugin.getDataFolder(), "arenas.yml");
        if (!ArenaLoader.saveArena(target, imported)) {
            sender.sendMessage(color("&c写入 arenas.yml 失败，请检查文件权限。"));
            return;
        }

        plugin.reloadAll();

        sender.sendMessage(color("&a已导入场地 &e" + sourceId + " &a为 &e" + targetId + "&a。"));
        if (namedByWbd) {
            sender.sendMessage(color("&7显示名 &f" + imported.getName()
                    + "&7（取自 WarZBombDefuse）"));
        } else {
            sender.sendMessage(color("&eWarZBombDefuse 那边没有给这个场地配 name&7，"
                    + "暂时只能用 id 显示。"));
            sender.sendMessage(color("&7去它的 arenas.yml 补上 name，"
                    + "之后 &f/tdm reload&7 就会自动读过来，不必重新导入。"));
        }
        sender.sendMessage(color("&7T 出生点 &f" + imported.getSpawns(TdmTeam.T).size()
                + " &7个，CT 出生点 &f" + imported.getSpawns(TdmTeam.CT).size() + " &7个。"));
        sender.sendMessage(color("&e该场地当前为未启用状态&7：核对坐标后执行 "
                + "&f/tdm enable " + targetId + "&7 打开。"));

        askAdoptWbdShop(sender);
    }

    /**
     * 删除一个死斗场地。
     *
     * <p>只动死斗自己的 {@code arenas.yml}，不会去碰 WarZBombDefuse 的同名场地 ——
     * 「删掉一个模式里的场地」和「把那个场地从服务器上抹掉」是两件事，
     * 把它们绑在一起迟早会有人误删掉量了很久的坐标。
     */
    private void handleDelete(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(color("&c用法：/tdm delete <场地id>"));
            sender.sendMessage(color("&7用 &f/tdm list &7查看现有场地 id。"));
            return;
        }

        String arenaId = args[1];
        TdmArena arena = plugin.getMatchManager().getArena(arenaId);
        if (arena == null) {
            sender.sendMessage(color("&c没有这个场地：&f" + arenaId));
            return;
        }

        // 先把场上的人送走。顺序不能反：删完配置 MatchManager 会重建所有比赛，
        // 那时候场上的人会悬在一场已经不在列表里的比赛上 —— 走也走不掉、
        // 复活也没人管，只能等他自己断开
        int players = 0;
        TdmMatch match = plugin.getMatchManager().getMatch(arenaId);
        if (match != null && match.size() > 0) {
            players = match.size();
            match.clearAll();
        }

        File file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!ArenaLoader.deleteArena(file, arenaId)) {
            sender.sendMessage(color("&c删除失败 —— 可能是 arenas.yml 没有写权限。"));
            return;
        }

        plugin.reloadAll();

        sender.sendMessage(color("&a已删除场地 &e" + arenaId
                + " &7(" + arena.getName() + ")&a。"));
        if (players > 0) {
            sender.sendMessage(color("&7场上 " + players + " 名玩家已送回大厅。"));
        }
        sender.sendMessage(color("&7WarZBombDefuse 里的同名场地未受影响。"));
    }

    /**
     * 把手持物品导出成能直接贴进 {@code config.yml} 的物品数据。
     *
     * <p>为什么必须有这条命令：那段 {@code bukkit-object:} 串有几百个字符，
     * 没有人能手写出来，也没有办法从一个物品名字反推出它来。
     * 服主把枪配好（改配件、换皮肤、调弹药）之后，只有在游戏里拿着它导出，
     * 才拿得到准确的那一串 —— 这正是 {@code loadout} 能原样发枪的前提。
     */
    private void handleExportItem(CommandSender sender) {
        if (!requireAdmin(sender)) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c这条命令要由玩家执行 —— 它读的是你手里拿的东西。"));
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(color("&c你主手没拿任何东西。"));
            return;
        }

        String encoded = ItemCodec.encode(hand);
        if (encoded == null) {
            player.sendMessage(color("&c这件物品序列化失败，导出不了。"));
            return;
        }

        String material = hand.getType().name();
        StringBuilder text = new StringBuilder();
        text.append("# 由 /tdm exportitem 生成。导出时的物品：").append(material).append('\n');
        text.append("# 用法：把下面 items 里的字符串整段复制到 config.yml 的 loadout 段。\n");
        text.append("# 想指定落点就在前面加「槽位@」—— 1@bukkit-object:... 会放进第 2 格。\n");
        text.append("# 不写落点则自动找第一个空位。\n");
        text.append("# 这段数据里带着物品的全部 NBT（枪械 id、弹药、开火模式、配件），\n");
        text.append("# 发出去的就是同一件物品 —— 这是按材质重建做不到的。\n");
        text.append("items:\n");
        text.append("  - \"").append(encoded).append("\"\n");

        File out = new File(plugin.getDataFolder(), "exported-item.yml");
        try {
            Files.writeString(out.toPath(), text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            player.sendMessage(color("&c写入文件失败：" + e.getMessage()));
            plugin.getLogger().warning("导出物品数据失败：" + e);
            return;
        }

        player.sendMessage(color("&a已导出 &f" + material + "&a（&f" + encoded.length() + "&a 字符）。"));
        player.sendMessage(color("&7文件：&fplugins/WBDTeamDeathmatch/exported-item.yml"));
        player.sendMessage(color("&7把里面的字符串粘到 &fconfig.yml &7的 &floadout.common&7、"
                + "&floadout.t&7 或 &floadout.ct&7 下即可，改完 &f/tdm reload&7。"));
        plugin.getLogger().info("已导出手持物品数据（" + material + "）到 " + out.getPath());
    }

    /**
     * 查询段位。
     *
     * <p>不带参数查自己，带玩家名查别人。按名字查的是**内存档案**而不是
     * 在线玩家列表，所以离线的人也能查 —— 这很重要，玩家问「我刚那把掉了
     * 多少分」时通常已经退出游戏了。
     */
    private void handleRank(CommandSender sender, String[] args) {
        RankedService ranked = plugin.getRanked();
        Messages messages = plugin.getMessages();

        if (!ranked.available()) {
            sender.sendMessage(messages.get("commands.rank-disabled"));
            return;
        }

        RankedProfile profile;
        String queried;
        boolean self;

        if (args.length >= 2) {
            queried = args[1];
            profile = ranked.findByName(queried);
            self = false;
        } else if (sender instanceof Player player) {
            queried = player.getName();
            profile = ranked.profileOf(player.getUniqueId());
            self = true;
        } else {
            sender.sendMessage(color("&c控制台请用 &f/tdm rank <玩家名>&c。"));
            return;
        }

        if (profile == null) {
            sender.sendMessage(messages.get("commands.rank-none",
                    "player", queried == null ? "?" : queried));
            return;
        }

        sender.sendMessage(messages.get(self ? "commands.rank-self" : "commands.rank-other",
                "player", profile.name(),
                "tier", ranked.tiers().display(profile.rp()),
                "rp", profile.rp(),
                "played", profile.played(),
                "wins", profile.wins(),
                "losses", profile.losses(),
                "draws", profile.draws()));
    }

    /**
     * 打开某位玩家的比赛战报界面。
     *
     * <p>查询放在异步线程：这是按 uuid 查数据库再组装几十场记录，
     * 在主线程做就是在玩家敲下回车的那一瞬间卡他一下 —— 而这类卡顿
     * 恰恰最难归因，玩家只会觉得「这个命令有时候会顿」。
     * 拿到结果后再回主线程开界面（Bukkit 的 GUI 只能在主线程碰）。
     */
    private void handleMatchReport(CommandSender sender, String[] args) {
        RankedService ranked = plugin.getRanked();
        Messages messages = plugin.getMessages();

        if (!ranked.available()) {
            sender.sendMessage(messages.get("report-menu.unavailable"));
            return;
        }
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(color("&c战报界面只能由玩家打开。"));
            return;
        }

        UUID targetUuid;
        String targetName;

        if (args.length >= 2) {
            String input = args[1];
            Player online = Bukkit.getPlayerExact(input);
            if (online != null) {
                targetUuid = online.getUniqueId();
                targetName = online.getName();
            } else {
                // 离线玩家：从内存档案里按名字找 uuid。
                // 这正是 uuid 必须存在比赛记录里的原因 —— 只靠名字的话，
                // 这里根本没法把「查询的名字」对应到「历史记录里的那个人」
                RankedProfile profile = ranked.findByName(input);
                if (profile == null) {
                    sender.sendMessage(messages.get("commands.rank-none", "player", input));
                    return;
                }
                targetUuid = profile.uuid();
                targetName = profile.name();
            }
        } else {
            targetUuid = viewer.getUniqueId();
            targetName = viewer.getName();
        }

        final UUID uuid = targetUuid;
        final String name = targetName;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MatchRecord> records = ranked.recentMatches(uuid, ReportMenu.SIZE);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (records.isEmpty()) {
                    viewer.sendMessage(messages.get("report-menu.empty", "player", name));
                    return;
                }
                viewer.openInventory(new ReportMenu(plugin, uuid, name, records).getInventory());
            });
        });
    }

    /** 赛季信息与手动软重置。 */
    private void handleSeason(CommandSender sender, String[] args) {
        RankedService ranked = plugin.getRanked();
        Messages messages = plugin.getMessages();

        if (!ranked.available()) {
            sender.sendMessage(messages.get("commands.rank-disabled"));
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            if (!requireAdmin(sender)) {
                return;
            }
            ranked.resetSeason(true);
            sender.sendMessage(messages.get("commands.season-reset"));
            return;
        }

        if (!ranked.settings().seasonEnabled) {
            sender.sendMessage(messages.get("commands.season-disabled"));
            return;
        }

        long daysLeft = ranked.seasonDaysLeft();
        sender.sendMessage(messages.get("commands.season-info",
                "season", ranked.settings().currentSeason,
                "days", daysLeft < 0 ? "?" : daysLeft));
    }

    /**
     * 导入场地之后顺带问一句：要不要把 WBD 的商店配置也接过来。
     *
     * <p>场地和商店本是两件独立的事，但都属于「开一个死斗场」的准备工作，
     * 放在同一条流程里提醒最不容易漏。做成可点击按钮而不是让服主记命令 ——
     * 这种一次性的配置动作，没人会记得住命令名。
     *
     * <p>只在当前还读着 WBD 那份时才问：已经是本地副本了就不必再复制一遍。
     */
    private void askAdoptWbdShop(CommandSender sender) {
        if (!plugin.getSettings().shopEnabled) {
            return;
        }
        if (!"wbd".equalsIgnoreCase(plugin.getSettings().shopSource)) {
            return;
        }
        File wbdShop = resolveWbdShopFile();
        if (wbdShop == null || !wbdShop.exists()) {
            return;
        }

        sender.sendMessage(color(""));
        sender.sendMessage(color("&6是否也采用 WarZBombDefuse 的商店配置？"));
        if (sender instanceof Player player) {
            TextComponent button = new TextComponent(color("&a&l[ 点击采用 ]"));
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tdm shop adopt"));
            button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(color("&7把 WBD 的商店复制一份到本插件目录\n"
                            + "&7之后两边独立维护，互不影响")).create()));
            player.spigot().sendMessage(button);
        } else {
            sender.sendMessage(color("&a/tdm shop adopt"));
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析命令里指向的比赛；不明确时给出可操作的提示并返回 null。 */
    private TdmMatch resolveMatch(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            TdmMatch match = plugin.getMatchManager().getMatch(args[index]);
            if (match == null) {
                sender.sendMessage(color("&c找不到场地：" + args[index]));
            }
            return match;
        }
        if (sender instanceof Player player) {
            TdmMatch own = plugin.getMatchManager().getMatch(player);
            if (own != null) {
                return own;
            }
        }
        List<TdmMatch> playable = plugin.getMatchManager().getPlayableMatches();
        if (playable.size() == 1) {
            return playable.get(0);
        }
        sender.sendMessage(color(playable.isEmpty()
                ? "&c没有可用的死斗场地。"
                : "&c有多个场地，请指定一个：/tdm status <场地id>"));
        return null;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("tdm.admin")) {
            sender.sendMessage(color("&c你没有权限执行此命令。"));
            return false;
        }
        return true;
    }

    /**
     * 定位 WarZBombDefuse 的竞技场文件。
     *
     * <p>配置里写的是「相对服务端根目录」的路径（例如 {@code plugins/WarZBombDefuse/arenas.yml}），
     * 但服主也可能只写了相对 plugins 的 {@code WarZBombDefuse/arenas.yml}。
     * 两种写法都试着解析一次，省掉一轮「为什么找不到文件」的来回。
     */
    private File resolveWbdArenasFile() {
        String configured = plugin.getSettings().wbdArenasFile;
        if (configured == null || configured.isBlank()) {
            return null;
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        File serverRoot = pluginsDir == null ? null : pluginsDir.getParentFile();

        if (serverRoot != null) {
            File direct = new File(serverRoot, configured);
            if (direct.exists()) {
                return direct;
            }
        }
        if (pluginsDir != null) {
            File relative = new File(pluginsDir, configured.replaceFirst("^[/\\\\]?plugins[/\\\\]", ""));
            if (relative.exists()) {
                return relative;
            }
        }
        return serverRoot == null ? null : new File(serverRoot, configured);
    }

    // ------------------------------------------------------------------
    // Tab 补全
    // ------------------------------------------------------------------

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(input))
                    .filter(s -> !isAdminOnly(s) || sender.hasPermission("tdm.admin"))
                    .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            if (sub.equals("join") || sub.equals("status") || sub.equals("start")
                    || sub.equals("stop") || sub.equals("enable") || sub.equals("disable")) {
                return filter(plugin.getMatchManager().getMatches().stream()
                        .map(m -> m.getArena().getId()), args[1]);
            }
        }
        if (args.length == 3 && sub.equals("join")) {
            return filter(java.util.stream.Stream.of("t", "ct"), args[2]);
        }
        if (args.length == 2 && sub.equals("shop")) {
            return filter(java.util.stream.Stream.of("adopt"), args[1]);
        }
        if (args.length == 2 && sub.equals("shopedit")) {
            return filter(java.util.stream.Stream.of("T", "CT", "1", "2", "3"), args[1]);
        }
        return new ArrayList<>();
    }

    private boolean isAdminOnly(String sub) {
        return sub.equals("start") || sub.equals("stop") || sub.equals("reload")
                || sub.equals("import") || sub.equals("delete")
                || sub.equals("enable") || sub.equals("disable")
                || sub.equals("shopedit") || sub.equals("exportitem");
    }

    private List<String> filter(java.util.stream.Stream<String> source, String prefix) {
        String lower = prefix.toLowerCase();
        return source.filter(s -> s.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}

package alphahu.tdm.hud;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.game.MatchState;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.game.TdmPlayer;
import alphahu.tdm.util.Text;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 死斗的表现层：侧边栏战报、击杀提示、状态条与比赛倒计时条。
 *
 * <h2>侧边栏为什么是「每人一块」而不是全服共用</h2>
 * 这里推翻过一次设计，值得记下来。
 *
 * <p>最初的方案是把侧边栏注册在**主计分板**上，所有 TDM 玩家共用一块 ——
 * 这样最省事，而且能保住 JourneyMap 队伍桥接（原版队伍数据跟着玩家当前所用的
 * 计分板下发，共用主计分板就等于什么都没动）。
 *
 * <p>但共用一块板子意味着**只能放全员一致的信息**：比分、剩余时间可以，
 * 个人击杀/死亡就不行 —— 那一栏对所有人都是同一行字。
 *
 * <p>所以现在改成给每人建一块独立计分板，再把主计分板上的原版队伍**镜像**过来
 * （见 {@link #mirrorTeams}）。镜像这一步是关键：没有它，玩家一切到自己的板子，
 * WBDAddon 同步的 {@code wbd_*} 队伍就传不过去了，JourneyMap 会静默失效。
 * 多花一点同步开销，换来个人战报与队伍可见性并存。
 *
 * <h2>三种显示通道的分工</h2>
 * <ul>
 *   <li><b>侧边栏</b> —— 常驻的个人战报与比分</li>
 *   <li><b>ActionBar</b> —— 只在击杀时闪一条「[武器] 受害者」，两秒后淡出。
 *       平时**不占用**它，免得常驻信息盖掉别的插件的提示</li>
 *   <li><b>BossBar</b> —— 状态条（等待复活 / 复活无敌）与比赛倒计时条</li>
 * </ul>
 *
 * @author AlphaHu
 */
public final class MatchHud {

    /** 侧边栏的 objective 名。 */
    private static final String OBJECTIVE_NAME = "tdm_sidebar";

    /** 侧边栏行锚点的 team 名前缀。 */
    private static final String LINE_TEAM_PREFIX = "tdmline";

    /** 原版侧边栏最多显示 15 行。 */
    private static final int MAX_LINES = 15;

    /** 用来做行锚点的不可见字符表。 */
    private static final String ANCHORS = "0123456789abcdef";

    /** 击杀提示淡出前多久开始转暗色（毫秒）。 */
    private static final long KILL_FADE_MILLIS = 400L;

    /**
     * 队伍镜像的最小间隔（毫秒）。
     *
     * <p>刻意用时间而不是调用次数来控制：{@code render} 是**按玩家**逐个调用的，
     * 用计数器的话，场上人越多镜像得越勤 —— 15 个人就是 15 倍频率，
     * 凭空多出一堆没必要的队伍包。按时间算才与人数无关。
     */
    private static final long MIRROR_INTERVAL_MILLIS = 2000L;

    private final WBDTeamDeathmatch plugin;

    /** 状态条：等待复活倒计时 / 复活后的无敌时间。 */
    private final Map<UUID, BossBar> statusBars = new HashMap<>();

    /** 比赛倒计时条。 */
    private final Map<UUID, BossBar> matchBars = new HashMap<>();

    /** 每个玩家自己的侧边栏计分板。 */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    /** 待淡出的击杀提示。 */
    private final Map<UUID, KillMessage> killMessages = new HashMap<>();

    /** 每块板子上次镜像队伍的时刻（毫秒）。 */
    private final Map<UUID, Long> lastMirrorAt = new HashMap<>();

    /** 一条待显示的击杀提示。 */
    private static final class KillMessage {
        private final String text;
        private final long expiresAt;
        private boolean dimmed;

        KillMessage(String text, long expiresAt) {
            this.text = text;
            this.expiresAt = expiresAt;
        }
    }

    public MatchHud(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    /**
     * 刷新某个玩家的全部 HUD。
     *
     * @param now 当前墙钟毫秒，由调用方传入以免在每个玩家身上重复取时间
     */
    public void render(TdmMatch match, TdmPlayer record, Player player, long now) {
        renderKillMessage(player, now);
        renderStatusBar(match, record, player, now);
        renderMatchBar(match, player, now);
        renderScoreboard(match, record, player, now);
    }

    // ------------------------------------------------------------------
    // 击杀提示（ActionBar）
    // ------------------------------------------------------------------

    /**
     * 显示一条击杀提示。
     *
     * <p>只在击杀发生时调用一次，之后由 {@link #renderKillMessage} 负责淡出与清除。
     * ActionBar 平时是**空着的** —— 常驻信息交给侧边栏和 BossBar，
     * 把这一行留给「刚刚发生了什么」，提示才不会被淹没。
     */
    public void showKillMessage(Player player, String text) {
        if (player == null || !player.isOnline()) {
            return;
        }
        long duration = Math.max(250L, plugin.getSettings().killMessageDurationMs);
        killMessages.put(player.getUniqueId(),
                new KillMessage(text, System.currentTimeMillis() + duration));
        sendActionBar(player, Text.color(text));
    }

    /**
     * 维护击杀提示的生命周期：到点前转为暗色近似淡出，到点后清空。
     *
     * <p>原版 ActionBar 没有真正的透明度动画（无法淡出），所以用「最后一段转为
     * 暗灰色」来近似。它看起来仍然像是渐渐退去，而且不会像直接消失那样突兀。
     */
    private void renderKillMessage(Player player, long now) {
        KillMessage message = killMessages.get(player.getUniqueId());
        if (message == null) {
            return;
        }

        if (now >= message.expiresAt) {
            killMessages.remove(player.getUniqueId());
            // 发一条空内容把这一行收回，否则最后那句话会一直挂在屏幕上
            sendActionBar(player, "");
            return;
        }

        // 只在跨过「开始淡出」那一刻重发一次，不必每 tick 刷
        if (!message.dimmed && message.expiresAt - now <= KILL_FADE_MILLIS) {
            message.dimmed = true;
            sendActionBar(player, "\u00a78" + Text.strip(message.text));
        }
    }

    /**
     * 发送 ActionBar。
     *
     * <p>走 spigot 的旧接口而非 Paper 的 {@code sendActionBar}：
     * 本插件要在 Arclight 上跑，那里没有 Paper 的扩展方法。
     */
    public void sendActionBar(Player player, String text) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(text));
        } catch (Throwable ignored) {
            // 少数魔改端连 spigot() 都没有，HUD 发不出去不该影响比赛本身
        }
    }

    // ------------------------------------------------------------------
    // 状态条（等待复活 / 复活无敌）
    // ------------------------------------------------------------------

    /**
     * 状态条：阵亡等待期间显示「还有几秒复活」，复活后的无敌保护期间显示
     * 「还有几秒无敌」。
     *
     * <p>把无敌时间摆出来是必要的：保护期内玩家免疫伤害，如果界面上毫无表示，
     * 他会以为自己打不动人、或者以为对手开了挂 —— 那几秒钟的信息空缺很容易
     * 被误读成故障。
     */
    private void renderStatusBar(TdmMatch match, TdmPlayer record, Player player, long now) {
        // 结算阶段不显示：比赛已经分出胜负，此刻写「3 秒后复活」只会让人以为马上重开
        if (match.getState() != MatchState.LIVE) {
            hideRespawnBar(player);
            return;
        }

        if (record.isAlive()) {
            if (record.isProtected(now)) {
                double remain = Math.max(0.0, (record.protectionUntil() - now) / 1000.0);
                double total = Math.max(0.001, plugin.getSettings().protectionSeconds);
                showStatusBar(player, parseProtectionColor(), parseProtectionStyle(),
                        plugin.getSettings().protectionBarTitle,
                        Map.of("seconds", String.format(java.util.Locale.US, "%.1f", remain),
                                "player", player.getName()),
                        Math.max(0.0, Math.min(1.0, remain / total)));
            } else {
                hideRespawnBar(player);
            }
            return;
        }

        int secondsLeft = record.respawnSecondsLeft(now);
        if (secondsLeft <= 0) {
            hideRespawnBar(player);
            return;
        }
        int total = Math.max(1, plugin.getSettings().respawnDelaySeconds);
        showStatusBar(player, parseRespawnColor(), parseRespawnStyle(),
                plugin.getSettings().respawnBarTitle,
                Map.of("seconds", String.valueOf(secondsLeft),
                        "player", player.getName()),
                Math.max(0.0, Math.min(1.0, (double) secondsLeft / total)));
    }

    private void showStatusBar(Player player, BarColor color, BarStyle style,
                               String titleTemplate, Map<String, String> placeholders,
                               double progress) {
        BossBar bar = statusBars.get(player.getUniqueId());
        if (bar == null) {
            // 颜色/样式只在创建时定：同一条 BossBar 在「等待复活」与「复活无敌」
            // 之间是复用的，两边配色不同，所以每次显示前比对一次
            bar = Bukkit.createBossBar("", color, style);
            bar.addPlayer(player);
            statusBars.put(player.getUniqueId(), bar);
        } else {
            if (bar.getColor() != color) {
                bar.setColor(color);
            }
            if (bar.getStyle() != style) {
                bar.setStyle(style);
            }
        }

        bar.setTitle(Text.color(Text.fill(titleTemplate, placeholders)));
        bar.setProgress(progress);

        // 玩家可能因为传送掉出可见列表，这里补一次
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
    }

    /** 移除并隐藏某个玩家的状态条。 */
    public void hideRespawnBar(Player player) {
        BossBar bar = statusBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    // ------------------------------------------------------------------
    // 比赛倒计时条
    // ------------------------------------------------------------------

    /**
     * 比赛剩余时间 / 开局倒计时。
     *
     * <p>死斗一局可能十几分钟，光靠侧边栏上的一行数字很容易打忘了时间；
     * BossBar 带进度条、位置又显眼，一眼就能看出还剩多少。
     */
    private void renderMatchBar(TdmMatch match, Player player, long now) {
        if (!plugin.getSettings().matchBarEnabled) {
            hideMatchBar(player);
            return;
        }

        MatchState state = match.getState();
        if (state != MatchState.LIVE && state != MatchState.COUNTDOWN) {
            hideMatchBar(player);
            return;
        }

        BossBar bar = matchBars.computeIfAbsent(player.getUniqueId(), uuid -> {
            BossBar created = Bukkit.createBossBar("", parseMatchBarColor(), parseMatchBarStyle());
            created.addPlayer(player);
            return created;
        });

        bar.setProgress(match.matchBarProgress(now));
        bar.setTitle(Text.color(Text.fill(plugin.getSettings().matchBarFormat,
                match.hudPlaceholders(player, now))));

        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
    }

    /** 移除并隐藏某个玩家的比赛倒计时条。 */
    public void hideMatchBar(Player player) {
        BossBar bar = matchBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    // ------------------------------------------------------------------
    // 个人侧边栏
    // ------------------------------------------------------------------

    private void renderScoreboard(TdmMatch match, TdmPlayer record, Player player, long now) {
        if (!plugin.getSettings().scoreboardEnabled) {
            removeBoard(player);
            return;
        }

        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            board = createBoard(player);
            boards.put(player.getUniqueId(), board);
            player.setScoreboard(board);
        }

        // 低频镜像原版队伍：JourneyMap 靠它判断队友关系，
        // 而队伍数据不会跟着我们换板子自动过来
        Long lastMirror = lastMirrorAt.get(player.getUniqueId());
        if (lastMirror == null || now - lastMirror >= MIRROR_INTERVAL_MILLIS) {
            lastMirrorAt.put(player.getUniqueId(), now);
            mirrorTeams(board);
        }

        updateLines(board, buildLines(match, player, now));
    }

    private List<String> buildLines(TdmMatch match, Player player, long now) {
        Map<String, String> placeholders = match.hudPlaceholders(player, now);
        List<String> out = new ArrayList<>();
        for (String template : plugin.getSettings().scoreboardLines) {
            out.add(Text.color(Text.fill(template, placeholders)));
            if (out.size() >= MAX_LINES) {
                break;
            }
        }
        if (out.isEmpty()) {
            // 配置里一行都没写时的兜底。刻意只放个人数据：
            // 比分与剩余时间已经在 BossBar 上了，这块板子再写一遍就是重复占位
            out.add(Text.color("&7金钱 &e$" + placeholders.get("money")));
            out.add(Text.color("&7击杀 &f" + placeholders.get("kills")));
            out.add(Text.color("&7死亡 &f" + placeholders.get("deaths")));
        }
        return out;
    }

    /**
     * 建一块属于该玩家的计分板。
     *
     * <p>行文本用「team 前缀承载 + 不可见字符做行锚点」的经典做法：计分板 API
     * 不允许直接改动条目的显示文本，只能改分数，所以文字要挂在条目所属队伍的 prefix 上。
     */
    private Scoreboard createBoard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE_NAME, "dummy",
                Text.color(plugin.getSettings().scoreboardTitle));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        return board;
    }

    private void updateLines(Scoreboard board, List<String> lines) {
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            Team team = board.getTeam(LINE_TEAM_PREFIX + i);
            String anchor = anchorFor(i);
            if (team == null) {
                team = board.registerNewTeam(LINE_TEAM_PREFIX + i);
                team.addEntry(anchor);
                objective.getScore(anchor).setScore(lines.size() - i);
            }
            String text = lines.get(i);
            if (!text.equals(team.getPrefix())) {
                team.setPrefix(text);
            }
        }

        // 行数变少时把多余的行清掉，否则会留下上一场的残行
        for (int i = lines.size(); i < MAX_LINES; i++) {
            Team team = board.getTeam(LINE_TEAM_PREFIX + i);
            if (team != null) {
                team.unregister();
            }
        }
    }

    /**
     * 把主计分板上的原版队伍镜像到玩家自己的板子上。
     *
     * <p>这是「换成独立计分板」之后必须补的一步。原版队伍数据是跟着玩家**当前所用
     * 的**计分板下发的：玩家一切到我们给的板子，服务端就不再向他同步主计分板上的
     * {@code wbd_*} 队伍，JourneyMap 于是读不到队友关系 —— 队友可见、敌军隐藏全部
     * 失效，而且**不会报任何错**。把队伍复制一份过来就恢复了。
     *
     * <p>镜像的范围不只是名字、前后缀与成员，还包括**队伍选项**。这一条尤其容易漏：
     * {@code NAME_TAG_VISIBILITY} 正是「隐藏敌人名牌」的全部实现，选项跟着计分板
     * 下发，不镜像就等于把那项功能悄悄关掉了（见下面 {@code Team.Option} 那段）。
     *
     * <p>只处理「主计分板上有、或曾经有」的队伍；侧边栏自己的行锚点队伍要跳过，
     * 那些只是用来挂文字的。
     */
    private void mirrorTeams(Scoreboard board) {
        var manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard main = manager.getMainScoreboard();

        for (Team source : main.getTeams()) {
            Team target = board.getTeam(source.getName());
            if (target == null) {
                target = board.registerNewTeam(source.getName());
            }
            if (!source.getPrefix().equals(target.getPrefix())) {
                target.setPrefix(source.getPrefix());
            }
            if (!source.getSuffix().equals(target.getSuffix())) {
                target.setSuffix(source.getSuffix());
            }
            // 队伍选项必须一起搬过来 —— 漏了它，敌人名牌就会重新露出来。
            //
            // 这里踩过一次，症状很隐蔽：名字、成员都镜像了，唯独没镜像
            // NAME_TAG_VISIBILITY。它在主计分板上是 FOR_OWN_TEAM（敌人看不见），
            // 但**队伍选项是跟着玩家当前所用的计分板下发的**：玩家一切到我们给的
            // 板子，客户端收到的队伍就退回默认的 ALWAYS，敌人头顶的名字照样穿墙可见，
            // 而且不报任何错 —— 表现成「隐藏敌人名牌这个功能根本没生效」。
            //
            // 这里把所有选项通用地拷一遍，而不是只补 NAME_TAG_VISIBILITY：
            // 原版以后新增选项时，漏掉同样不会报错，只是行为悄悄不对。
            for (Team.Option option : Team.Option.values()) {
                Team.OptionStatus wanted = source.getOption(option);
                if (target.getOption(option) != wanted) {
                    target.setOption(option, wanted);
                }
            }
            // canSeeFriendlyInvisibles 不是 Option 枚举的一项，得单独同步
            if (target.canSeeFriendlyInvisibles() != source.canSeeFriendlyInvisibles()) {
                target.setCanSeeFriendlyInvisibles(source.canSeeFriendlyInvisibles());
            }
            for (String entry : source.getEntries()) {
                if (!target.hasEntry(entry)) {
                    target.addEntry(entry);
                }
            }
            // 主板上已经移除的成员，这里也要跟着移除
            for (String entry : new ArrayList<>(target.getEntries())) {
                if (!source.hasEntry(entry)) {
                    target.removeEntry(entry);
                }
            }
        }

        // 主板上已经不存在的队伍（例如某个模式退场后清理掉了）也一并撤掉
        for (Team target : new ArrayList<>(board.getTeams())) {
            if (target.getName().startsWith(LINE_TEAM_PREFIX)) {
                continue;
            }
            if (main.getTeam(target.getName()) == null) {
                target.unregister();
            }
        }
    }

    private void removeBoard(Player player) {
        boards.remove(player.getUniqueId());
        lastMirrorAt.remove(player.getUniqueId());
        if (player.isOnline() && Bukkit.getScoreboardManager() != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** 生成第 i 行的不可见锚点，保证互不相同。 */
    private String anchorFor(int index) {
        int high = index / ANCHORS.length();
        int low = index % ANCHORS.length();
        return "\u00a7" + ANCHORS.charAt(high % ANCHORS.length())
                + "\u00a7" + ANCHORS.charAt(low);
    }

    // ------------------------------------------------------------------
    // 计分板归属
    // ------------------------------------------------------------------

    /**
     * 让玩家用上自己那块战报板。
     *
     * <p>进比赛（含复活）时调用。他要是在别处（例如上一场退出后）被换到了别的板子，
     * 这里换回来。
     */
    public void attachToSharedBoard(Player player) {
        if (!player.isOnline() || Bukkit.getScoreboardManager() == null) {
            return;
        }
        Scoreboard board = boards.get(player.getUniqueId());
        if (board != null && player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
    }

    /**
     * 让玩家彻底脱离死斗的计分板。
     *
     * <p>退出比赛时调用：把板子交还给主计分板，这样他不会再看到那一栏战报。
     * 之前共用主计分板的方案做不到这件事 —— objective 一旦注册上去，
     * 所有用主板的人都看得见，而原版没有「对某人隐藏某个 objective」的能力。
     * 换成独立板之后这个问题自然消失。
     */
    public void detachFromSharedBoard(Player player) {
        if (!player.isOnline() || Bukkit.getScoreboardManager() == null) {
            return;
        }
        if (boards.containsKey(player.getUniqueId())) {
            removeBoard(player);
        }
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /** 收回玩家的个人 HUD；比赛内重开时用，不动计分板归属。 */
    public void clearPlayerHud(Player player) {
        hideRespawnBar(player);
        hideMatchBar(player);
        killMessages.remove(player.getUniqueId());
    }

    /** 插件卸载时清理全部状态。 */
    public void shutdown() {
        for (BossBar bar : statusBars.values()) {
            bar.removeAll();
        }
        statusBars.clear();
        for (BossBar bar : matchBars.values()) {
            bar.removeAll();
        }
        matchBars.clear();
        killMessages.clear();
        lastMirrorAt.clear();

        var manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            for (UUID uuid : new ArrayList<>(boards.keySet())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.setScoreboard(manager.getMainScoreboard());
                }
            }
        }
        boards.clear();
    }

    // ------------------------------------------------------------------
    // 配置解析
    // ------------------------------------------------------------------

    private BarColor parseColor(String raw, BarColor fallback) {
        try {
            return BarColor.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return fallback;
        }
    }

    private BarStyle parseStyle(String raw, BarStyle fallback) {
        try {
            return BarStyle.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return fallback;
        }
    }

    private BarColor parseRespawnColor() {
        return parseColor(plugin.getSettings().respawnBarColor, BarColor.RED);
    }

    private BarStyle parseRespawnStyle() {
        return parseStyle(plugin.getSettings().respawnBarStyle, BarStyle.SOLID);
    }

    private BarColor parseProtectionColor() {
        return parseColor(plugin.getSettings().protectionBarColor, BarColor.BLUE);
    }

    private BarStyle parseProtectionStyle() {
        return parseStyle(plugin.getSettings().protectionBarStyle, BarStyle.SOLID);
    }

    private BarColor parseMatchBarColor() {
        return parseColor(plugin.getSettings().matchBarColor, BarColor.YELLOW);
    }

    private BarStyle parseMatchBarStyle() {
        return parseStyle(plugin.getSettings().matchBarStyle, BarStyle.SOLID);
    }
}

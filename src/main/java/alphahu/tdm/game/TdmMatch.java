package alphahu.tdm.game;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.arena.ArenaRules;
import alphahu.tdm.arena.TdmArena;
import alphahu.tdm.config.TdmSettings;
import alphahu.tdm.message.Messages;
import alphahu.tdm.ranked.RankOutcome;
import alphahu.tdm.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 一个场地上正在进行的团队死斗比赛。
 *
 * <p>与 WarZBombDefuse 的爆破模式最本质的区别是**没有回合**：
 * 这里是「一场比赛 = 一段连续的对抗」，期间玩家反复阵亡与复活，
 * 直到某一方达到击杀目标、或者时间耗尽为止。
 * 因此这个类里没有 {@code startRound/endRound}，只有
 * {@code WAITING → COUNTDOWN → LIVE → ENDED} 这一条线性推进。
 *
 * <p>时间一律用墙钟毫秒（{@link System#currentTimeMillis()}）度量，
 * 不用 tick 计数：{@code Bukkit.getCurrentTick()} 是 Paper 专有 API，
 * 在 Arclight 这类服务端上不存在，而复活延时、保护时长、比赛限时
 * 本来就是「秒」的概念，用真实时间表达反而更贴合语义。
 *
 * @author AlphaHu
 */
public final class TdmMatch {

    /** 广播前缀。 */
    private static final String PREFIX = "&8[&6死斗&8] &r";

    private final WBDTeamDeathmatch plugin;
    private final TdmArena arena;
    private final ArenaRules rules;
    private final TdmSettings settings;
    private final Random random = new Random();

    /** 参赛玩家，保持插入顺序让播报与列表面板稳定。 */
    private final Map<UUID, TdmPlayer> players = new LinkedHashMap<>();

    /** 加入比赛前的状态，离场时还原。 */
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();

    private MatchState state = MatchState.WAITING;
    private int tScore;
    private int ctScore;

    /** COUNTDOWN / ENDED 阶段的结束时刻（epoch ms）。 */
    private long stateEndsAt;

    /** LIVE 阶段的时间上限时刻（epoch ms）。0 表示不限时。 */
    private long matchEndsAt;

    /**
     * LIVE 真正开始的时刻（epoch ms）。0 表示本场还没开打。
     *
     * <p>战报要用它算时长。刻意取「进入战斗」而不是「玩家加入」的时刻 ——
     * 等待阶段可能持续很久，把它算进比赛时长会让战报上的数字毫无意义。
     */
    private long startedAt;

    /** 本场胜者。null 表示平局或尚未结束。 */
    private TdmTeam winner;

    /** 结算原因，用于播报。 */
    private String endReason;

    /** HUD 刷新降频计数。 */
    private int hudCounter;

    /**
     * 是否正在清场。
     *
     * <p>清场（关服、强制停赛、场地重建）会走 {@link #leave}，
     * 但那不是玩家的选择 —— 让他们替服务器维护承担「中途离开」的处罚
     * 说不通，而且一次关服就会罚掉全场人。
     */
    private boolean clearing;

    public TdmMatch(WBDTeamDeathmatch plugin, TdmArena arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.settings = plugin.getSettings();
        this.rules = arena.resolveRules(settings);
    }

    // ------------------------------------------------------------------
    // 基本信息
    // ------------------------------------------------------------------

    public TdmArena getArena() {
        return arena;
    }

    public ArenaRules getRules() {
        return rules;
    }

    public MatchState getState() {
        return state;
    }

    public int getScore(TdmTeam team) {
        return team == TdmTeam.T ? tScore : ctScore;
    }

    public TdmTeam getWinner() {
        return winner;
    }

    /** LIVE 开始时刻（epoch ms）；还没开打返回 0。战报用它算时长。 */
    public long getStartedAt() {
        return startedAt;
    }

    /** 结束原因（例如「达到目标击杀数」）。战报里记这一笔，便于回溯判负依据。 */
    public String getEndReason() {
        return endReason;
    }

    /** 本场地是否结算段位。 */
    public boolean isRanked() {
        return arena.ranked();
    }

    /**
     * 玩家**此刻**能否购买（购买区限制）。
     *
     * <p>收成一个方法是有意的：打开商店有两个入口（右键商店物品、{@code /tdm shop}），
     * 检查写在两处就一定会有一处漏掉 —— 而漏掉的那个入口会让整条限制形同虚设。
     *
     * <p>另外，「打开菜单时检查」本身也不够：菜单开着的时候玩家可以走出去。
     * 所以调用方在**点击购买**时还要再问一次。
     */
    public boolean canPurchase(Player player) {
        TdmPlayer record = getPlayer(player);
        if (record == null) {
            return false;
        }
        return arena.allowsPurchase(record.team(), player.getLocation(),
                settings.shopBuyZoneRequired);
    }

    /** 参赛人数（含正在等待复活的）。 */
    public int size() {
        return players.size();
    }

    /** 当前存活人数。 */
    public int aliveCount() {
        int count = 0;
        for (TdmPlayer record : players.values()) {
            if (record.isAlive()) count++;
        }
        return count;
    }

    /** 某队人数。 */
    public int teamSize(TdmTeam team) {
        int count = 0;
        for (TdmPlayer record : players.values()) {
            if (record.team() == team) count++;
        }
        return count;
    }

    public Collection<TdmPlayer> getPlayers() {
        return players.values();
    }

    public TdmPlayer getPlayer(Player player) {
        return player == null ? null : players.get(player.getUniqueId());
    }

    public TdmPlayer getPlayer(UUID uuid) {
        return players.get(uuid);
    }

    public boolean contains(Player player) {
        return player != null && players.containsKey(player.getUniqueId());
    }

    /** 该玩家是否为在比赛中、可正常参战的一员。 */
    public boolean isActive(Player player) {
        TdmPlayer record = getPlayer(player);
        return record != null && record.isAlive();
    }

    public boolean isSameTeam(Player first, Player second) {
        TdmPlayer a = getPlayer(first);
        TdmPlayer b = getPlayer(second);
        return a != null && b != null && a.team() == b.team();
    }

    // ------------------------------------------------------------------
    // 加入与离开
    // ------------------------------------------------------------------

    /**
     * 让玩家加入本场比赛。
     *
     * <p>比赛中途加入会直接投入战斗（死斗本来就是随时可进的），
     * 而不是像爆破模式那样排队等下一回合——回合制那套在无回合的模式里没有意义。
     *
     * @param requested 玩家指定的阵营，null 表示自动分配
     * @return 加入失败时返回一句可直接发给玩家的原因
     */
    public String join(Player player, TdmTeam requested) {
        if (players.containsKey(player.getUniqueId())) {
            return "&c你已在这场比赛中。";
        }
        if (!arena.isEnabled()) {
            return "&c场地 " + arena.getName() + " 未启用。";
        }
        if (players.size() >= rules.maxPlayers()) {
            return "&c这场比赛已满（" + rules.maxPlayers() + " 人）。";
        }

        TdmTeam team = chooseTeam(requested);
        TdmPlayer record = new TdmPlayer(player.getUniqueId(), player.getName(), team);
        players.put(player.getUniqueId(), record);
        snapshots.put(player.getUniqueId(), new PlayerSnapshot(player));

        // 中途加入标记必须在**加入这一刻**定下来。段位结算读它，
        // 而结算发生在比赛结束之后 —— 那时再判断「比赛是否在进行」永远是 false
        record.setJoinedLate(state == MatchState.LIVE);

        if (state == MatchState.LIVE) {
            // 中途加入：立刻进入战斗，并按复活规则给上保护，免得一进场就被秒
            spawnPlayer(player, record, true);
        } else {
            // 未开赛：先去大厅等着，开局时统一投入
            record.setAlive(true);
            // 等待期间就发装备与启动资金：他这会儿就能开商店挑装备，
            // 而不是干等开场那十秒
            record.setMoney(clampMoney(settings.economyStartMoney));
            plugin.getLoadout().apply(player, team);
            plugin.getHud().attachToSharedBoard(player);
            Location waitingSpot = arena.getLobby() != null
                    ? arena.getLobby()
                    : arena.pickSpawn(team, settings.spawnMode, random);
            if (waitingSpot != null) {
                player.teleport(waitingSpot);
            }
            player.setGameMode(GameMode.SURVIVAL);
        }

        // 全服公告放在最后：上面任何一步抛异常都不会广播一条「某某加入了」
        // 但人其实没进来的假消息
        announceJoin(player, team, record.joinedLate());

        return null;
    }

    /**
     * 向**全服**播报有人加入了场地。
     *
     * <p>用全服广播而不是场内 broadcast，是因为这条公告的作用是「告诉别人
     * 这边打得起来了」—— 只发给已经在场里的人毫无意义。WBD 的 join-global
     * 也是这么做的。
     *
     * <p>段位取自 RankedService 的内存档案（异步加载，可能还没到），
     * 拿不到就显示「未定级」。这只是显示上的短暂延迟，不值得为它阻塞主线程。
     */
    private void announceJoin(Player player, TdmTeam team, boolean lateJoin) {
        Messages messages = plugin.getMessages();
        if (!messages.has("broadcast.join-global")) {
            return;
        }
        String tier = plugin.getRanked().tierTextOf(player.getUniqueId());
        String ranked = arena.ranked()
                ? messages.raw("broadcast.ranked-yes", "排位")
                : messages.raw("broadcast.ranked-no", "非排位");

        Bukkit.getServer().broadcastMessage(messages.get("broadcast.join-global",
                "player", player.getName(),
                "arena", arena.getName(),
                "arena_id", arena.getId(),
                "tier", tier,
                "ranked", ranked,
                "team", team.id(),
                "players", players.size(),
                "max", rules.maxPlayers(),
                "late", lateJoin ? "1" : "0"));
    }

    /**
     * 让玩家离开本场比赛，并还原其进入前的状态。
     *
     * @param restoreState 是否还原位置与背包。玩家主动退出时为 true；
     *                     比赛清场时同样是 true；服务器关服时可为 false，省掉无谓的传送
     */
    public void leave(Player player, boolean restoreState) {
        TdmPlayer record = players.remove(player.getUniqueId());
        if (record == null) {
            return;
        }

        plugin.getHud().clearPlayerHud(player);
        // 侧边栏挂在主计分板上，原版没法只对一个人隐藏它 ——
        // 所以退出的人必须换一块独立计分板，否则那一栏会一直留在屏幕上
        plugin.getHud().detachFromSharedBoard(player);

        PlayerSnapshot snapshot = snapshots.remove(player.getUniqueId());
        if (restoreState && snapshot != null) {
            // 先脱离旁观，否则还原后的位置会被旁观状态的重力/碰撞规则打乱
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            snapshot.restore(player);
        }

        broadcast("&e" + player.getName() + " &7离开了比赛。");

        // 与加入对称的全服公告，让人知道这边空出了位置
        Messages messages = plugin.getMessages();
        if (messages.has("broadcast.leave-global")) {
            Bukkit.getServer().broadcastMessage(messages.get("broadcast.leave-global",
                    "player", player.getName(),
                    "arena", arena.getName(),
                    "arena_id", arena.getId(),
                    "team", record.team().id(),
                    "players", players.size(),
                    "max", rules.maxPlayers()));
        }

        // 中途离开：本局不结算排位分，但入场费双倍扣。
        //
        // 只在 LIVE 阶段算「中途」：等待阶段还没开打、ENDED 阶段结果已定，
        // 那时候走人没有任何可逃避的东西，罚他们没有道理。
        //
        // 掉线也走这条路径（quit → leave），这是刻意的：否则拔网线
        // 就成了逃避败场的标准操作
        if (state == MatchState.LIVE && !clearing) {
            plugin.getRanked().penalizeLeave(record);
        }

        onRosterChanged();
    }

    /** 玩家离线：当作离开处理，但要在他真正断开前把状态还原回去。 */
    public void quit(Player player) {
        TdmPlayer record = players.get(player.getUniqueId());
        if (record == null) {
            return;
        }
        broadcast("&e" + player.getName() + " &7掉线了。");
        leave(player, true);
    }

    /**
     * 名单变化后的收尾：人走光了就把场子收拾干净，回到等待状态。
     */
    private void onRosterChanged() {
        if (players.isEmpty()) {
            resetToWaiting();
            return;
        }
        // 只剩一方有人时，比赛没有继续的意义，直接按人数占优判胜
        // （这与爆破模式的「全灭即负」不同，是死斗特有的兜底）
        if (state == MatchState.LIVE) {
            if (teamSize(TdmTeam.T) == 0 && teamSize(TdmTeam.CT) > 0) {
                endMatch(TdmTeam.CT, "对手已全部离开");
            } else if (teamSize(TdmTeam.CT) == 0 && teamSize(TdmTeam.T) > 0) {
                endMatch(TdmTeam.T, "对手已全部离开");
            }
        }
    }

    /**
     * 选择阵营。
     *
     * <p>玩家明确指定就尊重指定（强制平衡不在本插件职责内，
     * 需要严格平衡的服务器应该用权限约束或赛前协调）；
     * 未指定时按人数较少的一方分配，避免「一边 5 人一边 1 人」的碾压局。
     * 两端人数相等时随机，免得总是同一批人当 T。
     */
    private TdmTeam chooseTeam(TdmTeam requested) {
        if (requested != null) {
            return requested;
        }
        if (!settings.autoBalance) {
            return random.nextBoolean() ? TdmTeam.T : TdmTeam.CT;
        }
        int t = teamSize(TdmTeam.T);
        int ct = teamSize(TdmTeam.CT);
        if (t < ct) return TdmTeam.T;
        if (ct < t) return TdmTeam.CT;
        return random.nextBoolean() ? TdmTeam.T : TdmTeam.CT;
    }

    // ------------------------------------------------------------------
    // 状态推进
    // ------------------------------------------------------------------

    /** 主循环。由插件的主任务每 tick 调用一次。 */
    public void tick() {
        long now = System.currentTimeMillis();

        switch (state) {
            case WAITING -> {
                if (players.size() >= rules.minPlayers()) {
                    startCountdown(now);
                }
            }
            case COUNTDOWN -> {
                if (players.size() < rules.minPlayers()) {
                    broadcast("&c人数不足，已取消开局。");
                    resetToWaiting();
                } else if (now >= stateEndsAt) {
                    startLive(now);
                }
            }
            case LIVE -> {
                enforceBoundary();
                if (rules.hasTimeLimit() && now >= matchEndsAt) {
                    finishByTime();
                }
            }
            case ENDED -> {
                if (now >= stateEndsAt) {
                    if (rules.autoRestart()) {
                        restart();
                    } else {
                        clearAll();
                    }
                }
            }
        }

        // 复活排期在除结算以外的任何阶段都要跑：
        // 等待与倒计时阶段玩家同样可能摔死或掉进虚空，如果只在 LIVE 处理，
        // 他们会一直卡在旁观状态，等比赛开始也不会被放回来。
        // 结算阶段刻意不复活——那时比赛已经分出胜负，把人放回战场没有意义。
        if (state != MatchState.ENDED) {
            processRespawns(now);
        }

        // 每个玩家的 HUD 每 2 tick 刷一次：人眼分辨不出 1 tick 与 2 tick 的差别，
        // 但包量与字符串拼接的开销差一倍。
        //
        // 侧边栏、状态条、击杀提示都在这一次里一起处理 —— 侧边栏现在是每人一块
        // 独立计分板，不再有「多人共用一块、需要单独降频」的问题。
        if (++hudCounter % 2 == 0) {
            refreshPlayerHud(now);
        }
    }

    private void startCountdown(long now) {
        state = MatchState.COUNTDOWN;
        stateEndsAt = now + rules.countdownSeconds() * 1000L;
        tScore = 0;
        ctScore = 0;
        winner = null;
        endReason = null;
        // 上一场的开始时间必须清掉：否则万一这一场中途被 clearAll 收场，
        // 战报会拿着上一场的时间戳算出一个荒谬的时长
        startedAt = 0L;
        for (TdmPlayer record : players.values()) {
            record.setAlive(true);
        }
        broadcast("&a比赛即将开始，目标击杀数 &e" + rules.targetKills()
                + " &a，时间上限 &e" + Text.clock(rules.timeLimitSeconds()) + "&a。");
    }

    private void startLive(long now) {
        state = MatchState.LIVE;
        startedAt = now;
        matchEndsAt = rules.hasTimeLimit() ? now + rules.timeLimitSeconds() * 1000L : 0L;
        for (TdmPlayer record : players.values()) {
            // 每场比赛重新发一次启动资金：经济是「一场之内」的积累，
            // 跨场带着上一场的钱会让优势一路滚下去
            record.setMoney(clampMoney(settings.economyStartMoney));
            Player player = Bukkit.getPlayer(record.uuid());
            if (player == null) continue;
            spawnPlayer(player, record, true);
        }
        broadcast("&a比赛开始！");
        playAll("ENTITY_PLAYER_LEVELUP", 1.2f);
    }

    /** 结束比赛并进入结算展示。 */
    public void endMatch(TdmTeam team, String reason) {
        if (state == MatchState.ENDED) {
            return;
        }
        state = MatchState.ENDED;
        winner = team;
        endReason = reason;
        stateEndsAt = System.currentTimeMillis() + rules.endDelaySeconds() * 1000L;

        broadcast("&6=========================");
        if (team == null) {
            broadcast("&e平局！&7（" + reason + "）");
        } else {
            broadcast(team.color() + team.displayName() + " &a获胜！&7（" + reason + "）");
        }
        broadcast("&cT &f" + tScore + " &7- &bCT &f" + ctScore);

        TdmPlayer mvp = findMvp();
        if (mvp != null) {
            broadcast("&7本场最佳：&e" + mvp.name()
                    + " &7（击杀 &f" + mvp.kills() + " &7/ 死亡 &f" + mvp.deaths()
                    + " &7/ 最高连杀 &f" + mvp.bestKillStreak() + "&7）");
        }
        broadcast("&6=========================");

        spawnFireworks();
        if (team != null) {
            playAll("ENTITY_ENDER_DRAGON_GROWL", 0.8f);
        }

        // 段位结算放在这里，而不是等 restart()。
        // restart() 会遍历玩家执行 resetForNewMatch()，把击杀、死亡、连杀
        // 全部清零 —— 晚一步就会变成「所有人按 0 击杀结算」，而且不报错，
        // 只表现为分数永远偏低。这是整套排位里最容易踩且最难查的一个顺序问题。
        Map<UUID, RankOutcome> outcomes = plugin.getRanked().settle(this, team);

        // 战报同理，而且顺序不能反：它要带上本局的段位变化，
        // 所以得等 settle 给出结果之后再落盘
        plugin.getReports().save(this, outcomes);

        // 同一份数据再写一份进数据库，供 /tdm matchrp 按玩家查询。
        // 文件给人看，表给命令查 —— 命令若去扫 reports/ 目录解析 YAML，
        // 几百份文件就是几百次磁盘 IO，玩家随手一敲就卡住了
        plugin.getRanked().recordMatch(this, outcomes);
    }

    /** 时间耗尽：击杀多者胜，相同则平局。 */
    private void finishByTime() {
        if (tScore > ctScore) {
            endMatch(TdmTeam.T, "时间到，击杀数领先");
        } else if (ctScore > tScore) {
            endMatch(TdmTeam.CT, "时间到，击杀数领先");
        } else {
            endMatch(null, "时间到，双方击杀数相同");
        }
    }

    /**
     * 自动重开：清比分、把所有人送回大厅，回到等待状态。
     *
     * <p>下一 tick 的 WAITING 分支会重新走倒计时，所以这里只需要负责「复位」。
     */
    private void restart() {
        broadcast("&7新的一场比赛即将开始。");
        tScore = 0;
        ctScore = 0;
        winner = null;
        endReason = null;
        // 必须清掉上一场的时间上限：否则等待期间侧边栏还在拿它算剩余时间，
        // 显示出一个早已过期的数字
        matchEndsAt = 0L;

        Location lobby = arena.getLobby();
        int returned = 0;
        for (TdmPlayer record : players.values()) {
            record.resetForNewMatch();
            Player player = Bukkit.getPlayer(record.uuid());
            if (player == null) {
                continue;
            }
            plugin.getHud().clearPlayerHud(player);
            player.setGameMode(GameMode.SURVIVAL);

            Location destination = lobby != null
                    ? lobby
                    // 没配大厅就退回己方出生点。总比把人扔在上一场的交火位置强 ——
                    // 那会让他们在「等待」阶段继续挨打，也让「回合结束」看起来像没结束
                    : arena.pickSpawn(record.team(), settings.spawnMode, random);
            if (destination != null) {
                player.teleport(destination);
                returned++;
            }
        }

        state = MatchState.WAITING;

        if (lobby == null) {
            plugin.getLogger().warning("场地 " + arena.getId()
                    + " 没有配置 lobby，比赛结束后已把玩家送回各自出生点。"
                    + "建议补上大厅坐标，否则「回到大厅等待」这一步不会发生。");
        }
        plugin.getLogger().info("场地 " + arena.getId() + " 的比赛已结束，"
                + returned + " 名玩家回到等待位置。");

        // 刻意不清空伤害账本：那个 DamageTracker 是 WBDAddon 与爆破模式共享的实例，
        // 死斗开新一场时去 reset() 会把正在进行的爆破回合统计一并抹掉。
        // 账本按「受害者→攻击者」聚合，跨场累积对击杀归属的判断几乎没有影响。
    }

    /** 回到等待状态但不保留任何进度（人走光时用）。 */
    private void resetToWaiting() {
        state = MatchState.WAITING;
        tScore = 0;
        ctScore = 0;
        winner = null;
        endReason = null;
    }

    /** 清场：把所有人送出比赛并还原状态。 */
    public void clearAll() {
        clearing = true;
        try {
            for (Player player : onlinePlayers()) {
                leave(player, true);
            }
        } finally {
            // 放在 finally 里：leave 中途抛异常的话，标志留成 true
            // 会让这个场地之后所有真实的「中途离开」都不再受罚
            clearing = false;
        }
        players.clear();
        snapshots.clear();
        resetToWaiting();
    }

    /** 管理员强制开始。 */
    public String forceStart() {
        if (state == MatchState.LIVE) {
            return "&c比赛已经在进行中。";
        }
        if (players.isEmpty()) {
            return "&c场上没有人。";
        }
        startCountdown(System.currentTimeMillis());
        stateEndsAt = System.currentTimeMillis() + 3000L;
        broadcast("&e管理员强制开始了比赛。");
        return null;
    }

    /** 管理员强制结束（按当前比分判定）。 */
    public String forceStop() {
        if (state != MatchState.LIVE) {
            return "&c当前没有进行中的比赛。";
        }
        finishByTime();
        return null;
    }

    // ------------------------------------------------------------------
    // 阵亡与复活
    // ------------------------------------------------------------------

    /**
     * 结算一次击杀的赏金，返回实际到手金额。
     *
     * <p>连杀越高给得越多，但额外部分有上限 —— 不封顶的话，一个滚起来的
     * 高手会迅速变成「钱多到随便买、还越打越强」，把局面推向一边倒。
     * 有限的正反馈才留得住翻盘空间。
     */
    /**
     * 发放一次击杀奖励（含连杀加成）。
     *
     * @return 实际到账的数目：已到金钱上限时可能小于名义奖励
     */
    private int awardKillMoney(TdmPlayer killer) {
        if (!settings.economyEnabled) {
            return 0;
        }

        int reward = settings.economyKillReward;
        int streak = killer.killStreak();
        if (streak >= 2 && settings.economyKillstreakBonus > 0) {
            // (连杀数 - 1) × 每级加成，总额封顶在 killstreak-bonus-max
            reward += Math.min(settings.economyKillstreakBonusMax,
                    (streak - 1) * settings.economyKillstreakBonus);
        }
        return addMoney(killer, reward);
    }

    /**
     * 给一名玩家加钱，返回**实际**到账的数目。
     *
     * <p>返回值刻意不等于传入的 amount：到上限时「+300」就是假的，
     * 播报必须如实显示，否则玩家会以为自己被吞了钱。
     *
     * <p>这是全插件唯一往玩家账上加钱的入口 —— 击杀奖励、局内补助、
     * 将来的段位奖励都该走它。让 {@code Math.min} 散落在各个业务方法里，
     * 迟早会有一处忘了夹，而金钱夹错了没有报错、只有玩家能感觉到。
     */
    private int addMoney(TdmPlayer record, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int before = record.money();
        record.setMoney(clampMoney(before + amount));
        return record.money() - before;
    }

    /** 把金额收进 [0, economy.max-money]。 */
    private int clampMoney(int amount) {
        return Math.max(0, Math.min(settings.economyMaxMoney, amount));
    }

    /**
     * 给玩家一笔钱（带上限）。
     *
     * @return 实际到账的数目；未开经济、玩家不在比赛中、或已到上限时为 0
     */
    public int giveMoney(Player player, int amount) {
        if (amount <= 0 || !settings.economyEnabled) {
            return 0;
        }
        TdmPlayer record = getPlayer(player);
        return record == null ? 0 : addMoney(record, amount);
    }

    /** 取玩家手上的金钱；不在比赛中返回 0。 */
    public int moneyOf(Player player) {
        TdmPlayer record = getPlayer(player);
        return record == null ? 0 : record.money();
    }

    /**
     * 处理一次阵亡。
     *
     * <p>这个方法承载了「无限复活」的全部逻辑：记录击杀与死亡、把阵亡者
     * 转入等待复活状态，并在 {@code delay-seconds} 之后由 {@link #processRespawns(long)}
     * 把他放回战场。
     *
     * @param victim 阵亡者
     * @param killer 击杀者，可能为 null（摔死、掉虚空、自杀）
     */
    public void handleDeath(Player victim, Player killer) {
        TdmPlayer victimRecord = getPlayer(victim);
        if (victimRecord == null || !victimRecord.isAlive()) {
            return;
        }

        long now = System.currentTimeMillis();
        victimRecord.setAlive(false);
        victimRecord.setDeathLocation(victim.getLocation().clone());
        int endedStreak = victimRecord.addDeath();

        // 判定击杀归属：死亡事件给的 killer 在 TacZ 环境下经常为空或指错人，
        // 因此只在它确实是敌人时才采信，否则回退到伤害账本
        TdmPlayer killerRecord = resolveKiller(victim, killer);

        if (killerRecord != null) {
            killerRecord.addKill();
            if (killerRecord.team() == TdmTeam.T) {
                tScore++;
            } else {
                ctScore++;
            }
            // 结算这次击杀的赏金，然后才播报 —— 播报里要带上到手多少钱
            int reward = awardKillMoney(killerRecord);
            announceKill(killerRecord, victimRecord, killer, endedStreak, reward);
        } else {
            announceDeath(victimRecord);
        }

        // 复活排期。延时为 0 时把时刻设为当下，下一次 tick 就会立刻放人；
        // 但真正动手前还要等「切旁观」走完，见 TdmPlayer#spectatorPending
        victimRecord.setRespawnAt(now + settings.respawnDelaySeconds * 1000L);
        victimRecord.setSpectatorPending(true);

        if (state == MatchState.LIVE) {
            checkKillTarget();
        }
    }

    /**
     * 确定这次击杀该记在谁头上。
     *
     * <p>优先采信原版给出的 killer，但要求它确实是本场比赛中、属于敌对阵营、
     * 且当时还活着的玩家。任一条件不满足就回退到伤害账本里对该受害者
     * 输出最高的敌人——这是 TacZ 环境下把击杀归属拉回正确的主要手段。
     */
    private TdmPlayer resolveKiller(Player victim, Player killer) {
        TdmPlayer victimRecord = getPlayer(victim);
        if (victimRecord == null) {
            return null;
        }

        if (killer != null) {
            TdmPlayer candidate = getPlayer(killer);
            if (candidate != null && candidate.team() != victimRecord.team()) {
                return candidate;
            }
        }

        var ledger = plugin.getDamageLedger();
        if (ledger == null) {
            return null;
        }
        // 伤害账本按累计伤害降序给出，取第一个属于敌方的记录
        for (Map.Entry<UUID, Double> entry : ledger.getDamageSources(victim.getUniqueId())) {
            TdmPlayer candidate = players.get(entry.getKey());
            if (candidate != null && candidate.team() != victimRecord.team()) {
                return candidate;
            }
        }
        return null;
    }

    /** 把到点的阵亡玩家放回战场。 */
    private void processRespawns(long now) {
        for (TdmPlayer record : new ArrayList<>(players.values())) {
            if (!record.isRespawnDue(now)) {
                continue;
            }
            Player player = Bukkit.getPlayer(record.uuid());
            if (player == null) {
                // 人不在线：把复活时刻推后，等他回来时立刻投入战斗，
                // 而不是让一个陈旧的到期时刻在重连瞬间反复触发
                record.setRespawnAt(now + 1000L);
                continue;
            }
            // 复活：保留背包，只复位状态
            spawnPlayer(player, record, false);
        }
    }

    /**
     * 把玩家放进战场：传送、发装备、给保护。
     *
     * <p>这条路同时被「开局投放 / 中途加入」和「阵亡复活」使用，两者的装备处理
     * 完全不同，所以用 {@code freshLoadout} 区分：
     * <ul>
     *   <li>{@code true} —— 清空背包并发放出生装备（进入比赛）</li>
     *   <li>{@code false} —— **保留背包**，只复位状态（阵亡复活）</li>
     * </ul>
     * 后者是死斗的关键体验：玩家在商店里挑好的装备不该因为一次阵亡就白买。
     *
     * <p>另外还要自己把游戏模式从 SPECTATOR 切回来 —— 漏掉这一步的表现是
     * 玩家复活后依然是个穿墙的旁观者，能看见一切却打不到人。
     */
    private void spawnPlayer(Player player, TdmPlayer record, boolean freshLoadout) {
        TdmTeam team = record.team();
        Location spawn = arena.pickSpawn(team, settings.spawnMode, random);
        if (spawn == null) {
            spawn = arena.getLobby();
        }

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        // 上一场退出时他被挪到了独立计分板上，这里挪回来 —— 共享侧边栏挂在主计分板上
        plugin.getHud().attachToSharedBoard(player);
        if (spawn != null) {
            player.teleport(spawn);
        }

        if (freshLoadout) {
            plugin.getLoadout().apply(player, team);
        } else {
            // 复活：背包原样保留，只复位血量与状态
            plugin.getLoadout().restoreAfterRespawn(player, team);
        }
        plugin.getHud().hideRespawnBar(player);

        record.setAlive(true);
        record.setRespawnAt(0L);
        record.setDeathLocation(null);
        record.setSpectatorPending(false);

        long protectionMillis = (long) (settings.protectionSeconds * 1000L);
        record.setProtectionUntil(protectionMillis > 0
                ? System.currentTimeMillis() + protectionMillis
                : 0L);
    }

    /**
     * 阵亡后进入旁观状态。
     *
     * <p>由监听器在死亡事件的下一 tick 调用：那时玩家的死亡界面已经关掉
     * （{@code spigot().respawn()} 已执行），切旁观才不会立刻被打回死亡屏。
     *
     * <p>同时清掉 {@code spectatorPending}，把「可以复活了」这个信号放出去。
     * 这一步必须在方法末尾执行：如果切成旁观的过程中抛了异常，
     * 标志位留在 true 上，玩家会一直停在旁观而不是被复活到错误的位置——
     * 前者是一次可见的卡死，后者是静默地把人丢到主城。
     */
    public void enterSpectator(Player player) {
        TdmPlayer record = getPlayer(player);
        if (record == null || record.isAlive()) {
            record.setSpectatorPending(false);
            return;
        }
        try {
            player.setGameMode(GameMode.SPECTATOR);
            Location point = record.deathLocation() != null ? record.deathLocation() : arena.getLobby();
            if (point != null) {
                player.teleport(point);
            }
        } finally {
            record.setSpectatorPending(false);
        }
    }

    // ------------------------------------------------------------------
    // 胜负判定
    // ------------------------------------------------------------------

    private void checkKillTarget() {
        if (!rules.hasKillTarget()) {
            return;
        }
        if (tScore >= rules.targetKills()) {
            endMatch(TdmTeam.T, "达到击杀目标");
        } else if (ctScore >= rules.targetKills()) {
            endMatch(TdmTeam.CT, "达到击杀目标");
        }
    }

    private TdmPlayer findMvp() {
        TdmPlayer best = null;
        for (TdmPlayer record : players.values()) {
            if (best == null
                    || record.kills() > best.kills()
                    || (record.kills() == best.kills() && record.deaths() < best.deaths())) {
                best = record;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 场地维护
    // ------------------------------------------------------------------

    /**
     * 把跑出边界的玩家拉回来。
     *
     * <p>只在 LIVE 阶段生效：等待与结算阶段玩家本来就该能自由活动，
     * 那时候把他们按在场地里只会让人烦躁。
     */
    private void enforceBoundary() {
        if (!arena.hasBoundary()) {
            return;
        }
        for (TdmPlayer record : players.values()) {
            Player player = Bukkit.getPlayer(record.uuid());
            if (player == null || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (!arena.contains(player.getLocation())) {
                player.teleport(arena.clamp(player.getLocation()));
            }
        }
    }

    private void refreshPlayerHud(long now) {
        for (TdmPlayer record : players.values()) {
            Player player = Bukkit.getPlayer(record.uuid());
            if (player != null) {
                plugin.getHud().render(this, record, player, now);
            }
        }
    }

    // ------------------------------------------------------------------
    // HUD 数据
    // ------------------------------------------------------------------

    /**
     * 顶部状态行的文本，供 WBDAddon 的 match-status 模块取用。
     *
     * <p>这里**恒定返回 null**：死斗不再让 ActionBar 常驻显示比分与时间。
     * 那一行现在专门留给击杀提示（「[武器] 受害者」，两秒后淡出）——
     * 常驻信息一旦占着它，击杀提示就会被不断覆盖，等于什么都没提示。
     * 比分和时间都在侧边栏与 BossBar 上，不差这一行。
     */
    public String hudLine(Player player) {
        return null;
    }

    /**
     * 生成**全员一致**的占位符表，供共享侧边栏使用。
     *
     * <p>个人化的数据（击杀、死亡、复活倒计时）不能放进来：
     * 侧边栏是所有人共用的一块板子，写进去只会变成一行对谁都不准的数字。
     */
    public Map<String, String> sharedPlaceholders() {
        return sharedPlaceholders(System.currentTimeMillis());
    }

    /** 同上，但复用调用方已经取好的时间，避免同一帧里出现两个时间点。 */
    public Map<String, String> sharedPlaceholders(long now) {
        Map<String, String> values = new HashMap<>();
        values.put("t_score", String.valueOf(tScore));
        values.put("ct_score", String.valueOf(ctScore));
        values.put("target", rules.hasKillTarget() ? String.valueOf(rules.targetKills()) : "∞");
        values.put("time", timeText(now));
        values.put("alive", String.valueOf(aliveCount()));
        values.put("size", String.valueOf(players.size()));
        values.put("arena", arena.getName());
        return values;
    }

    /** 生成 HUD 与记分板共用的占位符表（含个人数据）。 */
    public Map<String, String> hudPlaceholders(Player player, long now) {
        Map<String, String> values = new HashMap<>(sharedPlaceholders(now));

        TdmPlayer record = getPlayer(player);
        values.put("respawn", record == null ? "" : String.valueOf(record.respawnSecondsLeft(now)));
        values.put("kills", record == null ? "0" : String.valueOf(record.kills()));
        values.put("deaths", record == null ? "0" : String.valueOf(record.deaths()));
        values.put("kd", record == null ? "0.00" : String.format(java.util.Locale.US, "%.2f", record.killDeathRatio()));
        values.put("money", record == null ? "0" : String.valueOf(record.money()));
        // 段位取的是内存档案，不查库 —— 计分板每 20 tick 就会重算一次，
        // 在这里碰数据库等于把主线程交给磁盘。拿不到档案时显示「未定级」，
        // 那只是启动初期异步加载还没完成的短暂状态
        values.put("tier", plugin.getRanked().tierTextOf(player.getUniqueId()));
        return values;
    }

    /**
     * 比赛倒计时条的进度（0-1），供 BossBar 使用。
     *
     * <p>开局倒计时按「剩余 / 总时长」；比赛阶段同理。没设时间上限时恒为 1
     * —— 那种情况下没有「还剩多少」可言，让进度条保持满格比让它乱跳更诚实。
     */
    public double matchBarProgress(long now) {
        if (state == MatchState.COUNTDOWN) {
            int total = rules.countdownSeconds();
            if (total <= 0) {
                return 1.0;
            }
            double remain = Math.max(0, stateEndsAt - now) / 1000.0;
            return Math.max(0.0, Math.min(1.0, remain / total));
        }
        if (state == MatchState.LIVE) {
            if (!rules.hasTimeLimit()) {
                return 1.0;
            }
            double total = rules.timeLimitSeconds();
            double remain = Math.max(0, matchEndsAt - now) / 1000.0;
            return Math.max(0.0, Math.min(1.0, remain / total));
        }
        return 1.0;
    }

    /**
     * 显示用的时间文本。
     *
     * <p>只有 LIVE 才谈得上「剩余时间」。等待与结算阶段必须显式给出状态词 ——
     * 否则它会拿上一场残留的 {@code matchEndsAt} 接着算，屏幕上挂出一个
     * 早已过期的倒计时（或停在 00:00），看起来就像「比赛结束了还在计时」。
     */
    public String timeText(long now) {
        return switch (state) {
            case WAITING -> "等待中";
            case COUNTDOWN -> "开局 " + (int) Math.ceil(Math.max(0, stateEndsAt - now) / 1000.0);
            case ENDED -> "已结算";
            case LIVE -> {
                if (!rules.hasTimeLimit()) {
                    yield "--:--";
                }
                yield Text.clock((int) Math.ceil(Math.max(0, matchEndsAt - now) / 1000.0));
            }
        };
    }

    // ------------------------------------------------------------------
    // 播报与音效
    // ------------------------------------------------------------------

    private void announceKill(TdmPlayer killer, TdmPlayer victim, Player killerPlayer,
                              int endedStreak, int reward) {
        String weapon = plugin.resolveWeaponName(killerPlayer);
        Map<String, String> values = new HashMap<>();
        values.put("killer", killer.name());
        values.put("victim", victim.name());
        values.put("weapon", weapon);
        values.put("killer_team", killer.team().displayName());
        values.put("victim_team", victim.team().displayName());
        values.put("money", String.valueOf(reward));

        // 击杀者自己的屏幕上方闪一条「[武器] 受害者 +赏金」，两秒后淡出。
        // 只发给击杀者：全场广播的是下面那条聊天消息，这里是他自己的战果回执
        if (killerPlayer != null && killerPlayer.isOnline()) {
            plugin.getHud().showKillMessage(killerPlayer,
                    Text.fill(settings.killMessageFormat, values));
        }

        broadcast(Text.fill(settings.killMessage, values));

        // 终结连杀：被终结的一方连杀越高，这条播报越值得单独提一句
        if (endedStreak >= 3) {
            broadcast("&7" + victim.name() + " 的 &e" + endedStreak + " &7连杀被终结。");
        }

        int streak = killer.killStreak();
        if (settings.killstreakBroadcast > 0 && streak > 0 && streak % settings.killstreakBroadcast == 0) {
            broadcast("&6" + killer.name() + " &7已经连杀 &e" + streak + " &7人！");
        }
    }

    private void announceDeath(TdmPlayer victim) {
        Map<String, String> values = new HashMap<>();
        values.put("victim", victim.name());
        broadcast(Text.fill(settings.deathMessageNoKiller, values));
    }

    /** 给场内所有玩家发一条消息。 */
    public void broadcast(String message) {
        String line = Text.color(PREFIX + message);
        for (Player player : onlinePlayers()) {
            player.sendMessage(line);
        }
    }

    private void playAll(String sound, float pitch) {
        try {
            org.bukkit.Sound parsed = org.bukkit.Sound.valueOf(sound);
            for (Player player : onlinePlayers()) {
                player.playSound(player.getLocation(), parsed, 1.0f, pitch);
            }
        } catch (IllegalArgumentException ignored) {
            // 音效名在不同版本里会变，取不到就静默跳过，不该因为一个音效打断比赛结算
        }
    }

    private void spawnFireworks() {
        for (Location location : arena.getFireworks()) {
            try {
                location.getWorld().spawn(location, org.bukkit.entity.Firework.class);
            } catch (Throwable ignored) {
                // 世界被卸载等情况，放不出烟花不值得报错
            }
        }
    }

    private List<Player> onlinePlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                out.add(player);
            }
        }
        return out;
    }
}

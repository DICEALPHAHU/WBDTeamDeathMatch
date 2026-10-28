package alphahu.tdm.ranked;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.arena.TdmArena;
import alphahu.tdm.config.RankedSettings;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.game.TdmPlayer;
import alphahu.tdm.game.TdmTeam;
import alphahu.tdm.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 段位系统的主逻辑：装载、结算、保护、赛季。
 *
 * <p>计分沿用 Apex 的三段式，但针对「两队死斗」做了改写：
 * <pre>
 *   RP变化 = 名次分(胜负) + 比分差修正 + 击杀分 + 队内排名分 − 入场费
 * </pre>
 *
 * <p>为什么不能照搬 Apex 的「名次分」：Apex 一局有 20 队，名次是 1~20 的连续量，
 * 所以「第 3 名」和「第 17 名」能拉开差距。死斗只有两队，名次是二元的，
 * 直接套用会退化成「赢 +120、输 −60」，个人表现完全不影响分数。
 * 所以额外加了比分差修正（区分碾压与险胜）和队内排名分（区分 carry 与躺赢）。
 *
 * <p>另一处刻意与 Apex 不同：**击杀分必须封顶**。Apex 里杀人与名次相关性弱，
 * 而死斗里击杀就是胜负的成因，两者高度共线。不封顶等于把同一件事算两遍，
 * 纯刷人头的收益会盖过团队胜负。
 *
 * @author AlphaHu
 */
public final class RankedService {

    /** 赛季开始时间的 meta 键。 */
    private static final String META_SEASON_START = "season_started_at";
    private static final String META_SEASON = "season";

    private final WBDTeamDeathmatch plugin;
    private final RankedStore store;
    private final Map<UUID, RankedProfile> profiles = new ConcurrentHashMap<>();

    private RankedSettings settings = RankedSettings.defaults();
    private boolean available;
    /** 赛季开始时间（epoch 秒）。0 表示尚未记录，首次写入时补上。 */
    private volatile long seasonStartedAt;

    public RankedService(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
        this.store = new RankedStore(plugin.getLogger());
    }

    // ------------------------------------------------------------------
    // 装载
    // ------------------------------------------------------------------

    /**
     * 读取 {@code season.yml} 并打开数据库。
     *
     * <p>任何一步失败都只是「段位功能不可用」，不会影响死斗本体：
     * 段位是一层附加玩法，它坏掉不该让整场比赛打不成。
     */
    public void load(FileConfiguration seasonConfig) {
        this.settings = RankedSettings.load(seasonConfig);

        String problem = settings.tierProblem();
        if (problem != null) {
            plugin.getLogger().warning("season.yml 的段位表配置有问题：" + problem);
            plugin.getLogger().warning("段位功能已停用（死斗本体不受影响）。请修正 tiers 后 /tdm reload。");
            available = false;
            return;
        }

        File db = new File(plugin.getDataFolder(), "ranked.db");
        if (!store.open(db)) {
            plugin.getLogger().warning("段位数据库不可用，段位功能已停用（死斗本体不受影响）。");
            available = false;
            return;
        }
        available = true;

        // 异步读全部档案。主线程不能等 SQLite —— 几千条记录读起来是几百毫秒
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, RankedProfile> loaded = store.loadAll();
            profiles.putAll(loaded);

            String startedAt = store.meta(META_SEASON_START, null);
            if (startedAt == null) {
                seasonStartedAt = System.currentTimeMillis() / 1000L;
                store.setMeta(META_SEASON_START, String.valueOf(seasonStartedAt));
                store.setMeta(META_SEASON, String.valueOf(settings.currentSeason));
            } else {
                try {
                    seasonStartedAt = Long.parseLong(startedAt);
                } catch (NumberFormatException e) {
                    seasonStartedAt = System.currentTimeMillis() / 1000L;
                }
            }

            plugin.getLogger().info("段位系统已就绪：载入 " + loaded.size() + " 份档案，"
                    + settings.describe() + "。");
        });
    }

    /** 只重读配置，不重开数据库（重载配置时用）。 */
    public void reloadSettings(FileConfiguration seasonConfig) {
        this.settings = RankedSettings.load(seasonConfig);
        if (available) {
            plugin.getLogger().info("段位设置已重载：" + settings.describe());
        }
    }

    public boolean available() {
        return available;
    }

    public RankedSettings settings() {
        return settings;
    }

    /** 段位表，供命令与公告取显示名。 */
    public TierTable tiers() {
        return settings.tiers;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 取档案；没有则返回 null（不建档，建档由结算负责）。 */
    public RankedProfile profileOf(UUID uuid) {
        return uuid == null ? null : profiles.get(uuid);
    }

    /**
     * 按玩家名查档案（不区分大小写）。
     *
     * <p>查的是内存档案而不是在线玩家列表，所以离线的人也能查到。
     * 玩家问「我刚那把掉了多少分」时通常已经退出游戏了 ——
     * 那个时候查不到，这条命令就等于没有。
     */
    public RankedProfile findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (RankedProfile profile : profiles.values()) {
            if (name.equalsIgnoreCase(profile.name())) {
                return profile;
            }
        }
        return null;
    }

    /**
     * 段位显示文本，用于公告与 HUD。
     *
     * <p>没有档案时返回「未定级」而不是空串：公告里出现一个空白方括号
     * 看起来像插件坏了。档在异步加载完成前可能还没到，此时同样显示未定级 ——
     * 这只是显示上的短暂延迟，玩家不会在意。
     */
    public String tierTextOf(UUID uuid) {
        RankedProfile profile = profileOf(uuid);
        if (profile == null) {
            return Messages.color(plugin.getMessages().raw("broadcast.unranked-tier", "未定级"));
        }
        return settings.tiers.display(profile.rp());
    }

    // ------------------------------------------------------------------
    // 结算
    // ------------------------------------------------------------------

    /**
     * 结算一局。
     *
     * <p><b>必须在 {@code TdmMatch.restart()} 之前调用</b>：那个方法会遍历玩家
     * 执行 {@code resetForNewMatch()}，把击杀、死亡、连杀全部清零。晚一步就会
     * 变成「所有人按 0 击杀结算」，而且不会有任何报错，只是分数永远偏低。
     *
     * @return 每位参与者的结算明细；场地不排位或系统不可用时为空表
     */
    public Map<UUID, RankOutcome> settle(TdmMatch match, TdmTeam winner) {
        Map<UUID, RankOutcome> outcomes = new LinkedHashMap<>();
        if (!available) {
            return outcomes;
        }

        TdmArena arena = match.getArena();
        if (!arena.ranked()) {
            for (TdmPlayer record : match.getPlayers()) {
                Player player = Bukkit.getPlayer(record.uuid());
                if (player != null) {
                    player.sendMessage(plugin.getMessages().get("ranked.unranked-note"));
                }
            }
            return outcomes;
        }

        Collection<TdmPlayer> participants = match.getPlayers();
        int target = Math.max(1, match.getRules().targetKills());

        for (TdmPlayer record : participants) {
            RankedProfile profile = profiles.computeIfAbsent(record.uuid(),
                    uuid -> RankedProfile.fresh(uuid, record.name(), settings.currentSeason));
            profile.setName(record.name());

            outcomes.put(record.uuid(), score(profile, record, match, winner, target, arena.getId()));
        }

        // 播报在主线程（此时就在主线程），落库丢到异步
        for (Map.Entry<UUID, RankOutcome> entry : outcomes.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                announce(player, entry.getValue());
            }
        }
        persist();

        return outcomes;
    }

    /** 算出并应用一名玩家的分数变化。 */
    private RankOutcome score(RankedProfile profile, TdmPlayer record, TdmMatch match,
                              TdmTeam winner, int target, String arenaId) {
        int rpBefore = profile.rp();
        Tier tierBefore = settings.tiers.tierOf(rpBefore);
        String tierBeforeText = tierBefore == null ? "" : tierBefore.display(rpBefore);

        boolean draw = winner == null;
        boolean win = !draw && record.team() == winner;
        boolean lateJoin = record.joinedLate() && settings.lateJoinKillOnly;

        int placement;
        int margin = 0;
        int killRp = killPointsFor(record.kills());
        int teamPlace = 0;
        int entryFee;

        if (lateJoin) {
            // 中途加入：只给击杀分。名次分是「整场表现」的度量，
            // 给中途加入者算等于奖励「专挑赢面大的局进去蹭分」
            placement = 0;
            entryFee = 0;
        } else {
            // 名次分可以先按段位覆盖：低段位「赢得多、输得少」才爬得出去。
            // 覆盖只写一部分时，没写的仍用 scoring.placement 的全局值。
            int winRp = settings.rpWin;
            int drawRp = settings.rpDraw;
            int lossRp = settings.rpLoss;
            if (tierBefore != null && tierBefore.hasPlacementOverride()) {
                if (tierBefore.winRp() != null) {
                    winRp = tierBefore.winRp();
                }
                if (tierBefore.drawRp() != null) {
                    drawRp = tierBefore.drawRp();
                }
                if (tierBefore.lossRp() != null) {
                    lossRp = tierBefore.lossRp();
                }
            }
            placement = draw ? drawRp : (win ? winRp : lossRp);
            margin = marginFor(record, match, target);
            teamPlace = teamPlacementFor(record, match);
            entryFee = settings.entryFeeFor(rpBefore);
        }

        int raw = rpBefore + placement + margin + killRp + teamPlace - entryFee;
        int rpAfter = Math.max(settings.floorRp, raw);

        // 降段保护：升段后的前 N 场内把 RP 托在本段位下限之上。
        // 这样「段位」和「RP」始终自洽 —— 如果只让显示不掉而 RP 照跌，
        // 玩家会在下一局看到一个和他记忆中不一样的段位，那才是真的难解释。
        boolean shielded = false;
        if (tierBefore != null && profile.shieldLeft() > 0 && rpAfter < tierBefore.minRp()) {
            rpAfter = tierBefore.minRp();
            shielded = true;
        }

        profile.setRp(rpAfter);

        Tier tierAfter = settings.tiers.tierOf(rpAfter);
        String tierAfterText = tierAfter == null ? "" : tierAfter.display(rpAfter);

        boolean promoted = tierBefore != null && tierAfter != null
                && tierAfter.minRp() > tierBefore.minRp();
        boolean demoted = tierBefore != null && tierAfter != null
                && tierAfter.minRp() < tierBefore.minRp();

        // 保护场次的消耗：升段那场不消耗（刚拿到保护就开始倒计时不合理）
        if (promoted && settings.demotionShieldMatches > 0) {
            profile.setShieldLeft(settings.demotionShieldMatches);
        } else if (profile.shieldLeft() > 0) {
            profile.setShieldLeft(profile.shieldLeft() - 1);
        } else if (shielded) {
            // 理论上到不了这里（shielded 为真说明 shieldLeft > 0），留作兜底
            profile.setShieldLeft(0);
        }

        profile.recordResult(win, draw);

        return new RankOutcome(arenaId, true, lateJoin,
                placement, margin, killRp, teamPlace, entryFee,
                rpBefore, rpAfter, tierBeforeText, tierAfterText, promoted, demoted);
    }

    /** 击杀分：每杀一次，封顶 kill-cap 个。 */
    private int killPointsFor(int kills) {
        int counted = settings.killCap <= 0 ? kills : Math.min(kills, settings.killCap);
        return counted * settings.killPoints;
    }

    /**
     * 比分差修正。
     *
     * <p>用来区分「50:20 碾压」和「50:45 险胜」，以及「20:50 惨败」和
     * 「45:50 惜败」。没有这一项的话，赢多少都是 +120，队伍就没有把
     * 优势扩大的动力了。
     */
    private int marginFor(TdmPlayer record, TdmMatch match, int target) {
        if (settings.marginWeight <= 0) {
            return 0;
        }
        int mine = match.getScore(record.team());
        int theirs = match.getScore(record.team().opposite());
        double ratio = (mine - theirs) / (double) target;
        double clamped = Math.max(-1.0, Math.min(1.0, ratio));
        return (int) Math.round(clamped * settings.marginWeight);
    }

    /**
     * 队内排名分：按本队击杀数排序，取「第几名」对应的加分。
     *
     * <p>并列的处理：击杀相同时按 uuid 次序稳定排序。不追求绝对公平 ——
     * 并列本来就无法区分谁贡献更大，稳定排序至少保证同一局结果可复现。
     */
    private int teamPlacementFor(TdmPlayer record, TdmMatch match) {
        if (settings.teamPlacementPoints.isEmpty()) {
            return 0;
        }
        List<TdmPlayer> mates = new ArrayList<>();
        for (TdmPlayer other : match.getPlayers()) {
            if (other.team() == record.team()) {
                mates.add(other);
            }
        }
        mates.sort((a, b) -> {
            int byKills = Integer.compare(b.kills(), a.kills());
            return byKills != 0 ? byKills : a.uuid().compareTo(b.uuid());
        });
        for (int i = 0; i < mates.size(); i++) {
            if (mates.get(i).uuid().equals(record.uuid())) {
                return settings.teamPlacementPointsFor(i + 1);
            }
        }
        return 0;
    }

    /** 把结算明细发给玩家。 */
    private void announce(Player player, RankOutcome outcome) {
        Messages messages = plugin.getMessages();
        player.sendMessage(messages.get("ranked.header"));
        if (outcome.lateJoin() && messages.has("ranked.late-join-note")) {
            player.sendMessage(messages.get("ranked.late-join-note"));
        }
        player.sendMessage(messages.get("ranked.line-detail",
                "placement", outcome.placement(),
                "margin", outcome.margin(),
                "kills_rp", outcome.killRp(),
                "team_place", outcome.teamPlace(),
                "entry_fee", outcome.entryFee()));
        player.sendMessage(messages.get("ranked.line-total",
                "rp_before", outcome.rpBefore(),
                "rp_after", outcome.rpAfter(),
                "delta", outcome.deltaText()));
        player.sendMessage(messages.get("ranked.line-tier",
                "tier_before", outcome.tierBefore(),
                "tier_after", outcome.tierAfter()));
        if (outcome.promoted()) {
            player.sendMessage(messages.get("ranked.promoted", "tier_after", outcome.tierAfter()));
        } else if (outcome.demoted()) {
            player.sendMessage(messages.get("ranked.demoted", "tier_after", outcome.tierAfter()));
        }
        player.sendMessage(messages.get("ranked.footer"));
    }

    /** 异步把脏档案写回。 */
    private void persist() {
        List<RankedProfile> dirty = new ArrayList<>();
        for (RankedProfile profile : profiles.values()) {
            if (profile.dirty()) {
                dirty.add(profile);
            }
        }
        if (dirty.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int written = store.saveAll(dirty);
            if (written >= 0) {
                // 只有确实落库了才清脏标记。写失败时保留标记，
                // 下次结算会把这份改动一起带上，不至于静默丢失
                dirty.forEach(RankedProfile::markClean);
            }
        });
    }

    // ------------------------------------------------------------------
    // 中途离开
    // ------------------------------------------------------------------

    /**
     * 中途离开的处罚：本局不结算排位分，但入场费双倍扣除。
     *
     * <p>为什么必须有这条：没有它，玩家一发现局面不对就可以直接退出，
     * 既不吃败场也不掉分 —— 而留下来打完的人反而要承担失败。这在排位里
     * 是致命的：退出的成本必须高于打完，否则纪律会迅速崩掉，
     * 最后没人愿意留在劣势局里。
     *
     * <p>扣的是**两倍入场费**而不是固定值：入场费本身就是段位的函数，
     * 段位越高退出代价越重，与入场费那条机制一脉相承，也不必再维护一张表。
     *
     * <p>代价是菜鸟与青铜（入场费为 0）退出没有分数惩罚。这两个段位本来
     * 就有保护，让新手的失误不至于一击致命，这个结果是可接受的。
     */
    public void penalizeLeave(TdmPlayer record) {
        if (!available || record == null) {
            return;
        }
        RankedProfile profile = profiles.computeIfAbsent(record.uuid(),
                uuid -> RankedProfile.fresh(uuid, record.name(), settings.currentSeason));
        profile.setName(record.name());

        int rpBefore = profile.rp();
        Tier tier = settings.tiers.tierOf(rpBefore);
        int entryFee = tier == null ? 0 : tier.entryFee();
        int penalty = entryFee * 2;
        int rpAfter = Math.max(settings.floorRp, rpBefore - penalty);

        profile.setRp(rpAfter);
        // 记一场负。中途离开不该在战绩里凭空消失 —— 否则一个总在劣势时
        // 退出的玩家会拥有一个漂亮的胜率，而那个数字是假的
        profile.recordResult(false, false);
        persist();

        Player player = Bukkit.getPlayer(record.uuid());
        if (player != null) {
            Messages messages = plugin.getMessages();
            if (penalty > 0) {
                player.sendMessage(messages.get("ranked.leave-penalty",
                        "penalty", penalty,
                        "rp_before", rpBefore,
                        "rp_after", rpAfter));
            } else {
                player.sendMessage(messages.get("ranked.leave-penalty-free"));
            }
        }
        plugin.getLogger().info("玩家 " + record.name() + " 中途离开比赛，段位处罚 -"
                + penalty + " RP（" + rpBefore + " → " + rpAfter + "）。");
    }

    // ------------------------------------------------------------------
    // 比赛历史
    // ------------------------------------------------------------------

    /**
     * 把一场比赛记入历史库，供 {@code /tdm matchrp} 查询。
     *
     * <p>必须在 {@code restart()} 之前调用（与段位结算同样的理由），
     * 内容在主线程构建、写入丢到异步。
     */
    public void recordMatch(TdmMatch match, Map<UUID, RankOutcome> outcomes) {
        if (!available) {
            return;
        }
        MatchRecord record = buildRecord(match, outcomes);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> store.recordMatch(record));
    }

    /** 在主线程把比赛对象固化成一条记录。 */
    private MatchRecord buildRecord(TdmMatch match, Map<UUID, RankOutcome> outcomes) {
        TdmArena arena = match.getArena();
        TdmTeam winner = match.getWinner();

        List<MatchRecord.Participant> participants = new ArrayList<>();
        for (TdmPlayer player : match.getPlayers()) {
            RankOutcome outcome = outcomes == null ? null : outcomes.get(player.uuid());
            participants.add(new MatchRecord.Participant(
                    player.uuid(),
                    player.name(),
                    player.team().id(),
                    player.kills(),
                    player.deaths(),
                    player.bestKillStreak(),
                    player.money(),
                    player.joinedLate(),
                    // 非排位场地没有 outcome，段位字段留空 —— 展示层靠
                    // tierAfter 是否为空来判断「这一场算不算排位」
                    outcome == null ? 0 : outcome.rpBefore(),
                    outcome == null ? 0 : outcome.rpAfter(),
                    outcome == null ? "" : outcome.tierAfter(),
                    outcome != null && outcome.promoted(),
                    outcome != null && outcome.demoted()));
        }

        return new MatchRecord(0L,
                arena.getId(),
                arena.getName(),
                match.isRanked(),
                match.getStartedAt(),
                System.currentTimeMillis(),
                winner == null ? null : winner.id(),
                match.getScore(TdmTeam.T),
                match.getScore(TdmTeam.CT),
                match.getEndReason(),
                participants);
    }

    /**
     * 查某位玩家最近的比赛记录（时间倒序）。
     *
     * <p><b>这是同步的数据库查询。</b>调用方必须在异步线程里调它，
     * 拿到结果后再回主线程展示 —— 在主线程直接调会把一次磁盘 IO
     * 塞进玩家敲命令的那一瞬间。
     */
    public List<MatchRecord> recentMatches(UUID uuid, int limit) {
        if (!available) {
            return List.of();
        }
        return store.recentMatches(uuid, limit);
    }

    // ------------------------------------------------------------------
    // 赛季
    // ------------------------------------------------------------------

    /** 赛季剩余天数；未启用或未记录起始时间时返回 -1。 */
    public long seasonDaysLeft() {
        if (!settings.autoSeason() || seasonStartedAt <= 0) {
            return -1;
        }
        long elapsed = System.currentTimeMillis() / 1000L - seasonStartedAt;
        long total = settings.seasonLengthDays * 86400L;
        long left = (total - elapsed) / 86400L;
        return Math.max(0, left);
    }

    /** 每 tick 调一次太重，由 MatchManager 降频调用即可。 */
    public void tickSeason() {
        if (!available || !settings.autoSeason() || seasonStartedAt <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() / 1000L - seasonStartedAt;
        if (elapsed >= settings.seasonLengthDays * 86400L) {
            plugin.getLogger().info("赛季已到期，执行自动软重置。");
            resetSeason(true);
        }
    }

    /**
     * 执行赛季软重置。
     *
     * <pre>新 RP = 基准 + (旧 RP − 基准) / 2</pre>
     *
     * <p>刻意不清零：清零会让所有人回到同一起跑线，上赛季的努力完全作废，
     * 而且下赛季前期高手与新人被混在一起，两边体验都差。软重置只掉一半，
     * 段位通常降一档，但进度感还在。
     *
     * @param announce 是否向全服播报
     */
    public void resetSeason(boolean announce) {
        if (!available) {
            return;
        }
        int base = settings.softResetBase;
        int nextSeason = settings.currentSeason + 1;

        // 注意这里用的是普通 setter（会置脏）而不是「安静版」：
        // 软重置必须落库，而 saveAll 只写脏档案。用不起眼的 setter 会让
        // 重置在内存里生效、重启后全部还原 —— 那种 bug 要到下次重启才发现
        for (RankedProfile profile : profiles.values()) {
            int before = profile.rp();
            int after = base + (before - base) / 2;
            profile.setRp(Math.max(settings.floorRp, after));
            profile.setSeason(nextSeason);
            profile.setShieldLeft(0);
        }

        seasonStartedAt = System.currentTimeMillis() / 1000L;
        long startedAt = seasonStartedAt;
        List<RankedProfile> snapshot = new ArrayList<>(profiles.values());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.setMeta(META_SEASON_START, String.valueOf(startedAt));
            store.setMeta(META_SEASON, String.valueOf(nextSeason));
            int written = store.saveAll(snapshot);
            if (written >= 0) {
                snapshot.forEach(RankedProfile::markClean);
            }
        });

        plugin.getLogger().info("已执行赛季软重置（基准分 " + base + "），进入第 "
                + nextSeason + " 赛季。");

        if (announce) {
            Bukkit.getServer().broadcastMessage(
                    plugin.getMessages().get("commands.season-reset-broadcast"));
        }
    }

    /** 关闭数据库。 */
    public void shutdown() {
        persist();
        store.close();
    }
}

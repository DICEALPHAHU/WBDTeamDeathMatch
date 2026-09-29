package alphahu.tdm.game;

import org.bukkit.Location;

import java.util.UUID;

/**
 * 一名玩家在某场比赛里的全部可变状态。
 *
 * <p>所有时间点都用 {@link System#currentTimeMillis()} 的绝对毫秒值记录，
 * 而不是「剩余 tick 数」。原因是死斗的复活延时与保护时长都是**秒级**概念，
 * 用 tick 计数就得自己维护一个全局计数器（{@code Bukkit.getCurrentTick()}
 * 是 Paper 才有的 API，在 Arclight 这类服务端上取不到），
 * 而墙钟时间在任何服务端上都能拿到，服务器卡顿时它的语义也更直观：
 * 「等 3 秒」就是等 3 秒。
 *
 * @author AlphaHu
 */
public final class TdmPlayer {

    private final UUID uuid;
    private final String name;
    private final TdmTeam team;

    /** 是否处于「可正常参战」状态。false 表示已阵亡、正在等复活。 */
    private boolean alive = true;

    private int kills;
    private int deaths;

    /** 当前连杀数，阵亡即清零。 */
    private int killStreak;

    /** 本场最高连杀，用于结算展示。 */
    private int bestKillStreak;

    /**
     * 手上的金钱。
     *
     * <p>死斗不是「全免费自选」，而是「打得好才买得起更好的」—— 这条数值就是
     * 那层正反馈。每场比赛开始时重置为初始金额，中途靠击杀赚。
     */
    private int money;

    /** 复活时刻（epoch ms）。0 表示不在等待复活。 */
    private long respawnAt;

    /** 无敌保护截止时刻（epoch ms）。0 表示没有保护。 */
    private long protectionUntil;

    /** 最后一次阵亡的位置，用于确定旁观时的落点。 */
    private Location deathLocation;

    /**
     * 阵亡后的「切旁观」动作是否还没走完。
     *
     * <p>这个标志解决的是一个真实的竞态：死亡处理要等到下一 tick 才能把玩家
     * 从死亡界面拽出来并切成旁观，而复活排期在同一时间也在跑。
     * 若 {@code delay-seconds} 配成 0，复活可能抢在切旁观之前执行——
     * 那时玩家还卡在死亡界面上，{@code setGameMode} 和传送全部无效，
     * 随后到来的 {@code respawn()} 会把他丢到主城出生点。
     *
     * <p>有了它，复活逻辑会一直等到旁观切换完成再动手。
     */
    private boolean spectatorPending;

    /**
     * 是否在比赛已经开打之后才加入。
     *
     * <p>段位结算要用它：中途加入者只结算击杀分，不给名次分和队内排名分，
     * 也不扣入场费。原因是他只参与了一部分比赛，而名次分度量的是「整场表现」——
     * 给中途加入者算名次分，等于奖励「专挑赢面大的局进去蹭分」。
     *
     * <p>这个标志必须在这里记下来，而不是结算时回头判断「比赛是不是还在进行」：
     * 结算那一刻比赛早已结束，那个判断永远是 false。
     */
    private boolean joinedLate;

    public TdmPlayer(UUID uuid, String name, TdmTeam team) {
        this.uuid = uuid;
        this.name = name;
        this.team = team;
    }

    // ------------------------------------------------------------------
    // 计分
    // ------------------------------------------------------------------

    /** 记一次击杀，并推进连杀。 */
    public void addKill() {
        kills++;
        killStreak++;
        if (killStreak > bestKillStreak) {
            bestKillStreak = killStreak;
        }
    }

    /**
     * 记一次阵亡。
     *
     * @return 阵亡前的连杀数，供播报「终结了对手的 N 连杀」使用
     */
    public int addDeath() {
        deaths++;
        int ended = killStreak;
        killStreak = 0;
        return ended;
    }

    /** 死亡数（K/D 用）。 */
    public double killDeathRatio() {
        if (deaths == 0) {
            return kills;
        }
        return (double) kills / deaths;
    }

    // ------------------------------------------------------------------
    // 复活与保护
    // ------------------------------------------------------------------

    /** 是否正在等待复活。 */
    public boolean isAwaitingRespawn() {
        return !alive;
    }

    /** 是否处于无敌保护期。 */
    public boolean isProtected(long now) {
        return protectionUntil > now;
    }

    /** 立即解除保护（保护期内主动攻击敌人时调用）。 */
    public void clearProtection() {
        protectionUntil = 0L;
    }

    /** 剩余复活秒数（向上取整），不在等待复活时返回 0。 */
    public int respawnSecondsLeft(long now) {
        if (alive || respawnAt <= 0) return 0;
        long remain = respawnAt - now;
        return remain <= 0 ? 0 : (int) Math.ceil(remain / 1000.0);
    }

    /** 复活是否到点。 */
    public boolean isRespawnDue(long now) {
        return !alive && !spectatorPending && respawnAt > 0 && now >= respawnAt;
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public TdmTeam team() {
        return team;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    public int killStreak() {
        return killStreak;
    }

    public int bestKillStreak() {
        return bestKillStreak;
    }

    public int money() {
        return money;
    }

    public void setMoney(int money) {
        this.money = Math.max(0, money);
    }

    public long respawnAt() {
        return respawnAt;
    }

    public void setRespawnAt(long respawnAt) {
        this.respawnAt = respawnAt;
    }

    public long protectionUntil() {
        return protectionUntil;
    }

    public void setProtectionUntil(long protectionUntil) {
        this.protectionUntil = protectionUntil;
    }

    public Location deathLocation() {
        return deathLocation;
    }

    public void setDeathLocation(Location deathLocation) {
        this.deathLocation = deathLocation;
    }

    public boolean isSpectatorPending() {
        return spectatorPending;
    }

    public void setSpectatorPending(boolean spectatorPending) {
        this.spectatorPending = spectatorPending;
    }

    public boolean joinedLate() {
        return joinedLate;
    }

    public void setJoinedLate(boolean joinedLate) {
        this.joinedLate = joinedLate;
    }

    /** 重置成「刚开场」的状态，用于开新一场时复用玩家对象。 */
    public void resetForNewMatch() {
        alive = true;
        kills = 0;
        deaths = 0;
        killStreak = 0;
        bestKillStreak = 0;
        money = 0;
        respawnAt = 0L;
        protectionUntil = 0L;
        deathLocation = null;
        // 也必须清掉：这个玩家对象会被下一场复用，留着上一场的「中途加入」
        // 会让新一场的完整参与者被当成半场玩家，白少一大截名次分
        joinedLate = false;
        spectatorPending = false;
    }
}

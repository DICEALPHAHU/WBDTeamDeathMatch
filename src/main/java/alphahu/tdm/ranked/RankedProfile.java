package alphahu.tdm.ranked;

import java.util.UUID;

/**
 * 一名玩家的排位档案。
 *
 * <p>主线程只读写内存里的这个对象，落库在异步线程做 —— 结算发生在比赛结束时，
 * 那一刻正好是服务器最不该卡的时候（要传送一堆人、播报、发烟花）。
 *
 * <p>{@link #dirty} 标记「改过但还没落库」。之所以要它：一局结束会更新很多字段，
 * 全量写一遍既没必要也更容易在并发下写出半截数据。只写脏档，
 * 而且同一玩家的连续改动可以合并成一次写入。
 *
 * @author AlphaHu
 */
public final class RankedProfile {

    private final UUID uuid;
    private String name;
    private int rp;
    private int season;
    private int shieldLeft;
    private int played;
    private int wins;
    private int losses;
    private int draws;
    private boolean dirty;

    public RankedProfile(UUID uuid, String name, int rp, int season, int shieldLeft,
                         int played, int wins, int losses, int draws) {
        this.uuid = uuid;
        this.name = name;
        this.rp = Math.max(0, rp);
        this.season = season;
        this.shieldLeft = Math.max(0, shieldLeft);
        this.played = Math.max(0, played);
        this.wins = Math.max(0, wins);
        this.losses = Math.max(0, losses);
        this.draws = Math.max(0, draws);
    }

    /** 新档：RP 为 0，落在最低段位。 */
    public static RankedProfile fresh(UUID uuid, String name, int season) {
        return new RankedProfile(uuid, name, 0, season, 0, 0, 0, 0, 0);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.equals(this.name)) {
            this.name = name;
            this.dirty = true;
        }
    }

    public int rp() {
        return rp;
    }

    public void setRp(int rp) {
        int clamped = Math.max(0, rp);
        if (clamped != this.rp) {
            this.rp = clamped;
            this.dirty = true;
        }
    }

    /**
     * 只改 RP、**不**置脏标记。
     *
     * <p>给「软重置」用：重置是整体批量操作，逐条置脏会让
     * 「哪些是真需要落库的」失去意义，重置流程自己负责统一写回。
     */
    public int season() {
        return season;
    }

    public void setSeason(int season) {
        this.season = season;
        this.dirty = true;
    }

    public int shieldLeft() {
        return shieldLeft;
    }

    public void setShieldLeft(int shieldLeft) {
        this.shieldLeft = Math.max(0, shieldLeft);
        this.dirty = true;
    }

    public int played() {
        return played;
    }

    public int wins() {
        return wins;
    }

    public int losses() {
        return losses;
    }

    public int draws() {
        return draws;
    }

    /** 记一局战绩。 */
    public void recordResult(boolean win, boolean draw) {
        played++;
        if (draw) {
            draws++;
        } else if (win) {
            wins++;
        } else {
            losses++;
        }
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    /** 胜率，用于展示；没打过返回 0。 */
    public double winRate() {
        return played == 0 ? 0.0 : (wins * 100.0 / played);
    }
}

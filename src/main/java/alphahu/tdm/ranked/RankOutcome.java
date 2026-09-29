package alphahu.tdm.ranked;

/**
 * 一局比赛的段位结算明细。
 *
 * <p>逐项保留而不是只留一个净变化，是为了能回答「我这局为什么是 −24 分」。
 * 玩家一定会问，而且只给一个总数是答不上来的。
 *
 * @param arenaId     场地 id
 * @param ranked      本局是否结算段位（场地 {@code unrank} 或系统不可用时为 false）
 * @param lateJoin    是否中途加入（只结算击杀分）
 * @param placement   名次分（胜负）
 * @param margin      比分差修正
 * @param killRp      击杀分
 * @param teamPlace   队内排名分
 * @param entryFee    入场费（正数，实际是扣的）
 * @param rpBefore    结算前 RP
 * @param rpAfter     结算后 RP
 * @param tierBefore  结算前段位显示名（含颜色）
 * @param tierAfter   结算后段位显示名
 * @param promoted    本局是否升段
 * @param demoted     本局是否降段
 * @author AlphaHu
 */
public record RankOutcome(String arenaId, boolean ranked, boolean lateJoin,
                          int placement, int margin, int killRp, int teamPlace, int entryFee,
                          int rpBefore, int rpAfter,
                          String tierBefore, String tierAfter,
                          boolean promoted, boolean demoted) {

    /** 净变化。刻意用 after − before 算，而不是把各项加起来 —— 后者在夹了上下限之后会撒谎。 */
    public int delta() {
        return rpAfter - rpBefore;
    }

    /** 带正负号的净变化文本，例如 {@code +186} / {@code -24} / {@code +0}。 */
    public String deltaText() {
        int delta = delta();
        return (delta > 0 ? "+" : "") + delta;
    }

    /** 只结算击杀分的那种。 */
    public static RankOutcome killOnly(String arenaId, int killRp, int rpBefore, int rpAfter,
                                       String tierBefore, String tierAfter,
                                       boolean promoted, boolean demoted) {
        return new RankOutcome(arenaId, true, true,
                0, 0, killRp, 0, 0,
                rpBefore, rpAfter, tierBefore, tierAfter, promoted, demoted);
    }

    /** 不结算（场地标记了 unrank）。 */
    public static RankOutcome unranked(String arenaId) {
        return new RankOutcome(arenaId, false, false,
                0, 0, 0, 0, 0, 0, 0, "", "", false, false);
    }
}

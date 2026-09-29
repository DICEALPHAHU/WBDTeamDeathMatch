package alphahu.tdm.ranked;

import java.util.List;
import java.util.UUID;

/**
 * 一场比赛的完整记录，供 {@code /tdm matchrp} 查询与展示。
 *
 * <p>和落盘的 YAML 战报是同一份数据的两种形态：文件给人看，这张表给命令查。
 *
 * @param id           比赛 id（数据库自增）
 * @param arenaId      场地 id
 * @param arenaName    场地显示名
 * @param ranked       本场是否结算段位
 * @param startedAt    开打时刻（epoch ms）
 * @param endedAt      结束时刻（epoch ms）
 * @param winner       胜者阵营 id；null 表示平局
 * @param tScore       T 方比分
 * @param ctScore      CT 方比分
 * @param endReason    结束原因
 * @param participants 全部参与者
 * @author AlphaHu
 */
public record MatchRecord(long id, String arenaId, String arenaName, boolean ranked,
                          long startedAt, long endedAt, String winner,
                          int tScore, int ctScore, String endReason,
                          List<Participant> participants) {

    /**
     * 一场比赛里的一名参与者。
     *
     * <p>{@code uuid} 是必须的，不能只靠名字：{@code /tdm matchrp} 就是按它
     * 反查「这名玩家参加过哪些比赛」。名字会改、会重名，用它当键的话，
     * 改名后的玩家会查不到自己的历史，两个同名玩家会互相看到对方的战绩。
     */
    public record Participant(UUID uuid, String name, String team, int kills, int deaths,
                              int bestStreak, int moneyLeft, boolean lateJoin,
                              int rpBefore, int rpAfter, String tierAfter,
                              boolean promoted, boolean demoted) {

        /** K/D；零死亡时直接给击杀数（避免除零，也符合直觉）。 */
        public double kd() {
            return deaths <= 0 ? kills : kills / (double) deaths;
        }

        /** RP 变化。 */
        public int delta() {
            return rpAfter - rpBefore;
        }

        /** 带正负号的 RP 变化文本。 */
        public String deltaText() {
            int delta = delta();
            return (delta > 0 ? "+" : "") + delta;
        }

        /**
         * 这一场是否结算了段位。
         *
         * <p>判据是「有没有段位名」而不是 RP 是否为 0：一个新手第一场打排位，
         * RP 前后都可能是 0，但那一场确实结算过（段位是「菜鸟 IV」）。
         */
        public boolean hasRank() {
            return tierAfter != null && !tierAfter.isEmpty();
        }
    }

    /** 该阵营是否获胜。 */
    public boolean won(String team) {
        return winner != null && team != null && winner.equalsIgnoreCase(team);
    }

    /** 是否平局。 */
    public boolean draw() {
        return winner == null || winner.isBlank();
    }

    /** 时长（秒）。 */
    public long durationSeconds() {
        return startedAt <= 0 ? 0 : Math.max(0, (endedAt - startedAt) / 1000L);
    }

    /** 按阵营取出参与者，保持原顺序。 */
    public List<Participant> teamOf(String team) {
        return participants.stream()
                .filter(p -> p.team() != null && p.team().equalsIgnoreCase(team))
                .toList();
    }
}

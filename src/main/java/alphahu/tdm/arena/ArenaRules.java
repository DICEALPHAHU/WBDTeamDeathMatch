package alphahu.tdm.arena;

/**
 * 一场比赛实际生效的规则 = 全局默认值 + 场地覆盖。
 *
 * <p>把「合并」这一步从比赛逻辑里抽出来，是为了让 {@link alphahu.tdm.game.TdmMatch}
 * 不必到处写 {@code arena.getTargetKills() >= 0 ? ... : settings.targetKills}——
 * 那种三元表达式散落在十几个地方，改一次默认值就得全找一遍。
 *
 * @author AlphaHu
 */
public record ArenaRules(
        int targetKills,
        int timeLimitSeconds,
        int countdownSeconds,
        int endDelaySeconds,
        boolean autoRestart,
        int minPlayers,
        int maxPlayers
) {

    /** 是否设了击杀目标（0 表示只按时间判定胜负）。 */
    public boolean hasKillTarget() {
        return targetKills > 0;
    }

    /** 是否设了时间上限（0 表示不限时）。 */
    public boolean hasTimeLimit() {
        return timeLimitSeconds > 0;
    }
}

package alphahu.tdm.config;

import alphahu.tdm.ranked.Tier;
import alphahu.tdm.ranked.TierTable;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 段位设置，对应 {@code season.yml}。
 *
 * <p>与 {@link TdmSettings} 一样是不可变的：加载完就不再改动，重载时整体换新实例。
 * 结算发生在比赛结束时，读取过程中配置被替换成一半新一半旧是最难查的那类问题。
 *
 * @author AlphaHu
 */
public final class RankedSettings {

    // ---- season ----
    public final boolean seasonEnabled;
    public final int seasonLengthDays;
    public final int softResetBase;
    public final int currentSeason;

    // ---- tiers ----
    public final TierTable tiers;

    // ---- scoring ----
    public final int rpWin;
    public final int rpDraw;
    public final int rpLoss;
    public final int marginWeight;
    public final int killPoints;
    public final int killCap;
    public final List<Integer> teamPlacementPoints;
    public final boolean lateJoinKillOnly;

    // ---- protection ----
    public final int floorRp;
    public final int demotionShieldMatches;

    private RankedSettings(boolean seasonEnabled, int seasonLengthDays, int softResetBase,
                           int currentSeason, TierTable tiers,
                           int rpWin, int rpDraw, int rpLoss, int marginWeight,
                           int killPoints, int killCap, List<Integer> teamPlacementPoints,
                           boolean lateJoinKillOnly, int floorRp, int demotionShieldMatches) {
        this.seasonEnabled = seasonEnabled;
        this.seasonLengthDays = seasonLengthDays;
        this.softResetBase = softResetBase;
        this.currentSeason = currentSeason;
        this.tiers = tiers;
        this.rpWin = rpWin;
        this.rpDraw = rpDraw;
        this.rpLoss = rpLoss;
        this.marginWeight = marginWeight;
        this.killPoints = killPoints;
        this.killCap = killCap;
        this.teamPlacementPoints = List.copyOf(teamPlacementPoints);
        this.lateJoinKillOnly = lateJoinKillOnly;
        this.floorRp = floorRp;
        this.demotionShieldMatches = demotionShieldMatches;
    }

    public static RankedSettings load(FileConfiguration c) {
        boolean seasonEnabled = c.getBoolean("season.enabled", true);
        // 0 天没有意义，会把赛季当成「立刻过期」，所以下限取 1
        int seasonLengthDays = Math.max(1, c.getInt("season.length-days", 60));
        int softResetBase = Math.max(0, c.getInt("season.soft-reset-base", 2000));
        int currentSeason = Math.max(1, c.getInt("season.current", 1));

        // 名次分先解析：下面的段位表要拿它当「没写覆盖时的兜底」
        int rpWin = c.getInt("scoring.placement.win", 120);
        int rpDraw = c.getInt("scoring.placement.draw", 30);
        int rpLoss = c.getInt("scoring.placement.loss", -60);

        List<Tier> tiers = new ArrayList<>();
        List<?> rawTiers = c.getList("tiers");
        if (rawTiers != null) {
            for (Object entry : rawTiers) {
                if (!(entry instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                String id = stringOf(map.get("id"), "tier" + (tiers.size() + 1));
                String name = stringOf(map.get("name"), id);
                String color = stringOf(map.get("color"), "&f");
                int minRp = intOf(map.get("min-rp"), 0);
                int maxRp = intOf(map.get("max-rp"), minRp + 999);
                int subTiers = Math.max(0, intOf(map.get("sub-tiers"), 4));
                int entryFee = Math.max(0, intOf(map.get("entry-fee"), 0));

                // 可选的按段位覆盖：低段位「赢得多、输得少」才爬得出去。
                // 只写其中几项也可以，没写的继续用 scoring.placement 的全局值。
                Integer winRp = null;
                Integer drawRp = null;
                Integer lossRp = null;
                if (map.get("placement") instanceof java.util.Map<?, ?> pm) {
                    if (pm.containsKey("win")) {
                        winRp = intOf(pm.get("win"), rpWin);
                    }
                    if (pm.containsKey("draw")) {
                        drawRp = intOf(pm.get("draw"), rpDraw);
                    }
                    if (pm.containsKey("loss")) {
                        lossRp = intOf(pm.get("loss"), rpLoss);
                    }
                }
                tiers.add(new Tier(id, name, color, minRp, maxRp, subTiers, entryFee,
                        winRp, drawRp, lossRp));
            }
        }

        int marginWeight = Math.max(0, c.getInt("scoring.margin-weight", 25));
        int killPoints = Math.max(0, c.getInt("scoring.kill-points", 6));
        int killCap = Math.max(0, c.getInt("scoring.kill-cap", 10));

        List<Integer> teamPlacement = new ArrayList<>();
        for (Object value : c.getList("scoring.team-placement-points", List.of(15, 8, 3))) {
            teamPlacement.add(intOf(value, 0));
        }

        boolean lateJoinKillOnly = c.getBoolean("scoring.late-join-kill-only", true);
        int floorRp = Math.max(0, c.getInt("protection.floor-rp", 0));
        int shield = Math.max(0, c.getInt("protection.demotion-shield-matches", 3));

        return new RankedSettings(seasonEnabled, seasonLengthDays, softResetBase, currentSeason,
                new TierTable(tiers), rpWin, rpDraw, rpLoss, marginWeight,
                killPoints, killCap, teamPlacement, lateJoinKillOnly, floorRp, shield);
    }

    /** 默认设置，用于 {@code season.yml} 读不出来时兜底，保证结算逻辑不会 NPE。 */
    public static RankedSettings defaults() {
        List<Tier> tiers = List.of(
                new Tier("rookie", "菜鸟", "&7", 0, 999, 4, 0),
                new Tier("bronze", "青铜", "&6", 1000, 1999, 4, 0),
                new Tier("silver", "白银", "&f", 2000, 2999, 4, 12),
                new Tier("gold", "黄金", "&e", 3000, 3999, 4, 24),
                new Tier("platinum", "铂金", "&b", 4000, 4999, 4, 36),
                new Tier("diamond", "钻石", "&3", 5000, 5999, 4, 48),
                new Tier("master", "大师", "&5", 6000, 7499, 4, 60),
                new Tier("predator", "猎杀", "&c", 7500, 999999, 0, 75));
        return new RankedSettings(true, 60, 2000, 1, new TierTable(tiers),
                120, 30, -60, 25, 6, 10, List.of(15, 8, 3), true, 0, 3);
    }

    private static String stringOf(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 段位表有问题时返回描述，否则 null。 */
    public String tierProblem() {
        return tiers.validate();
    }

    /** 是否启用了赛季自动重置。 */
    public boolean autoSeason() {
        return seasonEnabled && seasonLengthDays > 0;
    }

    /** 某个段位的入场费；段位表为空时为 0。 */
    public int entryFeeFor(int rp) {
        Tier tier = tiers.tierOf(rp);
        return tier == null ? 0 : tier.entryFee();
    }

    /** 队内第 place 名（1 起）的加分；超出配置长度返回 0。 */
    public int teamPlacementPointsFor(int place) {
        if (place < 1 || place > teamPlacementPoints.size()) {
            return 0;
        }
        return teamPlacementPoints.get(place - 1);
    }

    /** 供日志输出的摘要。 */
    public String describe() {
        return "段位表 " + tiers.all().size() + " 段"
                + "，击杀分 " + killPoints + "×最多 " + killCap
                + "，入场费 " + tiers.all().stream().mapToInt(Tier::entryFee).min().orElse(0)
                + "~" + tiers.all().stream().mapToInt(Tier::entryFee).max().orElse(0)
                + (seasonEnabled ? "，赛季 " + seasonLengthDays + " 天" : "，赛季已关闭");
    }
}

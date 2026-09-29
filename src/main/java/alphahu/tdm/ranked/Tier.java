package alphahu.tdm.ranked;

/**
 * 一个段位的定义，对应 {@code season.yml} 里 {@code tiers} 的一项。
 *
 * <p>分数区间是**闭区间**：[{@link #minRp()}, {@link #maxRp()}]。段位表由
 * {@link TierTable} 负责校验连续性与顺序，这里只描述单段。
 *
 * @param id        段位标识，用于配置与命令（{@code gold}）
 * @param name      显示名（{@code 黄金}）
 * @param color     颜色代码（{@code &e}）
 * @param minRp     本段位的最低 RP（含）
 * @param maxRp     本段位的最高 RP（含）
 * @param subTiers  小段数量；4 表示 IV/III/II/I，0 表示无小段
 * @param entryFee  入场费，每局开始先扣
 * @author AlphaHu
 */
public record Tier(String id, String name, String color, int minRp, int maxRp,
                   int subTiers, int entryFee) {

    /** 小段数量为 4 时使用的罗马数字，索引 0 是最低小段。 */
    private static final String[] ROMAN = {"IV", "III", "II", "I"};

    /**
     * 算出某个 RP 落在本段位的哪一小段。
     *
     * <p>小段序号从**低到高**排：IV 是最低、I 是最高，与 Apex 一致。
     * 所以 RP 越高，罗马数字越小 —— 这不是 bug，是段位系统的惯例。
     *
     * @return 小段标签；本段位没有小段时返回空串
     */
    public String subTierLabel(int rp) {
        if (subTiers <= 0) {
            return "";
        }
        // 每小段的跨度。用 (max - min + 1) 而非 (max - min)，
        // 这样闭区间能被整除干净，最高分不会溢出到下一段
        int span = Math.max(1, (maxRp - minRp + 1) / subTiers);
        int index = Math.max(0, Math.min(subTiers - 1, (rp - minRp) / span));
        return subTiers == 4 ? ROMAN[index] : String.valueOf(subTiers - index);
    }

    /** 带颜色、含小段的显示名，例如 {@code &e黄金 II}。 */
    public String display(int rp) {
        String sub = subTierLabel(rp);
        return color + name + (sub.isEmpty() ? "" : " " + sub);
    }

    /** 带颜色、不含小段的显示名。 */
    public String displayBare() {
        return color + name;
    }

    /** RP 是否落在本段位区间内。 */
    public boolean contains(int rp) {
        return rp >= minRp && rp <= maxRp;
    }

    /** 本段位跨度的下一个段位起点，用于生成提示文案。 */
    public int nextTierStart() {
        return maxRp + 1;
    }
}

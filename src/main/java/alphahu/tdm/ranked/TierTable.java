package alphahu.tdm.ranked;

import java.util.List;

/**
 * 段位表：把 RP 映射到段位，并负责校验配置写对没有。
 *
 * <p>校验放在这里而不是加载时就抛异常，是因为段位表来自服主手写的 YAML：
 * 区间重叠、顺序颠倒、负数分数都写得出。启动时打一条明确的警告，
 * 比让插件因为一个数字启动失败要好 —— 排位坏了大不了不结算，
 * 整个死斗跟着起不来就过分了。
 *
 * @author AlphaHu
 */
public final class TierTable {

    private final List<Tier> tiers;

    public TierTable(List<Tier> tiers) {
        this.tiers = List.copyOf(tiers);
    }

    public List<Tier> all() {
        return tiers;
    }

    public boolean isEmpty() {
        return tiers.isEmpty();
    }

    /**
     * 找到 RP 所属的段位。
     *
     * <p>低于最低段位时返回最低段位（而不是 null）—— 一个刚建档、RP 为 0
     * 的玩家必须能看到自己的段位，哪怕配置的最低段位是从 1000 开始的。
     * 高于最高段位时返回最高段位。
     */
    public Tier tierOf(int rp) {
        if (tiers.isEmpty()) {
            return null;
        }
        Tier fallback = rp < tiers.get(0).minRp() ? tiers.get(0) : tiers.get(tiers.size() - 1);
        for (Tier tier : tiers) {
            if (tier.contains(rp)) {
                return tier;
            }
        }
        return fallback;
    }

    /** 带颜色与小段的段位显示名；段位表为空时返回空串。 */
    public String display(int rp) {
        Tier tier = tierOf(rp);
        return tier == null ? "" : tier.display(rp);
    }

    /** 段位显示名（不含小段）。 */
    public String displayBare(int rp) {
        Tier tier = tierOf(rp);
        return tier == null ? "" : tier.displayBare();
    }

    /** 按 id 找段位；找不到返回 null。 */
    public Tier byId(String id) {
        if (id == null) {
            return null;
        }
        for (Tier tier : tiers) {
            if (tier.id().equalsIgnoreCase(id)) {
                return tier;
            }
        }
        return null;
    }

    /** 最低段位，用于新档初始化。 */
    public Tier lowest() {
        return tiers.isEmpty() ? null : tiers.get(0);
    }

    /**
     * 校验段位表。
     *
     * @return 问题描述；配置正确时返回 {@code null}
     */
    public String validate() {
        if (tiers.isEmpty()) {
            return "tiers 是空的，没有任何段位可用";
        }
        Tier previous = null;
        for (Tier tier : tiers) {
            if (tier.minRp() > tier.maxRp()) {
                return "段位 " + tier.id() + " 的 min-rp(" + tier.minRp()
                        + ") 大于 max-rp(" + tier.maxRp() + ")";
            }
            if (tier.subTiers() < 0) {
                return "段位 " + tier.id() + " 的 sub-tiers 不能是负数";
            }
            if (previous != null) {
                if (tier.minRp() <= previous.maxRp()) {
                    return "段位 " + tier.id() + " 的 min-rp(" + tier.minRp()
                            + ") 与前一段位 " + previous.id() + " 的 max-rp("
                            + previous.maxRp() + ") 重叠";
                }
                if (tier.minRp() != previous.maxRp() + 1) {
                    return "段位 " + tier.id() + " 与 " + previous.id()
                            + " 之间有分数空隙：" + previous.maxRp() + " → " + tier.minRp();
                }
            }
            previous = tier;
        }
        return null;
    }
}

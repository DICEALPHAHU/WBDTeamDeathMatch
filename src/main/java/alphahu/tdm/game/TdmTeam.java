package alphahu.tdm.game;

/**
 * 死斗的两方阵营。
 *
 * <p>沿用 WarZBombDefuse 的 T / CT 命名与配色，服务器上两套模式的观感才统一；
 * 但这里刻意不引用 WBD 的 {@code Team} 枚举——死斗独立运行时不希望多一份
 * 对 WBD 本体的类依赖。对接 WBDAddon 时靠 {@link #id()} 传递阵营标识即可。
 *
 * @author AlphaHu
 */
public enum TdmTeam {

    T("T", "&cT 方", "&c", "&4"),
    CT("CT", "&bCT 方", "&b", "&1");

    private final String id;
    private final String displayName;
    private final String color;
    private final String darkColor;

    TdmTeam(String id, String displayName, String color, String darkColor) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.darkColor = darkColor;
    }

    /** 稳定标识，会被拼进原版队伍名，因此只含字母。 */
    public String id() {
        return id;
    }

    /** 带颜色的显示名。 */
    public String displayName() {
        return displayName;
    }

    /** 主色（已带 {@code &} 码）。 */
    public String color() {
        return color;
    }

    /** 同色系的暗色，用于记分板等需要区分层次的地方。 */
    public String darkColor() {
        return darkColor;
    }

    /** 对方阵营。 */
    public TdmTeam opposite() {
        return this == T ? CT : T;
    }

    /**
     * 从玩家输入解析阵营。
     *
     * @return 无法识别时返回 null（调用方据此退回自动分队）
     */
    public static TdmTeam fromInput(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.equalsIgnoreCase("t") || value.equalsIgnoreCase("terrorist")) {
            return T;
        }
        if (value.equalsIgnoreCase("ct") || value.equalsIgnoreCase("counter")
                || value.equalsIgnoreCase("counterterrorist")) {
            return CT;
        }
        return null;
    }
}

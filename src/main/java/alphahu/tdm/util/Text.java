package alphahu.tdm.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文本处理工具。
 *
 * <p>只做两件事：把 {@code &} 颜色码翻译成原版颜色符号，以及做占位符替换。
 * 看起来微不足道，但这两件事在播报、HUD、记分板里到处都是，
 * 每处各写一遍很快就会写出「某个提示忘了翻译颜色码」这种低级不一致。
 *
 * @author AlphaHu
 */
public final class Text {

    private Text() {
    }

    /** 翻译 {@code &} 颜色码。null 视为空串。 */
    public static String color(String raw) {
        return raw == null ? "" : ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** 批量翻译。 */
    public static List<String> color(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw) {
            out.add(color(line));
        }
        return out;
    }

    /** 剥掉颜色码，用于把文本塞进有长度或格式限制的地方。 */
    public static String strip(String raw) {
        return raw == null ? "" : ChatColor.stripColor(color(raw));
    }

    /**
     * 按顺序替换全部占位符。
     *
     * @param template 模板，null 视为空串
     * @param values   占位符名（不含 {@code %}）到替换值的映射；值为 null 时替换成空串
     */
    public static String fill(String template, Map<String, String> values) {
        String out = template == null ? "" : template;
        if (values == null) return out;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            out = out.replace("%" + entry.getKey() + "%", value);
        }
        return out;
    }

    /**
     * 把秒数格式化成 {@code mm:ss}。
     *
     * @param seconds 剩余秒数，负数按 0 处理
     */
    public static String clock(int seconds) {
        int total = Math.max(0, seconds);
        return String.format("%02d:%02d", total / 60, total % 60);
    }
}

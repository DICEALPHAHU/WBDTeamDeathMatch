package alphahu.tdm.message;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code messages.yml} 的读取与占位符替换。
 *
 * <p>两条刻意的设计：
 *
 * <p><b>不认识的变量原样保留。</b>写错变量名是配文案时最常见的错误，
 * 而「静默变成空字符串」会让服主对着一条缺了名字的公告反复检查逻辑。
 * 留下 {@code %foo%} 他一眼就知道是变量名写错了。
 *
 * <p><b>只用 jar 内的默认值补全，不写回文件。</b>升级后新增的消息能立刻生效，
 * 但不会去重写服主那份文件 —— 重写会把他辛苦写的注释和排版全冲掉，
 * 那种「升级一次配置毁一次」的行为比缺几条默认值讨厌得多。
 *
 * @author AlphaHu
 */
public final class Messages {

    private final JavaPlugin plugin;
    private FileConfiguration config = new YamlConfiguration();
    private String prefix = "";

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 从数据目录读取，缺失时用 jar 内的资源生成。
     *
     * @param fileName        数据目录里的文件名（{@code messages.yml}）
     * @param resourceFallback jar 内的资源名
     */
    public void load(String fileName, String resourceFallback) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(resourceFallback, false);
            } catch (Throwable t) {
                plugin.getLogger().warning("无法生成默认的 " + resourceFallback + "：" + t);
            }
        }

        config = YamlConfiguration.loadConfiguration(file);

        // 用资源里的版本补全缺失键。copyDefaults 只影响读取，不改文件
        try (InputStream in = plugin.getResource(resourceFallback)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("读取 " + resourceFallback + " 的默认值时出错：" + t);
        }

        this.prefix = color(config.getString("prefix", ""));
    }

    /** 消息前缀（已上色）。 */
    public String prefix() {
        return prefix;
    }

    /** 取原始模板，不做变量替换。 */
    public String raw(String path, String fallback) {
        String value = config.getString(path);
        return value == null ? fallback : value;
    }

    /** 该路径是否存在且非空。 */
    public boolean has(String path) {
        String value = config.getString(path);
        return value != null && !value.isEmpty();
    }

    /**
     * 取一条消息并替换变量。
     *
     * @param pairs 交替的「变量名, 值」序列，例如 {@code "player", name, "arena", arena}
     */
    public String get(String path, Object... pairs) {
        Map<String, String> vars = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            vars.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : String.valueOf(pairs[i + 1]));
        }
        return format(raw(path, ""), vars);
    }

    /** 用变量表替换模板里的 {@code %key%}，然后上色。 */
    public String format(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String out = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String value = entry.getValue();
            out = out.replace("%" + entry.getKey() + "%", value == null ? "" : value);
        }
        return color(out);
    }

    /** 上色。 */
    public static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }
}

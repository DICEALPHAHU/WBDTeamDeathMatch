package alphahu.tdm.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 坐标串的读写。
 *
 * <p>格式刻意与 WarZBombDefuse 的 {@code arenas.yml} 保持一致：
 * <pre>
 *   world,x,y,z,yaw,pitch          # 位置
 *   world / min: x,y,z / max: ...  # 边界（另外解析）
 * </pre>
 * 这样服主用 {@code /wbd setup} 量好的点可以原样搬过来（甚至用 {@code /tdm import} 直接导入），
 * 不必在两套插件之间换算坐标。
 *
 * @author AlphaHu
 */
public final class LocationCodec {

    private LocationCodec() {
    }

    /**
     * 解析一个位置串。
     *
     * @return 解析失败（字段不足或世界不存在）时返回 null
     */
    public static Location parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = resolveWorld(parts[0].trim());
        if (world == null) {
            return null;
        }
        double x = parseDouble(parts[1], 0.0);
        double y = parseDouble(parts[2], 0.0);
        double z = parseDouble(parts[3], 0.0);
        float yaw = parts.length > 4 ? (float) parseDouble(parts[4], 0.0) : 0.0f;
        float pitch = parts.length > 5 ? (float) parseDouble(parts[5], 0.0) : 0.0f;
        return new Location(world, x, y, z, yaw, pitch);
    }

    /** 批量解析，跳过解析失败的条目。 */
    public static List<Location> parseAll(List<String> values) {
        List<Location> out = new ArrayList<>();
        if (values == null) return out;
        for (String value : values) {
            Location location = parse(value);
            if (location != null) {
                out.add(location);
            }
        }
        return out;
    }

    /** 把位置写回坐标串（与 {@link #parse(String)} 对称）。 */
    public static String serialize(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return location.getWorld().getName()
                + "," + round(location.getX())
                + "," + round(location.getY())
                + "," + round(location.getZ())
                + "," + round(location.getYaw())
                + "," + round(location.getPitch());
    }

    /**
     * 解析 {@code x,y,z} 三元组，用于边界。
     *
     * @return 长度不足 3 时返回 null
     */
    public static double[] parseVector3(String value) {
        if (value == null) return null;
        String[] parts = value.split(",");
        if (parts.length < 3) return null;
        return new double[]{
                parseDouble(parts[0], 0.0),
                parseDouble(parts[1], 0.0),
                parseDouble(parts[2], 0.0)
        };
    }

    /**
     * 解析世界名。
     *
     * <p>世界没加载但磁盘上有对应目录时会尝试加载一次——服主把竞技场配在
     * 一个尚未加载的多世界地图上是很常见的情况，静默返回 null 只会让人
     * 对着「坐标配置无效」的报错发愣。
     */
    public static World resolveWorld(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        World world = Bukkit.getWorld(name);
        if (world != null) {
            return world;
        }
        File folder = new File(Bukkit.getWorldContainer(), name);
        if (!folder.isDirectory()) {
            return null;
        }
        try {
            return Bukkit.createWorld(new WorldCreator(name));
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[WBDTeamDeathmatch] 无法加载坐标中的世界 " + name + "：" + e.getMessage());
            return null;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String round(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }
}

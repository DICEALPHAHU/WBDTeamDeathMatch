package alphahu.tdm.arena;

import alphahu.tdm.game.TdmTeam;
import alphahu.tdm.util.LocationCodec;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场地配置的读取。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #load(File)} —— 读本插件自己的 {@code arenas.yml}</li>
 *   <li>{@link #readArena(String, File)} —— 从任意一个同格式的文件里读一个场地，
 *       主要给 {@code /tdm import} 用来把 WarZBombDefuse 里已经量好的坐标搬过来</li>
 * </ul>
 *
 * <p>之所以能共用同一段解析代码，是因为死斗的 {@code arenas.yml} 刻意采用了
 * 与 WBD 相同的字段结构。反过来说，如果哪天 WBD 改了它的格式，
 * 这里的导入功能会先失效——它只是「省去手抄坐标」的便利，
 * 死斗本身的运行不依赖 WBD 的文件。
 *
 * @author AlphaHu
 */
public final class ArenaLoader {

    private ArenaLoader() {
    }

    /**
     * 读出 {@code arenas.yml} 里的全部场地定义。
     *
     * <p>文件不存在或没有任何场地时返回空表，由调用方给出提示。
     * 解析过程中的问题会收集进 {@code problems}，一次性报给服主，
     * 而不是遇到第一个错误就中断——配置错三处却要重启三次才能改完，那太折磨人了。
     *
     * @param problems 收集到的可读问题描述（可为 null）
     */
    public static Map<String, TdmArena> load(File file, List<String> problems) {
        Map<String, TdmArena> arenas = new LinkedHashMap<>();

        if (!file.exists()) {
            if (problems != null) {
                problems.add("找不到竞技场文件：" + file.getPath());
            }
            return arenas;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("arenas");
        if (root == null) {
            if (problems != null) {
                problems.add("竞技场文件里没有 arenas 段落：" + file.getPath());
            }
            return arenas;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                if (problems != null) {
                    problems.add("场地 " + id + " 不是一个配置段落，已跳过");
                }
                continue;
            }
            TdmArena arena = readArena(id, section, problems);
            if (arena != null) {
                arenas.put(id, arena);
            }
        }
        return arenas;
    }

    /**
     * 从一个外部文件（例如 WarZBombDefuse 的 {@code arenas.yml}）里读一个场地，
     * 并沿用源文件里的 id。
     *
     * @param sourceId 源文件里的场地 id
     * @param file     源文件
     * @return 读不到时返回 null
     */
    public static TdmArena readArena(String sourceId, File file, List<String> problems) {
        return readArena(sourceId, sourceId, file, problems);
    }

    /**
     * 从一个外部文件里读一个场地，并挂到另一个 id 下。
     *
     * <p>两个 id 分开是必要的：把 WBD 的 {@code dust2} 导入成死斗的 {@code tdm_dust2}
     * 是很自然的诉求，而如果只按源 id 建对象，导入后场地仍叫 {@code dust2}，
     * {@code /tdm enable tdm_dust2} 会找不到它。
     *
     * @param sourceId 源文件里的场地 id（数据从这里读）
     * @param targetId 导入后在死斗里使用的 id（对象用这个构造）
     */
    public static TdmArena readArena(String sourceId, String targetId, File file, List<String> problems) {
        if (!file.exists()) {
            if (problems != null) {
                problems.add("找不到源文件：" + file.getPath());
            }
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("arenas." + sourceId);
        if (section == null) {
            if (problems != null) {
                problems.add("源文件里没有场地 " + sourceId);
            }
            return null;
        }
        return readArena(targetId, section, problems);
    }

    /**
     * 解析购买区。
     *
     * <p>格式：{@code world,x,y,z,yaw,pitch,radius}，与 WarZBombDefuse 一致。
     * yaw 与 pitch 对球形区域没有意义，读进来只为兼容那份配置的格式 ——
     * 服主从 WBD 的 arenas.yml 里直接抄一行过来就能用，不必重新理解一套规则。
     */
    private static void readBuyZones(ConfigurationSection section, TdmArena arena,
                                     String id, List<String> problems) {
        if (section == null) {
            return;
        }
        for (String teamId : section.getKeys(false)) {
            for (String raw : section.getStringList(teamId)) {
                BuyZone zone = parseBuyZone(raw, id, teamId, problems);
                if (zone != null) {
                    arena.addBuyZone(teamId, zone);
                }
            }
        }
    }

    private static BuyZone parseBuyZone(String raw, String id, String teamId,
                                        List<String> problems) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 7) {
            if (problems != null) {
                problems.add("场地 " + id + " 的 buy-zones." + teamId
                        + " 格式不对（需要 world,x,y,z,yaw,pitch,radius）：" + raw);
            }
            return null;
        }
        World world = Bukkit.getWorld(parts[0].trim());
        if (world == null) {
            if (problems != null) {
                problems.add("场地 " + id + " 的购买区所在世界不存在：" + parts[0].trim());
            }
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            double radius = Double.parseDouble(parts[6].trim());
            if (radius <= 0) {
                if (problems != null) {
                    problems.add("场地 " + id + " 的购买区半径必须大于 0：" + raw);
                }
                return null;
            }
            return new BuyZone(world, x, y, z, radius);
        } catch (NumberFormatException e) {
            if (problems != null) {
                problems.add("场地 " + id + " 的购买区坐标不是数字：" + raw);
            }
            return null;
        }
    }

    /**
     * 用 WarZBombDefuse 那边的场地名补全死斗这边的显示名。
     *
     * <p>解决的是这个尴尬：WBD 里的场地 id 往往是 {@code tdm_ship} 这类
     * 标识符，而它的 {@code name} 才是「运输船」这种人看的名字。
     * 导入时如果那边还没配 name，死斗这边也只能拿 id 顶着 ——
     * 但服主之后在 WBD 里补上名字时，不该需要重新导入一遍。
     *
     * <p>只在「死斗这边的名字还等于自己的 id」时才动手，也就是说：
     * 服主一旦自己取过名字（例如「运输船（死斗）」），这里就不碰它。
     * 覆盖自定义名字比留着一个不好看的默认值糟糕得多。
     *
     * @return 补了几个
     */
    public static int applyWbdNames(Map<String, TdmArena> arenas, File wbdFile) {
        if (arenas == null || arenas.isEmpty() || wbdFile == null || !wbdFile.exists()) {
            return 0;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(wbdFile)
                .getConfigurationSection("arenas");
        if (root == null) {
            return 0;
        }

        int filled = 0;
        for (TdmArena arena : arenas.values()) {
            if (!needsName(arena)) {
                continue;
            }
            // sourceArena 是导入时记下的 WBD 侧 id。没导入过的场地没有它，
            // 也就无从补起 —— 那属于「自己量出来的场地」，名字由服主自己定
            String sourceId = arena.getSourceArena();
            if (sourceId == null || sourceId.isBlank()) {
                continue;
            }
            ConfigurationSection node = root.getConfigurationSection(sourceId);
            if (node == null) {
                continue;
            }
            String wbdName = node.getString("name");
            if (wbdName == null || wbdName.isBlank() || wbdName.equals(sourceId)) {
                continue;
            }
            arena.setName(wbdName + "（死斗）");
            filled++;
        }
        return filled;
    }

    /** 这个名字是不是「还没人取过」（空、或等于自己的 id）。 */
    private static boolean needsName(TdmArena arena) {
        String name = arena.getName();
        return name == null || name.isBlank() || name.equals(arena.getId());
    }

    /**
     * 从 arenas.yml 里删掉一个场地。
     *
     * @return 是否确实删掉了
     */
    public static boolean deleteArena(File file, String arenaId) {
        if (file == null || !file.exists() || arenaId == null) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String base = "arenas." + arenaId;
        if (!yaml.contains(base)) {
            return false;
        }
        yaml.set(base, null);
        try {
            yaml.save(file);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** 解析单个场地段落。WBD 的段落比死斗多出爆破点/购买区等字段，这里一律忽略。 */
    private static TdmArena readArena(String id, ConfigurationSection section, List<String> problems) {
        TdmArena arena = new TdmArena(id);
        arena.setEnabled(section.getBoolean("enabled", false));
        arena.setName(section.getString("name", id));
        arena.setMinPlayers(section.getInt("min-players", -1));
        arena.setMaxPlayers(section.getInt("max-players", -1));
        arena.setTargetKills(section.contains("target-kills") ? section.getInt("target-kills") : -1);
        arena.setTimeLimitSeconds(section.contains("time-limit-seconds")
                ? section.getInt("time-limit-seconds") : -1);
        arena.setCountdownSeconds(section.contains("countdown-seconds")
                ? section.getInt("countdown-seconds") : -1);
        arena.setEndDelaySeconds(section.contains("end-delay-seconds")
                ? section.getInt("end-delay-seconds") : -1);
        if (section.contains("auto-restart")) {
            arena.setAutoRestart(section.getBoolean("auto-restart"));
        }
        // 不排位开关。默认 false（结算段位）—— 让「什么都不写」的场地
        // 也参与排位，比反过来更符合预期：想要训练场的服主会主动去写这一行
        arena.setUnrank(section.getBoolean("unrank", false));

        // 购买区。格式与 WarZBombDefuse 完全一致，方便那份配置直接抄过来
        readBuyZones(section.getConfigurationSection("buy-zones"), arena, id, problems);

        String lobbyRaw = section.getString("lobby");
        if (lobbyRaw != null) {
            arena.setLobby(LocationCodec.parse(lobbyRaw));
            if (arena.getLobby() == null && problems != null) {
                problems.add("场地 " + id + " 的 lobby 坐标无法解析：" + lobbyRaw);
            }
        }

        arena.setBoundary(
                section.getString("boundary.world"),
                section.getString("boundary.min"),
                section.getString("boundary.max"));

        List<String> tRaw = section.getStringList("t-spawns");
        List<String> ctRaw = section.getStringList("ct-spawns");
        arena.getSpawns(alphahu.tdm.game.TdmTeam.T).addAll(LocationCodec.parseAll(tRaw));
        arena.getSpawns(alphahu.tdm.game.TdmTeam.CT).addAll(LocationCodec.parseAll(ctRaw));

        if (problems != null) {
            if (arena.getSpawns(alphahu.tdm.game.TdmTeam.T).size() < tRaw.size()) {
                problems.add("场地 " + id + " 有 "
                        + (tRaw.size() - arena.getSpawns(alphahu.tdm.game.TdmTeam.T).size())
                        + " 个 t-spawns 坐标无法解析");
            }
            if (arena.getSpawns(alphahu.tdm.game.TdmTeam.CT).size() < ctRaw.size()) {
                problems.add("场地 " + id + " 有 "
                        + (ctRaw.size() - arena.getSpawns(alphahu.tdm.game.TdmTeam.CT).size())
                        + " 个 ct-spawns 坐标无法解析");
            }
        }

        arena.getFireworks().addAll(LocationCodec.parseAll(section.getStringList("fireworks")));

        // 记下来源：导入时允许改 id，之后要拿它去 WBD 侧禁用同名场地
        arena.setSourceArena(section.getString("imported-from"));
        return arena;
    }

    /** 收集所有「启用了但不可用」的场地，用于启动时统一告警。 */
    public static List<String> collectValidationProblems(List<TdmArena> arenas) {
        List<String> problems = new ArrayList<>();
        for (TdmArena arena : arenas) {
            if (!arena.isEnabled()) {
                continue;
            }
            if (!arena.isValid()) {
                problems.add("场地 " + arena.getId() + " 已启用但无法使用："
                        + String.join("；", arena.validationProblems()));
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // 回写
    // ------------------------------------------------------------------

    /**
     * 写回文件时固定加在开头的说明。
     *
     * <p>为什么需要它：{@link YamlConfiguration} 的读写会**丢掉文件里原有的全部注释** ——
     * 它只保留数据，注释在解析阶段就已经不存在了。
     * 所以只要插件写过一次这个文件，服主原本看到的说明就会消失。
     *
     * <p>既然注释留不住，就改成每次保存都重新写入一段固定的头部说明：
     * 至少打开文件的人还能知道坐标格式是什么、该用哪些命令来改。
     * 同时明确告知「手写的注释不会保留」，免得有人认真注释了一番之后
     * 发现全没了，还以为是插件出了 bug。
     */
    private static final String FILE_HEADER = String.join("\n",
            "# ============================================================",
            "#  WBDTeamDeathmatch 竞技场配置",
            "# ============================================================",
            "# 坐标格式（与 WarZBombDefuse 的 arenas.yml 完全一致）：",
            "#     world,x,y,z,yaw,pitch",
            "# 边界格式：",
            "#     boundary.world / min: \"x,y,z\" / max: \"x,y,z\"",
            "#",
            "# 推荐在游戏里改，而不是手写本文件：",
            "#     /tdm import <wbd场地id>   从 WarZBombDefuse 导入已量好的坐标",
            "#     /tdm enable <场地id>      启用场地（并自动在 WBD 侧禁用同名场地）",
            "#     /tdm disable <场地id>     停用场地",
            "#     /tdm reload               让改动生效",
            "#",
            "# ⚠ 插件保存时会重写本文件，你手动添加的注释不会保留。",
            "#   需要写说明请放在 README 或 config.yml 里。",
            "# ============================================================",
            "");

    /**
     * 把一个场地写进 {@code arenas.yml}（已存在则覆盖同名段落）。
     *
     * <p>供 {@code /tdm import} 使用。写进去的场地一律先设为
     * {@code enabled: false}：导入的坐标是否正确，只有服主自己知道，
     * 直接启用可能让一个坐标写错的场地立刻把人传进虚空。
     * 核对之后再执行 {@code /tdm enable <场地id>} 打开，成本远低于事后排查。
     *
     * @return 写入成功返回 true
     */
    public static boolean saveArena(File file, TdmArena arena) {
        YamlConfiguration yaml = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        String base = "arenas." + arena.getId();
        // 先清掉旧段落，避免上一次导入残留的键（例如已经删掉的多余出生点）继续生效
        yaml.set(base, null);

        yaml.set(base + ".enabled", false);
        yaml.set(base + ".name", arena.getName());
        if (arena.getLobby() != null) {
            yaml.set(base + ".lobby", LocationCodec.serialize(arena.getLobby()));
        }
        if (arena.getBoundaryWorldName() != null) {
            yaml.set(base + ".boundary.world", arena.getBoundaryWorldName());
            yaml.set(base + ".boundary.min", join(arena.getBoundaryMin()));
            yaml.set(base + ".boundary.max", join(arena.getBoundaryMax()));
        }
        yaml.set(base + ".t-spawns", serializeAll(arena.getSpawns(TdmTeam.T)));
        yaml.set(base + ".ct-spawns", serializeAll(arena.getSpawns(TdmTeam.CT)));
        yaml.set(base + ".fireworks", serializeAll(arena.getFireworks()));
        if (arena.getSourceArena() != null) {
            // 供 /tdm enable 找到 WBD 侧真正该禁用的那个场地
            yaml.set(base + ".imported-from", arena.getSourceArena());
        }

        return write(file, yaml);
    }

    /**
     * 开/关一个场地。
     *
     * <p>只动 {@code enabled} 这一个键，其余数据原样保留。
     *
     * @return 文件不存在、或里面没有这个场地时返回 false
     */
    public static boolean setEnabled(File file, String arenaId, boolean enabled) {
        if (!file.exists()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String base = "arenas." + arenaId;
        if (!yaml.isConfigurationSection(base)) {
            return false;
        }
        yaml.set(base + ".enabled", enabled);
        return write(file, yaml);
    }

    /** 把 YAML 连同一段固定的头部说明写回磁盘。 */
    private static boolean write(File file, YamlConfiguration yaml) {
        try {
            java.nio.file.Files.write(file.toPath(),
                    (FILE_HEADER + yaml.saveToString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> serializeAll(List<org.bukkit.Location> locations) {
        List<String> out = new ArrayList<>();
        for (org.bukkit.Location location : locations) {
            String text = LocationCodec.serialize(location);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private static String join(double[] vector) {
        if (vector == null) return "";
        return vector[0] + "," + vector[1] + "," + vector[2];
    }
}

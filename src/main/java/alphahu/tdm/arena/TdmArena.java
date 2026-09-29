package alphahu.tdm.arena;

import alphahu.tdm.config.TdmSettings;
import alphahu.tdm.game.TdmTeam;
import alphahu.tdm.util.LocationCodec;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 一个死斗场地的静态定义。
 *
 * <p>字段结构与 WarZBombDefuse 的 {@code ArenaConfig} 对齐（lobby / boundary /
 * 双方出生点列表），坐标串格式也完全一致，好处是服主在 WBD 里量好的点
 * 可以直接搬过来用 {@code /tdm import} 导入。
 *
 * <p>与 WBD 不同的是这里没有爆破点、购买区、炸弹倒计时之类的东西——
 * 死斗的胜负完全由击杀数与时间决定。
 *
 * @author AlphaHu
 */
public final class TdmArena {

    private final String id;
    private String name;
    private boolean enabled;

    /** 覆盖全局值的本地设置；{@code -1} / {@code null} 表示不覆盖。 */
    private int minPlayers = -1;
    private int maxPlayers = -1;
    private int targetKills = -1;
    private int timeLimitSeconds = -1;
    private int countdownSeconds = -1;
    private int endDelaySeconds = -1;
    private Boolean autoRestart = null;

    /**
     * 本场地是否**不**结算段位。
     *
     * <p>给训练场、娱乐场、活动场用：这些场地上的输赢不该影响玩家的排位分，
     * 否则新手练习或在活动里乱打都会掉段。默认 {@code false}（即默认结算）。
     */
    private boolean unrank;

    private Location lobby;
    private final List<Location> tSpawns = new ArrayList<>();
    private final List<Location> ctSpawns = new ArrayList<>();
    private final List<Location> fireworks = new ArrayList<>();

    /**
     * 本场地是从哪个 WarZBombDefuse 场地导入的，null 表示不是导入来的。
     *
     * <p>需要记这一笔是因为导入时允许改 id：{@code /tdm import dust2 tdm_dust2}
     * 之后，死斗这边叫 {@code tdm_dust2}，而 WBD 那边仍然叫 {@code dust2}。
     * 如果只按死斗的 id 去 WBD 里找同名场地，就会得出「WBD 里没有这个场地」
     * 的结论，从而漏掉真正需要处理的冲突 —— 两个模式其实在抢同一个物理场地。
     */
    private String sourceArena;

    /**
     * 购买区，按阵营 id（{@code T} / {@code CT}）分组。
     *
     * <p>玩家只有站在**自己阵营**的购买区里才能打开商店。
     * 不配置的阵营不受位置限制 —— 这是为了与 WarZBombDefuse 的行为对齐，
     * 那边的注释就写着「留空表示该队任意位置都可购买」。
     * 想让「没配也不许买」生效，把 config.yml 的 shop.buy-zone-required 打开。
     */
    private final Map<String, List<BuyZone>> buyZones = new HashMap<>();

    // 边界。world 为 null 表示没有配置边界，此时不做越界限制。
    private World boundaryWorld;
    private double minX;
    private double minY;
    private double minZ;
    private double maxX;
    private double maxY;
    private double maxZ;

    public TdmArena(String id) {
        this.id = id;
    }

    // ------------------------------------------------------------------
    // 校验与规则
    // ------------------------------------------------------------------

    /**
     * 场地是否可用。
     *
     * <p>死斗的最低要求只有双方出生点：没有大厅最多是开局时人站在原地，
     * 没有边界最多是能跑出场地，都不算致命；但没有出生点就根本没有
     * 「无限复活」可言，所以只有这一项是硬性门槛。
     */
    public boolean isValid() {
        return !tSpawns.isEmpty() && !ctSpawns.isEmpty();
    }

    /** 列出还缺什么，用于启动时给出可操作的报错，而不是干巴巴一句「配置无效」。 */
    public List<String> validationProblems() {
        List<String> problems = new ArrayList<>();
        if (tSpawns.isEmpty()) {
            problems.add("缺少 t-spawns（T 方出生点）");
        }
        if (ctSpawns.isEmpty()) {
            problems.add("缺少 ct-spawns（CT 方出生点）");
        }
        if (lobby == null) {
            problems.add("缺少 lobby（等待大厅），玩家加入时会停在原地");
        }
        if (boundaryWorld == null) {
            problems.add("缺少 boundary（场地边界），玩家不会被限制在场地内");
        }
        return problems;
    }

    /** 把全局设置与场地覆盖合并成这场比赛的规则。 */
    public ArenaRules resolveRules(TdmSettings settings) {
        return new ArenaRules(
                targetKills >= 0 ? targetKills : settings.targetKills,
                timeLimitSeconds >= 0 ? timeLimitSeconds : settings.timeLimitSeconds,
                countdownSeconds >= 0 ? countdownSeconds : settings.countdownSeconds,
                endDelaySeconds >= 0 ? endDelaySeconds : settings.endDelaySeconds,
                autoRestart != null ? autoRestart : settings.autoRestart,
                maxPlayers > 0 ? Math.min(pick(minPlayers, settings.minPlayers), maxPlayers)
                               : pick(minPlayers, settings.minPlayers),
                maxPlayers > 0 ? maxPlayers : 20
        );
    }

    private int pick(int local, int global) {
        return local > 0 ? local : global;
    }

    // ------------------------------------------------------------------
    // 出生点与边界
    // ------------------------------------------------------------------

    /** 该阵营的出生点列表。 */
    public List<Location> spawnsFor(TdmTeam team) {
        return team == TdmTeam.T ? tSpawns : ctSpawns;
    }

    /**
     * 挑一个出生点。
     *
     * @param spawnMode {@code first} 时固定用列表第一个；其余情况随机
     * @return 没有配置出生点时返回 null
     */
    public Location pickSpawn(TdmTeam team, String spawnMode, Random random) {
        List<Location> spawns = spawnsFor(team);
        if (spawns.isEmpty()) {
            return null;
        }
        if ("first".equalsIgnoreCase(spawnMode) || spawns.size() == 1) {
            return spawns.get(0).clone();
        }
        return spawns.get(random.nextInt(spawns.size())).clone();
    }

    /** 是否配置了边界。 */
    public boolean hasBoundary() {
        return boundaryWorld != null;
    }

    /** 位置是否在场地内。未配置边界时一律视为在内。 */
    public boolean contains(Location location) {
        if (!hasBoundary() || location == null) {
            return true;
        }
        if (location.getWorld() == null || !location.getWorld().equals(boundaryWorld)) {
            return false;
        }
        return location.getX() >= minX && location.getX() <= maxX
                && location.getY() >= minY && location.getY() <= maxY
                && location.getZ() >= minZ && location.getZ() <= maxZ;
    }

    /**
     * 把越界的位置拉回边界内侧。
     *
     * <p>拉回时向内缩了半格到一格：正好落在边界坐标上的话，
     * 玩家贴边站会被反复判定越界，表现为人物在原地抖动。
     */
    public Location clamp(Location location) {
        if (!hasBoundary() || location == null) {
            return location;
        }
        double x = Math.max(minX + 0.5, Math.min(maxX - 0.5, location.getX()));
        double y = Math.max(minY + 1.0, Math.min(maxY - 1.0, location.getY()));
        double z = Math.max(minZ + 0.5, Math.min(maxZ - 0.5, location.getZ()));
        return new Location(boundaryWorld, x, y, z, location.getYaw(), location.getPitch());
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public String getId() {
        return id;
    }

    public String getName() {
        return name == null ? id : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 本场地是否结算段位。
     *
     * <p>对外只暴露这一个判断，而不是让调用方到处写 {@code !isUnrank()}：
     * 「不排位」是配置里的写法，「是否排位」是代码里的语义，两者反着，
     * 混用迟早会出现一个地方写反。
     */
    public boolean ranked() {
        return !unrank;
    }

    /** 配置里的原始开关，供写回 YAML 时使用。 */
    public boolean isUnrank() {
        return unrank;
    }

    public void setUnrank(boolean unrank) {
        this.unrank = unrank;
    }

    // ------------------------------------------------------------------
    // 购买区
    // ------------------------------------------------------------------

    /** 追加一个购买区。 */
    public void addBuyZone(String teamId, BuyZone zone) {
        if (teamId == null || zone == null) {
            return;
        }
        buyZones.computeIfAbsent(teamId.toUpperCase(java.util.Locale.ROOT), k -> new ArrayList<>())
                .add(zone);
    }

    /** 某阵营的购买区；没配置返回空表。 */
    public List<BuyZone> buyZonesFor(String teamId) {
        if (teamId == null) {
            return List.of();
        }
        return buyZones.getOrDefault(teamId.toUpperCase(java.util.Locale.ROOT), List.of());
    }

    /** 某阵营是否配了购买区。 */
    public boolean hasBuyZones(String teamId) {
        return !buyZonesFor(teamId).isEmpty();
    }

    /**
     * 玩家当前位置能否购买。
     *
     * <p>三种情况的取舍：
     * <ul>
     *   <li>配了购买区 → 必须站在区内。这与 WBD 一致，也是「不能想买就买」的实现</li>
     *   <li>没配 + {@code required=false} → 不限位置。默认值，为了兼容那些
     *       还没量购买区坐标的场地，否则它们会突然完全买不了东西</li>
     *   <li>没配 + {@code required=true} → 谁都不能买。给「必须配购买区」的服务器用</li>
     * </ul>
     *
     * @param required 没配购买区时是否也禁止购买
     */
    public boolean allowsPurchase(TdmTeam team, Location location, boolean required) {
        List<BuyZone> zones = buyZonesFor(team == null ? null : team.id());
        if (zones.isEmpty()) {
            return !required;
        }
        for (BuyZone zone : zones) {
            if (zone.contains(location)) {
                return true;
            }
        }
        return false;
    }

    public Location getLobby() {
        return lobby;
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby;
    }

    public List<Location> getFireworks() {
        return fireworks;
    }

    /** 来源的 WarZBombDefuse 场地 id，不是导入来的则返回 null。 */
    public String getSourceArena() {
        return sourceArena;
    }

    public void setSourceArena(String sourceArena) {
        this.sourceArena = sourceArena;
    }

    /**
     * 该场地在 WarZBombDefuse 侧对应的场地 id。
     *
     * <p>导入来的场地用记录下来的来源 id；手工配置的场地按同名处理
     * （服主手工配的时候通常两边会取一样的名字）。
     */
    public String resolveWbdArenaId() {
        return sourceArena != null ? sourceArena : id;
    }

    public List<Location> getSpawns(TdmTeam team) {
        return spawnsFor(team);
    }

    public void setMinPlayers(int minPlayers) {
        this.minPlayers = minPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public void setTargetKills(int targetKills) {
        this.targetKills = targetKills;
    }

    public void setTimeLimitSeconds(int timeLimitSeconds) {
        this.timeLimitSeconds = timeLimitSeconds;
    }

    public void setCountdownSeconds(int countdownSeconds) {
        this.countdownSeconds = countdownSeconds;
    }

    public void setEndDelaySeconds(int endDelaySeconds) {
        this.endDelaySeconds = endDelaySeconds;
    }

    public void setAutoRestart(Boolean autoRestart) {
        this.autoRestart = autoRestart;
    }

    /** 设置边界（world 为 null 时表示取消边界）。 */
    public void setBoundary(World world, double[] min, double[] max) {
        if (world == null || min == null || max == null) {
            this.boundaryWorld = null;
            return;
        }
        this.boundaryWorld = world;
        this.minX = Math.min(min[0], max[0]);
        this.minY = Math.min(min[1], max[1]);
        this.minZ = Math.min(min[2], max[2]);
        this.maxX = Math.max(min[0], max[0]);
        this.maxY = Math.max(min[1], max[1]);
        this.maxZ = Math.max(min[2], max[2]);
    }

    /** 设置边界的便捷重载：直接解析 {@code world} + 两个 {@code x,y,z} 串。 */
    public void setBoundary(String worldName, String min, String max) {
        setBoundary(LocationCodec.resolveWorld(worldName),
                LocationCodec.parseVector3(min),
                LocationCodec.parseVector3(max));
    }

    /** 边界所在世界名，未配置边界时返回 null。用于把场地回写到配置文件。 */
    public String getBoundaryWorldName() {
        return boundaryWorld == null ? null : boundaryWorld.getName();
    }

    /** 边界最小点 {x, y, z}，未配置时返回 null。 */
    public double[] getBoundaryMin() {
        return boundaryWorld == null ? null : new double[]{minX, minY, minZ};
    }

    /** 边界最大点 {x, y, z}，未配置时返回 null。 */
    public double[] getBoundaryMax() {
        return boundaryWorld == null ? null : new double[]{maxX, maxY, maxZ};
    }
}

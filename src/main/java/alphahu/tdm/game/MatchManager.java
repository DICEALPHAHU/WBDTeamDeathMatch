package alphahu.tdm.game;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.arena.ArenaLoader;
import alphahu.tdm.arena.TdmArena;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 所有场地与比赛的总管。
 *
 * <p>职责有三个：加载 {@code arenas.yml}、按场地建立比赛实例、每 tick 推进它们。
 * 这里刻意让「场地」与「比赛」一一对应：一个场地同时只跑一场死斗，
 * 想开第二场就再配一个场地。多实例共享同一份出生点会让复活位置互相打架，
 * 收益却很小，不值得为此增加复杂度。
 *
 * @author AlphaHu
 */
public final class MatchManager {

    private final WBDTeamDeathmatch plugin;

    /** 场地定义，按配置文件里的顺序。 */
    private final Map<String, TdmArena> arenas = new LinkedHashMap<>();

    /** 场地 id → 比赛实例。 */
    private final Map<String, TdmMatch> matches = new LinkedHashMap<>();

    /**
     * 维护模式：单人也能开局，且不结算段位、不因为对面没人自动判负。
     *
     * <p>开关由 {@code /tdm maintenance} 控制，只存内存、重启即失效 ——
     * 免得某次维护忘了关，服务器就带着「一人开局」的规则默默跑下去。
     */
    private boolean maintenance;

    public MatchManager(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    public boolean isMaintenance() {
        return maintenance;
    }

    public void setMaintenance(boolean value) {
        this.maintenance = value;
    }

    // ------------------------------------------------------------------
    // 加载与重载
    // ------------------------------------------------------------------

    /**
     * 重新读取竞技场文件并重建全部比赛。
     *
     * <p>重建前会先把场上的人送出去并还原状态——否则重载会把一批玩家
     * 从「正在参赛」变成「无主游魂」：背包还是出生装备、人还在场地里，
     * 而比赛对象已经不在管理表里了。
     */
    public void loadArenas() {
        clearAll();

        arenas.clear();
        matches.clear();

        File file = new File(plugin.getDataFolder(), "arenas.yml");
        List<String> problems = new ArrayList<>();
        arenas.putAll(ArenaLoader.load(file, problems));

        // 从 WarZBombDefuse 那边补显示名。放在这里而不是导入时：服主之后
        // 在 WBD 里给场地补了 name，一次 /tdm reload 就能读过来，
        // 不必把场地重新导入一遍
        int named = ArenaLoader.applyWbdNames(arenas,
                plugin.resolveServerFile(plugin.getSettings().wbdArenasFile));
        if (named > 0) {
            plugin.getLogger().info("已从 WarZBombDefuse 读取到 " + named + " 个场地的显示名。");
        }

        for (TdmArena arena : arenas.values()) {
            matches.put(arena.getId(), new TdmMatch(plugin, arena));
        }

        reportProblems(problems);
    }

    private void reportProblems(List<String> problems) {
        for (String problem : problems) {
            plugin.getLogger().warning(problem);
        }

        long enabled = arenas.values().stream().filter(TdmArena::isEnabled).count();
        if (arenas.isEmpty()) {
            plugin.getLogger().warning("没有配置任何死斗场地，模式不会启动。"
                    + "请编辑 plugins/WBDTeamDeathmatch/arenas.yml，"
                    + "或用 /tdm import <wbd竞技场id> 从 WarZBombDefuse 导入坐标。");
            return;
        }
        if (enabled == 0) {
            plugin.getLogger().warning("共有 " + arenas.size()
                    + " 个场地，但没有一个设成 enabled: true，模式不会启动。");
        } else {
            plugin.getLogger().info("已加载 " + arenas.size() + " 个死斗场地，其中 "
                    + enabled + " 个已启用。");
        }

        for (String problem : ArenaLoader.collectValidationProblems(new ArrayList<>(arenas.values()))) {
            plugin.getLogger().warning(problem);
        }

        // 缺 lobby / boundary 不会让场地「不可用」，所以 collectValidationProblems
        // 不管它们，但它们对体验的影响很具体：没有 lobby，比赛结束后玩家不会回到
        // 大厅等待，而是被留在上一场的交火位置 —— 那看起来就像「回合结束了却没结束」。
        for (TdmArena arena : arenas.values()) {
            if (!arena.isEnabled() || !arena.isValid()) {
                continue;
            }
            if (arena.getLobby() == null) {
                plugin.getLogger().warning("场地 " + arena.getId()
                        + " 没有配置 lobby。比赛结束后玩家会回到各自出生点而不是大厅等待，"
                        + "建议补上大厅坐标（可用 /wbd setup <id> lobby 量取后重新导入）。");
            }
            if (!arena.hasBoundary()) {
                plugin.getLogger().warning("场地 " + arena.getId()
                        + " 没有配置 boundary，玩家不会被限制在场地内。");
            }
        }

        if (plugin.getSettings().warnArenaConflict) {
            warnArenaConflicts();
        }
    }

    /**
     * 检查场地是否与 WarZBombDefuse 的启用竞技场撞名。
     *
     * <p>撞名的后果很严重却很难看出来：WBD 会同时在这个场地上推回合、发炸弹、
     * 判全灭，而死斗在搞无限复活，两边各改各的，表现成「玩家莫名其妙变成旁观者」
     * 「复活后又被传走」。宁可启动时吵一句，也别让服主去猜。
     */
    private void warnArenaConflicts() {
        File wbdFile = new File(plugin.getDataFolder().getParentFile(),
                "WarZBombDefuse/arenas.yml");
        if (!wbdFile.exists()) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(wbdFile);

        for (TdmArena arena : arenas.values()) {
            if (!arena.isEnabled()) continue;
            // 用场地记录的 WBD 来源 id：导入时可能改过名，
            // 死斗这边叫 tdm_dust2，WBD 那边仍然叫 dust2
            String wbdId = arena.resolveWbdArenaId();
            String path = "arenas." + wbdId;
            if (!yaml.isConfigurationSection(path)) continue;
            if (!yaml.getBoolean(path + ".enabled", false)) continue;

            plugin.getLogger().warning("场地 " + arena.getId()
                    + " 在 WarZBombDefuse 侧（场地 id：" + wbdId + "）处于启用状态。"
                    + "两个模式会互相干扰（WBD 会推回合、发炸弹、判全灭），"
                    + "请在 WBD 侧关闭它，或直接执行 /tdm enable " + arena.getId()
                    + " 由本插件代为处理。");
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Collection<TdmArena> getArenas() {
        return arenas.values();
    }

    public TdmArena getArena(String id) {
        return id == null ? null : arenas.get(id);
    }

    public Collection<TdmMatch> getMatches() {
        return matches.values();
    }

    /** 已启用、且配置完整的场地对应的比赛。 */
    public List<TdmMatch> getPlayableMatches() {
        List<TdmMatch> out = new ArrayList<>();
        for (TdmMatch match : matches.values()) {
            if (match.getArena().isEnabled() && match.getArena().isValid()) {
                out.add(match);
            }
        }
        return out;
    }

    public TdmMatch getMatch(String arenaId) {
        return arenaId == null ? null : matches.get(arenaId);
    }

    /** 玩家所在的比赛，不在任何比赛中时返回 null。 */
    public TdmMatch getMatch(Player player) {
        if (player == null) {
            return null;
        }
        for (TdmMatch match : matches.values()) {
            if (match.contains(player)) {
                return match;
            }
        }
        return null;
    }

    /** 找玩家当前可以加入的比赛，找不到时返回 null。 */
    public TdmMatch pickForJoin() {
        TdmMatch best = null;
        for (TdmMatch match : getPlayableMatches()) {
            if (best == null || match.size() < best.size()) {
                best = match;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 运行
    // ------------------------------------------------------------------

    /** 推进所有比赛。由插件主任务每 tick 调用。 */
    public void tick() {
        for (TdmMatch match : matches.values()) {
            try {
                match.tick();
            } catch (Throwable t) {
                // 单个场地的异常不该让其他场地一起停摆——那会表现为
                // 「开了一个新场地之后，原来好好的场地也不动了」
                plugin.getLogger().severe("推进场地 " + match.getArena().getId()
                        + " 的比赛时出错：" + t);
            }
        }
    }

    /** 把所有比赛里的人清出去并还原状态（重载、关服时调用）。 */
    public void clearAll() {
        for (TdmMatch match : matches.values()) {
            try {
                match.clearAll();
            } catch (Throwable t) {
                plugin.getLogger().warning("清理场地 " + match.getArena().getId()
                        + " 时出错：" + t);
            }
        }
    }

    public void shutdown() {
        clearAll();
        matches.clear();
        arenas.clear();
    }
}

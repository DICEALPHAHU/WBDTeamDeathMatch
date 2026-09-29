package alphahu.tdm.ranked;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 段位数据的 SQLite 存储。
 *
 * <p>为什么是 SQLite：段位和局内金钱有本质区别 —— 金钱每场重置，丢了无所谓；
 * 段位是跨场持久数据，丢一次就是玩家几周的进度，而且还要支持赛季回滚和
 * 「我这局为什么 −24 分」的历史查询。YAML 在几十人时够用，但它每次读写都要
 * 全量解析，且没有事务，崩服时正好写到一半就废了。
 *
 * <p>驱动不打包进 jar：Paper/Spigot/Arclight 都在 {@code libraries/} 里自带
 * {@code org.xerial:sqlite-jdbc}（本机服务端是 3.42.0.0）。所以这里显式
 * {@code Class.forName} 加载一次，失败就整体降级为「段位不可用」，
 * 而不是让 {@code DriverManager} 抛一句不提驱动的 "No suitable driver"。
 *
 * <p>所有方法都是 {@code synchronized}：SQLite 的单连接不是线程安全的，
 * 而落库发生在异步线程、读取发生在主线程。锁住整个 store 是这里最简单
 * 也最不容易出错的正确做法 —— 段位操作频率极低，不会成为瓶颈。
 *
 * @author AlphaHu
 */
public final class RankedStore {

    private static final String CREATE_PLAYERS = """
            CREATE TABLE IF NOT EXISTS ranked_players (
              uuid         TEXT PRIMARY KEY,
              name         TEXT    NOT NULL DEFAULT '',
              rp           INTEGER NOT NULL DEFAULT 0,
              season       INTEGER NOT NULL DEFAULT 1,
              shield_left  INTEGER NOT NULL DEFAULT 0,
              played       INTEGER NOT NULL DEFAULT 0,
              wins         INTEGER NOT NULL DEFAULT 0,
              losses       INTEGER NOT NULL DEFAULT 0,
              draws        INTEGER NOT NULL DEFAULT 0,
              updated_at   INTEGER NOT NULL DEFAULT 0
            )""";

    private static final String CREATE_META = """
            CREATE TABLE IF NOT EXISTS ranked_meta (
              key   TEXT PRIMARY KEY,
              value TEXT
            )""";

    /**
     * 比赛记录表。
     *
     * <p>为什么在「文件战报」之外还要存一份库：{@code /tdm matchrp <玩家>}
     * 要按玩家查最近几十场，而那意味着扫描整个 {@code reports/} 目录并解析
     * 每一份 YAML —— 几百份文件就是几百次磁盘 IO 加 YAML 解析，
     * 在一个玩家随手敲的命令里做这件事是不可接受的。
     */
    private static final String CREATE_MATCHES = """
            CREATE TABLE IF NOT EXISTS tdm_matches (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              arena_id   TEXT    NOT NULL DEFAULT '',
              arena_name TEXT    NOT NULL DEFAULT '',
              ranked     INTEGER NOT NULL DEFAULT 0,
              started_at INTEGER NOT NULL DEFAULT 0,
              ended_at   INTEGER NOT NULL DEFAULT 0,
              winner     TEXT,
              t_score    INTEGER NOT NULL DEFAULT 0,
              ct_score   INTEGER NOT NULL DEFAULT 0,
              end_reason TEXT
            )""";

    private static final String CREATE_RESULTS = """
            CREATE TABLE IF NOT EXISTS tdm_results (
              match_id    INTEGER NOT NULL,
              uuid        TEXT    NOT NULL,
              name        TEXT    NOT NULL DEFAULT '',
              team        TEXT    NOT NULL DEFAULT 'T',
              kills       INTEGER NOT NULL DEFAULT 0,
              deaths      INTEGER NOT NULL DEFAULT 0,
              best_streak INTEGER NOT NULL DEFAULT 0,
              money_left  INTEGER NOT NULL DEFAULT 0,
              late_join   INTEGER NOT NULL DEFAULT 0,
              rp_before   INTEGER NOT NULL DEFAULT 0,
              rp_after    INTEGER NOT NULL DEFAULT 0,
              tier_after  TEXT    NOT NULL DEFAULT '',
              promoted    INTEGER NOT NULL DEFAULT 0,
              demoted     INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (match_id, uuid)
            )""";

    /** 按玩家查历史是这个功能的主查询路径，没有索引会退化成全表扫描。 */
    private static final String CREATE_RESULTS_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_tdm_results_uuid ON tdm_results(uuid)";

    private static final String UPSERT = """
            INSERT INTO ranked_players
              (uuid, name, rp, season, shield_left, played, wins, losses, draws, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET
              name        = excluded.name,
              rp          = excluded.rp,
              season      = excluded.season,
              shield_left = excluded.shield_left,
              played      = excluded.played,
              wins        = excluded.wins,
              losses      = excluded.losses,
              draws       = excluded.draws,
              updated_at  = excluded.updated_at""";

    private final Logger logger;
    private Connection connection;

    public RankedStore(Logger logger) {
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 打开数据库并建表。
     *
     * @return 是否可用。false 表示驱动缺失或文件打不开，调用方应禁用段位功能
     */
    public synchronized boolean open(File file) {
        close();

        try {
            // 显式加载。失败时能给出「驱动缺失」这个准确结论
            Class.forName("org.sqlite.JDBC");
        } catch (Throwable t) {
            logger.warning("找不到 SQLite 驱动（org.sqlite.JDBC），段位功能将不可用。"
                    + "服务端通常自带该驱动；若确实没有，请把 sqlite-jdbc 放进服务端 libraries/。");
            return false;
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("无法创建段位数据库目录：" + parent.getPath());
                return false;
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                // WAL：写不阻塞读。段位是「主线程读、异步线程写」的模式，
                // 默认的 rollback journal 会让两边互相等
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.executeUpdate(CREATE_PLAYERS);
                st.executeUpdate(CREATE_META);
                st.executeUpdate(CREATE_MATCHES);
                st.executeUpdate(CREATE_RESULTS);
                st.executeUpdate(CREATE_RESULTS_INDEX);
            }
            logger.info("段位数据库已就绪：" + file.getPath());
            return true;
        } catch (Throwable t) {
            logger.warning("打开段位数据库失败：" + t);
            close();
            return false;
        }
    }

    public synchronized boolean isAvailable() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败没有补救手段，也不影响进程退出
            }
            connection = null;
        }
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /** 读出全部档案。失败返回空表（而不是抛异常）—— 段位丢了不该拖垮比赛。 */
    public synchronized Map<UUID, RankedProfile> loadAll() {
        Map<UUID, RankedProfile> out = new HashMap<>();
        if (!isAvailable()) {
            return out;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM ranked_players")) {
            while (rs.next()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(rs.getString("uuid"));
                } catch (IllegalArgumentException e) {
                    // 主键不是合法 UUID 说明有人手改过库，跳过这一行继续读其他的
                    continue;
                }
                out.put(uuid, new RankedProfile(uuid,
                        rs.getString("name"),
                        rs.getInt("rp"),
                        rs.getInt("season"),
                        rs.getInt("shield_left"),
                        rs.getInt("played"),
                        rs.getInt("wins"),
                        rs.getInt("losses"),
                        rs.getInt("draws")));
            }
        } catch (SQLException e) {
            logger.warning("读取段位数据失败：" + e);
        }
        return out;
    }

    /**
     * 写回一条档案（仅当它是脏的）。
     *
     * @return 是否确实写入
     */
    public synchronized boolean save(RankedProfile profile) {
        return saveAll(java.util.List.of(profile)) > 0;
    }

    /**
     * 批量写回，单事务。
     *
     * <p>一局结束会同时更新十几个人的档案，逐条提交意味着一局一次 fsync。
     * 放进一个事务里，要么全成功要么全回滚，不会出现「一半人结算了、
     * 另一半没结算」这种最让人困惑的状态。
     *
     * @return 实际写入的条数；-1 表示失败
     */
    public synchronized int saveAll(Collection<RankedProfile> profiles) {
        if (!isAvailable()) {
            return -1;
        }
        long now = System.currentTimeMillis() / 1000L;
        int written = 0;
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
                for (RankedProfile profile : profiles) {
                    if (profile == null || !profile.dirty()) {
                        continue;
                    }
                    ps.setString(1, profile.uuid().toString());
                    ps.setString(2, profile.name() == null ? "" : profile.name());
                    ps.setInt(3, profile.rp());
                    ps.setInt(4, profile.season());
                    ps.setInt(5, profile.shieldLeft());
                    ps.setInt(6, profile.played());
                    ps.setInt(7, profile.wins());
                    ps.setInt(8, profile.losses());
                    ps.setInt(9, profile.draws());
                    ps.setLong(10, now);
                    ps.addBatch();
                    written++;
                }
                if (written > 0) {
                    ps.executeBatch();
                }
            }
            connection.commit();
            return written;
        } catch (SQLException e) {
            logger.warning("写入段位数据失败，本次改动将丢失：" + e);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // 回滚都失败了，除了记一笔没有别的办法
            }
            return -1;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 恢复自动提交失败会在下次写入时暴露出来
            }
        }
    }

    /** 读一个元数据值。 */
    public synchronized String meta(String key, String fallback) {
        if (!isAvailable()) {
            return fallback;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM ranked_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : fallback;
            }
        } catch (SQLException e) {
            return fallback;
        }
    }

    /** 写一个元数据值。 */
    public synchronized boolean setMeta(String key, String value) {
        if (!isAvailable()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ranked_meta (key, value) VALUES (?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warning("写入段位元数据失败（" + key + "）：" + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 比赛记录
    // ------------------------------------------------------------------

    private static final String INSERT_MATCH = """
            INSERT INTO tdm_matches
              (arena_id, arena_name, ranked, started_at, ended_at, winner,
               t_score, ct_score, end_reason)
            VALUES (?,?,?,?,?,?,?,?,?)""";

    private static final String INSERT_RESULT = """
            INSERT INTO tdm_results
              (match_id, uuid, name, team, kills, deaths, best_streak, money_left, late_join,
               rp_before, rp_after, tier_after, promoted, demoted)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    /**
     * 写入一场比赛及其全部参与者。
     *
     * <p>整体一个事务：比赛行和参与者行必须同生共死。只写进去一半的话，
     * {@code /tdm matchrp} 会查出一场「没有人参加的比赛」，
     * 那种半截数据比完全没有更难解释。
     *
     * @return 新比赛 id；写入失败返回 -1
     */
    public synchronized long recordMatch(MatchRecord record) {
        if (!isAvailable() || record == null) {
            return -1L;
        }
        try {
            connection.setAutoCommit(false);
            long matchId;
            try (PreparedStatement ps = connection.prepareStatement(
                    INSERT_MATCH, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, record.arenaId());
                ps.setString(2, record.arenaName());
                ps.setInt(3, record.ranked() ? 1 : 0);
                ps.setLong(4, record.startedAt());
                ps.setLong(5, record.endedAt());
                ps.setString(6, record.winner());
                ps.setInt(7, record.tScore());
                ps.setInt(8, record.ctScore());
                ps.setString(9, record.endReason());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    matchId = keys.next() ? keys.getLong(1) : -1L;
                }
            }

            if (matchId < 0) {
                connection.rollback();
                logger.warning("写入比赛记录时没能拿到自增 id，本场不记入历史。");
                return -1L;
            }

            try (PreparedStatement ps = connection.prepareStatement(INSERT_RESULT)) {
                for (MatchRecord.Participant p : record.participants()) {
                    ps.setLong(1, matchId);
                    ps.setString(2, p.uuid() == null ? "" : p.uuid().toString());
                    ps.setString(3, p.name() == null ? "" : p.name());
                    ps.setString(4, p.team() == null ? "T" : p.team());
                    ps.setInt(5, p.kills());
                    ps.setInt(6, p.deaths());
                    ps.setInt(7, p.bestStreak());
                    ps.setInt(8, p.moneyLeft());
                    ps.setInt(9, p.lateJoin() ? 1 : 0);
                    ps.setInt(10, p.rpBefore());
                    ps.setInt(11, p.rpAfter());
                    ps.setString(12, p.tierAfter() == null ? "" : p.tierAfter());
                    ps.setInt(13, p.promoted() ? 1 : 0);
                    ps.setInt(14, p.demoted() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            connection.commit();
            return matchId;
        } catch (SQLException e) {
            logger.warning("写入比赛记录失败：" + e);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // 回滚都失败了，除了放弃这一场没有别的办法
            }
            return -1L;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 恢复自动提交失败会在下次写入时暴露
            }
        }
    }

    /**
     * 查某位玩家最近的比赛记录，按时间倒序。
     *
     * @param limit 最多取几场；调用方负责防止它变成一次全表扫描
     * @return 比赛记录；没有记录或库不可用时返回空表
     */
    public synchronized List<MatchRecord> recentMatches(UUID uuid, int limit) {
        if (!isAvailable() || uuid == null || limit <= 0) {
            return List.of();
        }

        // 第一步：找出这名玩家参与过的最近 N 场的 id
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT match_id FROM tdm_results WHERE uuid = ? ORDER BY match_id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            logger.warning("查询比赛历史失败：" + e);
            return List.of();
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, MatchRecord> byId = new HashMap<>();
        Map<Long, List<MatchRecord.Participant>> participants = new HashMap<>();

        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM tdm_matches WHERE id IN (" + placeholders + ")")) {
                bindIds(ps, ids);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        byId.put(id, new MatchRecord(id,
                                rs.getString("arena_id"),
                                rs.getString("arena_name"),
                                rs.getInt("ranked") != 0,
                                rs.getLong("started_at"),
                                rs.getLong("ended_at"),
                                rs.getString("winner"),
                                rs.getInt("t_score"),
                                rs.getInt("ct_score"),
                                rs.getString("end_reason"),
                                new ArrayList<>()));
                    }
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM tdm_results WHERE match_id IN (" + placeholders + ")")) {
                bindIds(ps, ids);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long matchId = rs.getLong("match_id");
                        UUID playerUuid = null;
                        try {
                            playerUuid = UUID.fromString(rs.getString("uuid"));
                        } catch (IllegalArgumentException ignored) {
                            // 库被手改过时留个 null，展示层不依赖它
                        }
                        final UUID resolvedUuid = playerUuid;
                        participants.computeIfAbsent(matchId, k -> new ArrayList<>())
                                .add(new MatchRecord.Participant(
                                        resolvedUuid,
                                        rs.getString("name"),
                                        rs.getString("team"),
                                        rs.getInt("kills"),
                                        rs.getInt("deaths"),
                                        rs.getInt("best_streak"),
                                        rs.getInt("money_left"),
                                        rs.getInt("late_join") != 0,
                                        rs.getInt("rp_before"),
                                        rs.getInt("rp_after"),
                                        rs.getString("tier_after"),
                                        rs.getInt("promoted") != 0,
                                        rs.getInt("demoted") != 0));
                    }
                }
            }
        } catch (SQLException e) {
            logger.warning("读取比赛记录失败：" + e);
            return List.of();
        }

        // 按 ids 的顺序组装：SQL 的 IN 不保证顺序，而展示顺序必须是最新的在前
        List<MatchRecord> out = new ArrayList<>(ids.size());
        for (long id : ids) {
            MatchRecord base = byId.get(id);
            if (base == null) {
                continue;
            }
            List<MatchRecord.Participant> list = participants.getOrDefault(id, List.of());
            out.add(new MatchRecord(base.id(), base.arenaId(), base.arenaName(), base.ranked(),
                    base.startedAt(), base.endedAt(), base.winner(),
                    base.tScore(), base.ctScore(), base.endReason(), list));
        }
        return out;
    }

    private static void bindIds(PreparedStatement ps, List<Long> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            ps.setLong(i + 1, ids.get(i));
        }
    }
}

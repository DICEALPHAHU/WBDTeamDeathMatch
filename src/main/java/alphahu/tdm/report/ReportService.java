package alphahu.tdm.report;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.arena.TdmArena;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.game.TdmPlayer;
import alphahu.tdm.game.TdmTeam;
import alphahu.tdm.ranked.RankOutcome;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 战后战报的生成与落盘。
 *
 * <h2>为什么值得存</h2>
 * 一场打完什么都不留，回头想问「上周那场是谁打的」「这个人最近是不是在掉分」
 * 就完全没有依据。聊天记录会被刷掉，控制台日志会被轮转，只有落成文件才留得住。
 *
 * <h2>两个必须注意的时序</h2>
 * <ol>
 *   <li><b>内容必须在主线程构建。</b>{@link #save} 收到的 {@code match} 是活对象，
 *       下一 tick 的 {@code restart()} 就会把击杀、死亡、连杀全部清零。
 *       所以这里先 {@code build} 出完整文本，再把「写文件」这一步丢到异步 ——
 *       反过来的话，异步任务拿到的会是一份全 0 的战报。</li>
 *   <li><b>写文件必须异步。</b>战报是几百行文本加一次磁盘 IO，
 *       在主线程做就是每局结束卡一下。而那一刻服务器正在传送、播报、发烟花，
 *       是最不该再添堵的时候。</li>
 * </ol>
 *
 * @author AlphaHu
 */
public final class ReportService {

    private static final String FILE_TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss";
    private static final String READABLE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 本插件生成的文件名样式：{@code 2026-09-26_11-30-00_tdm_demo.yml}。
     *
     * <p>清理时只认这个样式。{@code reports/} 目录里完全可能有服主自己放进去的
     * 文件（手工整理的赛季汇总、从别处拷来的战报），按「所有 .yml 都是战报」
     * 去删会把它们一起清掉 —— 那是在删用户的数据，性质比留一堆旧文件严重得多。
     */
    private static final java.util.regex.Pattern REPORT_NAME =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_.+\\.yml");

    private final WBDTeamDeathmatch plugin;

    public ReportService(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    /**
     * 生成并保存一份战报。
     *
     * <p>必须在 {@code TdmMatch.restart()} 之前调用，且方法本身要在主线程执行。
     *
     * @param match    刚结束的比赛
     * @param outcomes 段位结算结果，可为空表（非排位场地）
     */
    public void save(TdmMatch match, Map<UUID, RankOutcome> outcomes) {
        if (!plugin.getSettings().reportsEnabled) {
            return;
        }

        long endedAt = System.currentTimeMillis();
        // 在主线程把内容固化成字符串。之后 match 怎么变都与这份战报无关了
        String content = build(match, outcomes == null ? Map.of() : outcomes, endedAt);

        TdmArena arena = match.getArena();
        String fileName = new SimpleDateFormat(FILE_TIME_PATTERN).format(new Date(endedAt))
                + "_" + safeName(arena.getId()) + ".yml";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(fileName, content));
    }

    // ------------------------------------------------------------------
    // 内容
    // ------------------------------------------------------------------

    /** 构建战报正文。此方法必须在主线程调用。 */
    private String build(TdmMatch match, Map<UUID, RankOutcome> outcomes, long endedAt) {
        var settings = plugin.getSettings();
        TdmArena arena = match.getArena();
        TdmTeam winner = match.getWinner();
        long startedAt = match.getStartedAt();
        long durationSeconds = startedAt > 0 ? Math.max(0, (endedAt - startedAt) / 1000L) : 0L;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("# WBDTeamDeathmatch 战后战报\n");
        sb.append("# 由插件自动生成，可直接删除；修改不会被读回。\n");
        sb.append("# 可用 /tdm rank <玩家> 查询某人的累计段位，本文件只记这一局。\n");
        sb.append('\n');

        sb.append("arena: ").append(yaml(arena.getId())).append('\n');
        sb.append("arena-name: ").append(yaml(arena.getName())).append('\n');
        sb.append("ranked: ").append(match.isRanked()).append('\n');
        sb.append("started-at: ").append(yaml(readable(startedAt))).append('\n');
        sb.append("ended-at: ").append(yaml(readable(endedAt))).append('\n');
        sb.append("duration-seconds: ").append(durationSeconds).append('\n');
        sb.append("duration-text: ").append(yaml(clock(durationSeconds))).append('\n');
        sb.append("end-reason: ").append(yaml(match.getEndReason())).append('\n');
        sb.append("winner: ").append(winner == null ? "DRAW" : winner.id()).append('\n');
        sb.append("target-kills: ").append(match.getRules().targetKills()).append('\n');
        sb.append("players-count: ").append(match.size()).append('\n');
        sb.append('\n');

        sb.append("score:\n");
        sb.append("  T: ").append(match.getScore(TdmTeam.T)).append('\n');
        sb.append("  CT: ").append(match.getScore(TdmTeam.CT)).append('\n');
        sb.append('\n');

        if (!settings.reportsIncludePlayers) {
            return sb.toString();
        }

        // 名单按击杀降序，与结算播报的顺序一致，看着不别扭
        List<TdmPlayer> roster = new ArrayList<>(match.getPlayers());
        roster.sort(Comparator
                .comparingInt(TdmPlayer::kills).reversed()
                .thenComparing(TdmPlayer::name, String.CASE_INSENSITIVE_ORDER));

        TdmPlayer mvp = roster.isEmpty() ? null : roster.get(0);
        if (mvp != null) {
            sb.append("mvp: ").append(yaml(mvp.name())).append('\n');
            sb.append('\n');
        }

        sb.append("players:\n");
        for (TdmPlayer record : roster) {
            sb.append("  - name: ").append(yaml(record.name())).append('\n');
            sb.append("    uuid: ").append(yaml(record.uuid().toString())).append('\n');
            sb.append("    team: ").append(record.team().id()).append('\n');
            sb.append("    kills: ").append(record.kills()).append('\n');
            sb.append("    deaths: ").append(record.deaths()).append('\n');
            sb.append("    kd: ").append(kd(record.kills(), record.deaths())).append('\n');
            sb.append("    best-kill-streak: ").append(record.bestKillStreak()).append('\n');
            sb.append("    money-left: ").append(record.money()).append('\n');
            sb.append("    late-join: ").append(record.joinedLate()).append('\n');

            if (settings.reportsIncludeRanked) {
                RankOutcome outcome = outcomes.get(record.uuid());
                if (outcome != null && outcome.ranked()) {
                    sb.append("    rank:\n");
                    sb.append("      rp-before: ").append(outcome.rpBefore()).append('\n');
                    sb.append("      rp-after: ").append(outcome.rpAfter()).append('\n');
                    sb.append("      delta: ").append(outcome.delta()).append('\n');
                    sb.append("      tier-before: ").append(yaml(outcome.tierBefore())).append('\n');
                    sb.append("      tier-after: ").append(yaml(outcome.tierAfter())).append('\n');
                    sb.append("      promoted: ").append(outcome.promoted()).append('\n');
                    sb.append("      demoted: ").append(outcome.demoted()).append('\n');
                    // 逐项留档，这样「这局为什么是 -24」过一个月也答得出来
                    sb.append("      breakdown:\n");
                    sb.append("        placement: ").append(outcome.placement()).append('\n');
                    sb.append("        margin: ").append(outcome.margin()).append('\n');
                    sb.append("        kill: ").append(outcome.killRp()).append('\n');
                    sb.append("        team-placement: ").append(outcome.teamPlace()).append('\n');
                    sb.append("        entry-fee: ").append(-outcome.entryFee()).append('\n');
                }
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /** 在异步线程写文件，并清理超额的历史战报。 */
    private void write(String fileName, String content) {
        var settings = plugin.getSettings();
        File dir = new File(plugin.getDataFolder(), settings.reportsFolder);
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建战报目录：" + dir.getPath());
            return;
        }

        File file = new File(dir, fileName);
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("写入战报失败（" + fileName + "）：" + e);
            return;
        }

        prune(dir, settings.reportsKeep);
    }

    /**
     * 只保留最近的 N 份战报。
     *
     * <p>按**修改时间**而不是文件名排序：文件名里的时间戳在跨时区、跨夏令时
     * 或者有人手工拷进旧文件时都不可靠，而 mtime 是文件系统自己维护的。
     */
    private void prune(File dir, int keep) {
        if (keep <= 0) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> REPORT_NAME.matcher(name).matches());
        if (files == null || files.length <= keep) {
            return;
        }

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int toRemove = files.length - keep;
        int removed = 0;
        for (int i = 0; i < toRemove; i++) {
            if (files[i].delete()) {
                removed++;
            } else {
                plugin.getLogger().warning("无法删除旧战报：" + files[i].getName());
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("战报超过上限（" + keep + "），已清理 " + removed + " 份最旧的。");
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 把任意文本写成 YAML 双引号标量。
     *
     * <p>玩家名、场地名都可能含 {@code :}、{@code #}、引号这类会破坏 YAML 的字符。
     * 全部走双引号并转义，比逐个判断「这个值需不需要引号」可靠得多。
     */
    private static String yaml(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        // 换行会让值变成多行标量，直接压成空格——战报里没有需要保留换行的字段
        escaped = escaped.replace("\r", " ").replace("\n", " ");
        return "\"" + escaped + "\"";
    }

    /** 文件名里不能出现的字符换成下划线。 */
    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "arena";
        }
        return raw.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static String readable(long epochMillis) {
        if (epochMillis <= 0) {
            return "未知";
        }
        return new SimpleDateFormat(READABLE_TIME_PATTERN).format(new Date(epochMillis));
    }

    /** 秒数转 mm:ss / hh:mm:ss。 */
    private static String clock(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, secs)
                : String.format("%d:%02d", minutes, secs);
    }

    /** K/D，保留两位；零死亡时直接给击杀数（避免除零，也符合直觉）。 */
    private static String kd(int kills, int deaths) {
        if (deaths <= 0) {
            return String.format("%.2f", (double) kills);
        }
        return String.format("%.2f", kills / (double) deaths);
    }
}

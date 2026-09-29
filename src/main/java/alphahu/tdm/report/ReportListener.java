package alphahu.tdm.report;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.message.Messages;
import alphahu.tdm.ranked.MatchRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 战报界面的点击处理与聊天栏输出。
 *
 * <h2>输出顺序</h2>
 * 按你要求的形式：**先展示界面主人所在的那一方**，然后另一方，两方分开成块。
 * 理由是打开这个界面的人最先想看的是自己和队友打得怎么样，
 * 把对手摆在前面等于每次都要往下找。
 *
 * <p>每块内部按击杀降序 —— 与比赛结束时的播报顺序一致。
 *
 * @author AlphaHu
 */
public final class ReportListener implements Listener {

    /** 名单每行的名字列宽。中文名会有半个字的偏差，这是等宽字体之外无法避免的。 */
    private static final String ROW_FORMAT = "  &f%-14s &7%2d杀 %2d死 &7K/D &f%-5s %s &8[&f%s&8]";

    private final WBDTeamDeathmatch plugin;

    public ReportListener(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ReportMenu menu)) {
            return;
        }
        // 战报界面是只读的：任何点击都不该真的动到物品。
        // 不取消的话，玩家可以把图标拖进自己背包，白得一张纸
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 只处理箱子里那一半。玩家自己背包里的点击（整理、丢东西）不该触发展开
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof ReportMenu)) {
            return;
        }

        MatchRecord record = menu.recordAt(event.getRawSlot());
        if (record == null) {
            return;
        }

        // 先关界面再输出：箱子界面会盖住聊天栏，不关的话玩家看不到刚发出来的内容
        player.closeInventory();
        print(plugin, player, record, menu.ownerUuid());
    }

    /** 拖拽同样要拦：否则可以绕过点击把图标拖出去。 */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ReportMenu) {
            event.setCancelled(true);
        }
    }

    /**
     * 在聊天栏展开一场战报。
     *
     * @param ownerUuid 界面主人，决定先展示哪一方；为 null 时先展示 T 方
     */
    public static void print(WBDTeamDeathmatch plugin, Player viewer,
                             MatchRecord record, UUID ownerUuid) {
        Messages messages = plugin.getMessages();

        String myTeam = null;
        for (MatchRecord.Participant p : record.participants()) {
            if (ownerUuid != null && ownerUuid.equals(p.uuid())) {
                myTeam = p.team();
                break;
            }
        }
        // 界面主人不在这一场里（理论上不会发生），退化成 T 在前
        boolean ctFirst = "CT".equalsIgnoreCase(myTeam);

        viewer.sendMessage(messages.get("report.header"));
        viewer.sendMessage(Messages.color("&7地图 &f" + record.arenaName()
                + " &8(&f" + record.arenaId() + "&8)"
                + (record.ranked() ? " &7· &a排位" : " &7· &7非排位")));
        viewer.sendMessage(Messages.color("&7比分 &cT &f" + record.tScore()
                + " &7- &bCT &f" + record.ctScore()
                + " &7· " + (record.draw() ? "&e平局" : "&a" + record.winner() + " 获胜")));
        viewer.sendMessage(Messages.color("&7时间 &f" + ReportMenu.time(record.startedAt())
                + " &7~ &f" + ReportMenu.time(record.endedAt())
                + " &7· 时长 &f" + ReportMenu.clock(record.durationSeconds())));
        if (record.endReason() != null) {
            viewer.sendMessage(Messages.color("&7结束 &f" + record.endReason()));
        }
        viewer.sendMessage("");

        if (ctFirst) {
            printTeam(viewer, record, "CT");
            printTeam(viewer, record, "T");
        } else {
            printTeam(viewer, record, "T");
            printTeam(viewer, record, "CT");
        }

        viewer.sendMessage(messages.get("report.footer"));
    }

    /** 输出一个阵营的名单。空阵营直接跳过，不打印一个空标题。 */
    private static void printTeam(Player viewer, MatchRecord record, String team) {
        List<MatchRecord.Participant> members = new ArrayList<>(record.teamOf(team));
        if (members.isEmpty()) {
            return;
        }
        members.sort(Comparator
                .comparingInt(MatchRecord.Participant::kills).reversed()
                .thenComparing(MatchRecord.Participant::name, String.CASE_INSENSITIVE_ORDER));

        String color = "CT".equalsIgnoreCase(team) ? "&b" : "&c";
        viewer.sendMessage(Messages.color(color + "【" + team + " 方】"));

        for (MatchRecord.Participant p : members) {
            String kd = String.format(Locale.US, "%.2f", p.kd());
            String rank;
            if (p.hasRank()) {
                rank = p.tierAfter() + " &7" + p.deltaText();
            } else if (p.lateJoin()) {
                // 中途加入不结算名次分与入场费，说清楚免得被当成漏算了
                rank = "&8中途加入";
            } else {
                rank = "&8未结算";
            }
            viewer.sendMessage(Messages.color(String.format(ROW_FORMAT,
                    p.name(), p.kills(), p.deaths(), kd, rank, team)));
        }
        viewer.sendMessage("");
    }
}

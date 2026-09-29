package alphahu.tdm.report;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.message.Messages;
import alphahu.tdm.ranked.MatchRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 「比赛战报」的箱子界面：一格一场比赛，点一格就在聊天栏展开那一场的战报。
 *
 * <p>用 54 格的大箱子而不是分页：这个界面要回答的是「我最近打得怎么样」，
 * 一屏能看到最近 45 场已经远超需要，翻页只会多一层操作和一层状态。
 *
 * <p>为什么点一下就关闭界面而不是留在里面：战报是**文字**，
 * 展开在聊天栏里可以往上翻、可以复制、可以对着看；而箱子界面会挡在聊天栏前面。
 * 把两者叠在一起的结果是玩家点了半天什么也读不到。
 *
 * @author AlphaHu
 */
public final class ReportMenu implements InventoryHolder {

    /** 箱子格数。54 是原版单箱上限，也就是这里一次能展示的场次数。 */
    public static final int SIZE = 54;

    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm";

    private final UUID ownerUuid;
    private final String ownerName;
    private final List<MatchRecord> records;
    private final Inventory inventory;

    public ReportMenu(WBDTeamDeathmatch plugin, UUID ownerUuid, String ownerName,
                      List<MatchRecord> records) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.records = List.copyOf(records);
        // 标题走 messages.yml，方便服主改成自己的风格。
        // %player% 在这里手工替换而不是走 Messages.get：那个方法一次替换
        // 一整串键值对，只为一个变量调它反而更难读
        String title = plugin.getMessages().raw("report-menu.title", "&8%player% 比赛战报")
                .replace("%player%", ownerName);
        this.inventory = Bukkit.createInventory(this, SIZE, Messages.color(title));
        fill(plugin);
    }

    /** 逐格放入场次图标。 */
    private void fill(WBDTeamDeathmatch plugin) {
        Messages messages = plugin.getMessages();
        int limit = Math.min(records.size(), SIZE);
        for (int slot = 0; slot < limit; slot++) {
            inventory.setItem(slot, icon(plugin, records.get(slot), slot));
        }
    }

    private ItemStack icon(WBDTeamDeathmatch plugin, MatchRecord record, int index) {
        Material material = Material.matchMaterial(
                plugin.getMessages().raw("report-menu.icon-material", "PAPER"));
        if (material == null || material.isAir()) {
            material = Material.PAPER;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        MatchRecord.Participant me = findMe(record);
        String outcome = record.draw()
                ? "平局"
                : record.won(myTeam(record)) ? "&a胜" : "&c负";

        meta.setDisplayName(Messages.color(
                "&e#" + (index + 1) + " &f" + record.arenaName() + " &7" + outcome));

        List<String> lore = new ArrayList<>();
        lore.add(Messages.color("&7时间 &f" + time(record.endedAt())
                + " &7· 时长 &f" + clock(record.durationSeconds())));
        lore.add(Messages.color("&7比分 &cT &f" + record.tScore()
                + " &7- &bCT &f" + record.ctScore()
                + " &7· 胜者 &f" + (record.draw() ? "平局" : record.winner())));
        lore.add(Messages.color("&7结束 &f" + (record.endReason() == null ? "—" : record.endReason())));

        if (me != null) {
            lore.add(Messages.color("&7你的战绩 &f" + me.kills() + "&7杀 &f"
                    + me.deaths() + "&7死 &7K/D &f" + String.format(java.util.Locale.US, "%.2f", me.kd())));
            if (me.hasRank()) {
                lore.add(Messages.color("&7段位 &f" + me.tierAfter()
                        + " &8(&f" + me.deltaText() + " RP&8)"));
            } else {
                lore.add(Messages.color("&7本场未结算段位"));
            }
        }

        lore.add("");
        lore.add(Messages.color(plugin.getMessages().raw("report-menu.click-hint",
                "&a点击查看完整战报")));
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 查这台界面的主人在这场里的数据。 */
    private MatchRecord.Participant findMe(MatchRecord record) {
        for (MatchRecord.Participant p : record.participants()) {
            if (ownerUuid.equals(p.uuid())) {
                return p;
            }
        }
        return null;
    }

    /** 界面主人在这一场里的阵营，用于决定先展示哪一方。 */
    private String myTeam(MatchRecord record) {
        MatchRecord.Participant me = findMe(record);
        return me == null ? null : me.team();
    }

    /** 某格对应的比赛；空格或越界返回 null。 */
    public MatchRecord recordAt(int slot) {
        return slot >= 0 && slot < records.size() ? records.get(slot) : null;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    static String time(long epochMillis) {
        if (epochMillis <= 0) {
            return "未知";
        }
        return new SimpleDateFormat(TIME_PATTERN).format(new Date(epochMillis));
    }

    static String clock(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, secs)
                : String.format("%d:%02d", minutes, secs);
    }
}

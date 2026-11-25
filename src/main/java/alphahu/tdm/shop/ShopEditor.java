package alphahu.tdm.shop;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 可视化商店编辑器，用法对齐 WarZBombDefuse 的 {@code /wbd shopedit}。
 *
 * <h2>怎么用</h2>
 * {@code /tdm shopedit [T|CT] [页码]} 打开一个箱子，把要卖的东西摆进去，
 * 关掉箱子即保存。摆在哪一格，玩家看到就在哪一格。
 *
 * <p>这么设计是因为商品数据的核心是**物品本身** —— 枪械的 NBT、附魔、改名，
 * 手写 YAML 是不可能的，只能让管理员直接把实物摆进去，插件再把它序列化。
 * 这也正是 WBD 的做法。
 *
 * <h2>哪些操作被拦、为什么只拦这些</h2>
 *
 * <p>编辑器里每一格都直接对应「这里卖什么」，所以**一次动一整摞**的操作要拦：
 * Shift 点击（整摞搬进/搬出：搬进来就是「一次买 64 把枪」的商品，搬出去就是商品
 * 无声消失）、数字键（快捷栏换货）、双击（把同类全部聚到光标）、副手交换、
 * Ctrl+丢弃、一键整理。
 *
 * <p>除此之外**全部放行**，包括拿起、放下、箱内挪格、从背包拖进来、丢弃。
 * 曾经这些也被拦掉，那时候「放错了想挪一格」和「不想要了想删掉」都得去手改
 * YAML —— 一个自称可视化的编辑器，却连把商品拿起来都做不到，这明显是拦过头了。
 * 判断标准只有一条：<b>这个动作是不是一次改掉一整摞</b>；不是就让它正常工作。
 *
 * @author AlphaHu
 */
public final class ShopEditor implements Listener {

    /** 编辑器箱子的身份标记，同时带着正在编辑的是哪个阵营、哪一页。 */
    public static final class EditorHolder implements InventoryHolder {
        private final String teamId;
        private final int page;
        private Inventory inventory;

        EditorHolder(String teamId, int page) {
            this.teamId = teamId;
            this.page = page;
        }

        public String teamId() {
            return teamId;
        }

        public int page() {
            return page;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * 保存时写在文件开头的说明。
     *
     * <p>{@link YamlConfiguration} 的读写只保留数据，注释在解析阶段就没了 ——
     * 编辑器一旦保存，管理员原本写的注释就会消失。既然留不住，就改为每次写入
     * 一段固定的字段说明，至少打开文件的人知道每一项是什么意思。
     */
    private static final String FILE_HEADER = String.join("\n",
            "# ============================================================",
            "#  WBDTeamDeathmatch 商店配置",
            "# ============================================================",
            "# 本文件由 /tdm shopedit 可视化编辑器读写。",
            "# 格式与 WarZBombDefuse 的 shop.yml 一致，两边可以互相对拷。",
            "#",
            "# ⚠ 编辑器保存时会重写本文件，你手动添加的注释不会保留。",
            "#   需要写说明请放在 README 或 config.yml 里。",
            "#",
            "# 关于价格：商品按 price 收费，钱来自击杀与连杀；",
            "#   economy.enabled 设为 false 时才回到全免费。",
            "# 关于落点：购买后放进背包哪一格，由商品的「落点槽位」决定 ——",
            "#   落点相同的商品互相替换（几十把枪共用一个主武器槽），落点不同则并存",
            "#   （所以买手雷不会把枪顶掉）。优先级：shop.slot-overrides → 商品的",
            "#   give-slot → shop.material-slots（按图标材质兜底） → shop.fallback-slot。",
            "# ============================================================",
            "");

    private final WBDTeamDeathmatch plugin;

    public ShopEditor(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 打开
    // ------------------------------------------------------------------

    /** 打开编辑器。{@code teamId} 为 null 表示编辑公共商城。 */
    public void open(Player player, String teamId, int page) {
        ShopService shop = plugin.getShop();
        if (shop.getLoadedFile() == null || !shop.getLoadedFile().exists()) {
            player.sendMessage(Text.color("&c没有可编辑的商店文件。"
                    + "请先确认 config.yml 里 shop.source 指向的配置存在。"));
            return;
        }

        String label = teamId == null ? "公共商城" : teamId + " 阵营商城";
        int size = shop.getPageSize();
        int pageCount = shop.getPageCount(teamId);
        int current = Math.max(1, Math.min(pageCount, page));

        EditorHolder holder = new EditorHolder(teamId, current);
        Inventory inventory = Bukkit.createInventory(holder, size,
                Text.color("&8编辑：" + label + " 第 " + current + "/" + pageCount + " 页"));
        holder.setInventory(inventory);

        // 把该页已有的商品摆进去，管理员在此基础上增删
        for (ShopItem item : shop.itemsOnPage(teamId, current)) {
            if (item.slot() >= 0 && item.slot() < size) {
                inventory.setItem(item.slot(), item.display().clone());
            }
        }

        player.openInventory(inventory);
        player.sendMessage(Text.color("&6========= 商店编辑器 ========="));
        player.sendMessage(Text.color("&7正在编辑：&f" + label + " &7第 &f" + current + "/" + pageCount + " &7页"));
        player.sendMessage(Text.color("&7把要卖的东西摆进箱子，&f关掉箱子即保存&7。"));
        player.sendMessage(Text.color("&7点、拖、丢都可以用；只拦 Shift／数字键／双击这类「一次搬一摞」的操作。"));
        player.sendMessage(Text.color("&7商品落点由材质分组自动分配，想固定请配 shop.material-slots。"));
        player.sendMessage(Text.color("&6=============================="));
    }

    // ------------------------------------------------------------------
    // 交互拦截
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder)) {
            return;
        }

        // 只拦「一次动一整摞」的快捷操作，理由见类注释。
        //
        // 具体每种拦掉会坏在哪：
        //   isShiftClick               整摞搬进箱子 = 一个「64 件」的商品；
        //                              整摞搬出箱子 = 商品无声消失
        //   NUMBER_KEY                 快捷栏那件东西直接换进商品格
        //   DOUBLE_CLICK               把箱内同类全聚到光标上，一次清掉一整片
        //   SWAP_OFFHAND / CONTROL_DROP 同上，只是换了个键位
        //   COLLECT_TO_CURSOR          双击的同义动作（部分端单独报这个 action）
        //   MOVE_TO_OTHER_INVENTORY    Shift 点击的底层 action
        ClickType click = event.getClick();
        if (event.isShiftClick()
                || click == ClickType.NUMBER_KEY
                || click == ClickType.DOUBLE_CLICK
                || click == ClickType.SWAP_OFFHAND
                || click == ClickType.CONTROL_DROP
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            return;
        }

        // 其余一律不拦：普通点击、拿起放下、箱内挪格、背包与箱子之间的拖拽、
        // 丢弃（拿起来按 Q 就是「删掉这件商品」）。关闭界面时才保存，
        // 所以这些操作本身就是编辑器该有的编辑动作。
    }

    // ------------------------------------------------------------------
    // 关于拖拽：这里**故意没有** InventoryDragEvent 处理器
    //
    // 曾经有一个，内容是「编辑器里一律 event.setCancelled(true)」。它的后果是
    // 玩家连「在自己背包里挪一下位置」都做不到，更别说把武器拖进箱子 ——
    // 玩家反馈的「武器都拖不动」就是它。
    //
    // 拖拽恰恰是最贴合摆货的手势：一次往一格放一件，或者沿着箱子拉一排；
    // 而它**永远不会一次搬运一整摞**（左键/右键拖拽都是每格一件），
    // 所以不属于「一次改掉一整摞」那类需要拦的操作。
    //
    // 要拦的话请先想清楚拦的是不是「一次动一整摞」，别再整段取消。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getView().getTopInventory();
        String teamId = holder.teamId();
        int page = holder.page();

        // 延后一 tick：关闭事件里玩家可能还处在「手上拿着东西」的状态，
        // 那件东西此刻还没落进箱子，立刻读会漏掉它
        Bukkit.getScheduler().runTask(plugin, () -> save(player, inventory, teamId, page));
    }

    private void save(Player player, Inventory inventory, String teamId, int page) {
        ShopService shop = plugin.getShop();
        File file = shop.getLoadedFile();
        if (file == null) {
            player.sendMessage(Text.color("&c保存失败：找不到商店文件。"));
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String base = teamId == null || teamId.isBlank()
                ? "shop.items"
                : "shop.team-shops." + teamId.toUpperCase(Locale.ROOT) + ".items";

        // 1. 先删掉该页已有商品。只删这一页，别的页原样保留 ——
        //    管理员编辑的是「这一页摆了什么」，不该波及隔壁页
        ConfigurationSection existing = yaml.getConfigurationSection(base);
        if (existing != null) {
            for (String id : new ArrayList<>(existing.getKeys(false))) {
                if (existing.getInt(id + ".page", 1) == page) {
                    yaml.set(base + "." + id, null);
                }
            }
        }

        // 2. 写入箱子里现有的东西
        int written = 0;
        int failed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            String data = ShopService.serializeItem(item);
            if (data == null) {
                failed++;
                continue;
            }

            String id = buildId(item, slot, page);
            yaml.set(base + "." + id + ".page", page);
            yaml.set(base + "." + id + ".slot", slot);
            yaml.set(base + "." + id + ".team", teamId == null ? "ANY" : teamId.toUpperCase(Locale.ROOT));
            // 图标与实物用同一份数据：编辑器里看到什么，玩家就拿到什么。
            // 需要「图标是纸、实际给枪」这种玩法的话，手改 yml 加 click-commands 即可
            yaml.set(base + "." + id + ".display-data", data);
            yaml.set(base + "." + id + ".give-data", data);
            written++;
        }

        // 3. 落盘。用文本方式写以便带上固定说明头
        try {
            Files.write(file.toPath(),
                    (FILE_HEADER + yaml.saveToString()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            player.sendMessage(Text.color("&c保存失败：" + e.getMessage()));
            plugin.getLogger().warning("写入商店文件失败：" + e);
            return;
        }

        shop.reload();

        String label = teamId == null ? "公共商城" : teamId + " 阵营商城";
        player.sendMessage(Text.color("&a已保存 " + label + " 第 " + page + " 页：&f"
                + written + " &a件商品。"));
        if (failed > 0) {
            player.sendMessage(Text.color("&e有 " + failed
                    + " 件物品无法序列化（可能是模组物品），已跳过。"));
        }
    }

    /**
     * 生成商品 id。
     *
     * <p>用「页码 + 材质 + 槽位」而不是递增编号：同样的摆放重复保存会得到同样的 id，
     * 于是覆盖而不是堆积 —— 否则每保存一次，文件里就多出一整套重复商品。
     */
    private String buildId(ItemStack item, int slot, int page) {
        return "p" + page + "_" + item.getType().name().toLowerCase(Locale.ROOT) + "_" + slot;
    }
}

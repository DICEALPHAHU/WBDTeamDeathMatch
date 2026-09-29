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
import org.bukkit.event.inventory.InventoryDragEvent;
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
 * <h2>为什么要把快捷操作全拦掉</h2>
 * 编辑器里每一次点击都直接对应「这一格有什么商品」。Shift 点击会一次搬一整摞、
 * 数字键会把快捷栏的东西换进来、拖拽铺货会一次改一片 —— 这些操作在普通箱子里
 * 很顺手，在这里却会让管理员在毫无察觉的情况下改掉一整排商品。
 * 所以只保留最朴素的「点一下拿起、点一下放下」。
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
            "# 关于价格：死斗不比经济，price 字段会被忽略，所有商品免费。",
            "# 关于落点：购买后放进背包哪一格由「商品图标的材质」分组决定 ——",
            "#   同材质的商品互相替换（几十把枪共用一个主武器槽），不同材质并存",
            "#   （所以买手雷不会把枪顶掉）。要固定某一类的落点，在 TDM 的",
            "#   config.yml 里写 shop.material-slots。",
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
        player.sendMessage(Text.color("&7Shift、数字键、拖拽已被拦截，请用普通点击摆放。"));
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

        // 快捷操作一律拦掉：它们一次改动多格，而这里每一格都对应一件商品
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

        boolean inEditor = event.getRawSlot() < event.getView().getTopInventory().getSize();
        if (inEditor) {
            ItemStack cursor = event.getCursor();
            // 箱内、且手上没东西 —— 这是「把商品拿走」的动作，拦掉。
            // 手上拿着东西去点，是「放进去」，放行。
            if (cursor == null || cursor.getType().isAir()) {
                event.setCancelled(true);
            }
        }
        // 点在玩家背包上：允许，管理员得先从背包把东西拿出来
    }

    /** 编辑器内禁止拖拽铺货。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

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

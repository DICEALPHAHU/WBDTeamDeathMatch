package alphahu.tdm.shop;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.game.TdmPlayer;
import alphahu.tdm.util.ItemCodec;
import alphahu.tdm.util.ItemParser;
import alphahu.tdm.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 装备商店。
 *
 * <h2>为什么直接兼容 WarZBombDefuse 的商店格式</h2>
 * 服主为爆破模式配的那份 {@code shop.yml} 往往有上千行、几十件商品，
 * 其中枪械的完整物品数据（TacZ 的 NBT）是以 Bukkit 序列化形式内嵌的 ——
 * 那部分**不可能靠手写重来一遍**。所以这里选择读它的格式，
 * 让同一份配置可以在两个模式里共用（{@code source: wbd}）。
 *
 * <p>物品数据用 {@link BukkitObjectInputStream} 还原，这是 WBD 写入时用的同一套机制。
 *
 * <h2>价格与「购买覆盖」</h2>
 * 商品按 {@code price} 收费，{@code economy.enabled: false} 时才回到全免费。
 * 钱来自击杀与连杀 —— 打得好的队伍先买得起好枪，这条正反馈是模式的一部分。
 *
 * <p>落点槽位的优先级：{@code shop.slot-overrides}（按商品 id）→ 商品的
 * {@code give-slot} → 按图标材质分组的 {@code material-slots} →
 * {@code shop.fallback-slot}（默认 0）。
 *
 * <p>发出的每件物品都带一个**以落点为键**的分组标记；购买时先扫一遍背包
 * （含护甲槽与副手），把同组的旧物品清掉再放新的，所以落点相同的商品只会互相替换，
 * 不会堆成一背包武器。想让几件装备并存，给它们配不同的 {@code give-slot} 即可 ——
 * 也可以在 config.yml 里用 {@code slot-overrides} 单独指定，不必改动 WBD 的那份配置。
 *
 * @author AlphaHu
 */
public final class ShopService {

    /**
     * 一件商品的物品数据是从哪来的。
     *
     * <p>这个区分不是洁癖，而是「发出来的东西对不对」的唯一判据：
     * 只有 {@link #DATA} 是把 WBD 编辑器序列化的整个 {@code ItemStack} 原样还原 ——
     * 模组枪械的枪械 id、配件、弹药、NBT 全在里面，发出去就是**同一件物品**。
     * {@link #BRIEF} 只是拿材质新造一个空壳，模组物品根本没有对应材质，
     * 造出来的必然是错的东西。
     */
    private enum ItemSource {
        /** 来自 {@code *-data}，Bukkit 序列化数据的原样还原。 */
        DATA,
        /** 来自 {@code *} 简写段，按材质重建的空壳。 */
        BRIEF,
        /** 没有物品实体（纯命令商品）。 */
        NONE
    }

    /** 一次物品解析的结果：物品本体 + 它的来源。 */
    private record Resolved(ItemStack stack, ItemSource source) {
    }

    /** 加载时统计：有多少商品的发放物品是原样还原的。 */
    private int giveFromData;
    private int giveFromBrief;
    private int giveCommandOnly;

    /**
     * 商品 id → 它的发放物品来源。
     *
     * <p>记的是 id 而不是 {@code ShopItem} 内部的字段，是为了不动
     * {@code ShopItem} 这个 record 的构造签名 —— 编辑器那边也在造它。
     */
    private final Map<String, ItemSource> giveSources = new HashMap<>();

    /**
     * 商店菜单的持有者。
     *
     * <p>除了当身份标记，它还**带着当前是哪一页、哪个阵营** ——
     * 点击翻页或购买时要用。把这些塞进 holder 而不是另外维护一张
     * 「玩家 → 当前页」的表，是为了避免玩家关掉菜单、换人打开之后
     * 那张表还留着陈旧数据。
     */
    public static final class ShopHolder implements InventoryHolder {
        private final int page;
        private final String teamId;
        private Inventory inventory;

        ShopHolder(int page, String teamId) {
            this.page = page;
            this.teamId = teamId;
        }

        public int page() {
            return page;
        }

        public String teamId() {
            return teamId;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final WBDTeamDeathmatch plugin;
    private final Logger logger;

    // ---- 菜单外观 ----
    private int size = 54;
    private int pages = 1;
    private String title = "&8商店";
    private ItemStack openItem;
    private int openItemSlot = 8;

    // ---- 翻页按钮 ----
    private int previousSlot = 45;
    private int infoSlot = 49;
    private int nextSlot = 53;
    private ItemStack previousItem;
    private ItemStack nextItem;
    private ItemStack infoItem;

    // ---- 商品 ----
    private final List<ShopItem> commonItems = new ArrayList<>();
    private final Map<String, TeamShop> teamShops = new HashMap<>();

    /** 一个阵营专属商城。 */
    private record TeamShop(String title, int pages, List<ShopItem> items) {
    }

    /**
     * 商品材质 → 落点背包槽位。
     *
     * <p>这是「同类互相替换、异类并存」的关键：同材质的商品会被分到同一格
     * （所以 29 把枪共用主武器槽，互相替换），而枪、投掷物、配件、药品各自
     * 占不同的格，不会买个手雷就把枪顶掉。
     *
     * <p>分组依据取商品图标的材质 —— 这在本项目里恰好就是天然的分类：
     * 枪是 {@code TACZ_MODERN_KINETIC_GUN}、投掷物是 {@code LRTACTICAL_THROWABLE}、
     * 配件是 {@code TACZ_ATTACHMENT}。比按商品名猜关键字可靠得多。
     */
    private final Map<String, Integer> materialSlots = new HashMap<>();

    /** 当前加载的那份商店文件。编辑器要写回它。 */
    private File loadedFile;

    /**
     * 打在「商店发出的物品」上的分组标记。
     *
     * <p>用来识别哪些东西是这家店给的，从而在重复购买时把它们清掉。
     * 没有这个标记，光靠「覆盖落点那一格」是挡不住囤积的 —— 见 {@link #purgeGroup}。
     */
    private final NamespacedKey groupKey;

    /** 商品分类（落点、限购、预留格）。 */
    private final LoadoutCategories categories;

    public ShopService(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.groupKey = new NamespacedKey(plugin, "shop_group");
        this.categories = new LoadoutCategories(plugin);
    }

    public LoadoutCategories getCategories() {
        return categories;
    }

    // ------------------------------------------------------------------
    // 供编辑器使用的接口
    // ------------------------------------------------------------------

    /** 当前加载的商店文件，未加载时为 null。 */
    public File getLoadedFile() {
        return loadedFile;
    }

    /** 菜单的格子数。 */
    public int getPageSize() {
        return size;
    }

    /** 某个阵营的页数。 */
    public int getPageCount(String teamId) {
        return pagesFor(teamId);
    }

    /** 某个阵营的菜单标题（含变量占位符，未替换）。 */
    public String getDisplayTitle(String teamId) {
        return titleFor(teamId);
    }

    /**
     * 取某一页的商品原始数据，供编辑器预填。
     *
     * <p>刻意**不做阵营过滤**：编辑器是管理员视角，要看到并修改那一页的全部内容，
     * 包括当前正被过滤掉的那些商品。
     */
    public List<ShopItem> itemsOnPage(String teamId, int page) {
        List<ShopItem> source;
        if (teamId == null || teamId.isBlank()) {
            source = commonItems;
        } else {
            TeamShop shop = teamShops.get(teamId.toUpperCase(java.util.Locale.ROOT));
            source = shop == null ? List.of() : shop.items();
        }

        List<ShopItem> out = new ArrayList<>();
        for (ShopItem item : source) {
            if (item.page() == page) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 把一件物品序列化成 WBD 用的 {@code bukkit-object:} 串；失败返回 null。
     *
     * <p>与读取走的是同一个 {@link ItemCodec}，所以「导出 → 贴回配置 → 再读出来」
     * 拿到的必然是同一件物品，不会因为两处实现不同而悄悄丢字段。
     */
    public static String serializeItem(ItemStack item) {
        return ItemCodec.encode(item);
    }

    /** 重新加载当前文件（编辑器保存后调用）。 */
    public void reload() {
        if (loadedFile != null) {
            load(loadedFile);
        }
        categories.reload();
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 从一个 shop.yml（WBD 格式）读出全部配置。
     *
     * @return 是否读到了任何可用商品
     */
    public boolean load(File file) {
        this.loadedFile = file;
        commonItems.clear();
        teamShops.clear();
        giveSources.clear();
        giveFromData = 0;
        giveFromBrief = 0;
        giveCommandOnly = 0;

        if (!file.exists()) {
            logger.warning("找不到商店配置：" + file.getPath() + "，商店将是空的。");
            return false;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection shop = yaml.getConfigurationSection("shop");
        if (shop == null) {
            logger.warning("商店配置里没有 shop 段落：" + file.getPath());
            return false;
        }

        size = clampPageSize(shop.getInt("size", 54));
        pages = Math.max(1, shop.getInt("pages", 1));
        title = shop.getString("title", "&8商店");

        readOpenItem(shop.getConfigurationSection("open-item"));
        readNavigation(shop.getConfigurationSection("navigation"));

        commonItems.addAll(readItems(shop.getConfigurationSection("items"), "公共"));

        ConfigurationSection teamSection = shop.getConfigurationSection("team-shops");
        if (teamSection != null) {
            for (String teamId : teamSection.getKeys(false)) {
                ConfigurationSection node = teamSection.getConfigurationSection(teamId);
                if (node == null) continue;
                List<ShopItem> items = readItems(node.getConfigurationSection("items"), teamId + " 专属");
                teamShops.put(teamId.toUpperCase(java.util.Locale.ROOT), new TeamShop(
                        node.getString("title", title),
                        Math.max(1, node.getInt("pages", pages)),
                        items));
            }
        }

        logger.info("商店已加载：" + commonItems.size() + " 件公共商品"
                + (teamShops.isEmpty() ? "" : "，阵营专属 " + teamShops.size() + " 组")
                + "，共 " + pages + " 页。");
        logGiveAudit();
        logSlotAudit();
        computeMaterialSlots();
        return !commonItems.isEmpty() || !teamShops.isEmpty();
    }

    /**
     * 提醒「读到的多半是没调过的 WBD 原版配置」。
     *
     * <p>WBD 那边有经济系统，买什么都往空位塞，所以它的 {@code give-slot} 大量是 -1。
     * 直接共用那份文件时，所有装备都会挤进同一个 fallback 槽 —— 表现就是
     * 「刀子跑到第一格」「买了枪却把刀顶掉」。这条日志让服主一眼看出是**文件读错了**，
     * 而不是去怀疑发放逻辑。
     */
    private void logSlotAudit() {
        List<ShopItem> all = allItems();
        if (all.isEmpty()) {
            return;
        }
        int noSlot = 0;
        for (ShopItem item : all) {
            if (item.giveSlot() < 0) {
                noSlot++;
            }
        }
        if (noSlot * 2 > all.size()) {
            logger.warning("有 " + noSlot + "/" + all.size() + " 件商品没写 give-slot，"
                    + "它们会全部挤进 fallback-slot（当前 "
                    + plugin.getSettings().shopFallbackSlot + "）。"
                    + "如果你期望刀、手枪、主武器各自落一格，说明读到的是**未调整过的 WBD 原版**"
                    + "shop.yml —— 把调好的那份放到 shop.local-file 并把 shop.source 改成 local，"
                    + "或者在 config.yml 的 shop.slot-overrides / material-slots 里指定落点。"
                    + "当前读取的文件：" + (loadedFile == null ? "(未知)" : loadedFile.getPath()));
        }
    }

    /**
     * 把「发放物品到底是从哪来的」打进日志。
     *
     * <p>这是排查「发出来的枪/刀不对」的第一现场：一件模组物品只要出现在
     * 「材质简写」或「纯命令」的计数里，它就**不可能**被原样发出去 ——
     * 因为那两条路都不携带物品的 NBT，只能凭空造一个同材质的空壳。
     */
    private void logGiveAudit() {
        logger.info("发放数据审计：" + giveFromData + " 件用完整物品数据（give-data，原样还原），"
                + giveFromBrief + " 件只有材质简写，" + giveCommandOnly + " 件纯命令商品。");

        if (giveFromBrief > 0) {
            logger.warning("有 " + giveFromBrief + " 件商品只有材质简写、没有 give-data。"
                    + "这类商品是按材质当场新建的空壳，模组枪械/刀具拿不到正确的物品数据，"
                    + "发到手里必然是错的东西。请用 WBD 的商店编辑器重新保存一次，"
                    + "让它写出 give-data。");
        }
    }

    /**
     * 为每个材质分组分配一个背包落点。
     *
     * <p>规则：config.yml 里 {@code shop.material-slots} 显式指定的优先；
     * 其余的按**商品数量从多到少**依次占用空槽位。
     *
     * <p>为什么按数量排：枪械通常有几十把、投掷物只有几种，让件数最多的那类
     * 拿到靠前的槽位，主武器自然落在主手格上 —— 比按配置文件的书写顺序
     * 可靠得多（那份文件是编辑器生成的，顺序不受控）。
     *
     * <p>分配结果会打进日志：服主不用猜「手雷到底进了第几格」，
     * 想改就照着在 material-slots 里写一行。
     */
    private void computeMaterialSlots() {
        materialSlots.clear();
        materialSlots.putAll(plugin.getSettings().shopMaterialSlots);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ShopItem item : allItems()) {
            // 自己指定了 give-slot 的商品不参与分组，它落在自己声明的地方
            if (item.giveSlot() >= 0) {
                continue;
            }
            counts.merge(item.display().getType().name(), 1, Integer::sum);
        }

        List<String> remaining = new ArrayList<>();
        for (String key : counts.keySet()) {
            if (!materialSlots.containsKey(key)) {
                remaining.add(key);
            }
        }
        remaining.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));

        Set<Integer> used = new HashSet<>(materialSlots.values());
        int next = 0;
        int fallback = plugin.getSettings().shopFallbackSlot;
        for (String key : remaining) {
            while (used.contains(next) && next < 36) {
                next++;
            }
            int slot = next < 36 ? next : fallback;
            materialSlots.put(key, slot);
            used.add(slot);
            next++;
        }

        if (!materialSlots.isEmpty()) {
            logger.info("商店落点分组（同组互相替换，异组并存）：");
            materialSlots.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(e -> logger.info("    槽位 " + e.getValue() + " ← "
                            + e.getKey() + "（" + counts.getOrDefault(e.getKey(), 0) + " 件）"));
            logger.info("    想固定某一类的落点，在 config.yml 的 shop.material-slots 里指定即可。");
        }
    }

    /** 全部商品（公共 + 阵营专属），统计与 /tdm shopids 都用它。 */
    public List<ShopItem> allItems() {
        List<ShopItem> out = new ArrayList<>(commonItems);
        for (TeamShop shop : teamShops.values()) {
            out.addAll(shop.items());
        }
        out.sort(java.util.Comparator.comparing(ShopItem::id));
        return out;
    }

    private void readOpenItem(ConfigurationSection node) {
        // 默认值与 WBD 的写法一致：绿宝石、第 8 格
        Material material = Material.EMERALD;
        String name = "&a装备菜单";
        int slot = 8;

        if (node != null) {
            Material parsed = Material.matchMaterial(node.getString("material", "EMERALD"));
            if (parsed != null && !parsed.isAir()) {
                material = parsed;
            }
            name = node.getString("name", name);
            slot = Math.max(0, Math.min(35, node.getInt("slot", 8)));
        }

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            stack.setItemMeta(meta);
        }
        this.openItem = stack;
        this.openItemSlot = slot;
    }

    private void readNavigation(ConfigurationSection node) {
        if (node == null) {
            previousItem = simpleIcon(Material.ARROW, "&e上一页");
            nextItem = simpleIcon(Material.ARROW, "&e下一页");
            infoItem = simpleIcon(Material.PAPER, "&6第 %page% / %pages% 页");
            return;
        }
        previousSlot = node.getInt("previous-slot", 45);
        infoSlot = node.getInt("info-slot", 49);
        nextSlot = node.getInt("next-slot", 53);
        previousItem = readIcon(node.getConfigurationSection("previous-item"),
                Material.ARROW, "&e上一页");
        nextItem = readIcon(node.getConfigurationSection("next-item"),
                Material.ARROW, "&e下一页");
        infoItem = readIcon(node.getConfigurationSection("info-item"),
                Material.PAPER, "&6第 %page% / %pages% 页");
    }

    private ItemStack readIcon(ConfigurationSection node, Material fallback, String fallbackName) {
        if (node == null) {
            return simpleIcon(fallback, fallbackName);
        }
        Material material = Material.matchMaterial(node.getString("material", fallback.name()));
        ItemStack stack = new ItemStack(material == null || material.isAir() ? fallback : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(node.getString("name", fallbackName)));
            List<String> lore = node.getStringList("lore");
            if (!lore.isEmpty()) {
                meta.setLore(Text.color(lore));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack simpleIcon(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<ShopItem> readItems(ConfigurationSection section, String label) {
        List<ShopItem> out = new ArrayList<>();
        if (section == null) {
            return out;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(id);
            if (node == null) {
                continue;
            }

            ShopItem item = readItem(id, node, label);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private ShopItem readItem(String id, ConfigurationSection node, String label) {
        ItemStack display = resolveItem(node, "display");
        if (display == null) {
            // 图标是必填的：没有它就等于菜单里一个看不见的按钮，
            // 与其留个空洞，不如直接跳过并说明原因
            logger.warning("[" + label + "] 商品 " + id + " 没有可用的 display，已跳过。");
            return null;
        }

        Resolved giveResolved = resolve(node, "give");
        ItemStack give = giveResolved.stack();

        List<String> commands = new ArrayList<>(node.getStringList("click-commands"));
        if (commands.isEmpty()) {
            // 旧字段名，WBD 也仍然兼容
            commands.addAll(node.getStringList("commands"));
        }

        recordGiveSource(id, giveResolved.source(), give, commands);

        return new ShopItem(
                id,
                Math.max(1, node.getInt("page", 1)),
                node.getInt("slot", -1),
                node.getString("team", "ANY"),
                // 价格来自 WBD 的 price 字段。死斗不再是全免费，这条数值生效了
                Math.max(0, node.getInt("price", 0)),
                node.getString("permission", ""),
                display,
                give,
                node.getInt("give-slot", -1),
                commands,
                node.getString("command-sender", "console"),
                Math.max(1, Math.min(64, node.getInt("command-repeat", 1))),
                // 默认**不**关闭菜单：死斗里玩家的装备是逐件选的（武器、投掷物、护甲），
                // 点一下就关会逼着他反复右键。WBD 那边的语义也是这样 ——
                // 它的文档写的是「close-after-click 为 true 时自动关闭」，
                // 即默认就是开着菜单让人接着点。
                node.getBoolean("close-after-click", false));
    }

    /**
     * 记下一件商品的发放物品来源，供加载审计与默认装备校验使用。
     *
     * <p>同一 id 在公共段与阵营段里重名时只保留最先记下的那次：
     * 这种重名本来就会让 {@code findItem} 的结果变得不可预期，
     * 报出来比悄悄选一个更有用。
     */
    private void recordGiveSource(String id, ItemSource source, ItemStack give, List<String> commands) {
        switch (source) {
            case DATA -> giveFromData++;
            case BRIEF -> giveFromBrief++;
            case NONE -> giveCommandOnly++;
        }
        giveSources.putIfAbsent(id.toLowerCase(java.util.Locale.ROOT), source);

        if (source == ItemSource.NONE && give == null && commands.isEmpty()) {
            logger.warning("商品 " + id + " 既没有可发放的物品，也没有任何命令 —— 点了不会有反应。");
        }
    }

    /**
     * 这件商品的发放物品是不是 WBD 序列化数据的原样还原（{@code give-data}）。
     *
     * <p>{@code true} 意味着发出去的就是配置里存的那件物品本体：模组枪械的枪械 id、
     * 配件、弹药、皮肤 NBT 一个不少。{@code false} 意味着要么只有材质简写，
     * 要么是纯命令商品 —— 那两种情况都发不出模组物品。
     *
     * @return 没有该 id 的记录时返回 false（宁可不给，也不发错的东西）
     */
    public boolean isGiveFromData(String id) {
        return id != null
                && giveSources.get(id.toLowerCase(java.util.Locale.ROOT)) == ItemSource.DATA;
    }

    /**
     * 取商品的实际物品：优先完整序列化数据，退回只写了材质的简写形式。
     *
     * <p>这个优先级与 WBD 一致 —— {@code *-data} 是编辑器生成的权威数据，
     * 简写字段只是给人看的摘要。
     */
    private ItemStack resolveItem(ConfigurationSection node, String key) {
        return resolve(node, key).stack();
    }

    /**
     * 与 {@link #resolveItem} 同义，但额外告诉你这份物品是「原样还原的」还是「重建的空壳」。
     *
     * <p>加载时要靠这个来源打审计日志 —— 服主最需要知道的不是「读到几件商品」，
     * 而是「发出去的是不是读回来的那件东西」。
     */
    private Resolved resolve(ConfigurationSection node, String key) {
        ItemStack fromData = deserialize(node.getString(key + "-data"));
        if (fromData != null) {
            return new Resolved(fromData, ItemSource.DATA);
        }

        ConfigurationSection brief = node.getConfigurationSection(key);
        if (brief == null) {
            return new Resolved(null, ItemSource.NONE);
        }
        // 纯命令商品会写一个占位材质（例如 BARRIER），那不代表真的发放它
        Material material = Material.matchMaterial(brief.getString("material", ""));
        if (material == null || material.isAir()) {
            return new Resolved(null, ItemSource.NONE);
        }

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = brief.getString("name");
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(Text.color(name));
            }
            List<String> lore = brief.getStringList("lore");
            if (!lore.isEmpty()) {
                meta.setLore(Text.color(lore));
            }
            stack.setItemMeta(meta);
        }
        return new Resolved(stack, ItemSource.BRIEF);
    }

    /** 还原 WBD 写入的 Bukkit 序列化物品数据。 */
    private ItemStack deserialize(String data) {
        if (!ItemCodec.isEncoded(data)) {
            return null;
        }
        ItemStack stack = ItemCodec.decode(data);
        if (stack == null) {
            // 版本不匹配、数据被截断等都可能走到这里。商店少一件商品可以接受，
            // 但整份配置因为一件坏数据全部读不出来就不行了
            logger.warning("有一件商品的物品数据无法还原（可能是版本不一致或数据被截断）。");
        }
        return stack;
    }

    public boolean isEmpty() {
        return commonItems.isEmpty() && teamShops.isEmpty();
    }

    // ------------------------------------------------------------------
    // 商店入口物品
    // ------------------------------------------------------------------

    /** 生成「右键打开商店」的物品。 */
    public ItemStack createOpenItem() {
        return openItem == null ? new ItemStack(Material.EMERALD) : openItem.clone();
    }

    public int getOpenItemSlot() {
        return openItemSlot;
    }

    /**
     * 该物品是不是商店入口。
     *
     * <p>直接比对整件物品（材质 + 显示名 + lore），而不是打标记：
     * 因为这份物品来自服主自己的 shop.yml，比对内容才能如实反映配置改动 ——
     * 服主换了材质或名字后立刻生效，不会留下一件打不开的旧物品。
     */
    public boolean isOpenItem(ItemStack stack) {
        if (stack == null || openItem == null || stack.getType() != openItem.getType()) {
            return false;
        }
        ItemMeta a = stack.getItemMeta();
        ItemMeta b = openItem.getItemMeta();
        if (a == null || b == null) {
            return a == b;
        }
        return java.util.Objects.equals(a.getDisplayName(), b.getDisplayName())
                && java.util.Objects.equals(a.getLore(), b.getLore());
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    /** 给玩家打开指定页。 */
    public void open(Player player, int page, String teamId) {
        if (isEmpty()) {
            player.sendMessage(Text.color("&c商店里还没有配置任何商品。"));
            return;
        }

        List<ShopItem> items = itemsFor(teamId);
        int totalPages = pagesFor(teamId);
        int current = Math.max(1, Math.min(totalPages, page));

        ShopHolder holder = new ShopHolder(current, teamId);
        Inventory inventory = Bukkit.createInventory(holder, size,
                Text.color(fillTitle(titleFor(teamId), current, totalPages, teamId)));
        holder.setInventory(inventory);

        for (ShopItem item : items) {
            if (item.page() != current || item.slot() < 0 || item.slot() >= size) {
                continue;
            }
            inventory.setItem(item.slot(), decorate(item, player));
        }

        // 翻页按钮。故意在商品之后放置：按钮占据的槽位应当始终可用，
        // 万一服主把商品摆到了按钮格上，宁可让按钮赢，也不要出现点不动的情况
        if (current > 1) {
            inventory.setItem(previousSlot, previousItem.clone());
        }
        if (current < totalPages) {
            inventory.setItem(nextSlot, nextItem.clone());
        }
        inventory.setItem(infoSlot, buildInfoIcon(current, totalPages, teamId));

        player.openInventory(inventory);
    }

    private ItemStack buildInfoIcon(int page, int totalPages, String teamId) {
        ItemStack stack = infoItem.clone();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(fillTitle(
                    meta.getDisplayName() == null ? "" : meta.getDisplayName(), page, totalPages, teamId)));
            if (meta.getLore() != null) {
                List<String> lore = new ArrayList<>();
                for (String line : meta.getLore()) {
                    lore.add(Text.color(fillTitle(line, page, totalPages, teamId)));
                }
                meta.setLore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 按阵营过滤后的商品表：阵营专属会覆盖同 ID 的公共商品。 */
    private List<ShopItem> itemsFor(String teamId) {
        Map<String, ShopItem> merged = new java.util.LinkedHashMap<>();
        for (ShopItem item : commonItems) {
            merged.put(item.id(), item);
        }
        TeamShop shop = teamShops.get(teamId == null ? "" : teamId.toUpperCase(java.util.Locale.ROOT));
        if (shop != null) {
            for (ShopItem item : shop.items()) {
                merged.put(item.id(), item);
            }
        }

        List<ShopItem> out = new ArrayList<>();
        for (ShopItem item : merged.values()) {
            if (item.availableTo(teamId)) {
                out.add(item);
            }
        }
        return out;
    }

    private int pagesFor(String teamId) {
        TeamShop shop = teamShops.get(teamId == null ? "" : teamId.toUpperCase(java.util.Locale.ROOT));
        return shop == null ? pages : shop.pages();
    }

    private String titleFor(String teamId) {
        TeamShop shop = teamShops.get(teamId == null ? "" : teamId.toUpperCase(java.util.Locale.ROOT));
        return shop == null ? title : shop.title();
    }

    /** 替换标题与说明里的变量。 */
    private String fillTitle(String template, int page, int totalPages, String teamId) {
        String teamName = teamId == null ? "" : teamId;
        if ("T".equalsIgnoreCase(teamId)) {
            teamName = "T 方";
        } else if ("CT".equalsIgnoreCase(teamId)) {
            teamName = "CT 方";
        }
        return template
                .replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(totalPages))
                .replace("%team%", teamId == null ? "" : teamId)
                .replace("%team_name%", teamName);
    }

    /** 给图标补上「当前能不能买得起」的提示，让玩家不必点一下才知道。 */
    private ItemStack decorate(ShopItem item, Player player) {
        ItemStack stack = item.display().clone();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        int price = item.price();

        if (!item.permission().isBlank() && !player.hasPermission(item.permission())) {
            lore.add(Text.color("&c你没有权限购买"));
        } else if (price > 0 && plugin.getSettings().economyEnabled) {
            TdmMatch match = plugin.getMatchManager().getMatch(player);
            TdmPlayer record = match == null ? null : match.getPlayer(player);
            int money = record == null ? 0 : record.money();
            boolean affordable = money >= price;
            // 把「买不起」直接写在图标上：点下去才被拒绝的体验很差，
            // 尤其是在一屏几十件商品里逐个试的时候
            lore.add(Text.color((affordable ? "&a$" + price : "&c$" + price + " &7(金钱不足)")));
            lore.add(Text.color("&7余额 &f$" + money));
        } else {
            lore.add(Text.color("&a免费 · 点击选择"));
        }

        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    /**
     * 处理菜单里的一次点击。
     *
     * @param rawSlot 视图内的原始槽位
     * @return 是否应当关闭菜单
     */
    public boolean handleClick(Player player, ShopHolder holder, int rawSlot) {
        int page = holder.page();
        String teamId = holder.teamId();
        int totalPages = pagesFor(teamId);

        if (rawSlot == previousSlot && page > 1) {
            open(player, page - 1, teamId);
            return false;
        }
        if (rawSlot == nextSlot && page < totalPages) {
            open(player, page + 1, teamId);
            return false;
        }
        if (rawSlot == infoSlot) {
            return false;
        }

        for (ShopItem item : itemsFor(teamId)) {
            if (item.page() == page && item.slot() == rawSlot) {
                give(player, item);
                return item.closeAfterClick();
            }
        }
        return false;
    }

    /** 发放一件商品：验权限、扣钱、清同组旧物、放新的。 */
    public void give(Player player, ShopItem item) {
        if (!item.permission().isBlank() && !player.hasPermission(item.permission())) {
            player.sendMessage(Text.color("&c你没有权限购买这件商品。"));
            return;
        }

        // 限购先拦，钱还没扣，干净
        if (!categories.allowPurchase(player, item)) {
            return;
        }

        // 扣钱。经济关掉（或商品价格为 0）时这整段跳过
        TdmMatch match = plugin.getMatchManager().getMatch(player);
        TdmPlayer record = match == null ? null : match.getPlayer(player);
        int price = item.price();
        if (price > 0 && plugin.getSettings().economyEnabled) {
            if (record == null) {
                player.sendMessage(Text.color("&c你现在不在比赛中，无法购买。"));
                return;
            }
            if (record.money() < price) {
                player.sendMessage(Text.color("&c金钱不足：需要 &e" + price
                        + "&c，你只有 &e" + record.money() + "&c。"));
                return;
            }
            // 先扣钱再发货：反过来的话，发放过程里任何一步出错都会白送一件
            record.setMoney(record.money() - price);
        }

        applyTo(player, item);

        String suffix = price > 0 && plugin.getSettings().economyEnabled
                ? " &7(-&e" + price + "&7)"
                : "";
        player.sendMessage(Text.color("&a已选择：&f" + displayNameOf(item)
                + suffix + (record != null && plugin.getSettings().economyEnabled
                        ? " &7余额 &e" + record.money() : "")));
    }

    /**
     * 免费发放一件商品：不验权限、不扣钱、不播报。
     *
     * <p>给「开局默认装备」用。走的是和购买完全相同的落点与覆盖逻辑，
     * 所以默认装备落在哪一格、和玩家自己买它时一模一样 —— 不必再维护第二套位置规则。
     */
    public void grantFree(Player player, ShopItem item) {
        applyTo(player, item);
    }

    /** 真正把商品塞给玩家：清掉同组旧物 → 放新的 → 跑命令。 */
    private void applyTo(Player player, ShopItem item) {
        PlayerInventory inventory = player.getInventory();
        int slot = resolveSlot(item);
        String group = groupKeyOf(item);

        // 先清掉背包里本组的所有商店物品，**再**放新的。
        // 只覆盖落点那一格是不够的 —— 玩家把买到的东西往背包里一挪，那一格就空了，
        // 再买一次新的进来，旧的还躺在别处，几次下来就是一背包枪。
        purgeGroup(inventory, group);

        if (item.give() != null) {
            ItemStack stack = item.give().clone();
            // 标记是可关的：给物品写一个 PDC 标记，能让「再买同组的自动换掉旧的」
            // 生效；但它确实动了物品的 ItemMeta。想追求「发出去的和配置里存的
            // 一字不差」就把 shop.tag-granted-items 关掉，代价是只能覆盖目标槽位。
            if (plugin.getSettings().shopTagGrantedItems) {
                tagGroup(stack, group);
            }
            categories.place(player, inventory, stack, categories.classify(item), slot);
        }

        runCommands(player, item);
        player.updateInventory();
    }

    /**
     * 按 id 找商品：先看阵营专属，再看公共。
     *
     * @return 找不到返回 null
     */
    public ShopItem findItem(String id, String teamId) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (teamId != null && !teamId.isBlank()) {
            TeamShop shop = teamShops.get(teamId.toUpperCase(java.util.Locale.ROOT));
            if (shop != null) {
                for (ShopItem item : shop.items()) {
                    if (item.id().equalsIgnoreCase(id)) {
                        return item;
                    }
                }
            }
        }
        for (ShopItem item : commonItems) {
            if (item.id().equalsIgnoreCase(id)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 清除背包里属于指定分组、且**由商店发出**的物品。
     *
     * <p>这是「重复购买不会堆满背包」真正成立的地方。只覆盖落点那一格挡不住囤积：
     * 玩家把买到的东西往背包里一挪，那一格就空了，再买一次新的进来，
     * 旧的还躺在别处 —— 反复几次就是一背包枪。
     *
     * <p>只认带标记的物品：玩家从地上捡的、别人丢的同款没有标记，不会被误删。
     * 护甲槽与副手也要一起扫，那两个位置不属于 {@code getContents()}。
     */
    private void purgeGroup(PlayerInventory inventory, String group) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isTagged(inventory.getItem(i), group)) {
                inventory.setItem(i, null);
            }
        }
        if (isTagged(inventory.getHelmet(), group)) {
            inventory.setHelmet(null);
        }
        if (isTagged(inventory.getChestplate(), group)) {
            inventory.setChestplate(null);
        }
        if (isTagged(inventory.getLeggings(), group)) {
            inventory.setLeggings(null);
        }
        if (isTagged(inventory.getBoots(), group)) {
            inventory.setBoots(null);
        }
        if (isTagged(inventory.getItemInOffHand(), group)) {
            inventory.setItemInOffHand(null);
        }
    }

    /** 给一件商店发出的物品打分组标记。 */
    private void tagGroup(ItemStack stack, String group) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(groupKey, PersistentDataType.STRING, group);
        stack.setItemMeta(meta);
    }

    /** 该物品是不是本组由商店发出的东西。 */
    private boolean isTagged(ItemStack stack, String group) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        String stored = meta.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
        return group.equals(stored);
    }

    /**
     * 决定这件商品落在背包哪一格。
     *
     * <p>优先级：config.yml 的商品级 {@code slot-overrides} → 商品自己的
     * {@code give-slot} → **同材质分组槽位** → {@code shop.fallback-slot}。
     *
     * <p>给 config.yml 最高优先级是刻意的：服主那份 shop.yml 通常是从爆破模式
     * 直接共用过来的，里面 {@code give-slot} 大量是 -1（那边有经济系统，
     * 买什么都往空位塞）。要在死斗里换一套落点规则，就得能在**不修改**
     * 那份配置的前提下覆盖它。
     */
    private int resolveSlot(ShopItem item) {
        // 分类有明确落点就用它；没有才回去看商店配置的 override / give-slot
        int byCategory = categories.slotFor(categories.classify(item));
        if (byCategory >= 0) {
            return byCategory;
        }
        Integer override = plugin.getSettings().shopSlotOverrides.get(item.id());
        if (override != null) {
            return override;
        }
        if (item.giveSlot() >= 0) {
            return item.giveSlot();
        }
        Integer grouped = materialSlots.get(item.display().getType().name());
        return grouped != null ? grouped : plugin.getSettings().shopFallbackSlot;
    }

    /**
     * 分组键 —— **按落点**，不是按材质。
     *
     * <p>落点相同就意味着「互相替换」，落点不同就意味着「并存」，这正是玩家对
     * 一个格子一份装备的直觉。
     *
     * <p>这里踩过一个很隐蔽的坑：最初按商品图标的材质分组，看起来挺合理
     * （枪是 TACZ_MODERN_KINETIC_GUN、投掷物是 LRTACTICAL_THROWABLE），
     * 但**刀、手枪、主武器的图标材质是同一个** —— 那只是 TacZ 的通用底材，
     * 真正区别在 NBT 里。于是三者被归成一组：买把刀会把枪顶掉，
     * 发默认装备时刚给的刀又被随后发的手枪覆盖掉，表现成「刀根本没发到手上」。
     *
     * <p>按落点分组就没有这个问题：给它们配了不同的 give-slot，它们就是不同的组。
     * 而没配 give-slot 的商品仍走材质分组兜底，行为不变。
     */
    private String groupKeyOf(ShopItem item) {
        LoadoutCategories.Category category = categories.classify(item);
        // 同一类别的商品共用一组：买第二把手枪会把第一把清掉，
        // 而不是因为它落在别的格子就并排躺着
        if (category != LoadoutCategories.Category.NONE) {
            return "cat:" + category.key();
        }
        return "slot:" + resolveSlot(item);
    }

    private void runCommands(Player player, ShopItem item) {
        if (item.commands().isEmpty()) {
            return;
        }

        List<String> commands = item.commands();
        String sender = item.commandSender();
        int repeat = item.commandRepeat();

        // 延后一 tick：模组命令往往要读玩家当前的背包状态，而 updateInventory
        // 的客户端同步是异步的，抢在同一 tick 里容易被随后的同步包冲掉
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int i = 0; i < repeat; i++) {
                for (String raw : commands) {
                    String command = raw
                            .replace("%player%", player.getName())
                            .replace("%uuid%", player.getUniqueId().toString())
                            .replace("%item%", displayNameOf(item));
                    if ("player".equalsIgnoreCase(sender)) {
                        player.performCommand(command.startsWith("/") ? command.substring(1) : command);
                    } else {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    }
                }
            }
        });
    }

    private String displayNameOf(ShopItem item) {
        ItemMeta meta = item.display().getItemMeta();
        if (meta != null && meta.getDisplayName() != null) {
            return Text.strip(meta.getDisplayName());
        }
        return item.id();
    }

    /** 菜单尺寸必须是 9 的倍数且在 9-54 之间。 */
    private int clampPageSize(int raw) {
        int clamped = Math.max(9, Math.min(54, raw));
        return clamped - (clamped % 9);
    }

    /** 兼容旧接口，供出生装备使用。 */
    public ItemStack createShopItem() {
        return createOpenItem();
    }

    /** 兼容旧接口。 */
    public boolean isShopItem(ItemStack stack) {
        return isOpenItem(stack);
    }

    /** 兼容旧接口：按玩家所在阵营打开。 */
    public void open(Player player) {
        String teamId = null;
        var match = plugin.getMatchManager() == null ? null : plugin.getMatchManager().getMatch(player);
        if (match != null && match.getPlayer(player) != null) {
            teamId = match.getPlayer(player).team().id();
        }
        open(player, 1, teamId);
    }
}

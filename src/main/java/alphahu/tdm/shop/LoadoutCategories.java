package alphahu.tdm.shop;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 商品分类与落点管理。
 *
 * <h2>为什么死斗也要这一层</h2>
 *
 * <p>死斗的商店原来只有一条规则：{@code give-slot} 或「第一个空位」。
 * 结果是买一把手枪，第二把会挤进背包；买配件，配件占掉快捷栏；
 * 背包里随手捡的泥土能把主武器顶走。分类机制把「哪类东西该在哪一格」
 * 明确下来，购买、发放、拾取全部按同一套规则走。
 *
 * <h2>规则从哪来</h2>
 *
 * <p>{@code plugins/WBDTeamDeathmatch/item-categories.yml}，首次启动自动释放模板。
 * 归属判定按优先级：<b>商品 id</b> → <b>材质</b> → <b>显示名</b> → {@code types}（模组类型）。
 * 发放时会往物品写一个 PDC 标记，所以玩家把东西挪到背包以后依然认得出它是哪一类。
 *
 * <h2>和 WBDAddon 那份的关系</h2>
 *
 * <p>格式刻意对齐 WBDAddon 的 {@code item-categories.yml}：同名字段（
 * {@code categories.*.slot / limit / enabled}）含义一致，方便两边共用一份对照表，
 * 但死斗不依赖 WBDAddon——没装它这套规则照样工作。
 *
 * @author AlphaHu
 */
public final class LoadoutCategories {

    /** 类别。{@code NONE} 表示「不归这套规则管」。 */
    public enum Category {
        PRIMARY("主武器"),
        SECONDARY("副武器"),
        MELEE("近战武器"),
        THROWABLE("投掷物"),
        ATTACHMENT("配件"),
        UTILITY("功能道具"),
        NONE("物品");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Category parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "primary", "gun", "rifle" -> PRIMARY;
                case "secondary", "pistol" -> SECONDARY;
                case "melee", "knife" -> MELEE;
                case "throwable", "grenade" -> THROWABLE;
                case "attachment", "attach" -> ATTACHMENT;
                case "utility", "misc" -> UTILITY;
                case "none", "ignore", "skip", "off" -> NONE;
                default -> null;
            };
        }
    }

    /** 文件里没写时用的默认值：和 WBDAddon 的默认布局一致。 */
    private static final Map<Category, Integer> DEFAULT_SLOTS = Map.of(
            Category.PRIMARY, 0,
            Category.SECONDARY, 1,
            Category.MELEE, 2,
            Category.THROWABLE, 3);

    /** 功能道具默认落点（第 6、7 格）。 */
    private static final List<Integer> DEFAULT_UTILITY_SLOTS = List.of(5, 6);

    private final WBDTeamDeathmatch plugin;
    private final File file;
    private final NamespacedKey tagKey;

    private final Map<String, Category> ids = new HashMap<>();
    /** 枪械 id -> 类别（如 tacz:p320 -> secondary），靠 TacZ 桥接读 id。 */
    private final Map<String, Category> guns = new HashMap<>();
    private final Map<String, Category> materials = new HashMap<>();
    private final Map<String, Category> names = new HashMap<>();
    private final Map<String, Category> types = new HashMap<>();

    private final Map<Category, Boolean> enabled = new EnumMap<>(Category.class);
    private final Map<Category, Integer> slots = new EnumMap<>(Category.class);
    private final Map<Category, List<Integer>> multiSlots = new EnumMap<>(Category.class);
    private final Map<Category, Integer> limits = new EnumMap<>(Category.class);
    private final Map<Category, Boolean> replaceOnBuy = new EnumMap<>(Category.class);
    private final Set<Category> backpack = EnumSet.noneOf(Category.class);

    /** TacZ 桥接（反射持有，没装就是 null）。 */
    private Object taczApi;
    private java.lang.reflect.Method gunIdMethod;
    private boolean taczProbed;

    public LoadoutCategories(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "item-categories.yml");
        this.tagKey = new NamespacedKey(plugin, "loadout_category");
        load();
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    public void reload() {
        load();
    }

    private void load() {
        ids.clear();
        materials.clear();
        names.clear();
        types.clear();
        enabled.clear();
        slots.clear();
        multiSlots.clear();
        limits.clear();
        replaceOnBuy.clear();
        backpack.clear();

        for (Category category : Category.values()) {
            if (category == Category.NONE) continue;
            enabled.put(category, true);
            Integer slot = DEFAULT_SLOTS.get(category);
            if (slot != null) slots.put(category, slot);
            limits.put(category, defaultLimit(category));
            replaceOnBuy.put(category, true);
        }
        multiSlots.put(Category.UTILITY, new ArrayList<>(DEFAULT_UTILITY_SLOTS));
        backpack.add(Category.ATTACHMENT);

        if (!file.exists()) {
            writeTemplate();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        readTable(yaml, "ids", ids);
        readTable(yaml, "guns", guns);
        readTable(yaml, "materials", materials);
        readTable(yaml, "names", names);
        readTable(yaml, "types", types);

        ConfigurationSection root = yaml.getConfigurationSection("categories");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                Category category = Category.parse(key);
                if (category == null || category == Category.NONE) continue;
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;

                if (section.isSet("enabled")) {
                    enabled.put(category, section.getBoolean("enabled", true));
                }
                if (section.isSet("slot")) {
                    int slot = section.getInt("slot", -1);
                    if (slot >= 0 && slot <= 8) {
                        slots.put(category, slot);
                        multiSlots.remove(category);
                    }
                }
                if (section.isList("slots")) {
                    List<Integer> list = new ArrayList<>();
                    for (int slot : section.getIntegerList("slots")) {
                        if (slot >= 0 && slot <= 8 && !list.contains(slot)) {
                            list.add(slot);
                        }
                    }
                    if (!list.isEmpty()) {
                        multiSlots.put(category, list);
                        slots.remove(category);
                    }
                }
                if (section.isSet("limit")) {
                    limits.put(category, Math.max(0, section.getInt("limit", 0)));
                }
                if (section.isSet("on-buy")) {
                    String raw = String.valueOf(section.getString("on-buy", "replace"));
                    replaceOnBuy.put(category, !"keep".equalsIgnoreCase(raw)
                            && !"append".equalsIgnoreCase(raw));
                }
                if (section.isSet("to-backpack")) {
                    if (section.getBoolean("to-backpack", false)) {
                        backpack.add(category);
                    } else {
                        backpack.remove(category);
                    }
                }
            }
        }

        inheritFromWbdAddon(yaml);

        plugin.getLogger().info("商品分类已载入：id " + ids.size() + " / 枪械 " + guns.size()
                + " / 材质 " + materials.size()
                + " / 名称 " + names.size() + " / 类型 " + types.size() + " 条。");
    }

    /**
     * 继承 WBDAddon 的枪械分类表。
     *
     * <p>两边的 {@code item-categories.yml} 格式是同一套，服主在爆破模式那边
     * 已经按 GunId 标好的「哪把是手枪、哪把是步枪」没必要再抄一遍。
     * 本地写了的优先，本地没写才用对方的。
     *
     * <p>用 {@code inherit-wbdaddon: false} 可以关掉。
     */
    private void inheritFromWbdAddon(YamlConfiguration own) {
        if (!own.getBoolean("inherit-wbdaddon", true)) return;
        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir == null) return;
        File source = new File(new File(pluginsDir, "WBDAddon"), "item-categories.yml");
        if (!source.isFile()) return;
        try {
            YamlConfiguration other = YamlConfiguration.loadConfiguration(source);
            ConfigurationSection section = other.getConfigurationSection("guns");
            if (section == null) return;
            int added = 0;
            for (String gunId : section.getKeys(false)) {
                String key = gunId.trim().toLowerCase(Locale.ROOT);
                if (guns.containsKey(key)) continue;
                Category category = Category.parse(section.getString(gunId));
                if (category == null || category == Category.NONE) continue;
                guns.put(key, category);
                added++;
            }
            if (added > 0) {
                plugin.getLogger().info("已从 WBDAddon 继承 " + added + " 条枪械分类。");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("读取 WBDAddon 的 item-categories.yml 失败：" + t);
        }
    }

    private int defaultLimit(Category category) {
        return switch (category) {
            case PRIMARY, SECONDARY, MELEE -> 1;
            default -> 0;
        };
    }

    private void readTable(YamlConfiguration yaml, String path, Map<String, Category> target) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Category category = Category.parse(section.getString(key));
            if (category == null || category == Category.NONE) continue;
            target.put(normalizeKey(path, key), category);
        }
    }

    private String normalizeKey(String table, String raw) {
        String key = raw.trim();
        // 材质 / 类型都用大写比对；id 和显示名用小写
        if ("materials".equals(table) || "types".equals(table)) {
            return key.toUpperCase(Locale.ROOT);
        }
        return key.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public boolean enabled(Category category) {
        return enabled.getOrDefault(category, false);
    }

    /** 该类别固定在哪一格；{@code -1} 表示不接管（沿用商店配置的落点）。 */
    public int slotFor(Category category) {
        if (!enabled(category)) return -1;
        List<Integer> list = multiSlots.get(category);
        if (list != null && !list.isEmpty()) return list.get(0);
        return slots.getOrDefault(category, -1);
    }

    public List<Integer> slotsOf(Category category) {
        List<Integer> list = multiSlots.get(category);
        if (list != null && !list.isEmpty()) return list;
        Integer slot = slots.get(category);
        return slot == null ? List.of() : List.of(slot);
    }

    public int limitOf(Category category) {
        return enabled(category) ? limits.getOrDefault(category, 0) : 0;
    }

    public boolean toBackpack(Category category) {
        return backpack.contains(category);
    }

    /** 所有「预留」的快捷栏格子。 */
    public Set<Integer> reservedSlots() {
        Set<Integer> result = new LinkedHashSet<>();
        for (Category category : Category.values()) {
            if (category == Category.NONE || !enabled(category)) continue;
            if (toBackpack(category)) continue;
            result.addAll(slotsOf(category));
        }
        result.removeIf(slot -> slot < 0 || slot > 8);
        return result;
    }

    /** 商品属于哪一类。 */
    public Category classify(ShopItem item) {
        if (item == null) return Category.NONE;
        Category byId = ids.get(item.id().toLowerCase(Locale.ROOT));
        if (byId != null && enabled(byId)) return byId;
        ItemStack give = item.give();
        if (give != null) {
            Category byItem = classify(give);
            if (byItem != Category.NONE) return byItem;
        }
        ItemStack display = item.display();
        if (display != null) {
            return classify(display);
        }
        return Category.NONE;
    }

    /** 背包里的物品属于哪一类（先看 PDC 标记，再看材质/名称）。 */
    public Category classify(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Category.NONE;
        Category tagged = readTag(stack);
        if (tagged != null && enabled(tagged)) return tagged;

        // 枪械：能读到 TacZ 的 GunId 就按 guns 表分类；
        // 表里没有的枪返回 NONE（不接管），免得把所有枪都当成主武器塞第 1 格
        String gunId = gunIdOf(stack);
        if (gunId != null) {
            Category byGun = guns.get(gunId.toLowerCase(Locale.ROOT));
            return byGun != null && enabled(byGun) ? byGun : Category.NONE;
        }

        Category byMaterial = materials.get(stack.getType().name().toUpperCase(Locale.ROOT));
        if (byMaterial != null && enabled(byMaterial)) return byMaterial;

        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Category byName = names.get(stripColor(meta.getDisplayName()).toLowerCase(Locale.ROOT));
            if (byName != null && enabled(byName)) return byName;
        }
        String material = stack.getType().name().toUpperCase(Locale.ROOT);
        Category byType = types.get(material);
        if (byType != null && enabled(byType)) return byType;
        return Category.NONE;
    }

    /**
     * 用反射问 TacZ 桥接要 GunId。
     *
     * <p>不直接依赖 {@code TacZSpigotApi}：死斗要保持「没装桥接也能跑」，
     * 所以只按接口全名去 ServiceManager 里找实现，找到了才调。
     */
    /** 对外：读某件物品的 GunId（读不到返回 null），给 /tdm shopids 用。 */
    public String gunId(ItemStack stack) {
        return gunIdOf(stack);
    }

    private String gunIdOf(ItemStack stack) {
        probeTacz();
        if (taczApi == null || gunIdMethod == null) return null;
        try {
            Object result = gunIdMethod.invoke(taczApi, stack);
            if (result instanceof java.util.Optional<?> optional && optional.isPresent()) {
                return String.valueOf(optional.get());
            }
        } catch (Throwable ignored) {
            // 版本差异 / 物品不是枪：当作读不到
        }
        return null;
    }

    private void probeTacz() {
        if (taczProbed) return;
        taczProbed = true;
        try {
            Class<?> apiClass = Class.forName("com.tacz.bridge.spigot.api.TacZSpigotApi");
            Object service = plugin.getServer().getServicesManager().load(apiClass);
            if (service == null) {
                plugin.getLogger().info("商品分类未检测到 TacZ 桥接，枪械只能按材质/名称归类。");
                return;
            }
            taczApi = service;
            gunIdMethod = service.getClass().getMethod("getItemGunId", ItemStack.class);
            plugin.getLogger().info("商品分类已接入 TacZ 桥接：枪械按 GunId 分类。");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("商品分类未检测到 TacZ 桥接，枪械只能按材质/名称归类。");
        } catch (Throwable t) {
            plugin.getLogger().warning("商品分类探测 TacZ 桥接失败：" + t);
        }
    }

    private String stripColor(String raw) {
        return Text.color(raw).replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }

    private Category readTag(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
        return raw == null ? null : Category.parse(raw);
    }

    private void writeTag(ItemStack stack, Category category) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, category.key());
        stack.setItemMeta(meta);
    }

    // ------------------------------------------------------------------
    // 购买
    // ------------------------------------------------------------------

    /**
     * 购买前的检查。超过上限就拦下来（此时钱还没扣）。
     *
     * @return true 表示放行
     */
    public boolean allowPurchase(Player player, ShopItem item) {
        Category category = classify(item);
        if (category == Category.NONE) return true;
        int limit = limitOf(category);
        if (limit <= 0) return true;

        int owned = count(player, category);
        if (owned < limit) return true;

        player.sendMessage(Text.color("&c你最多只能携带 &e" + limit + " &c件"
                + category.displayName() + "。"));
        return false;
    }

    /** 清掉同类旧物（on-buy: replace 时用）。 */
    public void replaceCategory(PlayerInventory inventory, Category category) {
        if (category == Category.NONE || !replaceOnBuy.getOrDefault(category, true)) return;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (classify(stack) == category) {
                inventory.setItem(slot, null);
            }
        }
    }

    /**
     * 把商品放进合适的格子。
     *
     * @param fallbackSlot 没接管该类别时，原本商店会用的落点
     */
    public void place(Player player, PlayerInventory inventory, ItemStack stack,
                      Category category, int fallbackSlot) {
        writeTag(stack, category);

        if (category == Category.NONE) {
            inventory.setItem(fallbackSlot, stack);
            return;
        }
        if (toBackpack(category)) {
            int empty = firstEmptyBackpackSlot(inventory);
            if (empty >= 0) {
                inventory.setItem(empty, stack);
            } else {
                inventory.addItem(stack);
            }
            return;
        }
        List<Integer> targets = slotsOf(category);
        if (targets.isEmpty()) {
            inventory.setItem(fallbackSlot, stack);
            return;
        }
        replaceCategory(inventory, category);
        for (int slot : targets) {
            ItemStack occupant = inventory.getItem(slot);
            if (occupant == null || occupant.getType().isAir()) {
                inventory.setItem(slot, stack);
                return;
            }
        }
        inventory.setItem(targets.get(0), stack);
    }

    private int firstEmptyBackpackSlot(PlayerInventory inventory) {
        for (int slot = 9; slot < inventory.getStorageContents().length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) return slot;
        }
        return -1;
    }

    private int count(Player player, Category category) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            if (classify(stack) == category) total++;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 定期归位
    // ------------------------------------------------------------------

    /** 给主循环用：遍历所有参赛者做一次归位。 */
    public void tickAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (plugin.getMatchManager() == null) continue;
            if (plugin.getMatchManager().getMatch(player) == null) continue;
            try {
                tick(player);
            } catch (Throwable t) {
                plugin.getLogger().warning("装备归位失败（" + player.getName() + "）：" + t);
            }
        }
    }

    /** 由主循环每秒调一次：把商品放回该待的格子，把杂物赶出预留格。 */
    public void tick(Player player) {
        PlayerInventory inventory = player.getInventory();
        boolean changed = false;

        changed |= moveOutOfReserved(inventory);
        changed |= placeCategoryItems(inventory, Category.PRIMARY);
        changed |= placeCategoryItems(inventory, Category.SECONDARY);
        changed |= placeCategoryItems(inventory, Category.MELEE);
        changed |= placeCategoryItems(inventory, Category.THROWABLE);
        changed |= moveUtility(inventory);
        changed |= moveAttachmentsToBackpack(inventory);

        if (changed) {
            player.updateInventory();
        }
    }

    private boolean placeCategoryItems(PlayerInventory inventory, Category category) {
        if (!enabled(category)) return false;
        List<Integer> targets = slotsOf(category);
        if (targets.isEmpty()) return false;

        boolean changed = false;
        List<Integer> found = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (classify(stack) == category) found.add(slot);
        }
        if (found.isEmpty()) return false;

        // 已经在目标格里的不动
        for (int slot : targets) {
            found.remove(Integer.valueOf(slot));
        }
        for (int source : found) {
            int target = -1;
            for (int slot : targets) {
                ItemStack occupant = inventory.getItem(slot);
                if (occupant == null || occupant.getType().isAir()) {
                    target = slot;
                    break;
                }
            }
            if (target < 0) break;
            inventory.setItem(target, inventory.getItem(source));
            inventory.setItem(source, null);
            changed = true;
        }
        return changed;
    }

    private boolean moveOutOfReserved(PlayerInventory inventory) {
        Set<Integer> reserved = reservedSlots();
        if (reserved.isEmpty()) return false;
        boolean changed = false;
        for (int slot : reserved) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            Category actual = classify(stack);
            if (actual != Category.NONE && slotsOf(actual).contains(slot)) continue;

            int target = -1;
            for (int candidate = 0; candidate < 9; candidate++) {
                if (reserved.contains(candidate)) continue;
                ItemStack occupant = inventory.getItem(candidate);
                if (occupant == null || occupant.getType().isAir()) {
                    target = candidate;
                    break;
                }
            }
            if (target < 0) {
                target = firstEmptyBackpackSlot(inventory);
            }
            if (target < 0) continue;
            inventory.setItem(target, stack);
            inventory.setItem(slot, null);
            changed = true;
        }
        return changed;
    }

    private boolean moveUtility(PlayerInventory inventory) {
        if (!enabled(Category.UTILITY)) return false;
        List<Integer> targets = slotsOf(Category.UTILITY);
        if (targets.isEmpty()) return false;
        boolean changed = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (classify(stack) != Category.UTILITY) continue;
            if (targets.contains(slot)) continue;
            int target = -1;
            for (int candidate : targets) {
                ItemStack occupant = inventory.getItem(candidate);
                if (occupant == null || occupant.getType().isAir()) {
                    target = candidate;
                    break;
                }
            }
            if (target < 0) target = firstEmptyBackpackSlot(inventory);
            if (target < 0) continue;
            inventory.setItem(target, stack);
            inventory.setItem(slot, null);
            changed = true;
        }
        return changed;
    }

    private boolean moveAttachmentsToBackpack(PlayerInventory inventory) {
        if (!enabled(Category.ATTACHMENT) || !toBackpack(Category.ATTACHMENT)) return false;
        boolean changed = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (classify(stack) != Category.ATTACHMENT) continue;
            int target = firstEmptyBackpackSlot(inventory);
            if (target < 0) continue;
            inventory.setItem(target, stack);
            inventory.setItem(slot, null);
            changed = true;
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    private void writeTemplate() {
        String content = """
                # ==========================================================
                # 商品分类与落点
                # ----------------------------------------------------------
                # 买到的商品按类别固定到某个快捷栏格子，其它物品不许抢占。
                # 归属判定顺序：ids -> materials -> names -> types
                #
                # 下面的 id 只是示例，按你自己的商店（shop.yml 里的商品 id）改。
                # 商品 id 不写也能用：材质和名称的规则一样生效。
                # ==========================================================

                # 按商品 id 归类（最省事，优先级最高）
                ids:
                  # ak47: primary
                  # p320: secondary
                  # knife: melee
                  # he: throwable
                  # smoke: throwable
                  # defuse-kit: utility
                  # scope: attachment

                # 枪械按 GunId 归类（装了 TacZ 桥接才读得到；
                # 表里没写的手枪/步枪不会被接管，保持商店原本的落点）
                # 装了 WBDAddon 时会自动继承它的 guns 表（同格式），本地写了以本地为准；
                # 不想继承就把 inherit-wbdaddon 设成 false
                inherit-wbdaddon: true

                guns:
                  # tacz:ak47: primary
                  # tacz:p320: secondary
                  # twx:twx_kcb: primary

                # 按 Bukkit 材质归类（背包里的物品也认这个）
                materials:
                  LRTACTICAL_THROWABLE: throwable
                  LRTACTICAL_MELEE: melee
                  TACZ_ATTACHMENT: attachment

                # 按显示名归类（忽略颜色代码）
                names: {}
                  # "AK-47": primary

                # 模组类型兜底（材质的进一步细分，一般不用改）
                types: {}
                  # TACZ_ATTACHMENT: attachment

                # 每个类别的规则
                categories:
                  primary:
                    enabled: true
                    slot: 0          # 快捷栏第 1 格
                    limit: 1         # 最多几把，0 = 不限
                    on-buy: replace  # replace = 买新的换掉旧的；keep = 允许同时持有
                  secondary:
                    enabled: true
                    slot: 1
                    limit: 1
                    on-buy: replace
                  melee:
                    enabled: true
                    slot: 2
                    limit: 1
                    on-buy: replace
                  throwable:
                    enabled: true
                    slot: 3
                    limit: 0         # 0 = 不限；想限 4 颗就写 4
                  attachment:
                    enabled: true
                    to-backpack: true   # 配件不占快捷栏，直接进背包 9-35
                  utility:
                    enabled: true
                    slots: [5, 6]    # 功能道具（拆弹器、医疗包）只准待这几格
                """;
        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                return;
            }
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            plugin.getLogger().info("已生成商品分类模板：item-categories.yml");
        } catch (IOException e) {
            plugin.getLogger().warning("生成 item-categories.yml 失败：" + e.getMessage());
        }
    }
}

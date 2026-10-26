package alphahu.tdm.game;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.config.TdmSettings;
import alphahu.tdm.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.UUID;

/**
 * 队伍队服：给参赛玩家戴一件队伍配色的皮革装备，用来一眼分辨敌我。
 *
 * <h2>为什么是「染色皮革 + 负护甲修饰」</h2>
 * 原版没法给物品直接改护甲值，但 {@code generic.armor} 是个普通属性，
 * 皮革装备自带的护甲值同样是属性修饰。所以给它挂一条等值的**负数**修饰，
 * 穿戴上以后净加成正好是 0 —— 外观留下了，护甲优势没有。
 *
 * <p>这条修饰同时挂 {@link ItemFlag#HIDE_ATTRIBUTES} 遮住 tooltip，
 * 否则玩家会在物品说明里看到一行莫名其妙的负护甲。
 *
 * <h2>为什么每秒校正一次</h2>
 * 队服的价值全在「所有人都看得见」：只要一个人能偷偷换掉它，这个功能就失效了。
 * 点击拦截能挡住大部分路径，但拖拽、快捷方式、别的插件仍可能动它，
 * 所以再加一道按秒的兜底校正。校正时如果槽位里有玩家自己的装备，
 * 会先挪回背包（不销毁），而散落在背包里的队服副本会被清掉 ——
 * 否则「取下来 → 系统补发一件」就成了复制。
 *
 * @author AlphaHu
 */
public final class TeamUniformService implements Listener {

    /** 固定 UUID：重复下发时替换同一条修饰，不会叠加。 */
    private static final UUID ARMOR_MODIFIER_UUID =
            UUID.fromString("6f1c2a44-9d3b-4f7e-8a21-2c5d7e9b0f13");

    /** 队服所在的盔甲槽。 */
    public enum Slot {
        HELMET(Material.LEATHER_HELMET, 1, 39),
        CHESTPLATE(Material.LEATHER_CHESTPLATE, 3, 38),
        LEGGINGS(Material.LEATHER_LEGGINGS, 2, 37),
        BOOTS(Material.LEATHER_BOOTS, 1, 36);

        private final Material material;
        private final int baseArmor;
        /** 玩家背包（{@link PlayerInventory}）里的槽位下标，点击拦截要用。 */
        private final int inventorySlot;

        Slot(Material material, int baseArmor, int inventorySlot) {
            this.material = material;
            this.baseArmor = baseArmor;
            this.inventorySlot = inventorySlot;
        }
    }

    private final WBDTeamDeathmatch plugin;
    private final NamespacedKey marker;
    private final Slot slot;

    /** WBDAddon 是否已经开着队伍护甲（决定本插件要不要让位）。 */
    private boolean wbdAddonOwns;

    public TeamUniformService(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "team_uniform");
        this.slot = parseSlot(plugin.getSettings().uniformSlot);
        refresh();
    }

    /**
     * 重新判定「这套队服现在该不该由本插件负责」，并把结论打进日志。
     *
     * <p>启动与 {@code /tdm reload} 时各跑一次。之所以要看对方的**配置文件**
     * 而不是问它的 API：WBDAddon 的对外 API 只暴露模式注册与玩家状态，
     * 不暴露「某个模块开没开」，而读它的 data 目录是现成的做法
     * （本插件读 WarZBombDefuse 的 arenas.yml 用的就是同一招）。
     *
     * <p>判定规则：
     * <ol>
     *   <li>WBDAddon 没装 → 本插件负责</li>
     *   <li>装了，但它的 config.yml 里 {@code modules.team-armor.enabled} 是 false
     *       → 本插件负责</li>
     *   <li>装了且没写这个键（配置文件不存在 / 旧版本没有该模块）→ 本插件负责</li>
     *   <li>装了且明确开启 → 让位，用 WBDAddon 的</li>
     * </ol>
     */
    public void refresh() {
        TdmSettings settings = plugin.getSettings();
        this.wbdAddonOwns = settings.uniformFollowWbdAddon && wbdAddonTeamArmorEnabled();

        if (!settings.uniformEnabled) {
            return;
        }
        if (wbdAddonOwns) {
            plugin.getLogger().info("队伍队服：检测到 WBDAddon 已开启队伍护甲"
                    + "（modules.team-armor.enabled），本插件让位，不再套自己的队服。");
        } else {
            plugin.getLogger().info("队伍队服：由本插件负责（槽位 " + slot.name()
                    + "，T " + settings.uniformTColor + " / CT " + settings.uniformCtColor + "）。");
        }
    }

    /** 本插件的队服此刻是否应当生效。 */
    private boolean active() {
        return plugin.getSettings().uniformEnabled && !wbdAddonOwns;
    }

    /**
     * WBDAddon 的队伍护甲模块是否处于开启状态。
     *
     * <p>判定必须和 WBDAddon 自己的逻辑**完全一致**，否则仲裁会判反。它的
     * {@code ModuleManager.enableAll()} 是：
     * <pre>getBoolean("modules." + id + ".enabled", module.isEnabledByDefault())</pre>
     * 而 {@code TeamArmorModule.isEnabledByDefault()} 返回 <b>true</b>。
     * 也就是说：**配置里没有这个键时，模块是开着的**。
     *
     * <p>这里踩过一次：最初把「键不存在」当成「旧版没有该模块 → 不归它管」，
     * 但服务器上那份 config.yml 是很早以前生成的（新版加模块时 Bukkit 并不会
     * 把新键合并进已有文件），于是键确实不存在、模块却在跑，仲裁判反，
     * 本插件又去套自己的队服。
     *
     * <p>区分「旧版根本没有这个模块」靠的是**类是否存在**：用字符串方式探一下
     * WBDAddon 的类加载器，不产生编译期依赖，也就不违反本项目「只有 bridge
     * 类可以接触 alphahu.wbdaddon.*」的约定。
     */
    private boolean wbdAddonTeamArmorEnabled() {
        var addon = Bukkit.getPluginManager().getPlugin("WBDAddon");
        if (addon == null) {
            return false;
        }
        if (!moduleClassPresent(addon)) {
            // 旧版 WBDAddon 没有这个模块，队伍护甲自然不归它管
            return false;
        }
        File config = new File(addon.getDataFolder(), "config.yml");
        if (!config.isFile()) {
            // 插件在、配置还没生成：它启动时会用默认值（开启）
            return true;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config);
            // 缺省值与 TeamArmorModule.isEnabledByDefault() 保持一致，都是 true
            return yaml.getBoolean("modules.team-armor.enabled", true);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("读取 WBDAddon 的 config.yml 失败，"
                    + "队伍护甲按「未开启」处理：" + e.getMessage());
            return false;
        }
    }

    /**
     * WBDAddon 里是否存在「队伍护甲」模块类。
     *
     * <p>用字符串 + 对方的类加载器探测，**不引入编译期依赖**：本项目约定
     * 只有 {@code WbdAddonModeBridge} 可以出现 {@code alphahu.wbdaddon.*} 的
     * 类型引用，这里只写了一个类名字符串，不涉及 import。
     *
     * <p>{@code initialize=false}：只判断存在性，不触发静态初始化。
     */
    private static boolean moduleClassPresent(org.bukkit.plugin.Plugin addon) {
        try {
            Class.forName("alphahu.wbdaddon.module.TeamArmorModule", false,
                    addon.getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** 把配置里的槽位名解析成枚举；写错时退回头盔并告警。 */
    private static Slot parseSlot(String raw) {
        if (raw == null || raw.isBlank()) {
            return Slot.HELMET;
        }
        try {
            return Slot.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Slot.HELMET;
        }
    }

    /**
     * 给玩家穿上队伍队服。
     *
     * <p>必须在清空背包**之后**调用 —— {@code loadout.clear-inventory} 会把
     * 盔甲槽一起清掉，先穿后清等于白穿。
     */
    public void apply(Player player, TdmTeam team) {
        if (!active()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        // 槽位被占着：默认让位；overwrite-existing=true 时先把原护甲塞回背包再换
        if (occupied(inventory) && !isUniform(get(inventory))) {
            if (!overwriteExisting()) {
                return;
            }
            reclaim(player, get(inventory));
        }
        set(inventory, build(team));
        player.updateInventory();
    }

    /**
     * 兜底校正：槽位空着就补一件本队队服。
     *
     * <p><b>槽位被别的物品占着时默认让位，绝不抢占。</b>
     * 只有 {@code teammate.uniform.overwrite-existing: true} 才会顶掉，
     * 且原有护甲会先塞回背包（塞不下才掉在脚下），不会凭空销毁。
     *
     * <p>这里踩过一次很严重的坑：最初的做法是「把槽位里的东西挪进背包，
     * 再套上自己的」，看着很周到，实际会出人命 —— WBDAddon 有一个同类模块
     * （{@code TeamArmorModule}），它的逻辑是「头盔不是我的就直接覆盖」。
     * 两边都在每秒强制校正，于是：它覆盖我 → 我把它挪进背包再套我的 →
     * 它再覆盖……玩家背包里每秒多一件皮革头盔，**无限复制**。
     *
     * <p>现在默认「只填空槽」：任何一方占住之后，另一方都不会再动它。
     * 想让队服真正生效，就别和别的插件同时开同一个槽位的队服。
     */
    public void assertUniform(Player player, TdmTeam team) {
        if (!active()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (isUniform(get(inventory))) {
            return;
        }
        if (occupied(inventory)) {
            if (!overwriteExisting()) {
                return;
            }
            reclaim(player, get(inventory));
        }

        // 背包里散落的队服副本先清掉，避免「取下来 → 补发」变成复制
        purgeStray(inventory);

        set(inventory, build(team));
        player.updateInventory();
    }

    private boolean overwriteExisting() {
        return plugin.getSettings().uniformOverwriteExisting;
    }

    /** 把被顶掉的原有护甲塞回背包；塞不下就掉在脚下，不凭空销毁。 */
    private void reclaim(Player player, ItemStack old) {
        if (old == null || old.getType().isAir()) {
            return;
        }
        var leftover = player.getInventory().addItem(old.clone());
        leftover.values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    /** 该槽位是否已被任何物品占着（无论是不是队服）。 */
    private boolean occupied(PlayerInventory inventory) {
        ItemStack current = get(inventory);
        return current != null && !current.getType().isAir();
    }

    /** 离场时清掉队服。**必须排在快照还原之前**，否则会把玩家自己的盔甲一起清掉。 */
    public void remove(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (isUniform(get(inventory))) {
            set(inventory, null);
            player.updateInventory();
        }
        purgeStray(inventory);
    }

    /** 拦截对队服所在盔甲槽的点击 —— 点一下就能取下来的话，兜底校正会一直和玩家打架。 */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!active()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getClickedInventory() instanceof PlayerInventory inventory)) {
            return;
        }
        // getSlot() 是「在被点的那块背包里的下标」，盔甲槽固定 36-39，与开着什么界面无关
        if (event.getSlot() != slot.inventorySlot) {
            return;
        }
        if (!isUniform(get(inventory))) {
            return;
        }
        event.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // 物品构造
    // ------------------------------------------------------------------

    private ItemStack build(TdmTeam team) {
        ItemStack item = new ItemStack(slot.material);
        // 不写 (LeatherArmorMeta) 强转：混合端（Arclight）上并不保证给皮革专用实现，
        // 拿到通用 meta 时颜色只能放弃，但标记与负护甲修饰必须照样打上
        ItemMeta raw = item.getItemMeta();
        if (raw == null) {
            return item;
        }
        TdmSettings settings = plugin.getSettings();
        if (raw instanceof LeatherArmorMeta leather) {
            leather.setColor(parseColor(
                    team == TdmTeam.T ? settings.uniformTColor : settings.uniformCtColor, team));
        } else {
            plugin.getLogger().warning("队服：本服务端没有为 " + slot.material
                    + " 返回皮革专用 meta，颜色无法设置（其余功能正常）。");
        }
        raw.setUnbreakable(true);
        // HIDE_ATTRIBUTES：不遮的话 tooltip 上会多一行「护甲 -1」。
        // HIDE_DYE：避免物品说明里出现「已染色」，看起来像可编辑的普通装备。
        raw.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
        // displayName() 返回的是带 & 代码的串，必须转义后再设，否则会原样显示 "&cT 方"
        raw.setDisplayName(Text.color(team.displayName() + " &7队服"));

        // 用等值负数把皮革自带的护甲值抵掉：外观留下，护甲收益为 0
        raw.addAttributeModifier(Attribute.GENERIC_ARMOR,
                new AttributeModifier(ARMOR_MODIFIER_UUID, "tdm_uniform_armor",
                        -slot.baseArmor, AttributeModifier.Operation.ADD_NUMBER));

        // 打标记，用于识别「这件是不是我们发的」
        raw.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(raw);
        return item;
    }

    /** 支持 {@code #RRGGBB} 与 {@link DyeColor} 名（如 {@code RED}）；解析失败用队伍默认色。 */
    private Color parseColor(String raw, TdmTeam team) {
        if (raw != null && !raw.isBlank()) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("#") && trimmed.length() == 7) {
                try {
                    return Color.fromRGB(Integer.parseInt(trimmed.substring(1), 16));
                } catch (NumberFormatException ignored) {
                    // 落到下面的兜底
                }
            } else {
                try {
                    return DyeColor.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT)).getColor();
                } catch (IllegalArgumentException ignored) {
                    // 落到下面的兜底
                }
            }
        }
        return team == TdmTeam.T ? Color.fromRGB(0xC0392B) : Color.fromRGB(0x2E86DE);
    }

    // ------------------------------------------------------------------
    // 读写盔甲槽
    // ------------------------------------------------------------------

    private ItemStack get(PlayerInventory inventory) {
        return switch (slot) {
            case HELMET -> inventory.getHelmet();
            case CHESTPLATE -> inventory.getChestplate();
            case LEGGINGS -> inventory.getLeggings();
            case BOOTS -> inventory.getBoots();
        };
    }

    private void set(PlayerInventory inventory, ItemStack item) {
        switch (slot) {
            case HELMET -> inventory.setHelmet(item);
            case CHESTPLATE -> inventory.setChestplate(item);
            case LEGGINGS -> inventory.setLeggings(item);
            case BOOTS -> inventory.setBoots(item);
        }
    }

    /**
     * 这件物品是不是本插件发的队服。
     *
     * <p><b>这里绝不能把 meta 强转成 {@link LeatherArmorMeta}</b>：本方法要对盔甲槽里
     * 的**任意**物品作答，而钻石头盔、模组物品的 meta 是通用实现
     * （Arclight 上是 {@code CraftMetaItem}），强转会抛 ClassCastException。
     * 那个异常每 tick 抛一次，会把整段比赛 tick 和复活流程一起打断 ——
     * 表现就是「复活卡在出生点」。标记存在通用 meta 的 PDC 里，不转型也读得到。
     */
    private boolean isUniform(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .get(marker, PersistentDataType.BYTE) != null;
    }

    /** 清掉背包里散落的队服副本（只扫背包主格，盔甲槽由调用方处理）。 */
    private void purgeStray(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isUniform(contents[i])) {
                inventory.setItem(i, null);
            }
        }
    }
}

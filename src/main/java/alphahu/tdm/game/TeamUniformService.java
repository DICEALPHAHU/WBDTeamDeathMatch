package alphahu.tdm.game;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.config.TdmSettings;
import alphahu.tdm.util.Text;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

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

    public TeamUniformService(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "team_uniform");
        this.slot = parseSlot(plugin.getSettings().uniformSlot);
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
        if (!plugin.getSettings().uniformEnabled) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        set(inventory, build(team));
        player.updateInventory();
    }

    /**
     * 兜底校正：槽位里不是本队的队服就补一件。
     *
     * <p>已经是队服时什么都不做（热路径，每场每秒都会走一遍）。
     */
    public void assertUniform(Player player, TdmTeam team) {
        if (!plugin.getSettings().uniformEnabled) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (isUniform(get(inventory))) {
            return;
        }

        // 背包里散落的队服副本先清掉，避免「取下来又补发」变成复制
        purgeStray(inventory);

        // 槽位里如果有玩家自己的装备（比如自己买的头盔），挪回背包而不是销毁
        ItemStack existing = get(inventory);
        if (existing != null && !existing.getType().isAir() && !isUniform(existing)) {
            if (!inventory.addItem(existing).isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), existing);
            }
        }

        set(inventory, build(team));
        player.updateInventory();
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
        if (!plugin.getSettings().uniformEnabled) {
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
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        if (meta == null) {
            return item;
        }
        TdmSettings settings = plugin.getSettings();
        meta.setColor(parseColor(team == TdmTeam.T ? settings.uniformTColor : settings.uniformCtColor,
                team));
        meta.setUnbreakable(true);
        // HIDE_ATTRIBUTES：不遮的话 tooltip 上会多一行「护甲 -1」。
        // HIDE_DYE：避免物品说明里出现「已染色」，看起来像可编辑的普通装备。
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
        // displayName() 返回的是带 & 代码的串，必须转义后再设，否则会原样显示 "&cT 方"
        meta.setDisplayName(Text.color(team.displayName() + " &7队服"));

        // 用等值负数把皮革自带的护甲值抵掉：外观留下，护甲收益为 0
        meta.addAttributeModifier(Attribute.GENERIC_ARMOR,
                new AttributeModifier(ARMOR_MODIFIER_UUID, "tdm_uniform_armor",
                        -slot.baseArmor, AttributeModifier.Operation.ADD_NUMBER));

        // 打标记，用于识别「这件是不是我们发的」
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
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

    /** 这件物品是不是本插件发的队服。 */
    private boolean isUniform(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
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

package alphahu.tdm.game;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.config.TdmSettings;
import alphahu.tdm.shop.ShopItem;
import alphahu.tdm.shop.ShopService;
import alphahu.tdm.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 出生装备的解析与发放。
 *
 * <p>配置里的每一条可以是三类东西：
 * <ul>
 *   <li><b>序列化物品</b>：{@code bukkit-object:} 开头的整段数据（推荐）<br>
 *       模组枪械、投掷物只有这一条路能原样发放 —— 它带着枪械 id、弹药、
 *       开火模式、配件和全部 NBT。手里的物品用 {@code /tdm exportitem} 就能导出。</li>
 *   <li><b>原版物品</b>：{@code MATERIAL}、{@code MATERIAL:数量}、{@code MATERIAL:数量:耐久}<br>
 *       例如 {@code STONE_SWORD}、{@code ARROW:16}</li>
 *   <li><b>控制台命令</b>：以 {@code cmd:} 开头，{@code %player%} 会替换成玩家名<br>
 *       例如 {@code cmd:tacz give %player% p320}</li>
 * </ul>
 *
 * <p>三条都可以再加一个落点前缀 {@code 槽位@}，例如 {@code 1@bukkit-object:rO0AB...}
 * 表示放进第 2 格；不写就自动找第一个空位。
 *
 * <p>为什么会留 {@code cmd:} 这条口子：模组命令有时要做物品数据之外的事
 * （发钱、给权限、播放动画）。但**发枪不该用它** —— 模组自己的 give 命令
 * 生成出来的物品未必和配置里那一件一致，而且每次复活都得重跑一遍。
 * 想要「发出去的就是这一件」，就用 {@code bukkit-object:}。
 *
 * @author AlphaHu
 */
public final class LoadoutService {

    /** 一条装备配置：要么是一个物品，要么是一条命令。 */
    /**
     * 一条装备配置：一个物品或一条命令。
     *
     * @param slot 物品要放进哪一格；{@code -1} 表示找第一个空位
     */
    private record Entry(ItemStack item, int slot, String command, String source) {
    }

    private final WBDTeamDeathmatch plugin;
    private final List<Entry> common = new ArrayList<>();
    private final List<Entry> tOnly = new ArrayList<>();
    private final List<Entry> ctOnly = new ArrayList<>();

    public LoadoutService(WBDTeamDeathmatch plugin, TdmSettings settings, Logger logger) {
        this.plugin = plugin;
        parseInto(common, settings.commonItems, "loadout.common", logger);
        parseInto(tOnly, settings.tItems, "loadout.t", logger);
        parseInto(ctOnly, settings.ctItems, "loadout.ct", logger);
    }

    /**
     * 首次投放 / 中途加入：清空背包、发装备、复位状态。
     */
    public void apply(Player player, TdmTeam team) {
        PlayerInventory inventory = player.getInventory();

        if (plugin.getSettings().clearInventory) {
            inventory.clear();
            inventory.setArmorContents(null);
            player.setLevel(0);
            player.setExp(0f);
        }

        applyMaxHealth(player);
        giveItems(inventory, team);
        giveDefaultShopItems(player, team);
        resetVitals(player);
        player.updateInventory();

        // 命令类条目（loadout 里 cmd: 开头的那些）。
        // dispatchCommands 自己会延后一 tick 执行，不必在这里再包一层。
        dispatchCommands(player, team);

        // 队服放在最后：上面 clear-inventory 会连盔甲槽一起清掉，先穿等于白穿
        plugin.getUniform().apply(player, team);
    }

    /**
     * 发放「开局默认装备」。
     *
     * <p>配置里写的是**商店商品的 id**，而不是另配一套物品清单。
     * 好处是复用购买时那套落点与覆盖逻辑：默认给的枪落在哪一格，和玩家自己买它时
     * 完全一致；将来调了商品的 give-slot，默认装备会跟着走，不必两头改。
     *
     * <p>用 {@code grantFree} 而不是 {@code give}：开局不扣钱，否则玩家一进场
     * 就被自己的初始装备扣光了启动资金。
     */
    private void giveDefaultShopItems(Player player, TdmTeam team) {
        ShopService shop = plugin.getShop();
        if (shop == null || shop.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>(plugin.getSettings().defaultItemsCommon);
        ids.addAll(team == TdmTeam.T
                ? plugin.getSettings().defaultItemsT
                : plugin.getSettings().defaultItemsCt);

        for (String id : ids) {
            ShopItem item = shop.findItem(id, team.id());
            if (item == null) {
                plugin.getLogger().warning("loadout.default-items 里的商品 id 在商店里找不到："
                        + id + "（检查拼写，或确认它属于 " + team.id() + " 方）");
                continue;
            }

            // 开局装备只接受「原样还原的完整物品数据」。
            // 商店里点错还能再买一件，开局装备发错了玩家整局都拿着一件错的东西，
            // 而且没有任何界面会提示他。所以这里宁可少发一件、并在控制台说清楚，
            // 也不允许退回到「按材质造一个空壳」那条路。
            if (item.give() == null || !shop.isGiveFromData(id)) {
                plugin.getLogger().warning("loadout.default-items 的 " + id
                        + " 没有可原样发放的 give-data 物品数据，已跳过。"
                        + "请用 WBD 的商店编辑器重新保存这件商品让它写出 give-data，"
                        + "或用 /tdm shopedit 重新导出，否则发到手里只会是一个同材质的空壳。");
                continue;
            }

            shop.grantFree(player, item);
        }
    }

    /**
     * 阵亡复活：**不动背包**，只把状态复位。
     *
     * <p>这是死斗和爆破最大的体验差别之一。爆破每回合重发装备，因为回合之间
     * 本来就该重置；而死斗是连续对抗，玩家刚在商店里挑好的枪、配的投掷物，
     * 一死就全没了、还得再点一遍 —— 那不叫玩法，那叫折磨。
     * 所以复活只做三件事：恢复血量上限、补上商店入口（万一被丢掉了）、清掉负面状态。
     */
    public void restoreAfterRespawn(Player player, TdmTeam team) {
        applyMaxHealth(player);

        // 无条件补一次商店入口：giveShopItem 会先清掉背包里已有的那份再放新的，
        // 所以这里不必先判断「在不在」—— 判也判不准（玩家可能把它挪到别的格子了）
        giveShopItem(player.getInventory());

        resetVitals(player);
        player.updateInventory();

        // 复活保留背包，队服正常还在；assertUniform 在「还在」时什么都不做，
        // 只在被玩家换掉/打碎时才补一件
        plugin.getUniform().assertUniform(player, team);

        // 刻意不重跑 loadout 里的 cmd: 命令。
        // 那是「进入比赛」的一次性动作，每次复活都执行一遍的话，
        // 配置里写 `cmd:tacz give ...` 的服务器会每死一次就多收一把枪。
        // 玩家死亡时背包本来就保留着，不需要重发。
    }

    /** 按配置与阵营发放装备（不含商店入口）。 */
    private void giveItems(PlayerInventory inventory, TdmTeam team) {
        give(inventory, common);
        give(inventory, team == TdmTeam.T ? tOnly : ctOnly);
        giveShopItem(inventory);
    }

    /**
     * 按配置调整最大血量。
     *
     * <p>{@code loadout.max-health} 为 0 时不动它。改动只针对**基础值**，
     * 玩家身上来自其他插件的修饰符（如果有）不受影响；
     * 离场时由 {@link PlayerSnapshot} 还原成进入前的值。
     */
    private void applyMaxHealth(Player player) {
        double configured = plugin.getSettings().maxHealth;
        if (configured <= 0) {
            return;
        }
        try {
            var attribute = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attribute != null && Math.abs(attribute.getBaseValue() - configured) > 0.001) {
                attribute.setBaseValue(configured);
            }
        } catch (Throwable ignored) {
            // 属性不可用的服务端上跳过即可，不该因此中断投放
        }
    }

    /**
     * 复位血量、饥饿、火焰与药水效果。
     *
     * <p>必须在设置最大血量**之后**再设当前血量，否则上限还没调大就被夹回去了。
     */
    private void resetVitals(Player player) {
        for (var effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setFireTicks(0);
        player.setFoodLevel(17);
        player.setSaturation(0f);
        player.setHealth(Math.min(plugin.getSettings().health, maxHealth(player)));
    }

    /**
     * 执行配置里的命令类条目。
     *
     * <p>故意不放在 {@link #apply(Player, TdmTeam)} 里同步跑：
     * 模组命令往往要读玩家当前的物品栏，而 {@code updateInventory()} 的客户端同步
     * 是异步完成的。让命令延后一 tick 执行，枪才不会被随后到来的同步包清掉。
     */
    public void dispatchCommands(Player player, TdmTeam team) {
        List<Entry> entries = new ArrayList<>(common);
        entries.addAll(team == TdmTeam.T ? tOnly : ctOnly);

        for (Entry entry : entries) {
            if (entry.command() == null) continue;
            String command = entry.command().replace("%player%", player.getName());
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    private void give(PlayerInventory inventory, List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.item() == null) continue;
            ItemStack stack = entry.item().clone();
            int slot = entry.slot();
            if (slot >= 0 && slot < inventory.getSize()) {
                // 写了落点就放在那儿，哪怕那一格已经有东西 —— 开局装备本来就该
                // 覆盖「上一次留下的同一格」，不该出现两把刀叠在不同格子里
                inventory.setItem(slot, stack);
            } else {
                inventory.addItem(stack);
            }
        }
    }

    /**
     * 把商店入口放进固定槽位。
     *
     * <p>放之前先把背包里**所有**商店入口清掉，而不是只检查目标槽位。
     * 只查目标槽位挡不住这种情况：玩家把绿宝石挪到别的格子 → 死亡（背包保留）
     * → 复活时目标槽位是空的 → 又发一个 —— 于是每死一次就多一颗。
     * 先全清再放，既保证只有一个，位置也永远是固定的。
     */
    private void giveShopItem(PlayerInventory inventory) {
        ShopService shop = plugin.getShop();
        if (!plugin.getSettings().shopEnabled || shop == null || shop.isEmpty()) {
            return;
        }

        for (int i = 0; i < inventory.getSize(); i++) {
            if (shop.isOpenItem(inventory.getItem(i))) {
                inventory.setItem(i, null);
            }
        }
        inventory.setItem(shop.getOpenItemSlot(), shop.createOpenItem());
    }

    private double maxHealth(Player player) {
        try {
            var attribute = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            return attribute == null ? 20.0 : attribute.getValue();
        } catch (Throwable t) {
            return 20.0;
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private void parseInto(List<Entry> target, List<String> specs, String path, Logger logger) {
        if (specs == null) return;
        for (String raw : specs) {
            if (raw == null || raw.isBlank()) continue;
            String spec = raw.trim();

            // 可选的落点前缀：「槽位@」。写成 "2@bukkit-object:..." 就放进第 3 格。
            // 用 @ 而不是冒号，是因为下面两种格式本来就用冒号分隔
            // （MATERIAL:数量:耐久、bukkit-object:数据），再加一个冒号会分不清。
            int slot = -1;
            String body = spec;
            int at = spec.indexOf('@');
            if (at > 0 && at <= 2) {
                try {
                    int parsed = Integer.parseInt(spec.substring(0, at).trim());
                    if (parsed >= 0 && parsed <= 35) {
                        slot = parsed;
                        body = spec.substring(at + 1).trim();
                    }
                } catch (NumberFormatException ignored) {
                    // 不是落点前缀，整串按原样处理
                }
            }

            if (body.regionMatches(true, 0, "cmd:", 0, 4)) {
                String command = body.substring(4).trim();
                if (command.isEmpty()) {
                    logger.warning(path + " 里有一条空的 cmd: 条目，已跳过");
                    continue;
                }
                target.add(new Entry(null, -1, command, spec));
                continue;
            }

            // 序列化物品：把 WBD 那种 bukkit-object: 串整段贴进来即可。
            // 这是模组枪械、投掷物**唯一**能被原样发放的方式 —— 它们没有对应的
            // Material，走下面的 parseItem 按材质重建只会得到一个空壳。
            if (ItemCodec.isEncoded(body)) {
                ItemStack decoded = ItemCodec.decode(body);
                if (decoded == null) {
                    logger.warning(path + " 里有一条物品数据无法还原：" + shorten(spec)
                            + "（常见原因：来自别的游戏版本、复制时被截断或插入了换行）");
                } else {
                    target.add(new Entry(decoded, slot, null, spec));
                }
                continue;
            }

            ItemStack item = parseItem(body, path, logger);
            if (item != null) {
                target.add(new Entry(item, slot, null, spec));
            }
        }
    }

    /**
     * 日志里只显示序列化串的开头。
     *
     * <p>一件模组枪械的 {@code bukkit-object:} 串有几百个字符，整条打进控制台
     * 会把别的信息全刷掉，反而更难排查。
     */
    private static String shorten(String spec) {
        return spec.length() <= 64
                ? spec
                : spec.substring(0, 64) + "...（共 " + spec.length() + " 字符）";
    }

    /**
     * 解析原版物品串。
     *
     * <p>数量与耐久只做基本的边界收束：数量为 0 或负数会让 {@code setAmount}
     * 抛出异常，而配置写错一个数字就整个插件启动失败，代价太大。
     */
    private ItemStack parseItem(String spec, String path, Logger logger) {
        String[] parts = spec.split(":");
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null || material.isAir()) {
            logger.warning(path + " 里的物品无法识别：" + spec
                    + "（如果是模组物品，请改用 bukkit-object: 条目 ——"
                    + "手持该物品执行 /tdm exportitem 就能导出这段数据）");
            return null;
        }

        int amount = 1;
        if (parts.length > 1) {
            amount = parsePositiveInt(parts[1], 1);
        }
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize() * 4)));

        if (parts.length > 2) {
            int durability = parsePositiveInt(parts[2], 0);
            if (durability > 0 && item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                damageable.setDamage(durability);
                item.setItemMeta((org.bukkit.inventory.meta.ItemMeta) damageable);
            }
        }
        return item;
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

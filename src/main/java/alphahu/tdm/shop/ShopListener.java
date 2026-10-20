package alphahu.tdm.shop;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 商店的交互监听：右键打开、点击购买、翻页。
 *
 * @author AlphaHu
 */
public final class ShopListener implements Listener {

    private final WBDTeamDeathmatch plugin;

    public ShopListener(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    /**
     * 右键商店物品打开菜单。
     *
     * <p>只认主手：副手事件会在右键时跟着触发一次，不区分就会开两遍菜单。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!plugin.getShop().isOpenItem(event.getItem())) {
            return;
        }

        Player player = event.getPlayer();
        // 只有参赛中的玩家能用商店。场外拿着这个物品（例如刚退出比赛、
        // 背包还没还原）右键不该弹出菜单
        TdmMatch match = plugin.getMatchManager().getMatch(player);
        if (match == null || !match.isActive(player)) {
            return;
        }

        event.setCancelled(true);

        // 购买区限制：不在自己阵营的购买区里就买不了。
        // 这是「不能想买就买」的关键 —— 没有它，玩家可以站在掩体后面
        // 边打边换枪，装备选择就失去了代价
        if (!match.canPurchase(player)) {
            player.sendMessage(plugin.getMessages().get("shop.outside-buy-zone"));
            return;
        }

        plugin.getShop().open(player);
    }

    /** 菜单内点击：购买或翻页。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof ShopService.ShopHolder holder)) {
            return;
        }

        // 整个界面的点击都拦下：商品是「点一下就用」，
        // 不该被玩家拖进自己的背包
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        // 购买时**再检查一次**位置。
        //
        // 只在打开菜单时检查是不够的：玩家可以站在购买区里打开商店，
        // 然后走出去继续点 —— 菜单开着的时候不会自动关闭，
        // 于是「限制购买区」变成了一句空话。真正要限制的是「购买」这个动作，
        // 而不只是「打开菜单」这个动作。
        TdmMatch match = plugin.getMatchManager().getMatch(player);
        if (match != null && match.isActive(player) && !match.canPurchase(player)) {
            player.closeInventory();
            player.sendMessage(plugin.getMessages().get("shop.outside-buy-zone"));
            return;
        }

        if (plugin.getShop().handleClick(player, holder, rawSlot)) {
            player.closeInventory();
        }
    }

    /** 菜单内禁止拖拽。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopService.ShopHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * 比赛中的玩家不允许丢弃物品。
     *
     * <p>一刀切而不是只拦「商店发出的那些」：死斗里装备随时能从商店补，
     * 丢东西本来就没有实际意义。全面禁止的好处是**没有漏网之鱼** ——
     * 只拦商店物品的话，玩家还能把地上捡的、队友给的丢来丢去，
     * 而「买一个丢地上、再买一个、捡回来」这条刷装备的路子也得靠逐个识别才能堵。
     *
     * <p>只对**比赛中**的玩家生效：场外（大厅、别的玩法）正常丢东西，不受影响。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getSettings().preventItemDrop) {
            return;
        }
        Player player = event.getPlayer();

        // 死亡引发的掉落不是「主动丢弃」，必须放行。
        //
        // 这时服务端正在清理当场玩家的背包，把它拦下来既没有意义（物品本来就要没），
        // 还会给刚被打死的人弹一句「死斗中不能丢弃物品」—— 他什么都没按，
        // 却收到一条莫名其妙的警告。混合端在死亡处理时确实会走这个事件。
        if (player.isDead() || player.getHealth() <= 0.0) {
            return;
        }

        TdmMatch match = plugin.getMatchManager().getMatch(player);
        if (match == null || !match.isActive(player)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(Text.color("&7死斗中不能丢弃物品 —— 要换装备请打开商店。"));
    }
}

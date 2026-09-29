package alphahu.tdm.shop;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 商店里的一件商品。
 *
 * <p>字段与 WarZBombDefuse 的商店格式对齐，方便直接复用服主已经配好的
 * {@code shop.yml}。唯一刻意不支持的是 {@code price}：死斗不比经济，全部免费。
 *
 * @param id              标识，用于日志与槽位覆盖配置
 * @param page            所在页码（从 1 开始）
 * @param slot            在该页菜单里的槽位
 * @param team            {@code T} / {@code CT} / {@code ANY}
 * @param price           价格。0 表示免费
 * @param permission      购买所需权限，空表示不限制
 * @param display         菜单里显示的图标
 * @param give            实际发放的物品；为 null 表示这件商品只执行命令
 * @param giveSlot        发放到背包第几格；{@code < 0} 表示交给「兜底槽位」处理
 * @param commands        购买后执行的命令
 * @param commandSender   {@code console} 或 {@code player}
 * @param commandRepeat   命令重复次数
 * @param closeAfterClick 点击后是否关闭菜单
 *
 * @author AlphaHu
 */
public record ShopItem(
        String id,
        int page,
        int slot,
        String team,
        int price,
        String permission,
        ItemStack display,
        ItemStack give,
        int giveSlot,
        List<String> commands,
        String commandSender,
        int commandRepeat,
        boolean closeAfterClick
) {

    /** 该阵营能否购买这件商品。 */
    public boolean availableTo(String teamId) {
        if (team == null || team.isBlank() || "ANY".equalsIgnoreCase(team)) {
            return true;
        }
        return team.equalsIgnoreCase(teamId);
    }
}

package alphahu.tdm.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * 物品串解析。
 *
 * <p>格式：{@code MATERIAL}、{@code MATERIAL:数量}、{@code MATERIAL:数量:耐久损耗}。
 * 与出生装备用的是同一套写法，服主不必记两套语法。
 *
 * <p>模组物品（TacZ 之类）无法用 {@link Material} 表示，服务端也没有通用的
 * 「按字符串造任意模组物品」接口 —— 那些一律走命令，不走这里。
 *
 * @author AlphaHu
 */
public final class ItemParser {

    private ItemParser() {
    }

    /**
     * 解析一个物品串。
     *
     * @return 无法识别（材质名写错、或那是模组物品）时返回 null
     */
    public static ItemStack parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] parts = spec.trim().split(":");
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null || material.isAir()) {
            return null;
        }

        int amount = parts.length > 1 ? parsePositive(parts[1], 1) : 1;
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize() * 4)));

        if (parts.length > 2) {
            int durability = parsePositive(parts[2], 0);
            if (durability > 0 && item.getItemMeta() instanceof Damageable damageable) {
                damageable.setDamage(durability);
                item.setItemMeta(damageable);
            }
        }
        return item;
    }

    private static int parsePositive(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

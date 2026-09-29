package alphahu.tdm.arena;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * 一个球形的购买区。
 *
 * <p>格式与 WarZBombDefuse 完全一致：{@code world,x,y,z,yaw,pitch,radius}。
 * 用球体而不是立方体，是因为 WBD 那边就是这么定的 —— 场地坐标是服主用
 * {@code /wbd setup} 量出来的，能原样导进死斗、不必重新理解一套规则，
 * 比「立方体更精确」重要得多。
 *
 * @param world  所属世界
 * @param x      中心 X
 * @param y      中心 Y
 * @param z      中心 Z
 * @param radius 半径（方块）
 * @author AlphaHu
 */
public record BuyZone(World world, double x, double y, double z, double radius) {

    /** 玩家是否站在区内。 */
    public boolean contains(Location location) {
        if (location == null || world == null) {
            return false;
        }
        if (!world.equals(location.getWorld())) {
            return false;
        }
        double dx = location.getX() - x;
        double dy = location.getY() - y;
        double dz = location.getZ() - z;
        // 比较平方值，省掉一次开方。这个判断每次右键都会跑，虽然开销极小，
        // 但没有任何理由去做那个 sqrt
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** 供日志与校验输出。 */
    public String describe() {
        return (world == null ? "?" : world.getName())
                + " (" + x + "," + y + "," + z + ") r=" + radius;
    }
}

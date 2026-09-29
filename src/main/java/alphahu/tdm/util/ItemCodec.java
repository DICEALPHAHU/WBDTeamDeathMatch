package alphahu.tdm.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * WBD 那套「Bukkit 序列化物品」字符串的编解码。
 *
 * <p>格式就是 {@code bukkit-object:} 加上一段 Base64 —— 解开以后是一个
 * {@link org.bukkit.util.io.BukkitObjectInputStream} 写出的 {@link ItemStack}。
 * 模组枪械的枪械 id、弹药、开火模式、配件、皮肤这些自定义 NBT 全都
 * 原封不动躺在里面，所以它能把「一件物品」完整地从一个地方搬到另一个地方。
 *
 * <p>这正是「按材质重建」做不到的事：模组物品压根没有对应的 {@code Material}，
 * 凭空造一个同材质的空壳，发到手里必然不是那把枪。
 *
 * <p>写在 WBD 的 {@code give-data} 里是这样，写在 {@code config.yml} 的
 * {@code loadout} 里也是这样 —— 两边走的是同一份数据、同一段代码。
 *
 * @author AlphaHu
 */
public final class ItemCodec {

    /** 序列化物品数据的固定前缀，与 WBD 的命名保持一致。 */
    public static final String PREFIX = "bukkit-object:";

    private ItemCodec() {
    }

    /** 这段字符串是不是序列化物品数据。 */
    public static boolean isEncoded(String data) {
        return data != null && data.startsWith(PREFIX);
    }

    /**
     * 还原一件物品。
     *
     * @return 还原失败返回 {@code null}（数据被截断、来自别的版本等），
     *         调用方负责给出可读的提示
     */
    public static ItemStack decode(String data) {
        if (!isEncoded(data)) {
            return null;
        }
        String base64 = data.substring(PREFIX.length()).trim();
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            try (BukkitObjectInputStream in =
                         new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
                Object object = in.readObject();
                return object instanceof ItemStack stack ? stack : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把一件物品写成序列化字符串。
     *
     * <p>给 {@code /tdm exportitem} 用：服主手里拿着一把配好的枪，
     * 一条命令就能拿到能直接贴进 {@code config.yml} 的字符串，
     * 不必去猜格式、更不必手写 Base64。
     *
     * @return 编码失败返回 {@code null}
     */
    public static String encode(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(stack);
            out.flush();
            return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }
}

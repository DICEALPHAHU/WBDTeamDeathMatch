package alphahu.tdm.game;

import alphahu.tdm.WBDTeamDeathmatch;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 复活补弹：把玩家背包里所有 TacZ 枪的弹匣补满。
 *
 * <h2>为什么需要这一步</h2>
 * 死斗「复活保留背包」是有意的 —— 玩家在商店里花击杀赚的钱挑的枪不该因为一次阵亡就白买。
 * 但 TacZ 把「当前弹匣还剩几发」存在**物品 NBT** 里，跟着物品走：
 * 于是复活后枪还是那把枪，弹匣也还是死前那一两发，必须先换弹才能打。
 * 死斗的节奏本来就靠「死了三秒又回来」，每次回来都要换一次弹，实际是把人钉在原地。
 *
 * <h2>为什么不自己写 NBT</h2>
 * TacZ 的弹匣字段在不同版本里改过名（{@code GunCurrentAmmoCount} 等），
 * 而服务端已经装了 TacZSpigotBridge —— 它有 {@code getItemCurrentAmmo} /
 * {@code setItemCurrentAmmo} / {@code getGun}（其中 {@code ammoAmount} 就是弹匣容量）。
 * 走它的 API 比自己猜 NBT 稳，也不会因为 TacZ 升级而失效。
 *
 * <p>按本项目一贯的做法，这里**只用反射**，不把桥接设成编译期依赖：
 * 没装桥接时 {@link #refill(Player)} 直接什么都不做。
 *
 * @author AlphaHu
 */
public final class MagazineRefill {

    private final WBDTeamDeathmatch plugin;

    /** 每个 gunId 的弹匣容量；同一把枪反复问没有意义。 */
    private final Map<String, Integer> capacities = new HashMap<>();

    private boolean probed;
    private Object api;
    private Method getItemGunId;
    private Method getGun;
    private Method summaryAmmoAmount;
    private Method getItemCurrentAmmo;
    private Method setItemCurrentAmmo;
    private Method setItemBulletInBarrel;
    private Method setItemHeat;

    public MagazineRefill(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
    }

    /**
     * 把该玩家背包（含副手）里所有 TacZ 枪的弹匣补满。
     *
     * <p>已经满的枪不动 —— 避免每局都重写一遍 NBT，也避免把「弹匣比容量还多」的
     * 特殊状态给压回去。
     */
    public void refill(Player player) {
        if (!plugin.getSettings().respawnRefillMagazine) {
            return;
        }
        probe();
        if (api == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        boolean changed = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (refillOne(stack)) {
                inventory.setItem(slot, stack);
                changed = true;
            }
        }
        if (changed) {
            player.updateInventory();
        }
    }

    /** @return 是否真的补了弹药 */
    private boolean refillOne(ItemStack stack) {
        try {
            String gunId = stringOf(getItemGunId.invoke(api, stack));
            if (gunId == null) {
                return false;
            }
            int capacity = capacityOf(gunId);
            if (capacity <= 0) {
                return false;
            }
            int current = intOf(getItemCurrentAmmo.invoke(api, stack));
            if (current >= capacity) {
                return false;
            }

            setItemCurrentAmmo.invoke(api, stack, capacity);
            // 与商店刚发出来的一把新枪保持一致：弹匣满 + 膛内一发
            optionalInvoke(setItemBulletInBarrel, stack, Boolean.TRUE);
            // 枪管温度也归零：连续打完再复活，过热应该跟着死亡一起清掉
            optionalInvoke(setItemHeat, stack, 0f);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("补弹失败：" + t);
            return false;
        }
    }

    /** 弹匣容量。问不到（物品不是枪、或桥接版本没有 getGun）时返回 0。 */
    private int capacityOf(String gunId) {
        Integer cached = capacities.get(gunId);
        if (cached != null) {
            return cached;
        }
        int capacity = 0;
        try {
            Object optional = getGun.invoke(api, gunId);
            if (optional instanceof Optional<?> opt && opt.isPresent()) {
                Object summary = opt.get();
                capacity = intOf(summaryAmmoAmount.invoke(summary));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("读取枪械容量失败（" + gunId + "）：" + t);
        }
        capacities.put(gunId, capacity);
        return capacity;
    }

    /**
     * 探测 TacZ 桥接。只探一次：没装就是没装，不必每局重试。
     *
     * <p>方法名全部按 {@code TacZSpigotApi} 的公开接口取；某个方法不存在（旧版桥接）
     * 就只放弃对应的那一步，不影响其余。
     */
    private void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> apiClass = Class.forName("com.tacz.bridge.spigot.api.TacZSpigotApi");
            Object service = plugin.getServer().getServicesManager().load(apiClass);
            if (service == null) {
                plugin.getLogger().info("未检测到 TacZ 桥接，复活补弹不会生效。");
                return;
            }
            api = service;
            getItemGunId = apiClass.getMethod("getItemGunId", ItemStack.class);
            getGun = apiClass.getMethod("getGun", String.class);
            getItemCurrentAmmo = apiClass.getMethod("getItemCurrentAmmo", ItemStack.class);
            setItemCurrentAmmo = apiClass.getMethod("setItemCurrentAmmo", ItemStack.class, int.class);
            summaryAmmoAmount = Class.forName("com.tacz.bridge.spigot.api.model.TacZGunSummary")
                    .getMethod("ammoAmount");
            // 这两个是「锦上添花」，旧版桥接没有也能接受
            setItemBulletInBarrel = optionalMethod(apiClass, "setItemBulletInBarrel",
                    ItemStack.class, boolean.class);
            setItemHeat = optionalMethod(apiClass, "setItemHeat", ItemStack.class, float.class);
            plugin.getLogger().info("复活补弹已接入 TacZ 桥接。");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("未检测到 TacZ 桥接，复活补弹不会生效。");
        } catch (Throwable t) {
            api = null;
            plugin.getLogger().warning("探测 TacZ 桥接失败，复活补弹不会生效：" + t);
        }
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String stringOf(Object value) {
        if (value instanceof Optional<?> opt) {
            return opt.isPresent() ? String.valueOf(opt.get()) : null;
        }
        return null;
    }

    private static int intOf(Object value) {
        if (value instanceof Optional<?> opt) {
            return opt.filter(v -> v instanceof Integer).map(v -> (Integer) v).orElse(0);
        }
        return 0;
    }

    /**
     * 执行一个「可选」的桥接调用：方法不存在或抛异常都只吞掉。
     *
     * <p>这些是锦上添花（膛内上弹、清枪管温度），缺了也不该影响「补满弹匣」这件正事。
     */
    private void optionalInvoke(Method method, ItemStack stack, Object value) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(api, stack, value);
        } catch (Throwable ignored) {
            // 可选步骤，失败不影响补弹本身
        }
    }
}

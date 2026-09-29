package alphahu.tdm.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家加入比赛前的状态快照，离场时原样还原。
 *
 * <p>没有它会出一个很显眼的毛病：玩家打完死斗，背包被换成出生装备、
 * 人留在场地里回不去，等于被插件「扣」在了竞技场。
 * WarZBombDefuse 也是这么处理的（{@code PlayerSnapshot}），行为一致，
 * 服主和玩家都不会觉得意外。
 *
 * @author AlphaHu
 */
public final class PlayerSnapshot {

    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final GameMode gameMode;
    private final Location location;
    private final double health;
    private final int food;
    private final float saturation;
    private final float exp;
    private final int level;
    private final List<PotionEffect> effects;

    /**
     * 进入比赛前的最大血量**基础值**；-1 表示当时取不到这个属性。
     *
     * <p>必须单独记一笔：死斗允许把最大血量调大（比如 40 点，比爆破更耐打），
     * 而那改的是玩家属性的基础值，属于玩家自身状态的一部分。退出时不一并还原的话，
     * 玩家会带着「死斗的血量上限」回到大厅和其他玩法里去 ——
     * 这种残留特别难被发现，界面上看不出异常，只是血条比别人长一截。
     */
    private final double maxHealthBase;

    public PlayerSnapshot(Player player) {
        this.contents = player.getInventory().getContents().clone();
        this.armor = player.getInventory().getArmorContents().clone();
        this.offHand = player.getInventory().getItemInOffHand().clone();
        this.gameMode = player.getGameMode();
        this.location = player.getLocation().clone();
        this.health = player.getHealth();
        this.food = player.getFoodLevel();
        this.saturation = player.getSaturation();
        this.exp = player.getExp();
        this.level = player.getLevel();
        this.effects = new ArrayList<>(player.getActivePotionEffects());
        this.maxHealthBase = readMaxHealthBase(player);
    }

    /**
     * 还原玩家状态。
     *
     * <p>顺序有讲究：先清药水效果、再还原血量上限、然后给背包，最后传送。
     * 先是效果的顺序问题 —— 残留的失明/缓慢会跟着玩家回到大厅，
     * 看上去像插件把人弄坏了。血量上限则必须在设当前血量**之前**还原：
     * 反过来的话，若死斗把上限调大了，还原时会拿一个超出原上限的数值去
     * setHealth，被服务端夹回原上限，结果看起来就是「血量没恢复满」。
     */
    public void restore(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }
        if (gameMode != null) {
            player.setGameMode(gameMode);
        }

        writeMaxHealthBase(player, maxHealthBase);

        player.getInventory().setContents(contents);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(offHand);
        player.setHealth(Math.min(health, maxHealth(player)));
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExp(exp);
        player.setLevel(level);
        player.setFireTicks(0);
        if (location != null) {
            player.teleport(location);
        }
    }

    /** 读最大血量属性的基础值，取不到返回 -1。 */
    private static double readMaxHealthBase(Player player) {
        try {
            var attribute = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            return attribute == null ? -1.0 : attribute.getBaseValue();
        } catch (Throwable t) {
            return -1.0;
        }
    }

    /** 写回最大血量属性的基础值。 */
    private static void writeMaxHealthBase(Player player, double base) {
        if (base < 0) {
            return;
        }
        try {
            var attribute = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attribute != null) {
                attribute.setBaseValue(base);
            }
        } catch (Throwable ignored) {
            // 少数服务端或模组会动这个属性，还原不了也不该让离场流程中断
        }
    }

    /** 当前的最大血量；属性不可用时退回 20。 */
    private double maxHealth(Player player) {
        try {
            var attribute = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            return attribute == null ? 20.0 : attribute.getValue();
        } catch (Throwable t) {
            return 20.0;
        }
    }
}

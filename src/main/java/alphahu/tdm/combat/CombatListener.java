package alphahu.tdm.combat;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.game.MatchManager;
import alphahu.tdm.game.MatchState;
import alphahu.tdm.game.TdmMatch;
import alphahu.tdm.game.TdmPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * 战斗规则监听器：伤害过滤、击杀接管、死亡处理、复活落点。
 *
 * <p>这里是「无限复活」真正接线的地方。整个流程是：
 * <ol>
 *   <li>{@link #onDeath} 捕获阵亡，交给比赛引擎记账并排定复活时刻；
 *       同时清掉掉落物与死亡消息，让这一死看起来不像真的死</li>
 *   <li>同一 tick 的末尾，{@link #respawnThenSpectate} 调用
 *       {@code spigot().respawn()} 把玩家从死亡界面拽出来，再切成旁观</li>
 *   <li>{@link #onRespawn} 把重生点钉在竞技场内，避免玩家在客户端
 *       短暂地闪回主城出生点</li>
 *   <li>延时到点后，由比赛引擎的 tick 把玩家放回战场</li>
 * </ol>
 *
 * <p>为什么死亡处理要「取消死亡」这条路：WarZBombDefuse 的爆破模式里，
 * 致死伤害是被 WBD 自己拦掉的（改成调用它的 eliminate），玩家根本不会真死。
 * 死斗不打算复用那套逻辑，而是让玩家真正走一次死亡流程再复活——
 * 这样经验修补、死亡事件、其他插件的死亡监听都能正常工作，
 * 兼容性比「拦掉伤害再手工搬运玩家状态」好得多。
 *
 * @author AlphaHu
 */
public final class CombatListener implements Listener {

    private final WBDTeamDeathmatch plugin;
    private final MatchManager manager;

    public CombatListener(WBDTeamDeathmatch plugin, MatchManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------------------------
    // 伤害过滤
    // ------------------------------------------------------------------

    /**
     * 处理实体伤害。
     *
     * <p>用 {@code HIGHEST} 且 {@code ignoreCancelled = true}：让别的插件
     * （保护插件、区域插件等）先表态，它们已经取消的伤害这里不再重复处理。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        TdmMatch match = manager.getMatch(victim);
        if (match == null) {
            return;
        }

        Player attacker = resolveAttacker(event.getDamager());
        TdmPlayer victimRecord = match.getPlayer(victim);
        long now = System.currentTimeMillis();

        // 1. 开赛前与结算期间不允许玩家互殴。
        //    注意这里只拦「玩家打玩家」，环境伤害照常，否则在等待阶段
        //    摔进虚空的玩家会永远掉下去
        if (match.getState() != MatchState.LIVE) {
            if (attacker != null) {
                event.setCancelled(true);
            }
            return;
        }

        // 2. 友军伤害
        if (attacker != null && match.isSameTeam(victim, attacker)) {
            if (!plugin.getSettings().friendlyFire) {
                event.setCancelled(true);
                if (victimRecord != null) {
                    plugin.getHud().sendActionBar(attacker,
                            alphahu.tdm.util.Text.color("&c不要攻击队友！"));
                }
                return;
            }
        }

        // 3. 复活保护。
        //    先处理攻击者的保护：保护期内主动开火就该立刻失去保护，
        //    否则会出现「顶着无敌冲上去打人」的白嫖打法。
        if (attacker != null && attacker != victim && !match.isSameTeam(victim, attacker)) {
            TdmPlayer attackerRecord = match.getPlayer(attacker);
            if (attackerRecord != null && attackerRecord.isProtected(now)
                    && plugin.getSettings().breakProtectionOnAttack) {
                attackerRecord.clearProtection();
                plugin.getHud().sendActionBar(attacker,
                        alphahu.tdm.util.Text.color("&7保护已解除"));
            }
        }
        if (victimRecord != null && victimRecord.isProtected(now)) {
            event.setCancelled(true);
        }
    }

    /**
     * 处理非实体伤害（摔落、火焰、虚空等）。
     *
     * <p>只有一件事要做：保护期内免疫。其余情况一律放行——
     * 环境伤害在死斗里是有意义的（摔下高台就该付出代价），
     * 而且全拦掉会让掉进虚空的玩家永远卡在半空。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // 玩家造成的伤害已经在上面处理过，避免同一事件被处理两次
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        TdmMatch match = manager.getMatch(victim);
        if (match == null) {
            return;
        }
        // 虚空伤害不豁免：否则玩家掉出世界后会悬在那里，永远等不到复活
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        TdmPlayer record = match.getPlayer(victim);
        if (record != null && record.isProtected(System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // 死亡与复活
    // ------------------------------------------------------------------

    /**
     * 接管阵亡。
     *
     * <p>用 {@code HIGHEST} 是为了让别的插件先处理完掉落与消息，
     * 我们再统一覆盖成死斗想要的样子。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        TdmMatch match = manager.getMatch(victim);
        if (match == null) {
            return;
        }

        // 交给比赛引擎记账（击杀归属、比分、复活排期）
        match.handleDeath(victim, victim.getKiller());

        boolean dropItems = plugin.getSettings().dropItems;
        if (!dropItems) {
            // 死斗里满地掉落物既卡服又拖节奏，而且玩家马上要复活，
            // 捡回自己的东西这种体验并不成立
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
        if (plugin.getSettings().overrideDeathMessage) {
            // 死斗有自己格式化的击杀播报，原版那句「xxx 被 yyy 杀死了」是重复的
            event.setDeathMessage(null);
        }

        // 死亡界面处理必须放到下一 tick：在 PlayerDeathEvent 内部直接 respawn()
        // 在部分服务端上会被随后的死亡流程覆盖掉，表现成玩家卡在「你死了」界面上
        Bukkit.getScheduler().runTask(plugin, () -> respawnThenSpectate(match, victim));
    }

    /**
     * 把刚阵亡的玩家从死亡界面拽出来，转成旁观等待复活。
     */
    private void respawnThenSpectate(TdmMatch match, Player victim) {
        if (!victim.isOnline()) {
            return;
        }
        // 玩家可能在这一 tick 内已经退出了比赛（例如被管理员踢出）
        TdmPlayer record = match.getPlayer(victim);
        if (record == null || record.isAlive()) {
            return;
        }
        try {
            victim.spigot().respawn();
        } catch (Throwable t) {
            plugin.getLogger().warning("让玩家 " + victim.getName()
                    + " 脱离死亡界面时出错：" + t.getMessage());
        }
        match.enterSpectator(victim);
    }

    /**
     * 把重生点钉在竞技场内。
     *
     * <p>不设这一下的后果很显眼：玩家点「重生」会先在被设成主城出生点的位置
     * 闪一帧，再被本插件传回场地。动作快的人能看见世界闪一下，
     * 慢的甚至会被卡在错误的位置。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        TdmMatch match = manager.getMatch(player);
        if (match == null) {
            return;
        }
        TdmPlayer record = match.getPlayer(player);
        if (record == null || record.isAlive()) {
            return;
        }
        Location point = record.deathLocation() != null
                ? record.deathLocation()
                : match.getArena().getLobby();
        if (point != null) {
            event.setRespawnLocation(point);
        }
    }

    // ------------------------------------------------------------------
    // 离场
    // ------------------------------------------------------------------

    /**
     * 玩家掉线：当作离开处理。
     *
     * <p>必须在这里把人从比赛里摘出去并还原状态，而不是等他下次上线再说。
     * 否则玩家上线时会发现自己站在竞技场里、背包被换成了出生装备，
     * 位置还是上次阵亡的地方。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        TdmMatch match = manager.getMatch(event.getPlayer());
        if (match == null) {
            return;
        }
        // 旁观状态下离线，游戏模式还是 SPECTATOR，还原时必须切回来
        if (event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            event.getPlayer().setGameMode(GameMode.SURVIVAL);
        }
        match.quit(event.getPlayer());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 解析伤害来源玩家。
     *
     * <p>投射物要往上追一层到射击者：TacZ 的枪械、弓箭、雪球都走这条路，
     * 少了它，枪战打死的敌人会被记成「无击杀者」。
     */
    private Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}

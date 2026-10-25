package alphahu.tdm.game;

import alphahu.tdm.WBDTeamDeathmatch;
import alphahu.tdm.config.TdmSettings;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * 队伍皮肤：进入比赛时套上本方的统一皮肤，离开时还原。
 *
 * <h2>为什么用「命令模板」而不是直接调 SkinsRestorer 的 API</h2>
 * 玩家皮肤由客户端从 profile 纹理里取，服务端自己改不了，必须依赖这类插件。
 * 而它们的 API 在不同版本之间改名很频繁（同一个方法在 14.x / 15.x 可能就不叫这个名字），
 * 直接反射调用等于把版本适配的债揽到自己身上。
 *
 * <p>命令语法相对稳定，而且模板化之后不只是 SkinsRestorer —— SkinShuffle、
 * 甚至服主自己写的小插件都能接，本插件不需要认识它们中的任何一个。
 *
 * <h2>还原为什么带一个「标记」</h2>
 * 换皮肤改的是玩家在皮肤插件数据库里的记录，不是一次性的显示效果。如果服务端
 * 在比赛中途崩溃，玩家会带着队伍皮肤一直玩到下次有人改它为止 —— 而他本来就
 * 不该被永久改掉。所以套皮肤时在玩家身上写一个 PDC 标记，还原时清掉；
 * 带着标记进服（说明上次没还原成）会自动补救一次。
 *
 * @author AlphaHu
 */
public final class SkinService implements Listener {

    /** 标记「这个人的皮肤是本插件改的，还没还原」。 */
    private final NamespacedKey pending;

    private final WBDTeamDeathmatch plugin;

    public SkinService(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
        this.pending = new NamespacedKey(plugin, "team_skin_pending");
    }

    /**
     * 把玩家套成所属阵营的皮肤。
     *
     * <p>只在该玩家**加入比赛**时调用，不要在每次复活时调用：皮肤是存在
     * 皮肤插件数据库里的，不会因为死亡而丢失，反复下发只会多出一串没必要的命令。
     */
    public void apply(Player player, TdmTeam team) {
        TdmSettings settings = plugin.getSettings();
        if (!settings.skinEnabled) {
            return;
        }
        String skin = team == TdmTeam.T ? settings.skinT : settings.skinCt;
        if (skin == null || skin.isBlank()) {
            // 只配了一边也允许：另一边保持玩家自己的皮肤
            return;
        }
        if (!run(settings.skinApplyCommand, player, skin)) {
            return;
        }
        player.getPersistentDataContainer().set(pending, PersistentDataType.BYTE, (byte) 1);
    }

    /** 还原玩家原本的皮肤；没有标记说明本插件没改过，直接跳过。 */
    public void restore(Player player) {
        if (player.getPersistentDataContainer().get(pending, PersistentDataType.BYTE) == null) {
            return;
        }
        // 先清标记再发命令：即使命令失败，也不会让玩家每次进服都被重复还原一次。
        // 正常情况下失败只意味着「皮肤没还回去」，而不是「状态错乱」。
        player.getPersistentDataContainer().remove(pending);
        run(plugin.getSettings().skinRestoreCommand, player, null);
    }

    /** 关服时把所有还穿着队伍皮肤的人还原，避免把状态留到下一次开服。 */
    public void restoreAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            restore(player);
        }
    }

    /**
     * 崩溃 / 重启后的补救：带着标记进服，说明上一次没能还原成功。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().getPersistentDataContainer()
                .get(pending, PersistentDataType.BYTE) != null) {
            restore(event.getPlayer());
        }
    }

    /**
     * 启动自检：开了功能却连命令都解析不出来时，吵一句。
     *
     * <p>这类配置错误的共同点是**运行时一声不响**：玩家该有的皮肤没有，
     * 但控制台干干净净，服主只能靠肉眼在游戏里发现。宁可启动时说清楚。
     *
     * <p>判断方式刻意保守：命令表里查不到时**再看插件在不在**。因为
     * SkinsRestorer 15 的命令是运行时注册的（plugin.yml 里没有 commands 段），
     * 只查 {@code getPluginCommand} 会把「装好了但注册方式不同」误判成「没装」，
     * 反过来给服主一条假警告。两者都没有才报警。
     */
    public void checkPrerequisites() {
        TdmSettings settings = plugin.getSettings();
        if (!settings.skinEnabled) {
            return;
        }
        String apply = settings.skinApplyCommand;
        if (apply == null || apply.isBlank()) {
            plugin.getLogger().warning("teammate.skin.enabled 为 true，但 apply-command 是空的，"
                    + "队伍皮肤不会生效。");
            return;
        }
        String root = apply.trim().split("\\s+")[0];
        boolean commandKnown = Bukkit.getCommandMap() != null
                && Bukkit.getCommandMap().getCommand(root) != null;
        boolean pluginKnown = Bukkit.getPluginManager().getPlugin("SkinsRestorer") != null
                || Bukkit.getPluginManager().getPlugin("SkinShuffle") != null;
        if (!commandKnown && !pluginKnown) {
            plugin.getLogger().warning("队伍皮肤要执行 /" + root + "，但命令表里没有它、"
                    + "也没检测到 SkinsRestorer / SkinShuffle —— 请先装皮肤插件，"
                    + "或把 teammate.skin.apply-command 改成你实际在用的命令。");
        }
        if (settings.skinT.isBlank() || settings.skinCt.isBlank()) {
            plugin.getLogger().warning("队伍皮肤已开启，但 t-skin / ct-skin 里有空值，"
                    + "留空的那一方会保持玩家自己的皮肤。");
        }
    }

    /**
     * 执行一条命令模板。
     *
     * @return 命令是否被服务端接住（不代表皮肤插件那边一定成功）
     */
    private boolean run(String template, Player player, String skin) {
        if (template == null || template.isBlank()) {
            return false;
        }
        String command = template
                .replace("%player%", player.getName())
                .replace("%skin%", skin == null ? "" : skin)
                .trim();
        if (command.isEmpty()) {
            return false;
        }
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException e) {
            // 皮肤插件的命令抛异常不该把比赛流程带崩
            plugin.getLogger().warning("队伍皮肤命令执行失败：" + command
                    + "（" + e.getMessage() + "）");
            return false;
        }
    }
}

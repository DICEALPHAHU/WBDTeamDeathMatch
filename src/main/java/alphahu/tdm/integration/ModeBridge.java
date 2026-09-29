package alphahu.tdm.integration;

import org.bukkit.entity.Player;

/**
 * 与外部附加组件（目前是 WBDAddon）对接的抽象。
 *
 * <p>本接口由 {@link AddonBridge} 持有，实现类则被刻意隔离在
 * {@code WbdAddonModeBridge} 里 —— 那个类实现了 WBDAddon 的 {@code AddonMode}，
 * 因此**只有在确认 WBDAddon 确实存在之后才会被加载**。
 *
 * <p>这层间接不是过度设计，而是软依赖的硬性要求：JVM 加载一个类时就要解析
 * 它的父类与接口，所以「实现了可选依赖的接口」等价于「硬依赖它」。
 * 想让插件在对方缺席时照样启动，唯一的办法就是让引用它的那个类**推迟到确认存在之后再加载**。
 *
 * @author AlphaHu
 */
public interface ModeBridge {

    /** 该对接是否已成功建立。 */
    boolean isActive();

    /** 顶部状态行是否由对方代渲染（决定本插件要不要自己发 ActionBar）。 */
    boolean isHudRelayed();

    /** 解析武器显示名，能认 TacZ 枪械。 */
    String resolveWeaponName(Player killer);

    /** 伤害账本；对方没有提供该能力时返回 null。 */
    DamageLedger damageLedger();

    /** 注销对接。插件卸载或关闭集成时调用。 */
    void unregister();
}

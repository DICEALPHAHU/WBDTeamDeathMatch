package alphahu.tdm.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 伤害账本的只读视图。
 *
 * <p>它是 WBDAddon 的 {@code CombatStats} 在本插件内部的中立替身 ——
 * 存在的唯一理由是**不让 WBDAddon 的类型泄漏到主流程里**。
 *
 * <p>为什么这件事值得单开一个接口：WBDAddon 是可选的。如果 {@code TdmMatch}
 * 直接拿 {@code CombatStats} 当字段或返回值类型，那么 JVM 在加载 {@code TdmMatch}
 * 时就要解析那个类；没装 WBDAddon 的服务器上会直接
 * {@code NoClassDefFoundError}，整个插件起不来 —— 连带死斗本身也一起没了。
 * 换成本接口之后，主流程只认识自己的类型，WBDAddon 在与不在都不影响它加载。
 *
 * @author AlphaHu
 */
public interface DamageLedger {

    /**
     * 取某个受害者受到的伤害来源，按累计伤害从高到低。
     *
     * @return 攻击者 UUID 与累计伤害；无记录时返回空列表（不应返回 null）
     */
    List<Map.Entry<UUID, Double>> getDamageSources(UUID victim);
}

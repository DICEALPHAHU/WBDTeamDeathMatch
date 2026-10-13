# 与 WBDAddon 的集成

WBDAddon 是**软依赖**：没装时本插件照常运行，只是失去饥饿锁定、JourneyMap 桥接、武器名解析、队友名牌前缀这些附加能力。

---

## ⚠️ 最重要的约束：类型引用必须隔离

**`WbdAddonModeBridge` 是全项目唯一 `import alphahu.wbdaddon.*` 的地方**，且不允许被 `AddonBridge` 之外的任何代码直接引用——不能当类型、不能当字段、不能当返回值、不能当参数。

### 为什么

JVM 在加载一个类时就要解析它的接口与父类。只要主流程里有**任何一个类** `implements AddonMode`，那么在没装 WBDAddon 的服务器上，那个类一被加载就抛：

```
NoClassDefFoundError: alphahu/wbdaddon/api/AddonMode
```

插件连 `onEnable` 都跑不完。

**这个坑真实踩过**：早期版本让 `AddonBridge` 直接 `implements AddonMode`，结果代码里写的"取不到服务就降级"那段容错**根本执行不到**——备选分支放在一个加载阶段就会崩的类里，等于不存在。所以那段容错是自欺欺人的，看起来写了，实际一行都不会执行。

### 现在的加载路径

`AddonBridge.register()` 分三步，只有全部通过才会去加载真正引用对方的实现类：

```
第 1 步：WBDAddon 插件在不在、启用没有
        （只碰 org.bukkit.plugin.Plugin，不碰它的任何类）

第 2 步：它有没有 alphahu.wbdaddon.api 包
        Class.forName(API_CLASS, false, wbdAddon.getClass().getClassLoader())
        initialize=false 只检查类存在与否，不触发静态初始化；
        用对方的 ClassLoader 查询，不依赖本插件自己的可见性配置
        —— 这一步拦住 1.1.0 之前的旧版本

第 3 步：Class.forName("alphahu.tdm.integration.WbdAddonModeBridge")
        此时 AddonMode 一定解析得到，这个类也就一定能安全加载
```

实现类的类名刻意写成**字符串**而不是 `WbdAddonModeBridge.class`：后者会让 `AddonBridge` 在常量池里持有对实现类的引用，虽然常量池解析通常也是惰性的，但用字符串能百分百保证本类加载时不会连带触发实现类的加载。

**换句话说：`WbdAddonModeBridge` 的加载本身，就是"WBDAddon 可用"的证明。** 因此它的构造函数里不做任何探测，直接取服务、注册模式。

### 改完这段代码后该怎么验证

```bash
mvn -B clean package
javap -c -p target/classes/alphahu/tdm/integration/AddonBridge.class | grep -i wbdaddon
```

期望结果是：`wbdaddon` **只出现在字符串常量里**（`ldc` 指令的操作数），**没有 `CONSTANT_Class` 条目**。如果看到类引用，说明隔离被破坏了。

---

## AddonMode 接口

模式实现这个接口并注册进 `WbdAddonApi`，附加组件便只依赖"参赛 / 存活 / 阵营"这三个语义，而不再关心背后是爆破还是死斗。

| 方法 | 说明 |
|---|---|
| `getModeId()` | 模式唯一标识，本插件用 `team-deathmatch` |
| `getDisplayName()` | 用于日志与 `/wbdaddon modes` |
| `isParticipating(Player)` | 是否"在这个模式里"（含已阵亡等待复活的） |
| `isPlaying(Player)` | 是否正式参赛者（分到了阵营） |
| `isAlive(Player)` | 此刻是否存活可战斗 |
| `getTeamId(Player)` | 阵营稳定标识（`T` / `CT`），会被拼进原版队伍名 |
| `getArenaId(Player)` / `getArenaName(Player)` | 所在场地 |
| `isSameTeam(a, b)` | 默认比较 `getTeamId` |
| `hudLine(Player)` | 自定义 ActionBar 内容；返回 null 则 WBDAddon 跳过该玩家 |
| `nameTagPrefix(Player)` | **API 1.2 新增**。队友名牌前缀，返回 null 表示不改名牌 |
| `hideEnemyNameTags()` | **API 1.2 新增**。是否隐藏敌人头顶名牌 |

### 本插件的选择

`nameTagPrefix` / `hideEnemyNameTags` 由 `config.yml` 的 `teammate.name-tag` 控制，**两项共用一个开关**。不拆开是因为：原版队伍前缀是"所有人的客户端都收得到"的，只加前缀而不管可见性，敌人头上也会顶着一个 `[队友]`——比不做还糟。

### 与爆破模式的关系

`BombDefuseMode` 什么都不覆盖，所以那两项保持默认（不改名牌、不隐藏）。这是刻意的：**爆破模式的名牌显示由 WarZBombDefuse 主插件自己负责**，附加组件如果擅自设了前缀，就会和主插件抢同一个队伍的 prefix——最终显示哪个取决于谁后写，是那种"有时对有时错"、极难归因的故障。

---

## 队友名牌的实现

走的是**原版队伍机制**，不需要发包插件，因此在 Arclight 这类混合端上同样有效。

`TeamSynchronizer` 在**新建队伍时**应用两项设置：

```java
team.setPrefix(...);                        // [队友] 前缀
team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OWN_TEAM);
```

`FOR_OWN_TEAM` 的语义是"只对自己这一方可见"。

> **容易写错的地方**：直觉上会去找 `HIDE_FOR_OTHER_TEAMS` 这个名字，但它**不存在**。实际枚举只有四个：
>
> ```
> ALWAYS / NEVER / FOR_OTHER_TEAMS / FOR_OWN_TEAM
> ```
>
> 而 `FOR_OTHER_TEAMS` 的语义是"只对**其他**队伍可见"，跟想要的效果**正好相反**。写错的话表现是队友看不见名牌、敌人反而看得见。这个名字很容易凭印象写出来，一定要查实际 API。

**只在新建时设一次**，不是每轮都设：这两个值对同一个队伍是静态的，而每轮重写会向客户端推送多余的队伍包（20 tick 一轮、十几个人）。那正是之前修"计分板闪烁"时踩过的坑——当时的原因就是无条件增删队伍条目。

---

## 版本要求

| 用途 | 需要 |
|---|---|
| 注册模式，复用饥饿锁定 / JourneyMap 桥接 | WBDAddon **1.1.0**（API `1.1`） |
| 队友名牌前缀 + 隐藏敌人名牌 | WBDAddon **1.2.1**（API `1.2`） |

装 1.1.0 的 WBDAddon 配新版本插件**不会报任何错**，这两项只是**静默失效**——玩家看到的是一堆头顶没有标记的敌人，然后来问你插件是不是坏了。

所以 `WbdAddonModeBridge.checkApiVersion()` 会读 `getApiVersion()` 并比较，低于 1.2 且名牌功能开着时打印警告。

> 为什么能这么检查而不违反类型隔离：`getApiVersion()` 从 API `1.1` 起就存在，而且这个调用发生在**已经确认 WBDAddon 在场之后**的 `WbdAddonModeBridge` 内部。

---

## 伤害账本

死斗用它来校正 TacZ 环境下的击杀归属：那种场景里原版事件的 killer 可能为空。

```java
CombatStats stats = api.getCombatStats();   // kill-reports 模块没启用时返回 null
ledger = stats::getDamageSources;
```

刻意**不提供 `reset()`**：`DamageTracker` 是 WBDAddon 与爆破模式共享的实例，死斗去清空它会把正在进行的爆破回合统计一并抹掉。账本按"受害者 → 攻击者"聚合，跨场累积对击杀归属的判断几乎没有影响。

---

## 相关文档

- [architecture.md](architecture.md) —— 整体分包与数据流
- [configuration.md](configuration.md) —— `integration` 与 `teammate` 配置段
- [../libs/README.md](../libs/README.md) —— 编译期依赖怎么获取

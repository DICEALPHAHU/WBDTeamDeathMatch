# 架构

## 包的职责

| 包 | 职责 | 关键类 |
|---|---|---|
| `alphahu.tdm` | 插件主类：启动闸门、依赖检查、各服务装配与生命周期 | `WBDTeamDeathmatch` |
| `arena` | 场地的定义、读取、校验、购买区 | `TdmArena`、`ArenaLoader`、`ArenaRules`、`BuyZone` |
| `game` | 比赛本体：状态机、计分、队伍、出生装备、离场快照 | `TdmMatch`、`MatchManager`、`TdmPlayer`、`LoadoutService`、`PlayerSnapshot` |
| `combat` | 战斗事件：死亡处理、友伤过滤、复活保护 | `CombatListener` |
| `hud` | 三种显示通道：BossBar、ActionBar、侧边栏计分板 | `MatchHud` |
| `shop` | 商店：配置解析、界面、购买、编辑器 | `ShopService`、`ShopEditor`、`ShopListener`、`ShopItem` |
| `ranked` | 段位：计分、档案、SQLite 存储、赛季、比赛历史 | `RankedService`、`RankedStore`、`Tier`、`TierTable`、`MatchRecord` |
| `report` | 战报：内容生成、落盘、查询界面 | `ReportService`、`ReportMenu`、`ReportListener` |
| `integration` | 与 WBDAddon 的对接层（**唯一允许引用对方类型的地方**） | `AddonBridge`、`WbdAddonModeBridge`、`ModeBridge` |
| `message` | `messages.yml` 读取与占位符替换 | `Messages` |
| `config` | `config.yml` / `season.yml` 的类型化读取 | `TdmSettings`、`RankedSettings` |
| `command` | `/tdm` 全部子命令 | `TdmCommand` |
| `util` | 无状态工具 | `ItemCodec`、`ItemParser`、`LocationCodec`、`Text` |

## 关键数据流

### 启动

```
onEnable
  ├─ checkDependencies()          ← 硬依赖 WarZBombDefuse，缺则 disablePlugin 并返回
  └─ init()
       ├─ 释放默认配置（缺失时才生成）
       ├─ TdmSettings / Messages / RankedService / ReportService
       ├─ MatchManager.loadArenas()  ← 读 arenas.yml，并从 WBD 补场地显示名
       ├─ 注册事件监听
       ├─ registerCommand()
       ├─ AddonBridge.register()      ← 软依赖，三步探测
       └─ 启动两个定时任务
            ├─ matchManager.tick()   每 tick
            └─ ranked.tickSeason()   每 5 分钟
```

`checkDependencies()` 出现在 `printBanner()` 之后、任何初始化之前。`plugin.yml` 里已经写了 `depend`，正常情况下服务端根本不会走到这里；这一段是给"装了但加载失败、或被别的插件连带禁用"准备的。

### 一局比赛

```
WAITING ──人满──> COUNTDOWN ──倒计时结束──> LIVE ──达标/超时──> ENDED ──endDelay──> WAITING
```

`ENDED` 状态下 `endMatch()` 依次做四件事，**顺序不能改**：

```
1. 播报胜负、比分、MVP、放烟花
2. RankedService.settle()      → 段位加减 + 发结算明细
3. ReportService.save()        → 主线程构建文本，异步写 YAML
4. RankedService.recordMatch() → 异步写 SQLite，供 /tdm matchrp 查询
```

第 2 步之后、`restart()` 之前必须完成全部三步。`restart()` 会遍历玩家执行 `resetForNewMatch()`，把击杀、死亡、连杀清零——晚一步就是"所有人按 0 击杀结算"，**而且不报错**，只表现为分数永远偏低。

### 阵亡与复活

```
EntityDamageEvent(HIGHEST, ignoreCancelled) → CombatListener 判定致死
  → TdmMatch.handleDeath(victim, killer)
       ├─ DamageLedger 校正击杀归属（TacZ 环境下原版 killer 可能为空）
       ├─ 记分 + 赏金 + 连杀播报
       ├─ 清连杀、排复活时间
       └─ 下一 tick 切旁观
PlayerRespawnEvent → 复活点 → restoreAfterRespawn()
       └─ 只复位状态并补商店入口，**不动背包**
```

`spectatorPending` 标志解决一个真实竞态：`delay-seconds: 0` 时复活可能抢在切旁观之前执行，那时玩家还卡在死亡界面上，`setGameMode` 和传送全部无效，随后到来的 `respawn()` 会把他丢到主城出生点。

## 线程模型

| 操作 | 线程 | 原因 |
|---|---|---|
| 比赛推进、事件处理、计分、播报、开界面 | 主线程 | Bukkit API 要求 |
| 段位档案载入、落库、赛季软重置 | 异步 | 磁盘 IO，且结算时刻正是服务器最忙的时候 |
| 战报写文件 | 异步 | 几百行文本 + 一次 fsync |
| 战报内容**构建** | **主线程** | 读的是活对象，异步去做会拿到已被清零的数据 |
| `/tdm matchrp` 查询 | 异步 → 回主线程开界面 | 查库在主线程做就是卡玩家那一下 |
| 段位计分板读取 | 主线程（纯内存） | 计分板每 20 tick 重算一次，绝不能碰磁盘 |

所有 `RankedStore` 方法都是 `synchronized`：SQLite 单连接不是线程安全的，而读写发生在不同线程。段位操作频率极低，锁不会成为瓶颈。数据库开了 WAL 模式，写不阻塞读。

## 几处"看起来可以简化、其实不能动"的设计

### 1. `WbdAddonModeBridge` 必须孤立存在

它是全项目**唯一** `import alphahu.wbdaddon.*` 的地方，且不允许被 `AddonBridge` 之外的任何代码直接引用（类型、字段、返回值都不行）。

原因：WBDAddon 是可选依赖，而 **JVM 在加载一个类时就要解析它的接口与父类**。只要主流程里有任何一个类 `implements AddonMode`，那么在没装 WBDAddon 的服务器上，那个类一被加载就抛 `NoClassDefFoundError`，插件连 `onEnable` 都跑不完——写在代码里的"取不到服务就降级"那段容错**根本执行不到**，因为备选分支放在一个加载阶段就会崩的类里。

验证方法（改完这段代码后应重跑）：

```bash
javap -c -p target/classes/alphahu/tdm/integration/AddonBridge.class | grep wbdaddon
# 期望：只在字符串常量里出现，没有 CONSTANT_Class 条目
```

### 2. 硬依赖用 `depend` 而不是"启动后自己禁用"

Bukkit 会**先**按 `plugin.yml` 注册命令，**然后**才调 `onEnable`。所以"加载成功再自己禁用"会留下一个 `/tdm` 命令——它存在于命令表里，但 executor 永远没挂上，玩家执行只会看到干巴巴一句 `/tdm help`，看不出任何异常。写 `depend` 则是在加载阶段就被服务端拦下。

`checkDependencies()` 是第二道闸，不是替代品。

### 3. 购买区要在三个地方检查

打开菜单（右键商店物品）、命令入口（`/tdm shop`）、点击购买。

- 只查打开时机 → 玩家可以开完菜单再走出去继续买
- 只查右键路径 → `/tdm shop` 是绕过限制最省事的入口

判断收在 `TdmMatch.canPurchase(player)` 一处，避免写两遍漏一遍。

### 4. 金钱写入只有一个入口

`RankedService`/`TdmMatch.addMoney(record, amount)` 是唯一往玩家账上加钱的地方，且**返回实际到账数**而非传入值。到上限时播报显示真实的 `+0`——如果播报照喊 `+300` 而实际没加，玩家会认为钱被吞了。

### 5. 战报内容在主线程构建、写文件在异步

两步分开。合成一步放在异步里，拿到的是已被 `resetForNewMatch()` 清零的数据；合成一步放在主线程，就是每局结束卡一下。

### 6. 清理旧战报只认自己的文件名格式

按 `\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_.+\.yml` 匹配。`reports/` 目录里完全可能有服主自己放进去的文件（手工整理的赛季汇总、从别处拷来的战报），按"所有 `.yml` 都是战报"去删就是删用户数据。

## 配置的生命周期

`TdmSettings` 与 `RankedSettings` 都是**不可变**的：加载后不再改动，重载时整体换一个新实例。这样比赛进行中读到的永远是自洽的一整套，不会出现"读到一半配置被换了，一半新一半旧"。

`/tdm reload` 会重建：设置、出生装备、商店、场地、消息文案、段位设置。会先把场上的人还原送走，因此不适合在比赛正打到一半时随手执行。

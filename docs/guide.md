# WBDTeamDeathmatch

WarZBombDefuse 生态下的**团队死斗**模式：无限复活、限时击杀赛，先达到目标击杀数的一方获胜；
时间耗尽时击杀多的一方获胜，完全相同则判平局。

它是一个**独立插件**，拥有自己的场地与比赛循环，不依赖 WarZBombDefuse 运行；
同时通过 WBDAddon 的 `WbdAddonApi` 接入，从而复用附加组件已有的能力。

> 已在 **Arclight 1.20.1**（Forge + Bukkit 混合端）环境下按此设计实现并编译验证。
> 实际开服前请先在测试环境跑一轮。

## 它和 WarZBombDefuse 是什么关系

先说一个反直觉的结论：**死斗不能建立在 WBD 的爆破模式之上**。原因在 WBD 1.2.2 的实现里：

- `ArenaSession` 是 `final` 类，回合状态机（`WAITING → COUNTDOWN → FREEZE → LIVE → ROUND_END → MATCH_END`）
  由插件自己的秒级 tick 强制推进，`startRound / endRound / eliminate` 全是 `private`，
  外部无法覆盖，jar 里也不存在任何模式注册机制。
- **没有复活 API**：`eliminate()` 会把玩家标记为阵亡并丢进旁观者集合，
  之后 `join()` 对已在场的玩家直接拒绝，只能等下一回合统一重置。

也就是说，「把 WBD 的爆破模式改造成死斗」这条路不存在。

所以本插件的做法是：**另起一套比赛循环，与爆破模式平行**。两边唯一的交集是
WBDAddon 的 `WbdAddonApi` —— 把死斗玩家注册成一个「模式」，WBDAddon 的
饥饿锁定、JourneyMap 队伍桥接、伤害账本、枪械名解析便会自动覆盖到
死斗玩家身上。

### ⚠️ 一个必须遵守的约定

**被死斗使用的场地，在 WarZBombDefuse 侧必须保持 `enabled: false`。**

只要 WBD 启用了同一个场地，它就会同时在这个场地里推回合、发炸弹、判全灭、
强制旁观，和死斗的无限复活直接打架，表现为「玩家莫名变成旁观者」「复活后又被传走」。

好消息是这件事不用你操心：**`/tdm enable <场地id>` 会自动帮你处理** ——
它会调用 WBD 自己的 `/wbd disable <id>`，把场地让出来再让死斗接手。
启动时如果发现冲突也会告警提示。

（`integration.auto-disable-in-wbd: false` 可以关掉这个自动行为。）

### 为什么走 WBD 的命令

`/tdm enable` 处理 WBD 侧冲突时，调用的是 `/wbd disable <id>`，而不是去改它的
`arenas.yml`。两个原因，第二个是决定性的：

1. **直接改文件会抹掉 WBD 的注释。** YAML 的读写只保留数据，
   解析阶段注释就已经不存在了。WBD 那份配置里写着大量中文说明，
   服主打开发现注释全没了，这本身就是个事故。
2. **改了也没用。** WBD 的启用状态加载在内存里（`ArenaManager` 持有对应的
   `ArenaSession`）。你在磁盘上把它改成 `enabled: false`，但没人通知 WBD，
   那个场子照样推回合、发炸弹 —— 冲突一点没解决，只是变得更难发现了。

走它自己的命令，则持久化与运行时状态都由 WBD 自己同步，格式也由它维护。
命令执行后本插件会重新读一次 WBD 的配置**复核结果**，没生效就明确告诉你
手动执行 `/wbd disable <id>`，而不是报一句「已处理」了事。

> 同一个坑也存在于本插件自己的 `arenas.yml`：`/tdm import` 与 `/tdm enable`
> 保存时会重写文件，**你手动添加的注释不会保留**。为此插件每次保存都会写入
> 一段固定的头部说明（坐标格式、该用哪些命令），需要写更详细的备注请放在
> 本文档或 `config.yml` 里。

## 依赖

| 依赖 | 是否必需 | 说明 |
| --- | --- | --- |
| **Paper / Spigot / Arclight 1.20.1+** | 必需 | 按 Paper API 编译，走 spigot 兼容接口发送 HUD |
| **Java 17** | 必需 | |
| **WBDAddon 1.1.0+** | 可选 | 提供 `WbdAddonApi`。没装时死斗照常运行，只是失去饥饿锁定、JourneyMap 桥接、枪械名解析等附加功能 |
| **WarZBombDefuse** | 可选 | 死斗不依赖它。装了才能用 `/tdm import` 从它的 `arenas.yml` 导入已量好的坐标 |
| **TacZ + TacZSpigotBridge** | 可选 | 有 TacZ 枪械时，击杀播报能显示 `P320` 而不是 `tacz:p320` |

## 安装

1. 把 `WBDTDM-<版本>-<git短哈希>-<时间戳>.jar` 放进服务端 `plugins`
2. （推荐）同时安装 `WBDAddon-1.1.0+`，以复用附加功能
3. 重启服务器
4. 编辑 `plugins/WBDTeamDeathmatch/arenas.yml`，配上出生点与边界
5. 执行 `/tdm enable <场地id>` 启用场地

## 快速开始

### 方式一：从 WarZBombDefuse 导入坐标（推荐）

先在 WBD 里把场地量好：

```
/wbd create tdm_dust
/wbd setup tdm_dust
```

再把坐标导进死斗：

```
/tdm import tdm_dust
```

坐标会写进 `plugins/WBDTeamDeathmatch/arenas.yml`，**并且保持 `enabled: false`** ——
导入只负责省去手抄坐标，坐标对不对得你自己看一眼。

核对无误后，一条命令收尾：

```
/tdm enable tdm_dust
```

它会一次做完三件事：把死斗这侧设成启用、调用 `/wbd disable dust2` 让 WarZBombDefuse
把同名场地让出来、重载配置。之所以不用你手动改两个文件，原因见
[为什么走 WBD 的命令](#为什么走-wbd-的命令)。

### 方式二：手写配置

`arenas.yml` 的字段结构与 WBD 的 `arenas.yml` 完全一致（坐标串同为
`world,x,y,z,yaw,pitch`），照着示例改即可。

### 开打

```
/tdm join            加入人数最少的场地，自动分队
/tdm join tdm_dust t 加入指定场地并选择 T 方
/tdm leave           离开比赛，位置与背包自动还原
/tdm list            查看所有场地
/tdm status          查看当前比赛详情
```

人数达到 `match.min-players` 后自动开始倒计时，倒计时结束即开打。

## ⌨️ 命令

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/tdm help` | — | 帮助 |
| `/tdm list` | — | 列出所有场地与状态 |
| `/tdm join [场地] [t\|ct]` | `tdm.use` | 加入比赛，可指定阵营 |
| `/tdm leave` | `tdm.use` | 离开比赛并还原状态 |
| `/tdm shop` | `tdm.use` | 打开装备商店（也可以用出生时发的商店物品） |
| `/tdm status [场地]` | — | 查看比分、时间、各玩家战绩 |
| `/tdm enable <场地id>` | `tdm.admin` | 启用场地，并自动在 WarZBombDefuse 侧禁用同名场地 |
| `/tdm disable <场地id>` | `tdm.admin` | 停用场地（不动 WBD 侧） |
| `/tdm start [场地]` | `tdm.admin` | 强制开始 |
| `/tdm stop [场地]` | `tdm.admin` | 强制结束（按当前比分判定） |
| `/tdm reload` | `tdm.admin` | 重载配置与竞技场（会把场上的人送出去） |
| `/tdm import <wbd场地id> [新id]` | `tdm.admin` | 从 WarZBombDefuse 导入坐标 |

权限节点：`tdm.use`（默认所有玩家）、`tdm.admin`（默认 OP）。

## 玩法规则

### 装备与商店

玩家出生时会拿到一个**商店物品**（默认绿宝石，快捷栏最后一格），右键打开装备菜单；
也可以随时用 `/tdm shop`。商品**按 `price` 收费**，钱来自击杀与连杀 ——
打得好的队伍先买得起好枪，这条正反馈是模式的一部分（详见下文「装备、金钱与商店」；
把 `economy.enabled` 关掉就回到全免费）。

**商店直接兼容 WarZBombDefuse 的 `shop.yml` 格式**，默认 `shop.source: wbd` 就是读
`plugins/WarZBombDefuse/shop.yml`。你为爆破模式配好的那份可以直接共用：

- 页码、菜单槽位、翻页按钮、阵营过滤（`team`）都照常生效
- **阵营专属商城 `team-shops.T/CT` 也支持**，同 ID 会覆盖公共商品
- 枪械那种内嵌了完整物品数据（`display-data` / `give-data`，Bukkit 序列化）的商品
  能原样还原 —— 这部分是编辑器生成的，重写一遍不现实
- `price` **生效**（`economy.enabled: false` 时才回到全免费）；`limit-per-round` 忽略（死斗没有回合）
- `click-commands` / `commands`、`command-sender`、`command-repeat`、`close-after-click` 都支持

#### 购买覆盖，不会堆满背包

**光靠「覆盖落点那一格」是挡不住囤积的** —— 玩家把买到的东西往背包里一挪，
那一格就空了，再买一次新的进来，旧的还躺在别处，几次下来就是一背包枪。

所以每件由商店发出的物品都会被打上一个**分组标记**（标记里存的是它的**落点槽位**），
购买时先扫一遍背包（含护甲槽与副手），把同组的旧物品清掉，再放新的：

- **分组按落点，不按材质**：落点相同就是「互相替换」，落点不同就是「并存」——
  这正好对应玩家「一个格子一份装备」的直觉
- 早期版本按图标材质分组，而**刀、手枪、主武器的图标材质是同一个**
  （那只是 TacZ 的通用底材，真正的区别在 NBT 里），于是三者被归成一组：
  买把刀会把枪顶掉，发默认装备时刚给的刀又被随后发的手枪覆盖，表现成「刀根本没发到手上」
- **只清带标记的**：你从地上捡的、别人丢的同款没有标记，不会被误删

落点按这个优先级决定：

1. `config.yml` 里的 `shop.slot-overrides`（按商品 id）
2. 商品自己的 `give-slot`
3. **按商品图标的材质分组** —— 同材质共用一个槽位
4. `shop.fallback-slot`

**分配结果会打进启动日志**，想固定某一类就在 `config.yml` 里写：

```yaml
shop:
  material-slots:
    TACZ_MODERN_KINETIC_GUN: 0     # 枪 → 主武器槽
    LRTACTICAL_THROWABLE: 3        # 投掷物 → 第 3 格
    POTION: 4
```

#### 可视化编辑：`/tdm shopedit`

```
/tdm shopedit            编辑公共商城
/tdm shopedit T          编辑 T 方专属商城
/tdm shopedit T 2        编辑 T 方第 2 页
```

打开一个箱子，**把要卖的东西摆进去，关掉箱子即保存**。摆在哪一格，玩家就在
哪一格看到它。这比手写 YAML 现实得多 —— 枪械的 NBT、附魔、改名根本没法手敲。

> 编辑器会拦掉 Shift、数字键、拖拽铺货等快捷操作。它们一次改动多格，
> 而这里每一格都对应一件商品，误操作代价太大。

> ⚠️ 编辑器保存时用 YAML 重写文件，**你手动加的注释不会保留**（这是 YAML 读写
> 的固有限制）。所以每次保存都会写入一段固定的字段说明。需要写备注请放在本文档。

#### 从 WBD 采用商店配置

默认 `shop.source: wbd`，直接读 `plugins/WarZBombDefuse/shop.yml`，不复制、不干扰。

执行 `/tdm import <场地id>` 导入场地后，插件会问一句**是否也采用 WBD 的商店配置**，
点一下按钮即可：

```bash
/tdm shop adopt        # 或者手动执行
```

它会**把 WBD 的商店复制一份到 `plugins/WBDTeamDeathmatch/shop.yml`**，并把
`config.yml` 的 `shop.source` 改成 `local`，之后两边独立维护 —— 你为死斗调整商品
不会影响到爆破模式。

（改 `config.yml` 用的是逐行文本替换而不是 YAML 读写，就是为了不丢掉那份文件里
的大量注释。）

#### 初始装备

`loadout.common` 默认是**空的** —— 装备交给商店，玩家进场后自己选，这样「选装备」
才是一个真正的决策。想保留初始装备就往 `config.yml` 里加条目。

### 装备、金钱与商店

**死斗不是「全免费自选」**：打得好的队伍先买得起好枪，这条正反馈是模式的一部分。

```yaml
economy:
  enabled: true
  start-money: 800          # 每场比赛开始时的启动资金
  kill-reward: 300          # 每次击杀的基础赏金
  killstreak-bonus: 100     # 第 N 连杀时额外多给 (N-1) × 本值
  killstreak-bonus-max: 500 # 连杀额外部分的封顶
  max-money: 16000
```

**价格来自 `shop.yml` 里每件商品的 `price` 字段** —— 那份配置本来就有，之前被忽略。
关掉 `economy.enabled` 就回到全免费。

两个刻意的设计：

- **每场比赛重置启动资金，不跨场累积。** 钱一路带着走，优势会滚成雪球，后半场基本只剩一边在打。
- **连杀额外赏金有上限。** 不封顶的话，滚起来的高手会迅速「钱多到随便买、还越打越强」；有限的正反馈才留得住翻盘空间。

装备显示：

- **ActionBar 击杀提示**：`[武器] 受害者 +300`，两秒后淡出
- **侧边栏**：`%money%` 显示当前金钱
- **商店图标**：直接标出价格与余额，买不起的会标红 —— 一屏几十件商品，逐个点着试的体验太差

玩家**等待开场期间就能开商店**（进比赛时即发装备与启动资金），不必干等倒计时。

### 三种显示通道的分工

**同一份信息只在一个地方显示** —— 三块区域各管一摊，互不重复：

| 通道 | 放什么 |
|---|---|
| **BossBar** | 比赛倒计时条（剩余时间 + 双方比分）、状态条（等待复活 / 复活无敌） |
| **ActionBar** | 只在击杀时闪一条 `[武器] 受害者 +赏金`，两秒后淡出 |
| **侧边栏** | 个人数据：金钱、击杀、死亡、K/D、目标击杀数 |

所以侧边栏里**刻意不放** `%time%` 和双方比分 —— 那两项 BossBar 上已经有了，
再写一遍只是白占位置。

**ActionBar 平时是空的。** 常驻信息一旦占着它，击杀提示就会被不断覆盖，等于没提示。
所以比分与时间都挪到了侧边栏和 BossBar 上。

击杀提示的「淡出」是近似实现：原版 ActionBar 没有透明度动画，所以最后 400 毫秒
把文字转成暗灰色，看起来像渐渐退去。

```yaml
hud:
  kill-message-format: "&e[%weapon%] &f%victim% &a+%money%"
  kill-message-duration-ms: 2000
  protection-bar-title: "&b无敌 &f%seconds% &b秒"
```

**复活无敌会用 BossBar 显示剩余时间。** 保护期内玩家免疫伤害，界面上如果毫无表示，
他会以为自己打不动人、或者以为对手有问题 —— 几秒的信息空缺很容易被当成故障。

### 侧边栏为什么是「每人一块」

个人击杀/死亡要进侧边栏，就只能是**每人一块独立计分板** —— 全服共用一块时，
那一栏对所有人都是同一行字，装不下个人数据。

代价是：原版队伍数据跟着玩家**当前所用的计分板**下发，玩家一切到自己的板子，
就收不到主计分板上的 `wbd_*` 队伍，**JourneyMap 的队友可见性会静默失效**。

所以插件会把主计分板上的队伍**镜像**到每人的板子上（每 2 秒一次，按时间而不是
调用次数控制频率 —— `render` 是按玩家逐个调用的，用计数器会随人数放大）。
镜像之后两者可以并存：个人战报与 JourneyMap 队伍可见性互不影响。

**多个场地同时开也不受影响**：每人的板子只显示自己所在那场比赛的数据，
不存在「一块板子轮流显示几场比赛」的问题。

### 无限复活

- 阵亡后等待 `respawn.delay-seconds` 秒（默认 3 秒），随后在己方出生点复活
- **比赛中不能丢弃物品**（`combat.prevent-item-drop: true`）：
  装备随时能从商店补，丢东西没有实际意义；一刀切还能堵死
  「买一个丢地上、再买一个、捡回来」这条刷装备的路子。只对比赛中的玩家生效
- **死亡不掉落**（`respawn.drop-items: false`）：插件同时做两件事 ——
  设 `keepInventory` 并**直接清空掉落列表**。后者不依赖服务端是否正确实现了
  `keepInventory`，在这类混合端上是更可靠的那一层
- 死亡消息被替换成死斗格式的击杀播报
- 等待复活期间处于旁观状态，可以在场地内自由飞行观战，屏幕上有一条 BossBar 倒数
- 复活后获得 `respawn.protection-seconds` 秒无敌保护（默认 2 秒）
- **保护期内主动攻击敌人会立即失去保护** —— 否则会出现「顶着无敌冲上去打人」的白嫖打法

> 「死亡不掉落」只对**比赛中的玩家**生效（`CombatListener` 只在 `getMatch(player) != null`
> 时介入），所以它不会影响玩家在服务器其他地方的正常死亡。退出比赛后，位置、背包、
> 血量、经验、药水效果与游戏模式都会按进入前的快照逐项还原。

### 胜负判定

1. 任一方击杀数达到 `match.target-kills`（默认 50）→ 该方立即获胜
2. 时间达到 `match.time-limit-seconds`（默认 900 秒 = 15 分钟）→ 击杀多的一方获胜
3. 时间到且击杀数完全相同 → **平局**，直接结算，不加时

结算展示 `match.end-delay-seconds`（默认 30 秒）后：`auto-restart` 为 true 就自动开下一场；
**默认是 false** —— 到点后把所有人送出竞技场，位置、背包、血量上限、游戏模式全部还原到进场前，
相当于「这一局打完就回去继续干自己的事」，而不是把人锁在场地里等下一局。

### 击杀归属

优先采信原版的击杀者；如果它为空或指向队友（TacZ 环境下很常见），
就回退到 WBDAddon 的伤害账本，取对该受害者累计输出最高的敌人。
没装 WBDAddon 时没有账本可用，只能依赖原版归属。

### 中途加入

比赛进行中也可以加入，会立刻投入战斗并获得复活保护。
加入时按人数较少的一方自动分配阵营（`match.auto-balance` 关闭时改为随机）。

### 离场还原

加入比赛时会保存你的位置、背包、血量、经验、药水效果与游戏模式，
`/tdm leave`、掉线、比赛清场时逐项还原 —— 不会把人扣在竞技场里。

## 配置

两个文件，都在 `plugins/WBDTeamDeathmatch/` 下：

- `config.yml` —— 玩法参数（全局默认值）
- `arenas.yml` —— 场地（出生点、边界、大厅）
- `shop.yml` —— 装备商店的商品表

每个场地可以在 `arenas.yml` 里覆盖 `target-kills`、`time-limit-seconds`、
`countdown-seconds`、`end-delay-seconds`、`auto-restart`、`min-players`、`max-players`，
不写的字段沿用全局值。

### 出生装备

`loadout` 段的每一条可以是两类：

```yaml
loadout:
  common:
    - "STONE_SWORD"          # 原版物品
    - "ARROW:16"             # 数量
    - "DIAMOND_CHESTPLATE:1:50"   # 物品:数量:耐久损耗
    - "cmd:tacz give %player% p320"   # 控制台命令，%player% 替换为玩家名
```

模组枪械（TacZ 等）的物品 id 无法用 `Material` 表示，服务端也没有通用的
「按字符串造任意模组物品」接口，所以请用 `cmd:` 调模组自带的 give 命令。
命令会延后一 tick 执行，避免刚发出的枪被随后的背包同步包清掉。

## 与 WBDAddon 的集成

`integration.wbdaddon: true`（默认）时，本插件会：

1. 从 `ServicesManager` 取 `WbdAddonApi`，注册一个模式 id 为 `team-deathmatch` 的 `AddonMode`
2. 于是死斗玩家自动获得饥饿锁定、JourneyMap 队伍桥接
3. 击杀播报里的武器名走 WBDAddon 的解析（能认 TacZ 枪械）
4. 击杀归属使用 WBDAddon 的伤害账本

顶部状态行（ActionBar）默认交给 WBDAddon 的 `match-status` 模块渲染
（`integration.use-wbdaddon-hud: true`）。原因是在 Arclight 这类服务端上
不能用 Paper 的 `Player#sendActionBar`，需要走 spigot 的旧接口才生效；
这件事已经有人处理过了，就不要再各写一遍。

注册后会实地探测一次对方是否真的会代渲染（模块可能被禁用、顶栏开关可能被关掉），
探测不通过就自己发，不会出现「两边都以为对方在发，结果玩家屏幕上什么都没有」。

用 `/wbdaddon modes` 可以确认模式是否注册成功。

### ⚠️ 维护须知：软依赖的实现约束

**`WbdAddonModeBridge` 是全项目唯一允许 `import alphahu.wbdaddon.*` 的类，绝不能被其他代码直接引用**
（类型、字段、返回值、参数都不行）。

原因用一句话概括：**JVM 加载一个类时就要解析它的接口与父类，所以「实现了可选依赖的接口」等价于「硬依赖它」。**

这个坑真实发生过。最早的版本让 `AddonBridge` 直接 `implements AddonMode`，结果在没装
WBDAddon 的服务器上：

```
java.lang.NoClassDefFoundError: alphahu/wbdaddon/api/AddonMode
  at alphahu.tdm.WBDTeamDeathmatch.onEnable(WBDTeamDeathmatch.java:74)
```

插件连 `onEnable` 都跑不完，而代码里写的「取不到服务就降级」那段容错**根本执行不到** ——
备选分支放在一个加载阶段就会崩的类里，等于不存在。

现在的加载路径分三步，只有全部通过才会 `Class.forName` 加载实现类：

1. `Bukkit.getPluginManager().getPlugin("WBDAddon")` 在不在（只碰 `Plugin` 接口，不碰对方的类）
2. 用对方的 ClassLoader 探测 `alphahu.wbdaddon.api.WbdAddonApi` 是否存在（拦住 1.1.0 之前的旧版）
3. 才加载 `WbdAddonModeBridge`

主流程只认识本插件自己的 `ModeBridge` / `DamageLedger` 两个中立接口。
改动这块代码后，可以用下面这条命令自查是否又引入了类型引用：

```powershell
# 期望只列出 WbdAddonModeBridge
javap -v -p -cp target/WBDTDM-*.jar alphahu.tdm.integration.AddonBridge |
    Select-String "= Class.*wbdaddon"
```

## 构建

> ⚠️ 构建**前必须先有 `libs/WBDAddon.jar`**（本项目编译期依赖它的 `api` 包）。

```bash
# 1. 先构建 WBDAddon
cd ../WBDAddon && mvn clean package
# 2. 把产物拷成 TDM 项目的依赖
cp target/WBDAddon-1.2.1-*.jar ../WBDTeamDeathmatch/libs/WBDAddon.jar
# 3. 再构建本插件
cd ../WBDTeamDeathmatch && mvn clean package
```

产物在 `target/WBDTDM-<版本>-<git短哈希>-<时间戳>.jar`。

## 已知限制

- **同一场地只能跑一场比赛**：想开第二场请再配一个场地。多实例共享同一份出生点，
  会让复活位置互相打架，收益却很小
- **不强制队伍平衡**：玩家手动指定阵营时会被尊重，不管两边人数差多少。
  需要严格平衡的服务器请用权限约束或赛前协调
- **WarZBombDefuse 是硬依赖**：场地、商店、物品数据三种格式全部沿用它的，
  缺少时插件不会加载（`plugin.yml` 里是 `depend`）
- **段位依赖服务端自带的 SQLite 驱动**：服务端不提供时会自动降级，
  只禁用段位功能，死斗本体照常运行

## 许可证

GPL-3.0，与 WBDAddon 保持一致。

## 反馈

- 反馈 QQ：2387629002
- 欢迎提 Issue 与 Pull Request

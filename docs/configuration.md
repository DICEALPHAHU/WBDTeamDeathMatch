# 配置

## 四个文件的分工

| 文件 | 位置 | 管什么 |
|---|---|---|
| `config.yml` | 插件数据目录 | 跨场地通用的玩法默认值、HUD、经济、出生装备、战报、集成开关 |
| `arenas.yml` | 插件数据目录 | 每个场地的坐标、出生点、边界、购买区，以及可覆盖的玩法参数 |
| `season.yml` | 插件数据目录 | 段位表、计分公式、段位保护、赛季（详见 [ranked.md](ranked.md)） |
| `messages.yml` | 插件数据目录 | 全部对玩家显示的文案 |

四个文件**都只在缺失时生成**，升级插件不会覆盖你改过的内容。需要新增配置项时，请对照 `src/main/resources/` 下的同名文件手工补齐——jar 内的版本永远是权威的最新结构。

**改完用 `/tdm reload` 重载**（`config.yml` 与 `season.yml` 的数值类改动立即生效；`arenas.yml` 的改动会重建所有比赛，先把人送走）。

---

## config.yml

### `match` —— 比赛规则

| 键 | 默认 | 说明 |
|---|---|---|
| `target-kills` | — | 先达到该击杀数的队伍获胜；`0` 表示不设目标 |
| `time-limit-seconds` | — | 时间上限；`0` 表示不限时 |
| `countdown-seconds` | — | 开局倒计时 |
| `end-delay-seconds` | `30` | 结算展示停留多久；走完再决定重开还是清场 |
| `auto-restart` | `false` | 是否自动开下一场。**默认关闭**：到点后把所有人送出竞技场并还原进场前的状态 |
| `min-players` | — | 最少开局人数 |
| `auto-balance` | — | 是否自动平衡两队人数 |

以上都可在 `arenas.yml` 里按场地覆盖。

### `respawn` —— 复活

| 键 | 说明 |
|---|---|
| `delay-seconds` | 阵亡后等待几秒复活。**设 0 也能正确工作**（有一处竞态专门处理过） |
| `protection-seconds` | 复活后的无敌时间 |
| `break-protection-on-attack` | 自己在保护期内攻击别人时，是否立即失去保护 |
| `drop-items` | 阵亡是否掉落物品 |
| `spawn-mode` | `random` 或 `first`，从该阵营的出生点列表里怎么选 |

### `combat` —— 战斗

| 键 | 说明 |
|---|---|
| `friendly-fire` | 是否允许友伤 |
| `override-death-message` | 是否覆盖原版死亡消息 |
| `kill-message` | 击杀提示模板 |
| `killstreak-broadcast` | 达到几连杀时全服播报；`0` 关闭 |
| `prevent-item-drop` | 是否禁止比赛中丢弃物品 |

> `prevent-item-drop` 是一刀切的，不只拦商店发出的物品。死斗里装备随时能从商店换，丢东西本就没有实际意义；只拦商店物品的话，"买一个丢地上、再买一个、捡回来"这条刷装备的路子还得靠逐个识别才能堵。

### `hud` —— 显示

| 键 | 说明 |
|---|---|
| `kill-message-format` / `kill-message-duration-ms` | ActionBar 击杀播报的模板与时长（末尾会淡出） |
| `respawn-bar-*` | 复活倒计时 BossBar 的标题与颜色 |
| `protection-bar-*` | 无敌倒计时 BossBar |
| `match-bar-*` | 比分与时间 BossBar |
| `scoreboard-enabled` / `scoreboard-title` / `scoreboard-lines` | 侧边栏 |

侧边栏每人一块**独立**计分板，因此可以放个人数据。占位符：

```
%target%  %money%  %tier%  %kills%  %deaths%  %kd%
%respawn%  %alive%  %size%  %arena%
```

`%t_score%` `%ct_score%` `%time%` 这三项 BossBar 上已经有了，别在侧边栏重复。

> **代价与补偿**：原版队伍数据是跟着玩家"当前所用的那块计分板"下发的。玩家一切到自己的板子，就收不到主计分板上的队伍了，JourneyMap 的队友可见 / 敌军隐藏会静默失效。插件会把那些队伍**镜像**过来（每 2 秒一次，且只在成员真的变了时才发包），所以两者可以并存。

### `economy` —— 经济

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉就回到全免费，`price` 字段重新被忽略 |
| `start-money` | `800` | 每场开局的启动资金，会被 `max-money` 夹住 |
| `kill-reward` | `300` | 每次击杀的基础赏金 |
| `killstreak-bonus` | `100` | 第 N 连杀额外多给 `(N-1) × 本值` |
| `killstreak-bonus-max` | `500` | 连杀加成的封顶 |
| `max-money` | `16000` | **持有上限**。击杀赏金、连杀加成、启动资金全都受它约束 |

到顶之后击杀播报显示的是真实入账（可能是 `+0`），而不是照喊 `+300`——那样玩家会以为钱被吞了。

### `loadout` —— 出生装备

`clear-inventory` 只在**进入比赛**时生效，阵亡复活不清背包。

`common` / `t` / `ct` 里每条可以是三类东西：

```yaml
loadout:
  common:
    # 1. 序列化物品（推荐）——模组枪械、投掷物唯一能原样发放的方式
    - "3@bukkit-object:rO0AB..."
    # 2. 原版物品
    - "STONE_SWORD"
    - "ARROW:16"
    # 3. 控制台命令
    - "cmd:tacz give %player% p320"
```

前两类可加 `槽位@` 前缀指定落点（从 0 数起）；不写则自动找第一个空位。

`default-items` 是另一条路：直接写**商店商品的 id**，开局自动发放且不扣钱。它复用购买那套落点与覆盖逻辑，所以默认给的枪落在哪一格，和玩家自己买它时完全一致。两侧可以并存。

> 用 `cmd:` 发枪不建议：模组自己的 give 命令生成出来的物品未必和你要的那一件一致，而且每次复活都会重跑一遍。想要"发出去的就是这一件"，就用 `bukkit-object:`。

### `reports` —— 战报

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否生成战报文件 |
| `folder` | `reports` | 保存目录，相对插件数据目录 |
| `keep` | `500` | 最多保留多少份，超出后删最旧的；`0` 表示不限制 |
| `include-ranked` | `true` | 是否包含段位变化 |
| `include-players` | `true` | 关掉就只留一行比分摘要 |

> `keep` 建议保留上限：一局一份文件，一个活跃服务器每天能产生几十份。

### `shop` —— 商店

详见 [shop.md](shop.md)。这里列出与位置相关的两项：

| 键 | 默认 | 说明 |
|---|---|---|
| `source` | `wbd` | `wbd` = 直接读 WarZBombDefuse 的商店；`local` = 读本插件数据目录里的副本 |
| `buy-zone-required` | `false` | 场地**没配**购买区时是否也禁止购买 |
| `tag-granted-items` | `true` | 是否给商店发出的物品打分组标记（用于"买新的自动换掉旧的"） |
| `fallback-slot` / `slot-overrides` / `material-slots` | — | 落点规则 |

### `integration` —— 与 WBDAddon 的集成

| 键 | 默认 | 说明 |
|---|---|---|
| `wbdaddon` | `true` | 总开关 |
| `use-wbdaddon-hud` | `true` | 顶部状态行是否交给 WBDAddon 渲染 |
| `warn-arena-conflict` | `true` | 启动时检查 WBD 侧是否启用了同一个场地 |
| `auto-disable-in-wbd` | `true` | `/tdm enable` 时自动在 WBD 侧禁用同名场地 |
| `wbd-arenas-file` | `plugins/WarZBombDefuse/arenas.yml` | WBD 的场地文件路径 |

### `teammate` —— 队友名牌

```yaml
teammate:
  name-tag: true                          # 前缀 + 隐藏敌人名牌，共用一个开关
  name-tag-prefix: "&a[队友] &f"
```

**这两件事共用一个开关是有意的**：原版队伍的前缀是"所有人的客户端都收得到"的，只加前缀而不把名牌设成"仅自己方可见"，敌人头上也会顶着一个 `[队友]`——比不做还糟。拆成两个开关就会出现这个中间状态。

需要 WBDAddon **1.2.1+**。低于此版本该功能静默失效，插件会在控制台打印警告。

#### 队伍皮肤（`teammate.skin`）

```yaml
teammate:
  skin:
    enabled: false
    t-skin: ""                              # T 方皮肤名，留空则该方不换
    ct-skin: ""                             # CT 方皮肤名
    apply-command: "skin set %skin% %player%"
    restore-command: "skin undo %player%"
```

这是**真皮肤**，不是皮革甲：玩家皮肤由客户端从 profile 纹理里取，服务端自己改不了，必须依赖 SkinsRestorer 这类插件。命令模板默认对应 SkinsRestorer 15.x 的 `/skin set <皮肤名> <玩家>` —— 注意**皮肤名在前、玩家在后**。

做成命令模板而不是直接调 API，是因为这类插件的 API 在不同版本间改名频繁，而命令语法相对稳定；模板化之后 SkinsRestorer、SkinShuffle 甚至自写的小插件都能接。占位符 `%player%`、`%skin%`。

还原默认用 `skin undo`，即退回上一张皮肤，**不会动玩家自己设过的皮肤**。如果确定全员都不靠自己设皮肤，也可以改成 `skin clear %player%`，但那样会连带抹掉玩家原本的皮肤。

插件会在玩家身上写一个 PDC 标记，用于崩溃后补救：服务端在比赛中崩溃的话，玩家下次进服会被自动还原一次。关服时也会统一还原。

| 项 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 总开关。关闭时不发任何命令 |
| `t-skin` / `ct-skin` | 空 | 填 SkinsRestorer 认识的名字：正版 ID，或 `/sr createcustom` 建的自定义皮肤名 |
| `apply-command` | `skin set %skin% %player%` | 进场时以控制台身份执行 |
| `restore-command` | `skin undo %player%` | 离场、退出、关服时执行 |

换皮肤会让客户端重新加载该玩家的模型，进场那一下有极短重绘，属正常现象。

#### 队伍队服（`teammate.uniform`）

```yaml
teammate:
  uniform:
    enabled: true            # 总开关
    follow-wbdaddon: true    # WBDAddon 那边开着就让位给它
    slot: HELMET             # HELMET / CHESTPLATE / LEGGINGS / BOOTS
    t-color: "#C0392B"
    ct-color: "#2E86DE"
```

纯原版物品，**不依赖任何插件**：给参赛玩家戴一件队伍配色的皮革装备，一眼分辨敌我。与上面的 `skin` 互不影响，可以只开一个，也可以都开。

**本插件和 WBDAddon 都能干这件事，只能有一个人干。** WBDAddon 1.2.1 起自带「队伍护甲」模块（`team-armor`），两边同时强制同一个盔甲槽会互相覆盖 —— 曾经就是因此让玩家背包里无限多出皮革头盔。所以这里做了自动仲裁：

| 情况 | 谁负责 |
|---|---|
| WBDAddon 没装 | **本插件** |
| WBDAddon 装了，`config.yml` 里 `modules.team-armor.enabled: false` | **本插件** |
| WBDAddon 装了，配置里没有这个键（旧版本没有该模块） | **本插件** |
| WBDAddon 装了，且 `modules.team-armor.enabled: true` | **WBDAddon**，本插件完全不碰盔甲槽 |

判定读的是 `plugins/WBDAddon/config.yml`，因为 WBDAddon 的对外 API 只暴露模式注册与玩家状态，不暴露「某个模块开没开」；读对方 data 目录是本插件已有的做法（读 WarZBombDefuse 的 `arenas.yml` 用的就是同一招）。读取失败一律按「没开」处理 —— 最坏情况是两套都不套，而不是互相覆盖。

结论会打印在启动日志和 `/tdm reload` 的最后一行：

```
队伍队服：检测到 WBDAddon 已开启队伍护甲（modules.team-armor.enabled），本插件让位，不再套自己的队服。
队伍队服：由本插件负责（槽位 HELMET，T #C0392B / CT #2E86DE）。
```

`follow-wbdaddon: false` 可以关掉这套仲裁（不管 WBDAddon 开没开都套自己的），但只有在确定 WBDAddon 的 `team-armor` 已关闭时才该这么设。

**队服不带护甲值。** 原版没法直接改物品的护甲值，但 `generic.armor` 是个普通属性，皮革装备自带的护甲同样是一条属性修饰 —— 所以给它挂一条等值的**负数**修饰，穿上以后净加成正好为 0。同时用 `HIDE_ATTRIBUTES` 遮住 tooltip，否则玩家会看到一行「护甲 -1」。

代价值得说清楚：它**占住一个盔甲槽**，那个槽位就不能再穿更好的甲。所以默认用 `HELMET`（四个槽位里护甲值最低的一个）。四个槽位的皮革护甲值分别是 1 / 3 / 2 / 1，都会按上面的方式抵成 0。

**只填空槽位，绝不抢占。** 槽位里已经有任何物品（玩家自己买的头盔、或别的插件发的队服）时，本项一律让位 —— 这是第二道保险：即使仲裁判断失误，也不会再出现互相覆盖导致的复制。点击盔甲槽取下队服仍会被拦截；散落在背包里的队服副本会被清掉，避免「取下来 → 补发」变成复制。

| 项 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `follow-wbdaddon` | `true` | WBDAddon 开着队伍护甲时是否让位 |
| `slot` | `HELMET` | 队服占用哪个盔甲槽 |
| `t-color` / `ct-color` | `#C0392B` / `#2E86DE` | 支持 `#RRGGBB` 或染料名（`RED` / `BLUE` …） |

颜色写错（既不是合法 hex 也不是染料名）时按阵营默认色兜底，不会报错中断。

---

## arenas.yml

每个场地一个段落，坐标格式与 WarZBombDefuse **完全一致**：

```yaml
arenas:
  tdm_demo:
    enabled: false
    name: "死斗示例场地"                # 不写则回退成 id；导入的场地会自动从 WBD 读
    min-players: 2                      # 以下均为可选覆盖项
    max-players: 20
    lobby: "world,0.5,80.0,0.5,0,0"
    boundary:
      world: "world"
      min: "-80,0,-80"
      max: "80,255,80"
    t-spawns:
      - "world,-10.5,80.0,0.5,90,0"
    ct-spawns:
      - "world,10.5,80.0,0.5,-90,0"
    buy-zones:
      t:
        - "world,-10.5,80.0,0.5,90,0,8.0"    # 最后一位是球体半径
      ct:
        - "world,10.5,80.0,0.5,-90,0,8.0"
    unrank: false                        # true = 该场地不结算段位
    imported-from: dust2                 # 由 /tdm import 自动写入，指向 WBD 侧 id
    fireworks: []
```

### 坐标与边界格式

| 用途 | 格式 |
|---|---|
| 点（出生点、大厅） | `world,x,y,z,yaw,pitch` |
| 购买区 | `world,x,y,z,yaw,pitch,radius` |
| 边界 | `world` + `min: "x,y,z"` + `max: "x,y,z"` |

购买区是**球体**，`yaw`/`pitch` 对球形区域没有意义，保留只是为了让你能从 WBD 那份配置里直接抄一行过来。

### buy-zones

玩家必须站在**自己阵营**的购买区内才能打开商店。不配置的阵营不受位置限制（与 WBD 的"留空表示任意队任意位置都可购买"一致）；想让"没配也不许买"生效，把 `shop.buy-zone-required` 打开。

> ⚠️ 建议把购买区放在各自出生点附近，半径 6~10。太小会卡住复活后想换装备的人；太大就等于回到"哪里都能买"。坐标写错（球心落在地底或半空）的表现是**商店突然完全打不开**。

### unrank

`unrank: true` 的场地不结算段位，训练场、娱乐场、活动场用它。判定发生在比赛结束时，所以比赛打到一半改它，影响的也是这一局的结算。

### imported-from

由 `/tdm import` 自动写入，记录"这个场地是从 WBD 的哪个 id 导入的"。导入时允许改 id（`/tdm import dust2 tdm_dust2`），所以不能靠死斗这边的 id 去猜。

它有两个用途：一是 `/tdm enable` 时去 WBD 侧禁用**真正该禁的那个**场地；二是运行时从 WBD 读场地显示名。

---

## messages.yml

全部对玩家显示的文案。支持 `&` 颜色代码，改完 `/tdm reload` 重载。

两条刻意的设计：

- **不认识的变量原样保留**。写错变量名是配文案时最常见的错误，而"静默变成空字符串"会让你对着一条缺了名字的公告反复检查逻辑。留下 `%foo%` 一眼就知道是变量名写错了。
- **只用 jar 内的默认值补全，不写回文件**。升级后新增的消息立刻生效，但不会重写你那份文件——重写会把你写的注释和排版全冲掉。

分段：`broadcast`（全服公告）、`ranked`（段位结算）、`commands`（命令输出）、`shop`、`report-menu`、`report`。

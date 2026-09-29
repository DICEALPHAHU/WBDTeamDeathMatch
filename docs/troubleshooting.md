# 排查

症状 → 原因。表里的每一条都是实际遇到过并已修复或已确认的问题。

---

## 启动

### 插件完全不加载，日志里说缺少依赖

```
Unknown/missing dependency: WarZBombDefuse
```

WarZBombDefuse 是本插件的**硬依赖**（`plugin.yml` 里的 `depend`），没有它服务端根本不会加载本插件。这是刻意的——本插件没有一样数据格式是自己定义的（场地、商店、物品数据全用它的），硬撑着启动只会留下一个"命令存在但什么都不会发生"的插件。

把 WarZBombDefuse 放进 `plugins/` 再重启。

### 日志里出现方框警告「缺少硬依赖：WarZBombDefuse」

说明 WBD 装了但**加载失败或被别的插件连带禁用**。这种情况 `depend` 拦不住（Bukkit 只在加载阶段检查），所以 `onEnable` 里还有第二道闸会主动拒绝启动。

先解决 WBD 自身的问题。

### 日志说「WBDAddon 的 API 版本低于 1.2」

不影响启动，队友名牌功能会失效。升级 WBDAddon 到 1.2.0+。

### 执行 `/tdm` 只显示 `/tdm help`，什么也没发生

这是 **`onEnable` 中途抛异常**的典型表现。Bukkit 会先按 `plugin.yml` 注册命令、然后才调 `onEnable`，所以插件崩溃后命令仍在命令表里，只是 executor 永远没挂上。

往上翻日志找 `WBDTeamDeathmatch 启用失败` 那段异常栈。

---

## 场地

### 导入后地图名显示成 `tdm_xxx`

WarZBombDefuse 那边这个场地没有配 `name`，所以回退成了 id。

去 WBD 的 `arenas.yml` 给它加一行 `name: "运输船"`（或你想要的名字），然后 `/tdm reload`——**不用重新导入**。插件每次加载场地时都会去 WBD 读名字。

> 只在"死斗这边的名字还等于自己的 id"时才补。你自己取过的名字不会被覆盖。

### `/tdm enable` 之后两个模式还在抢同一个场子

`enable` 会自动在 WBD 侧禁用同名场地（走 `/wbd disable <id>` 控制台命令）。如果没生效，检查：

- `integration.auto-disable-in-wbd` 是否为 `true`
- 日志里 WBD 侧禁用那一步有没有报错
- 场地是用 `/tdm import` 导入的吗？`imported-from` 字段记录着 WBD 侧的真实 id，手工写配置时漏了这个字段就找不到该禁哪个

### 场地启用了但模式不启动

日志会说原因。常见两种：

- 没有一个场地设成 `enabled: true`
- 场地缺少 `lobby` 或 `boundary`（会有针对性警告）

---

## 商店与装备

### 商店里全是武器 / 我不想让它卖这些

插件**自带**的 `shop.yml` 是空的（`items` 为空），刻意不预设任何武器。你看到商品说明 `shop.source` 指向了 WarZBombDefuse 那份（默认行为）。

想用空商店：`shop.source: local` + `shop.local-file: "shop.yml"`。  
想用自己调好的那份：把它放进插件数据目录并让 `local-file` 指向它。  
想两边共用：保持 `source: wbd`，用 `slot-overrides` / `material-slots` 调落点，**不修改** WBD 那份。

### 所有装备都挤在第一格 / 刀跑到第一格

读到的是 WBD 那份**原版**配置，它的 `give-slot` 大量是 `-1`（那边买什么都往空位塞），于是全部落到 `fallback-slot`。

启动日志里有一条会直接告诉你：

```
有 46/50 件商品没写 give-slot，它们会全部挤进 fallback-slot（当前 0）...
```

解法：用 `shop.slot-overrides` / `shop.material-slots`，或把配置复制到本地再改。参考 [../examples/shop-tuned.yml](../examples/shop-tuned.yml)。

### 发出来的枪不是那把枪 / 手里是个空壳

商品缺少 `give-data`，服务端只能按材质造一个同材质的空壳。

启动日志的**发放数据审计**会点名：

```
发放数据审计：48 件用完整物品数据（give-data，原样还原），2 件只有材质简写，3 件纯命令商品。
[警告] 有 2 件商品只有材质简写、没有 give-data...
```

用 WarZBombDefuse 的商店编辑器（`/wbd shopedit`）或本插件的 `/tdm shopedit` 保存一次，就会自动写出 `give-data`。也可以 `/tdm exportitem` 手持导出再手工贴。

### 商店突然打不开了

`buy-zones` 的球心坐标不对——落在地底或半空，玩家永远进不去。

它会返回"你不在购买区内"，玩家会以为是插件坏了。检查 `arenas.yml` 的坐标，半径建议 6~10。

想先确认是这个原因：把 `shop.buy-zone-required` 设成 `false` **并删掉该阵营的 `buy-zones`**，如果能开了就是坐标问题。

### 站在购买区里开了菜单，走出去还能买

不应该发生——点击购买时会再检查一次位置，不在区内会关闭菜单并提示。

如果确实能买，检查 `ShopListener.onClick` 里那次检查是否还在。

### 买了好几把枪，背包里越攒越多

「买新的自动换掉旧的」按 PDC 标记识别旧物品。检查 `shop.tag-granted-items` 是否为 `true`（关掉之后只能覆盖目标槽位，玩家把旧的挪走再买就会留下）。

### 绿宝石越死越多

复活时又发了一次商店入口。正常情况下复活逻辑会**先清掉背包里所有商店入口再放新的**。

---

## 段位

### 所有人的 RP 都偏低 / 涨不上去

最常见的原因是**结算晚于 `resetForNewMatch()`**——那会变成全员按 0 击杀结算，而且**不报错**。

在 `TdmMatch.endMatch()` 里，`settle()` / `ReportService.save()` / `recordMatch()` 三步必须在 `restart()` 之前完成。

### `/tdm rank` 说"段位系统当前不可用"

三种可能：

- `season.yml` 的段位表配置有问题（日志会说明具体哪一项，例如区间重叠）
- SQLite 驱动缺失（服务端 `libraries/` 里没有 `sqlite-jdbc`）
- `ranked.db` 所在目录没有写权限

日志里会有明确的一行。

### 段位显示"未定级"但玩家明明打过

档案是**异步加载**的，服务器刚启动那几秒会显示"未定级"。如果一直如此，检查 `ranked.db` 是否真的写进去了。

### 中途离开没有扣分

- 只有 `LIVE` 阶段离开才算"中途"，等待阶段与结算阶段离开不受罚
- **菜鸟与青铜的入场费是 0**，所以这两个段位退出没有分数惩罚（扣"双倍入场费"这条规则本身的结果）

---

## 战报

### `reports/` 里没有文件

- `reports.enabled` 是否为 `true`
- 比赛是**正常结束**的吗？`clearAll()`（人走光、强制停赛）不生成战报
- 目录没有写权限时日志会有 `无法创建战报目录` 或 `写入战报失败`

### 战报里所有人都是 0 击杀

内容构建晚于 `resetForNewMatch()`。内容必须在**主线程**、在清零之前构建成字符串，再把写文件丢到异步。

### `/tdm matchrp` 说"还没有比赛记录"，但明明打过

- 战报写库失败（日志里有 `写入比赛记录失败`）
- 段位系统不可用——`recordMatch()` 在 `available == false` 时会直接返回，**战报文件仍然会写**，但库里没有记录，所以查不到

### 战报界面一片空白 / 点了没反应

`/tdm matchrp` 的查询是异步的（异步查库 → 回主线程开界面）。如果查库报错，日志里会有 `查询比赛历史失败`。

---

## 显示

### 敌人头上还显示名字

WBDAddon 版本低于 1.2.0，队友名牌功能**静默失效**。控制台会有一条 API 版本警告。

### 队友头上也没有 `[队友]`

- `teammate.name-tag` 是否为 `true`
- WBDAddon 是否装了且启用
- 前缀上色后不能超过 16 字符（原版队伍 prefix 的硬上限），超了会被截断

### 计分板在闪

队伍同步机制在每次刷新时无条件 `removeEntry` + `addEntry`，每秒数十个队伍包抖动客户端。正常情况下只在**成员真的变了**时才发包。检查 `MatchHud.mirrorTeams` 与 `TeamSynchronizer.syncPlayer` 里的"归属没变就返回"那一段是否还在。

### JourneyMap 上看不到队友

原版队伍数据是跟着玩家**当前所用的那块计分板**下发的。本插件给每人一块独立计分板（为了能显示个人数据），所以必须把主计分板上的队伍**镜像**过来。检查 `MatchHud.mirrorTeams` 是否还在跑。

---

## 其他

### 玩家离开后背包没还原

`leave(player, restoreState)` 的第二个参数为 `false` 时跳过还原（关服时为了省掉无谓的传送）。玩家主动 `/tdm leave` 走的是 `true`。

### 复活后卡在死亡界面

`respawn.delay-seconds: 0` 时复活可能抢在切旁观之前执行，那时 `setGameMode` 和传送全部无效，随后到来的 `respawn()` 会把玩家丢到主城出生点。

`TdmPlayer.spectatorPending` 标志专门处理这个竞态：复活逻辑会一直等到旁观切换完成再动手。如果复现，检查那个标志的设置与清除是否对称。

---

## 上报问题时请附上

1. **启动日志全文**（从 `WBDTeamDeathmatch` 那几行开始）——里面有依赖版本、商店审计、落点分组、场地名读取结果
2. **相关配置**：`config.yml` 的对应段、`arenas.yml` 的那一个场地、`season.yml`
3. **复现步骤**：做了什么、期望什么、实际什么
4. 涉及装备发放时，附上 `/tdm rank` 的输出或战报文件

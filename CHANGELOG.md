# 变更记录

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)。产物文件名格式：

```
WBDTDM-<版本>-<内部构建号>-<git短哈希>-<时间戳>.jar
例：WBDTDM-1.2.2-1-027236f-20261028-1420.jar
```

内部构建号每次构建自增，记录在 `buildNumber.properties`（不入库）。

## [1.2.2] — 未发布

首个完整版本。以下按模块列出能力，括注的是实现时踩过并已修复的坑。

### 装备分类与落点

- 新增 `item-categories.yml`：按**商品 id / TacZ GunId / 材质 / 显示名**归类，类别 `primary` / `secondary` / `melee` / `throwable` / `attachment` / `utility`
- 落点、限购（`limit`）、同类替换（`on-buy`）都按类别走，`give-slot` 只在没有分类时兜底
- **预留格**：被分类占用的快捷栏格子不许放别的东西，杂物会被挪走（没空位再进背包）
- 配件默认 `to-backpack: true`，买到直接进背包 9-35，不占快捷栏
- 归位任务每秒一次，只作用于比赛中的玩家
- 装了 WBDAddon 时自动继承它的 `guns` / `names` 表，**本地配置优先**
- 装了 TacZ 桥接时按 GunId 分类；表里没有的枪**不接管**，保持原落点

### 武器显示名

- 新增 `weapon-names.yml`：GunId → 显示名，击杀播报与 HUD 使用
- 优先级**本插件优先**：本地表 → WBDAddon 的表（补缺）→ 物品显示名 → GunId
- 装了 TacZ 桥接时模板会列出全部 GunId，照着填即可

### 命令

- 新增 `/tdm shopids`：列出商品 id、推断类别与 GunId，方便填 `item-categories.yml`

### 构建

- 新增 `build.sh`：不依赖 Maven 的 javac 构建，产物名带内部构建号
- 产物命名统一为 `WBDTDM-<版本>-<内部构建号>-<git短哈希>-<时间戳>.jar`

### 比赛与玩法

- 团队死斗比赛循环：等待 → 倒计时 → 对战 → 结算 → 自动重开
- 无限复活：阵亡后延时复活并给予短暂无敌，可配置攻击时是否立即破保护
- 复活**保留背包**（与爆破每回合重发装备相反），复活只补商店入口并复位状态
- 友伤开关、击杀播报、连杀播报、死亡消息覆盖
- 中途加入：立刻投入战斗并给保护，避免一进场就被秒
- 离场快照还原：位置、背包、最大血量、游戏模式全部还原，不把比赛状态带回大厅
- 竞技场边界限制与越界拉回

### 武器显示名

- 新增 `weapon-names.yml`：GunId → 显示名，击杀播报与 HUD 使用
- 优先级**本插件优先**：本地表 → WBDAddon 的表（补缺）→ 物品显示名 → GunId
- 装了 TacZ 桥接时模板会列出全部 GunId，照着填即可

### 装备分类与落点

- 新增 `item-categories.yml`：按**商品 id / 材质 / 显示名 / 模组类型**归类，类别
  `primary` / `secondary` / `melee` / `throwable` / `attachment` / `utility`
- 落点、限购、同类替换（`on-buy: replace`）都按类别走，`give-slot` 只在没有分类时兜底
- **预留格**：被分类占用的快捷栏格子不许放别的东西，杂物会被挪走（没空位再进背包）
- 配件默认 `to-backpack: true`，买到直接进背包 9-35，不占快捷栏
- 归位任务每秒一次，只作用于比赛中的玩家
- 格式与 WBDAddon 的 `item-categories.yml` 对齐，两边可以共用同一套分类思路

### 场地与配置

- 场地格式与 WarZBombDefuse **完全一致**（`world,x,y,z,yaw,pitch`），可直接从 WBD 导入
- `/tdm import <wbdId> [新id]`：导入坐标、出生点、边界
- **自动从 WBD 读取场地显示名**：WBD 里配了 `name` 就用它（补 `（死斗）` 后缀区分两个插件），没配则回退 id 并在导入时明确提示。运行时会持续补全，服主之后去 WBD 补名字，`/tdm reload` 即可生效，不必重新导入
- `/tdm delete <场地id>`：只删死斗自己的 `arenas.yml`，不动 WBD 那边
- 场地级覆盖：`target-kills`、`time-limit-seconds`、`countdown-seconds` 等
- 每个场地可独立标记 `unrank: true` 不结算段位
- `/tdm enable` 会自动在 WBD 侧禁用同名场地，避免两个模式抢同一个场子

### 经济与商店

- 击杀与连杀赚取金钱，连杀加成封顶；金钱每场重置、有持有上限
- 金钱写入收敛到唯一入口 `addMoney`，返回值是**实际到账**而非名义值——到达上限时击杀播报显示真实的 `+0` 而不是照喊 `+300`
- 商店格式兼容 WarZBombDefuse，可 `source: wbd` 直接共用或 `local` 用本地副本
- **购买区限制**：必须站在本阵营购买区内才能开商店，格式与 WBD 的 `buy-zones` 一致（`world,x,y,z,yaw,pitch,radius` 球体）。打开菜单、命令入口、点击购买**三处都检查**——只查打开时机的话，玩家可以开完菜单再走出去继续买
- 落点规则：`slot-overrides` → 商品 `give-slot` → `material-slots` → `fallback-slot`
- 「买新的自动换掉旧的」按**解析出的落点槽位**分组（早期版本按材质分组，导致共用材质的枪和刀互相顶掉）
- `/tdm shopedit` 可视化编辑商店；`/tdm shop adopt` 采用 WBD 配置
- `/tdm exportitem` 把手持物品导出成可贴进配置的物品数据
- `loadout` 支持直接写 `bukkit-object:` 物品数据，以及 `槽位@` 前缀指定落点

### 排位段位

- 8 段 × 4 小段（菜鸟 / 青铜 / 白银 / 黄金 / 铂金 / 钻石 / 大师 / 猎杀）
- Apex 式计分：`名次分 + 比分差修正 + 击杀分 + 队内排名分 − 入场费`
- 击杀分**封顶 10 杀**——死斗里击杀就是胜负的成因，与名次分共线，不封顶等于把同一件事算两遍
- 中途加入者只结算击杀分，不扣入场费、不给名次分
- **中途离开处罚**：本局不结算排位分，并**双倍扣除入场费**；掉线同样受罚（否则拔网线成了逃避败场的标准操作），清场不受罚
- 段位保护：菜鸟段 RP 不为负；升段后 3 场内有保护
- 赛季软重置：`新 RP = 基准 + (旧 RP − 基准) / 2`，支持定时自动与 `/tdm season reset` 手动
- SQLite 存储（`ranked.db`，WAL 模式），驱动用服务端自带的、不打包
- `/tdm rank [玩家]` 查询，**离线玩家也能查**

### 战报

- 每局结束落盘一份 YAML 战报到 `reports/`，含比分、胜负、每位玩家的击杀/死亡/连杀/收入与段位变化明细
- 战报同时写库（`tdm_matches` + `tdm_results`），供 `/tdm matchrp` 按玩家查询——扫目录解析 YAML 对一条随手敲的命令来说太慢
- `/tdm matchrp <玩家>` 打开 54 格箱子界面，一格一场，点击后关闭界面并在聊天栏展开该场战报
- 输出按阵营分块，**先展示被查询玩家所在的那一方**，块内按击杀降序
- 超过 `keep` 上限时按文件修改时间清理最旧的，且**只删自己生成的文件名格式**，不误伤服主自己放进去的文件

### 显示

- BossBar：时间与双方比分
- ActionBar：击杀播报（含武器名、金钱），到期后淡出
- 侧边栏：每人一块**独立**计分板，可放 `%money%` `%tier%` `%kills%` `%deaths%` `%kd%` 等个人数据；队伍镜像到每块板以保留 JourneyMap 兼容
- **队友名牌**：队友头顶带 `[队友]` 前缀，**敌人名牌完全隐藏**（原版队伍 `NAME_TAG_VISIBILITY`，不需要发包插件）

### 集成

- WarZBombDefuse 声明为**硬依赖**（`depend`）：缺少时服务端直接不加载本插件，而不是留一个"命令存在但什么都不会发生"的空壳
- WBDAddon 软依赖：`AddonBridge` 三步探测（插件在不在 → 有没有 api 包 → 才 `Class.forName` 实现类），所有 `alphahu.wbdaddon.*` 类型引用隔离在 `WbdAddonModeBridge` 一个类里
- 启动时检查 WBDAddon 的 API 版本，队友名牌功能需要 `1.2`，低于此版本**静默失效**，因此主动打印警告
- 全服加入/离开公告，含段位、是否排位、阵营与人数

### 修复记录（实现过程中踩到并已解决的）

| 问题 | 根因 |
|---|---|
| 计分板闪烁 | 队伍同步每轮无条件 `removeEntry` + `addEntry`，每秒数十个队伍包抖动客户端 |
| 刀和手枪互相顶掉 | 「购买覆盖」按商品图标材质分组，而枪和刀共用 `TACZ_MODERN_KINETIC_GUN` |
| 绿宝石越死越多 | 死亡保留背包的前提下，复活时又发一次商店入口 |
| 商店点一下就走 | `close-after-click` 默认值理解反了 |
| 发出来的枪不对 | 商品缺 `give-data`，退回按材质重建 |
| 复活卡在死亡界面 | `delay-seconds: 0` 时复活抢在切旁观之前，传送与 `setGameMode` 全部失效 |
| 中途离开没有惩罚 | `leave()` 会在结算前把人移出名单，结算时已找不到他 |
| 战报全员 0 击杀 | 结算晚于 `resetForNewMatch()` |
| 战报写成功但查不到 | `Participant` 漏了 uuid 字段，按 uuid 反查永远匹配不上 |
| 编译期无法引用 `OptionStatus.HIDE_FOR_OTHER_TEAMS` | 实际枚举名是 `FOR_OWN_TEAM`；`FOR_OTHER_TEAMS` 语义正好相反 |

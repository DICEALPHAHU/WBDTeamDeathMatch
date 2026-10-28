# WBDTeamDeathmatch

> 团队死斗模式，跑在 WarZBombDefuse 生态里：无限复活、限时击杀赛，带排位段位、战后战报、商店与装备分类。

一个 Bukkit/Spigot/Paper 插件（Arclight 实测）。它**复用 WarZBombDefuse 的场地坐标、商店格式与物品数据格式** —— 你在爆破模式里量好的场地、配好的枪都能直接搬过来；同时通过 WBDAddon 的 `WbdAddonApi` 借用饥饿锁定、JourneyMap 队伍桥接这些附加能力，不必重写一遍。

---

## 目录

- [功能一览](#功能一览)
- [快速开始](#快速开始)
- [命令速查](#命令速查)
- [配置文件](#配置文件)
- [装备分类与落点](#装备分类与落点)
- [武器显示名](#武器显示名)
- [依赖与兼容](#依赖与兼容)
- [构建](#构建)
- [常见坑](#常见坑)
- [文档索引](#文档索引)
- [许可](#许可)

---

## 功能一览

| 能力 | 说明 |
|---|---|
| **比赛循环** | 等待 → 倒计时 → 对战 → 结算 → 自动重开；达到击杀目标或时间耗尽即结束 |
| **无限复活** | 阵亡后延时复活并给短暂无敌；**复活保留背包**，不像爆破那样每回合重发装备 |
| **经济与商店** | 击杀与连杀赚钱；商店格式与 WBD 完全一致；金钱每场重置、有持有上限 |
| **购买区** | 必须站在本阵营购买区内才能开商店，格式同 WBD 的 `buy-zones` |
| **装备分类** | 主/副/近战/投掷物/配件/功能道具各自固定格子，杂物不许抢占；配件直接进背包 |
| **武器显示名** | 击杀播报显示中文枪名而不是 `twx:twx_kcb`；本插件配置优先，WBDAddon 的表兜底 |
| **排位段位** | 8 段 × 4 小段，Apex 式计分（名次 + 比分差 + 击杀 + 队内排名 − 入场费），含段位保护与赛季软重置 |
| **战后战报** | 每局落盘 YAML，同时写库；`/tdm matchrp` 打开箱子界面查历史 |
| **队友名牌** | 队友头顶 `[队友]` 前缀，敌人名牌隐藏（原版队伍机制，不依赖发包插件） |
| **HUD** | BossBar 显示时间与比分，ActionBar 显示击杀播报，每人独立侧边栏计分板（含段位与金钱） |
| **WBDAddon 集成** | 软依赖：注册本模式后自动获得饥饿锁定、JourneyMap 桥接、武器名解析 |

---

## 快速开始

```text
1. 把 WarZBombDefuse、WBDAddon、本插件一起放进 plugins/，重启服务器
2. /tdm import <wbd场地id>          # 从爆破模式导入已量好的坐标
3. /tdm enable <场地id>             # 启用（会自动在 WBD 侧禁用同名场地）
4. /tdm join <场地id>               # 加入比赛
```

**一条必须遵守的约定**：被死斗使用的场地，在 WarZBombDefuse 侧必须保持 `enabled: false`。两个模式同时启用同一场地时，WBD 的回合循环会和死斗的无限复活直接打架。`/tdm enable` 会自动处理，启动时也会检查并告警。

---

## 命令速查

| 命令 | 权限 | 说明 |
|---|---|---|
| `/tdm join [场地] [t\|ct]` | `tdm.use` | 加入比赛，可指定阵营 |
| `/tdm leave` | `tdm.use` | 离开比赛（**中途离开会受段位处罚**） |
| `/tdm shop` | `tdm.use` | 打开装备商店（需在购买区内） |
| `/tdm rank [玩家]` | `tdm.use` | 查询段位与赛季战绩（离线可查） |
| `/tdm matchrp [玩家]` | `tdm.use` | 打开战报界面 |
| `/tdm list` / `/tdm status` | `tdm.use` | 列出场地 / 查看比赛详情 |
| `/tdm import <wbdId> [新id]` | `tdm.admin` | 从 WBD 导入坐标 |
| `/tdm delete <场地id>` | `tdm.admin` | 删除死斗场地（**不动 WBD 那边**） |
| `/tdm enable` / `/tdm disable <场地id>` | `tdm.admin` | 启用 / 停用场地 |
| `/tdm start` / `/tdm stop [场地]` | `tdm.admin` | 强制开始 / 结束比赛 |
| `/tdm shopedit [T\|CT] [页]` | `tdm.admin` | 可视化编辑商店 |
| `/tdm shop adopt` | `tdm.admin` | 采用 WarZBombDefuse 的商店配置 |
| `/tdm shopids` | `tdm.admin` | 列出商品 id、推断类别与 GunId（贴进 `item-categories.yml`） |
| `/tdm exportitem` | `tdm.admin` | 把手持物品导出成可贴进配置的物品数据 |
| `/tdm season [reset]` | `tdm.admin` | 查看赛季剩余天数 / 手动软重置 |
| `/tdm reload` | `tdm.admin` | 重载全部配置、场地与分类表 |

---

## 配置文件

插件数据目录 `plugins/WBDTeamDeathmatch/`：

| 文件 | 作用 |
|---|---|
| `config.yml` | 主配置：比赛参数、经济、商店来源、HUD、队友名牌、武器名继承 |
| `arenas.yml` | 场地：坐标、出生点、购买区、边界、场地级覆盖 |
| `season.yml` | 赛季与段位表、计分权重 |
| `messages.yml` | 全部玩家可见文案 |
| `shop.yml` | 本地商店（默认空；也可以 `shop.source: wbd` 直接用爆破那份） |
| `item-categories.yml` | **装备分类与落点**（首次启动生成） |
| `weapon-names.yml` | **武器显示名**（首次启动生成） |
| `ranked.db` | SQLite：段位、赛季、比赛历史 |
| `reports/` | 每局一份 YAML 战报 |

---

## 装备分类与落点

买到的商品按类别固定到快捷栏格子，其它物品不许抢占。

```yaml
# item-categories.yml
inherit-wbdaddon: true   # 继承 WBDAddon 的 guns / names 表（本地优先）

ids:                     # 商品 id -> 类别（优先级最高）
  G17: secondary
guns:                    # TacZ GunId -> 类别
  tacz:p320: secondary
materials:               # Bukkit 材质兜底
  TACZ_ATTACHMENT: attachment
names:                   # 显示名兜底（忽略颜色）
  "AK-47": primary

categories:
  primary:    { enabled: true, slot: 0, limit: 1, on-buy: replace }
  secondary:  { enabled: true, slot: 1, limit: 1, on-buy: replace }
  melee:      { enabled: true, slot: 2, limit: 1, on-buy: replace }
  throwable:  { enabled: true, slot: 3, limit: 0 }      # 0 = 不限
  attachment: { enabled: true, to-backpack: true }      # 配件直接进背包
  utility:    { enabled: true, slots: [5, 6] }          # 拆弹器、医疗包
```

规则：

- **归属判定**：PDC 标记 → 商品 id → TacZ GunId → 材质 → 显示名
- **落点**：分类写了 `slot` 就以它为准，`give-slot` 只在没有分类时兜底
- **限购**：`limit > 0` 时超买直接拦下，**钱还没扣**
- **同类替换**：`on-buy: replace` 表示买第二把手枪会清掉第一把，不管它躺在哪一格
- **预留格**：被分类占用的格子只放对应类别；泥土、面包、别人掉的枪会被挪到空闲格，没空位再进背包 9-35
- **归位任务**：每秒一次，只作用于比赛中玩家

装了 TacZ 桥接时按 GunId 分类；读得出 GunId 但表里没写的枪**不接管**，保持商店原本的落点——不会因为认不出就把手枪塞进第 1 格。

---

## 武器显示名

击杀播报里写「twx:twx_kcb」没人看得懂，所以有一份对照表：

```yaml
# weapon-names.yml
items:
  "twx:twx_kcb": "克莱伯狙击枪"
  "tacz:ak47": "AK-47"
```

优先级（**本插件优先**）：

1. 本插件 `weapon-names.yml`
2. WBDAddon 的 `weapon-names.yml`（只补本地没有的键，`weapon-names.inherit-wbdaddon: false` 可关）
3. 物品自己的显示名
4. GunId 的可读形式（`twx_kcb` → `twx kcb`）

首次启动会生成模板；装了 TacZ 桥接时，模板里会把全部 GunId 以注释列出，照着改即可。

---

## 依赖与兼容

| 依赖 | 类型 | 说明 |
|---|---|---|
| **WarZBombDefuse** | **硬依赖** | 场地、商店、物品数据格式全部沿用；`plugin.yml` 声明为 `depend`，缺少时不加载 |
| **WBDAddon** | 软依赖 | 提供 `WbdAddonApi`。没装也能跑，只是少了饥饿锁定、JourneyMap 桥接、武器名解析 |
| **TacZ + TacZSpigotBridge** | 软依赖 | 装了才能按 GunId 分类与取名；没装则退化到材质/显示名 |
| **SQLite JDBC** | 服务端自带 | 段位与历史存在 `ranked.db`，插件不打包驱动 |

- **服务端**：Arclight / Paper / Spigot 1.20.1（`api-version: '1.20'`）
- **Java**：17
- **WBDAddon 版本**：需要 API `1.2+`（低于此版本时队友名牌功能静默失效，控制台会警告）

---

## 构建

本仓库**不打包**任何第三方 jar：WBDAddon 与 Paper API 都是编译期依赖。

```bash
# 1. 先构建 WBDAddon，把产物放到 libs/WBDAddon.jar
#    （见 libs/README.md 与 docs/development.md）

# 2a. 用 Maven（需要本地仓库里有依赖）
mvn -B clean package

# 2b. 或直接用 javac（不依赖 mvn，release 17）
./build.sh
```

产物命名：

```
WBDTDM-<对外版本>-<内部版本>-<git短哈希>-<时间戳>.jar
例：WBDTDM-1.2.2-1.2.2-07e4b71-20261028-1428.jar
```

两个版本号分工明确：

| 版本 | 来源 | 用途 |
|---|---|---|
| **对外版本** | `public-version.txt` | 玩家看到的：`plugin.yml` 的 `version`、`/plugins` 列表、发布帖 |
| **内部版本** | `pom.xml` 的 `<version>` | 每次更新自增，只体现在产物文件名与 `git.properties` 里 |

---

## 常见坑

以下几条都是实际踩过、**都不报错**、只表现为"说不清的怪现象"：

| 症状 | 原因 |
|---|---|
| 商店里全是武器 / 装备全挤在第一格 | `shop.source` 指向了未调过落点的配置；或读的是 WBD 原版（`give-slot` 多为 `-1`） |
| 买完第二把手枪，两把都在背包里 | 分类没配好：枪械同为一种材质，`materials` 分不开手枪与步枪，需要在 `ids` / `guns` 里点名 |
| 发出来的枪不是那把枪 | 商品缺少 `give-data`，只能按材质造空壳；启动日志的"发放数据审计"会点名 |
| 段位永远偏低 | 结算晚于 `resetForNewMatch()`，全员按 0 击杀结算 |
| 敌人头上还显示名字 | WBDAddon 版本低于要求，队友名牌功能静默失效 |
| 商店突然打不开 | `buy-zones` 球心坐标不对（落在地底/半空），玩家进不去 |

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/guide.md](docs/guide.md) | 详细玩法与设计取舍（最完整的一份） |
| [docs/architecture.md](docs/architecture.md) | 代码结构、分包职责、关键数据流 |
| [docs/commands.md](docs/commands.md) | 全部命令、参数、权限 |
| [docs/configuration.md](docs/configuration.md) | `config.yml` / `arenas.yml` / `season.yml` / `messages.yml` |
| [docs/ranked.md](docs/ranked.md) | 段位：段位表、计分、保护、赛季、中途离开处罚 |
| [docs/reports.md](docs/reports.md) | 战报格式与 `/tdm matchrp` 界面 |
| [docs/shop.md](docs/shop.md) | 商店格式、分类与落点、购买区、`bukkit-object:` |
| [docs/wbdaddon-api.md](docs/wbdaddon-api.md) | 与 WBDAddon 的集成契约（为什么类型引用必须隔离） |
| [docs/development.md](docs/development.md) | 构建步骤、依赖获取、调试建议 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 症状 → 原因排查表 |

仓库结构：

```
WBDTeamDeathmatch/
├── README.md / CHANGELOG.md / LICENSE / pom.xml
├── build.sh                      ← javac 构建脚本（可选，不依赖 mvn）
├── docs/                         ← 文档
├── examples/                     ← 示例配置
├── libs/                         ← 编译期依赖（jar 不入库）
└── src/main/
    ├── java/alphahu/tdm/
    │   ├── arena/       场地：定义、加载、购买区
    │   ├── combat/      战斗事件：死亡、友伤、保护
    │   ├── command/     /tdm 命令
    │   ├── config/      config.yml / season.yml 读取
    │   ├── game/        比赛本体：状态机、计分、队伍、出生装备
    │   ├── hud/         BossBar / ActionBar / 计分板
    │   ├── integration/ WBDAddon 对接层
    │   ├── message/     messages.yml 与占位符
    │   ├── ranked/      段位：计分、存储、赛季
    │   ├── report/      战报：落盘、查询界面
    │   ├── shop/        商店：解析、编辑、购买、分类
    │   └── util/        工具：物品编解码、坐标、文本、武器名
    └── resources/       默认配置（plugin.yml / config.yml / arenas.yml / season.yml / messages.yml / shop.yml）
```

---

## 许可

本项目以 **GPL-3.0** 开源，与同生态的 WBDAddon 保持一致。完整条款见 [LICENSE](LICENSE)。

Copyright (C) 2026 AlphaHu

GPL-3.0 是 copyleft 协议：基于本项目的衍生作品必须以相同条款开源分发，不能改为闭源。只在自己服务器上使用不构成分发，不受此约束。

---

## 反馈

问题与建议请开 Issue。提交 PR 前建议先看 [docs/architecture.md](docs/architecture.md)，那里记着几处"看起来可以简化、其实不能动"的设计。

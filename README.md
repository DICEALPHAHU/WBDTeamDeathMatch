# WBDTeamDeathmatch

> WarZBombDefuse 生态下的**团队死斗模式**：无限复活、限时击杀赛，带排位段位、战后战报与战报查询。

一个 Bukkit/Spigot/Paper 插件。它复用 WarZBombDefuse 的**场地坐标格式**、**商店配置格式**与**物品数据格式**，因此你已经在爆破模式里量好的场地、配好的商店可以直接搬过来；同时通过 WBDAddon 的 `WbdAddonApi` 复用饥饿锁定、JourneyMap 队伍桥接等附加能力。

---

## 目录

- [功能一览](#功能一览)
- [文档索引](#文档索引)
- [仓库结构](#仓库结构)
- [依赖与兼容性](#依赖与兼容性)
- [快速开始](#快速开始)
- [命令速查](#命令速查)
- [构建](#构建)
- [常见坑](#常见坑)
- [许可](#许可)

---

## 功能一览

| 模块 | 说明 | 详细文档 |
|---|---|---|
| **比赛循环** | 等待 → 倒计时 → 对战 → 结算 → 自动重开；到击杀目标或时间耗尽即结束 | [docs/guide.md](docs/guide.md) |
| **无限复活** | 阵亡后延时复活并给予短暂无敌；复活**保留背包**，不像爆破那样每回合重发装备 | [docs/guide.md](docs/guide.md) |
| **经济与商店** | 击杀与连杀赚取金钱，在商店买装备；金钱每场重置、有持有上限 | [docs/shop.md](docs/shop.md) |
| **购买区限制** | 必须站在本阵营的购买区内才能开商店，格式与 WBD 的 `buy-zones` 完全一致 | [docs/shop.md](docs/shop.md) |
| **排位段位** | 8 段 × 4 小段，Apex 式计分（名次分 + 比分差 + 击杀 + 队内排名 − 入场费），含段位保护与赛季软重置 | [docs/ranked.md](docs/ranked.md) |
| **战后战报** | 每局落盘一份 YAML 战报，同时写库供查询 | [docs/reports.md](docs/reports.md) |
| **战报查询** | `/tdm matchrp <玩家>` 打开箱子界面，点图标在聊天栏展开那一场 | [docs/reports.md](docs/reports.md) |
| **队友名牌** | 队友头顶带 `[队友]` 前缀，**敌人名牌完全隐藏**（原版队伍机制，不需要发包插件） | [docs/configuration.md](docs/configuration.md) |
| **HUD** | BossBar 显示时间与比分，ActionBar 显示击杀播报，每人一块独立侧边栏计分板（含段位与金钱） | [docs/configuration.md](docs/configuration.md) |
| **WBDAddon 集成** | 软依赖：注册本模式后自动获得饥饿锁定、JourneyMap 桥接、武器名解析 | [docs/wbdaddon-api.md](docs/wbdaddon-api.md) |

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/guide.md](docs/guide.md) | **详细玩法与设计说明**（最完整的一份，含大量"为什么这么做"的取舍记录） |
| [docs/architecture.md](docs/architecture.md) | 代码结构、各包职责、关键数据流与线程模型 |
| [docs/commands.md](docs/commands.md) | 全部命令、参数、权限节点 |
| [docs/configuration.md](docs/configuration.md) | `config.yml` / `arenas.yml` / `season.yml` / `messages.yml` 逐项说明 |
| [docs/ranked.md](docs/ranked.md) | 段位系统：段位表、计分公式、保护、赛季、中途离开处罚 |
| [docs/reports.md](docs/reports.md) | 战报文件格式与 `/tdm matchrp` 查询界面 |
| [docs/shop.md](docs/shop.md) | 商店格式、落点规则、购买区、物品数据（`bukkit-object:`） |
| [docs/wbdaddon-api.md](docs/wbdaddon-api.md) | 与 WBDAddon 的集成契约，以及**为什么类型引用必须隔离** |
| [docs/development.md](docs/development.md) | 构建步骤、依赖获取、调试建议 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 症状 → 原因的排查表 |

---

## 仓库结构

```
WBDTeamDeathmatch/
├── README.md                     ← 本文件（项目索引）
├── CHANGELOG.md                  ← 版本变更
├── LICENSE                       ← 许可
├── pom.xml                       ← Maven 构建
├── .gitignore / .gitattributes
│
├── docs/                         ← 文档
│   ├── guide.md                  ← 详细玩法与设计（正文）
│   ├── architecture.md
│   ├── commands.md
│   ├── configuration.md
│   ├── ranked.md
│   ├── reports.md
│   ├── shop.md
│   ├── wbdaddon-api.md
│   ├── development.md
│   └── troubleshooting.md
│
├── examples/                     ← 示例配置
│   ├── README.md
│   └── shop-tuned.yml            ← 已按死斗调好落点的商店示例
│
├── libs/                         ← 编译期依赖（jar 不入库，见其 README）
│   └── README.md
│
└── src/main/
    ├── java/alphahu/tdm/         ← 源码（按职责分包）
    │   ├── WBDTeamDeathmatch.java   插件主类
    │   ├── arena/                   场地：定义、加载、购买区
    │   ├── combat/                  战斗事件：死亡、友伤、保护
    │   ├── command/                 /tdm 命令
    │   ├── config/                  config.yml / season.yml 的读取
    │   ├── game/                    比赛本体：状态机、计分、队伍、出生装备
    │   ├── hud/                     BossBar / ActionBar / 计分板
    │   ├── integration/             与 WBDAddon 的对接层
    │   ├── message/                 messages.yml 读取与占位符
    │   ├── ranked/                  段位：计分、存储、赛季、比赛历史
    │   ├── report/                  战报：落盘、查询界面
    │   ├── shop/                    商店：解析、编辑、购买
    │   └── util/                    工具：物品编解码、坐标、文本
    └── resources/                ← 随插件发布的默认配置
        ├── plugin.yml
        ├── config.yml
        ├── arenas.yml
        ├── season.yml
        ├── messages.yml
        └── shop.yml               ← 空商店模板（刻意不带任何武器）
```

**为什么 `libs/` 里只有一个 README**：WBDAddon 的 jar 是编译期依赖，二进制不入库（`.gitignore` 里 `*.jar` 已忽略）。拉到仓库后需要先自行构建 WBDAddon 并放到该目录，见 [libs/README.md](libs/README.md) 与 [docs/development.md](docs/development.md)。

---

## 依赖与兼容性

| 依赖 | 类型 | 说明 |
|---|---|---|
| **WarZBombDefuse** | **硬依赖** | 场地坐标、商店配置、物品数据格式全部沿用它的。`plugin.yml` 里声明为 `depend`，缺少时服务端**不会加载本插件** |
| **WBDAddon** | 软依赖 | 提供 `WbdAddonApi`。没装时插件照常运行，只是失去饥饿锁定、JourneyMap 桥接、队友名牌前缀 |
| **SQLite JDBC** | 服务端自带 | 段位与比赛历史存在 `ranked.db`。Paper/Spigot/Arclight 的 `libraries/` 里都有（本机实测 `org.xerial:sqlite-jdbc:3.42.0.0`），插件不打包它 |

- **服务端**：Arclight / Paper / Spigot 1.20.1（`api-version: '1.20'`）
- **Java**：17
- **实测环境**：Arclight 1.20.1（Forge 47.4.18 + Bukkit 混合端）
- **WBDAddon 版本**：队友名牌功能需要 **1.2.1+**（API `1.2`），低于此版本该功能会**静默失效**，插件会在控制台给出警告

---

## 快速开始

```text
1. 把 WarZBombDefuse、WBDAddon、本插件一起放进 plugins/，重启服务器
2. /tdm import <wbd场地id>          # 从爆破模式导入已量好的坐标
3. 核对坐标后 /tdm enable <场地id>   # 启用（会自动在 WBD 侧禁用同名场地）
4. /tdm join <场地id>               # 加入
```

**一个必须遵守的约定**：被死斗使用的场地，必须在 WarZBombDefuse 侧保持 `enabled: false`。两个模式同时启用同一个场地时，WBD 的回合循环会和死斗的无限复活直接打架。`/tdm enable` 会自动替你处理，启动时也会检查并告警。

---

## 命令速查

| 命令 | 权限 | 说明 |
|---|---|---|
| `/tdm join [场地] [t\|ct]` | `tdm.use` | 加入比赛，可指定阵营 |
| `/tdm leave` | `tdm.use` | 离开比赛（**比赛中途离开会受段位处罚**） |
| `/tdm shop` | `tdm.use` | 打开装备商店（需在购买区内） |
| `/tdm rank [玩家]` | `tdm.use` | 查询段位与本赛季战绩（离线可查） |
| `/tdm matchrp [玩家]` | `tdm.use` | 打开比赛战报界面 |
| `/tdm list` / `status` | `tdm.use` | 列出场地 / 查看比赛详情 |
| `/tdm import <wbdId> [新id]` | `tdm.admin` | 从 WarZBombDefuse 导入坐标 |
| `/tdm delete <场地id>` | `tdm.admin` | 删除死斗场地（**不动 WBD 那边**） |
| `/tdm enable` / `disable <场地id>` | `tdm.admin` | 启用 / 停用场地 |
| `/tdm start` / `stop [场地]` | `tdm.admin` | 强制开始 / 结束比赛 |
| `/tdm exportitem` | `tdm.admin` | 把手持物品导出成可贴进配置的物品数据 |
| `/tdm shopedit [T\|CT] [页]` | `tdm.admin` | 可视化编辑商店 |
| `/tdm shop adopt` | `tdm.admin` | 采用 WarZBombDefuse 的商店配置 |
| `/tdm season [reset]` | `tdm.admin` | 查看赛季剩余天数 / 手动软重置 |
| `/tdm reload` | `tdm.admin` | 重载全部配置与场地 |

完整说明见 [docs/commands.md](docs/commands.md)。

---

## 构建

```bash
# 1. 先构建 WBDAddon，把产物放到本项目的 libs/WBDAddon.jar
#    （详见 docs/development.md）
# 2. 再构建本插件
mvn -B clean package
```

产物：`target/WBDTDM-<版本>-<git哈希>-<时间戳>.jar`

---

## 常见坑

以下几条都是实际踩过并已修复的，列在这里是因为它们**都不报错**，只表现为"说不清的怪现象"：

| 症状 | 原因 |
|---|---|
| 商店里全是武器 / 装备全挤在第一格 | `shop.source` 指向了未调过落点的配置；或读到的是 WBD 原版（`give-slot` 多为 `-1`） |
| 发出来的枪不是那把枪 | 商品缺少 `give-data`，只能按材质造空壳。启动日志的"发放数据审计"会点名 |
| 段位永远偏低 | 结算晚于 `resetForNewMatch()`，全员按 0 击杀结算 |
| 敌人头上还显示名字 | WBDAddon 版本低于 1.2.1，队友名牌功能静默失效 |
| 商店突然打不开 | `buy-zones` 球心坐标不对（落在地底/半空），玩家进不去 |

更多见 [docs/troubleshooting.md](docs/troubleshooting.md)。

---

## 许可

本项目以 **GPL-3.0** 开源，与同生态的 WBDAddon 保持一致。完整条款见 [LICENSE](LICENSE)。

Copyright (C) 2026 AlphaHu

GPL-3.0 是 copyleft 协议：基于本项目的衍生作品必须以相同条款开源分发，不能改为闭源。若只是想在自己的服务器上使用本插件，不构成分发，不受此约束。

---

## 反馈

问题与建议请开 Issue；提交 PR 前建议先读 [docs/architecture.md](docs/architecture.md) 了解分包约定与几处"看起来可以简化、其实不能动"的设计。

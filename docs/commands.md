# 命令

主命令 `/tdm`，别名 `/teamdeathmatch`。

权限节点两个：

| 权限 | 默认 | 覆盖范围 |
|---|---|---|
| `tdm.use` | 所有人 | 加入、离开、商店、段位与战报查询 |
| `tdm.admin` | OP | 场地管理、强制开局、商店编辑、导出、重载 |

---

## 玩家命令

### `/tdm join [场地id] [t|ct]`

加入比赛。省略场地时自动挑一个可加入的；省略阵营时自动平衡分配（人少的一方优先）。

比赛中途加入会**立刻投入战斗**并给予短暂无敌，避免一进场就被秒。这类玩家在排位结算时会被标记为"中途加入"——只结算击杀分，不扣入场费，也不给名次分。

### `/tdm leave`

离开比赛，位置与背包会被还原到你进入前的状态。

> ⚠️ **在比赛中途（`LIVE` 阶段）离开会受段位处罚**：本局不结算排位分，并双倍扣除入场费。等待阶段与结算阶段离开不受罚，掉线同样受罚。

### `/tdm shop`

打开装备商店。

> 需要站在**本阵营的购买区**内。购买区在 `arenas.yml` 里按阵营配置，格式与 WarZBombDefuse 一致。没有配置购买区的阵营不受位置限制（可用 `shop.buy-zone-required` 改成"没配也不许买"）。

### `/tdm rank [玩家名]`

查询段位与本赛季战绩：段位、RP、场次、胜负。

不带参数查自己；带名字查别人。**离线玩家也能查**——按名字查的是内存档案而不是在线玩家列表，因为玩家想问"我刚那把掉了多少分"时通常已经退出游戏了。

### `/tdm matchrp [玩家名]`

打开比赛战报界面：54 格箱子，一格一场最近的比赛（最多 54 场），图标上带时间、比分、结束原因、你自己那一场的战绩与段位变化。

**点一下图标**会关闭界面并在聊天栏展开那一场的完整战报，按阵营分块，块内按击杀降序，先展示被查询玩家所在的那一方。

### `/tdm list`

列出所有场地：id、显示名、启用状态、当前人数。

### `/tdm status [场地id]`

查看某场比赛的详情：状态、比分、剩余时间、双方人数。

### `/tdm help`

显示帮助。

---

## 管理命令（`tdm.admin`）

### `/tdm import <wbd场地id> [新的场地id]`

从 WarZBombDefuse 的 `arenas.yml` 导入一个场地的坐标、出生点与边界。

```text
/tdm import dust2              # 导入为同名场地
/tdm import dust2 tdm_dust2    # 导入并改名为 tdm_dust2
```

- 导入的场地**默认是未启用状态**，核对坐标后需要 `/tdm enable`
- 显示名会从 WBD 那边读 `name` 字段并补上 `（死斗）` 后缀；如果 WBD 那边没配 `name`，会明确提示你去补——补完之后 `/tdm reload` 就会自动读过来，**不必重新导入**
- 导入后会顺带询问是否也采用 WBD 的商店配置

### `/tdm delete <场地id>`

删除死斗自己的场地配置（`plugins/WBDTeamDeathmatch/arenas.yml`）。

- **不会**影响 WarZBombDefuse 里的同名场地——"删掉一个模式里的场地"和"把那个场地从服务器上抹掉"是两件事，绑在一起迟早有人误删掉量了很久的坐标
- 如果场上有玩家，会先把他们送回大厅再删。顺序不能反，否则那些人会悬在一场已经不在列表里的比赛上：走也走不掉、复活也没人管

### `/tdm enable <场地id>` / `/tdm disable <场地id>`

启用 / 停用场地。

`enable` 会**自动在 WarZBombDefuse 侧禁用同名场地**，避免两个模式抢同一个场子。它走的是 `/wbd disable <id>` 控制台命令，而不是直接改 WBD 的 YAML——后者会丢掉那份文件的注释，也读不到 WBD 的内存状态。

### `/tdm start [场地id]` / `/tdm stop [场地id]`

强制开始 / 结束比赛，忽略人数与目标条件。

### `/tdm shopedit [T|CT] [页]`

打开商店的图形化编辑器。用普通鼠标点击摆放商品；Shift 点击、数字键、拖拽铺货、一键整理会被拦截。关闭界面时保存。

需求权限：`tdm.admin`。

### `/tdm shop adopt`

把 WarZBombDefuse 的商店配置复制一份到本插件目录，并把 `shop.source` 改成 `local`。之后两边独立维护、互不影响。

> 注意复制过来的是 WBD 那份**原版**，`give-slot` 大量是 `-1`——直接用在死斗里会让所有装备挤进同一格。可以配合 `shop.slot-overrides` / `material-slots` 调整落点。

### `/tdm exportitem`

把手持物品导出成可以直接贴进 `config.yml` 的物品数据。

输出到 `plugins/WBDTeamDeathmatch/exported-item.yml`，里面是 `bukkit-object:` 开头的完整序列化数据。用在两处：

```yaml
# 1. 出生装备
loadout:
  common:
    - "3@bukkit-object:rO0AB..."   # 3@ 表示放进第 4 格

# 2. 商店商品的 give-data（通常由 shopedit 自动生成，不必手写）
```

为什么需要这条命令：那段数据有几百个字符，没有人能手写出来，也没有办法从一个物品名字反推出它来。模组枪械的枪械 id、弹药、开火模式、配件全在里面，而这是**唯一**能让模组物品被原样发放的方式。

### `/tdm season [reset]`

- 不带参数：显示当前赛季编号与剩余天数
- `reset`：立即执行赛季软重置（`新 RP = 基准 + (旧 RP − 基准) / 2`）并向全服播报

### `/tdm reload`

重载全部配置与场地：`config.yml`、`arenas.yml`、`season.yml`、`messages.yml`、商店配置。

会先把场上的人还原送走并重建所有比赛，因此不适合在比赛打到一半时执行。

---

## Tab 补全

补全会按权限过滤：`tdm.admin` 才看得到管理子命令（`start`、`stop`、`reload`、`import`、`delete`、`enable`、`disable`、`shopedit`、`exportitem`）。

`season` 不做整体过滤——普通玩家也能查看剩余天数，只有 `season reset` 会在执行时校验权限。

---

## 相关文档

- [configuration.md](configuration.md) —— 各配置项详解
- [ranked.md](ranked.md) —— 段位系统与中途离开处罚
- [reports.md](reports.md) —— 战报格式与查询界面
- [shop.md](shop.md) —— 商店格式与购买区

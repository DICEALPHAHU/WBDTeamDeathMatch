# 战报

每场比赛结束后会留下两份记录：

| 形态 | 位置 | 用途 |
|---|---|---|
| **YAML 文件** | `plugins/WBDTeamDeathmatch/reports/` | 给人看：翻历史、存档、交给别人查 |
| **数据库记录** | `ranked.db` 的 `tdm_matches` + `tdm_results` | 给命令查：`/tdm matchrp` 按玩家取最近 N 场 |

为什么存两份：如果 `/tdm matchrp` 去扫 `reports/` 目录解析 YAML，几百份文件就是几百次磁盘 IO 加 YAML 解析，而这发生在一条随手敲的命令里。走索引查表则是 O(1)。文件给人看，表给命令查。

---

## 文件

命名格式：`<结束时间>_<场地id>.yml`，例如 `2026-09-26_11-35-12_tdm_demo.yml`。

内容示例：

```yaml
# WBDTeamDeathmatch 战后战报
# 由插件自动生成，可直接删除；修改不会被读回。
# 可用 /tdm rank <玩家> 查询某人的累计段位，本文件只记这一局。

arena: "tdm_demo"
arena-name: "死斗示例场地"
ranked: true
started-at: "2026-09-26 11:20:00"
ended-at: "2026-09-26 11:35:12"
duration-seconds: 912
duration-text: "15:12"
end-reason: "达到目标击杀数"
winner: T
target-kills: 50
players-count: 2

score:
  T: 50
  CT: 43

mvp: "AlphaHu"

players:
  - name: "AlphaHu"
    uuid: "1f2e3d4c-..."
    team: T
    kills: 12
    deaths: 5
    kd: "2.40"
    best-kill-streak: 6
    money-left: 3200
    late-join: false
    rank:
      rp-before: 3250
      rp-after: 3436
      delta: 186
      tier-before: "&e黄金 II"
      tier-after: "&e黄金 I"
      promoted: true
      demoted: false
      breakdown:            # 逐项留档
        placement: 120
        margin: 15
        kill: 60
        team-placement: 15
        entry-fee: -24
```

`breakdown` 那一段是特意加的：过一个月有人问"我那局为什么是 −24"，翻出战报就能逐项对给他看，而不是只能回答"系统算的"。

### 保留上限

`config.yml` 的 `reports.keep`（默认 500）控制最多留多少份，超出后按**文件修改时间**删除最旧的。

用 mtime 而不是文件名里的时间戳：后者在跨时区、跨夏令时、或者有人手工拷进旧文件时都不可靠，而 mtime 是文件系统自己维护的。

清理**只认自己生成的文件名格式**（`\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_.+\.yml`）。`reports/` 目录里完全可能有你自己放进去的文件，按"所有 `.yml` 都是战报"去删就是删用户数据。

---

## 两个时序要点

**1. 内容必须在主线程构建。** `TdmMatch` 是活对象，`restart()` 会遍历玩家执行 `resetForNewMatch()` 把击杀、死亡、连杀清零。所以实现是"主线程先把整份文本生成出来，再把写文件丢到异步"——顺序反了，异步任务拿到的是一份全 0 的战报，而且不报错。

**2. 写文件必须异步。** 几百行文本加一次磁盘 IO，在主线程做就是每局结束卡一下。而那一刻服务器正在传送、播报、放烟花，是最不该再添堵的时候。

---

## 查询界面

`/tdm matchrp [玩家]`

不带参数查自己，带名字查别人（**离线也能查**——查的是内存档案而不是在线玩家列表，玩家想问"我刚那把掉了多少分"时通常已经退出游戏了）。

打开后是一个 **54 格的大箱子**，标题 `<玩家> 比赛战报`，一格一场最近的比赛（最多 54 场），默认图标是纸张。图标上带：

- 场次序号、场地名、胜负
- 时间、时长
- 比分、胜者、结束原因
- 你自己那一场的战绩（击杀/死亡/K/D）
- 段位与 RP 变化

**点一下图标**会**关闭界面**并在聊天栏展开那一场的完整战报。

> 用 54 格而不是分页：这个界面要回答的是"我最近打得怎么样"，一屏看到最近 45 场已经远超需要，翻页只会多一层操作和状态。
>
> 点击后关闭界面而不是留在里面：战报是**文字**，展开在聊天栏里可以往上翻、能复制、能对着看；箱子界面会挡在聊天栏前面。两者叠在一起的结果是玩家点了半天什么也读不到。

### 输出格式

按阵营分块，**先展示被查询玩家所在的那一方**，块内按击杀降序：

```
======== 比赛战报 ========
地图 死斗示例场地 (tdm_demo) · 排位
比分 T 50 - 43 CT · T 获胜
时间 2026-09-26 11:20 ~ 2026-09-26 11:35 · 时长 15:12
结束 达到目标击杀数

【T 方】
  AlphaHu         12杀  5死  K/D 2.40   &e黄金 I  +186  [T]
  Steve            8杀  9死  K/D 0.89   &f白银 III  +42  [T]

【CT 方】
  Alex            10杀 11死  K/D 0.91   &3铂金 IV  -24  [CT]
```

段位列的三种情况：有段位就显示"段位 + RP变化"；中途加入显示"中途加入"（他这一场确实没算名次分，写清楚免得被当成漏算了）；非排位场地显示"未结算"。

### 界面只读

点击与拖拽都被拦截——不拦的话玩家可以把图标拖进自己背包白得一张纸。

---

## 数据库表

```sql
tdm_matches(id, arena_id, arena_name, ranked, started_at, ended_at,
            winner, t_score, ct_score, end_reason)

tdm_results(match_id, uuid, name, team, kills, deaths, best_streak,
            money_left, late_join, rp_before, rp_after, tier_after,
            promoted, demoted,
            PRIMARY KEY (match_id, uuid))

CREATE INDEX idx_tdm_results_uuid ON tdm_results(uuid);
```

索引是必须的：按玩家查历史是这个功能的主查询路径，没有它每次都要全表扫描。

`tdm_results.uuid` 不能省。曾经漏过这个字段、用名字顶上，后果是 `/tdm matchrp` **永远查不到任何记录**——功能看起来正常、不报错、结果永远是空的。名字会改、会重名，用它当键的话，改名后的玩家查不到自己的历史，两个同名玩家会互相看到对方的战绩。

比赛行与参与者行在**同一个事务**里写。只写进去一半的话，会查出一场"没有人参加的比赛"，那种半截数据比完全没有更难解释。

查询用 `IN (...)` 批量取回，而不是逐个 id 查——54 场逐场查就是 100 多次往返。

---

## 相关文档

- [ranked.md](ranked.md) —— 段位计分与中途离开处罚
- [configuration.md](configuration.md) —— `reports` 配置段

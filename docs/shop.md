# 商店

商店格式**完全兼容 WarZBombDefuse**，所以你在爆破模式里配好的那份可以直接共用——商品页码、阵营过滤、翻页按钮，以及枪械那种内嵌了完整物品数据的商品都能原样读出来。

配置在 `config.yml` 的 `shop` 段与商店文件本身。

---

## 商品来源

```yaml
shop:
  source: wbd                                  # wbd | local
  wbd-file: "plugins/WarZBombDefuse/shop.yml"
  local-file: "shop.yml"
```

| `source` | 行为 |
|---|---|
| `wbd`（默认） | 直接读 WarZBombDefuse 的那份。你很可能已经在那边把枪、投掷物、配件都配好了 |
| `local` | 读本插件数据目录里的副本。`/tdm shop adopt` 可以把 WBD 那份复制过来并把 source 改成 local |

> 插件**自带**的 `shop.yml` 是**空商店**（`items` 为空），与 WarZBombDefuse 刚装上时的状态一致——刻意不预设任何武器，卖什么枪该由服主决定。
>
> WBD 那份原版的 `give-slot` 大量是 `-1`（那边有经济系统，买什么都往空位塞），直接共用到死斗会让**所有装备挤进同一格**，表现为"刀跑到第一格"。用下面的落点规则调整。

---

## 物品数据（`bukkit-object:`）

商品的 `give-data` 字段里是 WarZBombDefuse 编辑器生成的完整物品数据：

```yaml
  G17:
    page: 1
    slot: 1
    team: T
    price: 200
    display:
      material: TACZ_MODERN_KINETIC_GUN
    display-data: "bukkit-object:rO0ABXNyABpvcmcuYnVra2l0..."
    give:
      material: TACZ_MODERN_KINETIC_GUN
    give-data: "bukkit-object:rO0ABXNyABpvcmcuYnVra2l0..."
    give-slot: 1
```

`give-data` 是一段 Base64，解码后是 `BukkitObjectInputStream` 写出的 `ItemStack`，里面装着物品的全部 NBT。举例，一份真实配置里的物品数据解开来是：

| 商品 | 实际枪械 id | 附带数据 |
|---|---|---|
| `G17` | `tacz:glock_17` | SEMI、GunCurrentAmmoCount |
| `P320` | `tacz:p320` | SEMI |
| `RE45` | `twx:twx_re45` | AUTO |
| `tacz_modern_kinetic_gun`（刀） | `twx:twx_kw` | — |
| `Kraber` | `twx:twx_kcb` | `AttachmentSCOPE → twx:scope_set_mzj4` |

**这是模组物品能被原样发放的唯一方式。** 按材质重建做不到：模组枪械的意义全在那串 NBT 里，凭空造一个同材质的空壳，发到手里必然不是那把枪（枪与刀甚至共用同一个 `TACZ_MODERN_KINETIC_GUN` 材质）。

### 怎么生成

- 用 WarZBombDefuse 的商店编辑器（`/wbd shopedit`）保存一次，会自动写出 `give-data`
- 本插件的 `/tdm shopedit` 同样会写
- 也可以 `/tdm exportitem` 手持物品导出，再手工贴进去

### 审计

启动日志里会有一行：

```
发放数据审计：48 件用完整物品数据（give-data，原样还原），2 件只有材质简写，3 件纯命令商品。
```

只要某件模组物品出现在"材质简写"或"纯命令"的计数里，它就**不可能**被原样发出去。有一条材质简写的商品时会额外打印警告。

---

## 落点规则

商品买来放进背包第几格，按下面的优先级决定：

```
1. config.yml 的 shop.slot-overrides.<商品id>     ← 优先级最高
2. 商品自己的 give-slot
3. shop.material-slots.<图标材质>
4. shop.fallback-slot（默认 0）
```

给 `config.yml` 最高优先级是刻意的：你那份 shop.yml 通常是从爆破模式直接共用过来的，里面 `give-slot` 大量是 `-1`。要在**不修改**那份配置的前提下换一套落点规则，就必须能在外层覆盖它。

```yaml
shop:
  fallback-slot: 0
  slot-overrides:
    M67Grenade: 3
    Molotov: 4
  material-slots:
    TACZ_MODERN_KINETIC_GUN: 0
    LRTACTICAL_THROWABLE: 3
```

没在 `material-slots` 里列出的材质会自动分配：按商品数量从多到少依次占用剩余槽位。**分配结果会全部打进启动日志**，想调整就照着日志里的材质名写一行。

参考落点（[examples/shop-tuned.yml](../examples/shop-tuned.yml) 用的就是这套）：

| 槽位 | 内容 |
|---|---|
| 0 | 主武器 |
| 1 | 手枪 |
| 2 | 刀 |
| 3 | 投掷物 |
| 4 | 配件 |

---

## 「买新的自动换掉旧的」

同组商品落在同一个槽位，买新的会先**清掉背包里同组的旧物品**再放新的。

分组的依据是**解析出来的落点槽位**，不是商品材质。早期版本按材质分组，结果是共用 `TACZ_MODERN_KINETIC_GUN` 的枪和刀互相顶掉——刀被手枪顶掉、十几把主武器挤在同一格还互相删。

清理是按 PDC 标记识别的，所以玩家把旧枪挪到背包别的格子也照样清得掉，不会攒出一背包枪。

`shop.tag-granted-items: false` 可以关掉标记，此时物品一个字节都不改、纯粹原样发放，代价是只能覆盖目标槽位。

---

## 购买区

玩家必须站在**自己阵营**的购买区内才能打开商店。购买区在 `arenas.yml` 里按阵营配置，格式与 WarZBombDefuse 一致：

```yaml
    buy-zones:
      t:
        - "world,-10.5,80.0,0.5,90,0,8.0"    # world,x,y,z,yaw,pitch,radius
      ct:
        - "world,10.5,80.0,0.5,-90,0,8.0"
```

球体，最后一位是半径。`yaw`/`pitch` 对球形区域没有意义，保留只是为了让你能从 WBD 那份配置里直接抄一行过来。

**没配购买区的阵营不受位置限制**（与 WBD 的"留空表示任意位置都可购买"一致）。想让"没配也不许买"生效：

```yaml
shop:
  buy-zone-required: true
```

### 检查发生在三个地方

1. 右键商店物品打开菜单时
2. `/tdm shop` 命令
3. **点击商品购买时**

第 3 条是必需的：菜单开着的时候玩家可以走出去继续点，菜单不会因为离开区域自动关闭。只在打开时检查等于没检查，真正要限制的是"购买"这个动作。

第 2 条堵的是最省事的绕过入口——只在右键那条路径上做限制，等于把锁挂在一扇没锁的门上。

判断收在 `TdmMatch.canPurchase(player)` 一处，避免写两遍漏一遍。

> ⚠️ 购买区坐标写错（球心落在地底或半空）的表现是**商店突然完全打不开**。建议放在各自出生点附近，半径 6~10。

---

## 经济

价格来自商品的 `price` 字段，从玩家的局内金钱里扣。金钱的来源与上限见 [configuration.md](configuration.md) 的 `economy` 段。

`economy.enabled: false` 会让 `price` 重新被忽略，回到全免费。

---

## 编辑器

`/tdm shopedit [T|CT] [页]`

打开一个箱子界面编辑该页商品。用普通鼠标点击摆放；Shift 点击、数字键、拖拽铺货、一键整理会被拦截（这些快捷操作会绕过保存逻辑）。关闭界面时保存，只重写被编辑的那一页。

商品 id 自动生成：`p<页>_<材质>_<槽位>`。

---

## 相关文档

- [configuration.md](configuration.md) —— `shop` 配置段
- [commands.md](commands.md) —— `/tdm shop`、`/tdm shopedit`、`/tdm exportitem`
- [../examples/README.md](../examples/README.md) —— 一份调好落点的示例商店

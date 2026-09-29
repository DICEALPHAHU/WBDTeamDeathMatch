# examples/ —— 示例配置

## shop-tuned.yml

一份**已按死斗调好落点**的 WarZBombDefuse 格式商店配置，从实际运行的服务器上导出。它的价值在于 `give-slot` 已经按下面的规则分好：

| 槽位 | 内容 | 商品数 |
|---|---|---|
| 0 | 主武器（VK47、R201、AlternatorSMG、Kraber…） | 17 |
| 1 | 手枪（RE45、B3 小帮手、G17、P320） | 4 |
| 2 | 刀 | 8 |
| 3 | 投掷物（M67 手雷、燃烧瓶） | 2 |
| 4 | 配件 | 19 |

已注释掉不给的商品：烟雾弹（`M18SmokeGrenade`）、闪光弹（`M84Flashbang`）、医疗包（`MadicKit`）、拆弹器（`defuse_kit`，死斗里没有可拆的东西）。

每件商品都带 `display-data` / `give-data`——即 `bukkit-object:` 开头的完整物品数据，里面装着 TacZ 的枪械 id、弹药、开火模式与配件。**这是模组物品能被原样发放的唯一方式**：没有它，服务端只能按材质造一个同材质的空壳。

举例，这份配置里的物品数据解开来是：

| 商品 | 实际枪械 id |
|---|---|
| `G17` | `tacz:glock_17` |
| `P320` | `tacz:p320` |
| `RE45` | `twx:twx_re45` |
| `tacz_modern_kinetic_gun`（刀） | `twx:twx_kw` |
| `Kraber` | `twx:twx_kcb` + 配件 `twx:scope_set_mzj4` |

### 怎么用

**方式一：直接读它**（推荐，不碰爆破模式的配置）

```text
1. 把 shop-tuned.yml 复制到 plugins/WBDTeamDeathmatch/shop.yml
2. config.yml 里设 shop.source: local
3. /tdm reload
```

**方式二：作为起点改**

复制过去之后用 `/tdm shopedit [T|CT] [页]` 在游戏里改商品，也可以直接编辑文件。它是标准 WBD 格式，`/wbd shopedit` 同样能打开。

**方式三：只要落点规则**

不想用这份商品清单，只想让它别把所有东西塞进第一格，就保持 `shop.source: wbd`，然后在自己的 `config.yml` 里用：

```yaml
shop:
  slot-overrides:      # 按商品 id 指定落点，优先级最高
    M67Grenade: 3
    Molotov: 4
  material-slots:      # 按图标材质分组分配落点
    TACZ_MODERN_KINETIC_GUN: 0
    LRTACTICAL_THROWABLE: 3
```

这两种覆盖方式**不会修改** WBD 那份配置，只在死斗里生效。

### ⚠️ 它不是插件默认

插件**自带**的 `shop.yml` 是**空商店**（`items` 为空），与 WarZBombDefuse 刚装上时的状态一致——刻意不预设任何武器，卖什么枪该由服主决定。

这份 `shop-tuned.yml` 只是为了方便你参考落点规则和物品数据格式，**不会**被插件自动读取或释放。

### 注意

- 文件 166 KB，因为里面嵌了几十件商品的完整序列化数据
- 商品 id 与 TacZ 枪械 id 是**具体服务器**的配置，换一台服务器、换一个模组包就可能失效
- 如果你的 WBD 商店与它不同，以你自己那份为准

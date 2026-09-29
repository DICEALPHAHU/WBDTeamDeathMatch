# 开发

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17（`maven-compiler-plugin` 用 `release 17`，保证只链接 Java 17 的 API） |
| Maven | 3.8+ |
| 目标服务端 | Arclight / Paper / Spigot 1.20.1 |

---

## 依赖关系

本插件有**两个**外部插件依赖，性质完全不同：

| 依赖 | 作用域 | 获取方式 |
|---|---|---|
| `io.papermc.paper:paper-api:1.20.1` | `provided` | Maven 中央仓库自动拉取 |
| `alphahu:WBDAddon` | `system` | **需要你手工放进 `libs/WBDAddon.jar`** |

WBDAddon 的 jar **不入库**（`.gitignore` 里的 `*.jar` 已忽略二进制），所以从仓库拉下来后第一次构建会失败。见 [../libs/README.md](../libs/README.md)。

> `system` 作用域是 Maven 的过时特性，正常项目应避免。这里用它是因为 WBDAddon 是同一作者的姊妹插件、且不在任何公共仓库。若日后发布到仓库，应换成普通的 `provided`。

---

## 构建

```bash
# 1. 构建 WBDAddon
cd /path/to/WBDAddon
mvn -B clean package
cp target/WBDAddon-*.jar /path/to/WBDTeamDeathmatch/libs/WBDAddon.jar

# 2. 构建本插件
cd /path/to/WBDTeamDeathmatch
mvn -B clean package
```

产物：

```
target/WBDTeamDeathmatch-1.0.0-a4cbeed-20260926-1745.jar
                └ 版本号    └ git短哈希  └ 构建时间戳（UTC）
```

哈希与时间戳由 `git-commit-id-maven-plugin` + `maven-antrun-plugin` 自动追加。源码不在 git 仓库里时，哈希位置会退化成 `nogit`，产物名依然是干净的。

---

## 代码约定

### 注释

本项目的注释密度较高，且**重点写"为什么"而不是"是什么"**。`TdmMatch`、`ShopService`、`RankedService` 里有大量"为什么不能改成看起来更简单的写法"的记录——那些是踩过坑之后留下的，改动相关代码前建议先读一遍。

### 不可变配置

`TdmSettings` 与 `RankedSettings` 加载后不再改动，重载时整体换一个新实例。这样比赛进行中读到的永远是自洽的一整套，不会出现"读到一半配置被换了，一半新一半旧"。

### 线程

| 操作 | 线程 |
|---|---|
| 比赛推进、事件处理、计分、播报、开界面 | 主线程 |
| 数据库读写、文件写入 | 异步 |
| **需要读活对象的内容构建** | **主线程**（构建完再丢异步去写） |

`RankedStore` 的所有方法都是 `synchronized`——SQLite 单连接不是线程安全的。改动时不要去掉锁，也不要在持锁期间调用 Bukkit API。

### 需要留意的几处设计

改这些地方之前建议先看 [architecture.md](architecture.md) 的"几处看起来可以简化、其实不能动"：

1. `WbdAddonModeBridge` 的类型隔离（有验证命令）
2. 硬依赖用 `depend` 而不是"启动后自己禁用"
3. 购买区在三个地方检查
4. 金钱写入只有一个入口，且返回实际到账数
5. 战报内容在主线程构建、写文件在异步
6. 清理旧战报只认自己的文件名格式

---

## 调试建议

### 看启动日志

启动时会打印（都是有用的信息，不是噪音）：

```
依赖检查：WarZBombDefuse 1.2.2 已就绪。
依赖检查：WBDAddon 1.2.0 已就绪，附加功能已启用。
段位数据库已就绪：plugins/WBDTeamDeathmatch/ranked.db
段位系统已就绪：载入 12 份档案，段位表 8 段，击杀分 6×最多 10，入场费 0~75，赛季 60 天。
商店已加载：50 件公共商品，阵营专属 2 组，共 3 页。
发放数据审计：48 件用完整物品数据（give-data，原样还原），2 件只有材质简写，3 件纯命令商品。
商店落点分组（同组互相替换，异组并存）：
    槽位 0 ← TACZ_MODERN_KINETIC_GUN（17 件）
    槽位 1 ← TACZ_ATTACHMENT（19 件）
    ...
已从 WarZBombDefuse 读取到 3 个场地的显示名。
```

`发放数据审计` 和 `落点分组` 这两段是排查"发出来的东西不对"的第一现场。

### 没有服务端时能验证什么

本机没有运行中的服务端时，仍可做这些（都是实际用过的）：

**配置文件的语法与取值**——用 snakeyaml 直接解析：

```bash
mvn -B dependency:copy -Dartifact=org.yaml:snakeyaml:2.2 -DoutputDirectory=target/lib
jshell --class-path target/lib/snakeyaml-2.2.jar
```

```java
var y = new org.yaml.snakeyaml.Yaml();
var m = (java.util.Map) y.load(new java.io.FileInputStream("src/main/resources/config.yml"));
System.out.println(m.get("shop"));
```

**验证编译产物里真的有新方法**——`BUILD SUCCESS` 不代表改的代码被编进去了（尤其在中途改过包名或方法签名时）：

```java
// 直接读 class 文件的字节，找方法名常量
var zip = new java.util.zip.ZipFile("target/xxx.jar");
var e = zip.getEntry("alphahu/tdm/game/TdmMatch.class");
var text = new String(zip.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
System.out.println(text.contains("canPurchase"));
```

**验证物品数据的实际内容**——`give-data` 是「Base64 → Java 序列化流 → `internal` 字段是一段 Base64 → gzip → NBT」，逐层解开就能看到真实的枪械 id：

```bash
# 1. 取出 give-data 的值，去掉 "bukkit-object:" 前缀
# 2. Base64 解码 → 得到 Java 序列化流
# 3. 流里找 "H4sI" 开头的字符串（TC_STRING，长度在它前面 2 字节）
# 4. 对该字符串再做 Base64 解码 → gzip 解压 → NBT 文本
```

---

## 相关文档

- [architecture.md](architecture.md) —— 分包、数据流、线程模型
- [wbdaddon-api.md](wbdaddon-api.md) —— 集成契约与类型隔离
- [troubleshooting.md](troubleshooting.md) —— 症状排查
- [../libs/README.md](../libs/README.md) —— 依赖获取

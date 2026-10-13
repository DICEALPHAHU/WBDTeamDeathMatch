# libs/ —— 编译期依赖目录

## 这个目录里应该放什么

```
libs/WBDAddon.jar
```

**这个 jar 不入库**（根目录 `.gitignore` 里的 `*.jar` 已忽略二进制），你需要自己构建一份放进来，否则 `mvn package` 会在解析依赖时失败。

## 为什么是编译期依赖而不是运行期依赖

本项目只用到 WBDAddon 的 `alphahu.wbdaddon.api` 包（`AddonMode`、`WbdAddonApi`、`CombatStats`），作用域是 `system`——**编译时需要，运行时不需要**，因为服务端在运行时会加载 `plugins/WBDAddon.jar` 提供这些类。

这也是为什么可以在**没装 WBDAddon** 的服务器上运行：所有对 `alphahu.wbdaddon.*` 的类型引用都被隔离在 `WbdAddonModeBridge` 一个类里，由 `AddonBridge` 反射加载。详见 [../docs/wbdaddon-api.md](../docs/wbdaddon-api.md)。

## 怎么获得

### 方式一：构建 WBDAddon 源码

```bash
git clone <WBDAddon 仓库地址>
cd WBDAddon
mvn -B clean package
# 产物形如 target/WBDAddon-<版本>-<哈希>-<时间戳>.jar
cp target/WBDAddon-*.jar /path/to/WBDTeamDeathmatch/libs/WBDAddon.jar
```

### 方式二：取已发布的 WBDAddon 插件

把 `plugins/WBDAddon.jar` 直接拷成 `libs/WBDAddon.jar` 即可——同一个 jar，服务端能加载它，Maven 也能从它解析那些接口。

## 版本要求

| 用途 | 最低版本 | 说明 |
|---|---|---|
| 注册模式、复用饥饿锁定 / JourneyMap 桥接 | 1.1.0 | 提供 `WbdAddonApi`（API `1.1`） |
| **队友名牌前缀 + 隐藏敌人名牌** | **1.2.1** | `AddonMode.nameTagPrefix()` 与 `hideEnemyNameTags()`（API `1.2`） |

低于 1.2.1 时插件仍能编译运行（那两个方法是接口的默认方法，旧接口里不存在，`WbdAddonModeBridge` 里对它们的重写不会报错，只是不会被调用），但**队友名牌功能会静默失效**。插件启动时会检查 API 版本并打印警告。

`pom.xml` 里声明的版本号：

```xml
<dependency>
    <groupId>alphahu</groupId>
    <artifactId>WBDAddon</artifactId>
    <version>1.2.1</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/libs/WBDAddon.jar</systemPath>
</dependency>
```

> `system` 作用域是 Maven 的过时特性，正常项目应避免使用。这里用它是因为 WBDAddon 是**同一作者的姊妹插件、且不在任何公共仓库**，用它是最省事的方式。若日后 WBDAddon 发布到仓库，应换成普通的 `provided` 依赖。

## 相关文档

- [../docs/development.md](../docs/development.md) —— 完整构建步骤
- [../docs/wbdaddon-api.md](../docs/wbdaddon-api.md) —— 集成契约与类型隔离的原因

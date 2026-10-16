# EconomyPlugin

轻量级 Minecraft 经济插件，面向 **Bukkit 系服务端 1.20 ～ 1.21.x**（Spigot / Paper / Mohist 等）。提供余额、转账、带**逐玩家动态定价**的商店与每日签到 API，并通过 Bukkit ServicesManager 对外暴露 **EconomyAPI** 接口，供其他插件接入（如 [TerritoryPlugin 领地插件](https://github.com/lcxws/TerritoryPlugin)）。

> **版本兼容**：1.20 ～ 1.21.x 全系列（已在 1.21.1 API 上通过编译兼容性验证）；不支持 Folia 与 1.19 及更老版本。

## 功能

| 命令 | 说明 |
|------|------|
| `/balance` | 查询余额 |
| `/pay <玩家> <数量>` | 转账 |
| `/shop [reload]` | 打开商店 / 重载配置 |
| `/eco <give\|take\|set\|reload\|top>` | 管理员命令（权限 `economy.admin`，默认 OP） |

- **动态定价**：每个玩家、每种物品独立的价格曲线（指数公式），买入越多越贵、卖出越多越便宜；长时间无交易时价格缓慢回归基础价（变化率与衰减均可配置，见 `config.yml` 与 `prices.yml` 内注释）
- **每日签到**：`EconomyAPI.claimDaily()` 供其他插件调用（配合签到/登录奖励类插件）
- **财富榜**：`/eco top`
- 数据本地存储于 `plugins/Economy/data.yml`（YAML）

## 其他插件接入 EconomyAPI

通过 ServicesManager 获取接口实例，完整说明见 [API_USAGE_EXAMPLE.md](API_USAGE_EXAMPLE.md)：

```java
if (Bukkit.getPluginManager().isPluginEnabled("EconomyPlugin")) {
    EconomyAPI eco = Bukkit.getServicesManager().load(EconomyAPI.class);
    if (eco != null) {
        double bal = eco.getBalance(player);
    }
}
```

接口为纯接口依赖，接入方建议使用 `softdepend` 并对 null 做防御；写操作内部已自动切换主线程，可安全在异步线程调用。

## 构建

环境要求：JDK 17。

```bash
./gradlew build
# 产物: build/libs/EconomyPlugin-2.1.0.jar
```

## 开源协议

[MIT](LICENSE) © 2026 lcxws

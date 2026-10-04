# EconomyPlugin API 使用指南

## 概述

EconomyPlugin 现在提供了一个完整的 **EconomyAPI** 接口，其他插件可以通过 Bukkit 的 ServicesManager 来调用经济系统的功能。

---

## 其他插件如何接入

### 1. 软依赖配置（plugin.yml）

在你的插件的 `plugin.yml` 中添加软依赖：

```yaml
name: MyPlugin
version: 1.0.0
main: com.example.myplugin.MyPlugin
api-version: '1.20'

softdepend:
  - EconomyPlugin
```

### 2. 获取 API 实例

```java
import com.mohistmc.economy.api.EconomyAPI;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {
    private EconomyAPI economy;

    @Override
    public void onEnable() {
        // 方式一：通过 ServicesManager 获取（推荐）
        if (getServer().getPluginManager().isPluginEnabled("EconomyPlugin")) {
            economy = getServer().getServicesManager().load(EconomyAPI.class);
            if (economy != null) {
                getLogger().info("已连接到 EconomyPlugin API v" + economy.getVersion());
            }
        }
    }
}
```

### 3. 常用 API 调用示例

```java
import com.mohistmc.economy.api.EconomyAPI;
import org.bukkit.entity.Player;

public class EconomyExample {
    private EconomyAPI economy;

    public EconomyExample(EconomyAPI economy) {
        this.economy = economy;
    }

    // ========== 余额操作 ==========

    /** 查看玩家余额 */
    public void showBalance(Player player) {
        double bal = economy.getBalance(player);
        player.sendMessage("你的余额: " + economy.format(bal));
    }

    /** 给玩家发钱 */
    public void giveMoney(Player player, double amount) {
        economy.deposit(player, amount);
        player.sendMessage("获得 " + economy.format(amount));
    }

    /** 扣钱 */
    public boolean takeMoney(Player player, double amount) {
        if (economy.hasBalance(player, amount)) {
            economy.withdraw(player, amount);
            return true;
        }
        player.sendMessage("余额不足！需要 " + economy.format(amount));
        return false;
    }

    /** 转账 */
    public boolean transfer(Player from, Player to, double amount) {
        return economy.transfer(from, to, amount);
    }

    // ========== 商店价格查询 ==========

    /** 查询物品收购价 */
    public void checkSellPrice(Player player, Material material) {
        double price = economy.getSellPrice(player, material);
        if (price > 0) {
            player.sendMessage(material.name() + " 收购价: " + economy.format(price) + "/个");
        } else {
            player.sendMessage("该物品暂不被收购");
        }
    }

    /** 查询物品出售价 */
    public void checkBuyPrice(Player player, Material material) {
        double price = economy.getBuyPrice(player, material);
        if (price > 0) {
            player.sendMessage(material.name() + " 出售价: " + economy.format(price) + "/个");
        } else {
            player.sendMessage("该物品暂不出售");
        }
    }

    /** 计算批量出售总价 */
    public void calculateBatchSell(Player player, Material material, int amount) {
        double total = economy.calculateSellTotal(player, material, amount);
        player.sendMessage("出售 " + amount + " 个 " + material.name()
                + " 可获得 " + economy.format(total));
    }

    // ========== 排行榜 ==========

    /** 显示财富榜 */
    public void showTopWealth(Player player, int topN) {
        var top = economy.getTop(topN);
        player.sendMessage("=== 财富榜 TOP " + topN + " ===");
        int rank = 1;
        for (var entry : top) {
            String name = getServer().getOfflinePlayer(entry.getKey()).getName();
            player.sendMessage("#" + rank + " " + name + " - " + economy.format(entry.getValue()));
            rank++;
        }
    }

    // ========== 每日签到 ==========

    /** 执行签到 */
    public void doDailyClaim(Player player) {
        if (economy.claimDaily(player)) {
            player.sendMessage("签到成功！");
        } else {
            player.sendMessage("今天已经签到过了");
        }
    }
}
```

---

## API 完整接口列表

### 余额操作
| 方法 | 说明 |
|------|------|
| `getBalance(OfflinePlayer)` | 获取余额 |
| `getBalance(UUID)` | 通过 UUID 获取余额 |
| `setBalance(OfflinePlayer, double)` | 设置余额 |
| `deposit(OfflinePlayer, double)` | 存款 |
| `withdraw(OfflinePlayer, double)` | 取款（返回是否成功）|
| `hasBalance(OfflinePlayer, double)` | 检查余额是否充足 |
| `transfer(OfflinePlayer, OfflinePlayer, double)` | 转账 |

### 格式化与查询
| 方法 | 说明 |
|------|------|
| `format(double)` | 格式化金额显示 |
| `getCurrencyName()` | 获取货币名称 |
| `getStartingBalance()` | 获取起始余额 |
| `getDailyReward()` | 获取每日奖励 |

### 排行榜
| 方法 | 说明 |
|------|------|
| `getTop(int)` | 获取财富榜前 N 名 |

### 商店价格
| 方法 | 说明 |
|------|------|
| `getSellPrice(Player, Material)` | 获取收购价 |
| `getBuyPrice(Player, Material)` | 获取出售价 |
| `calculateSellTotal(Player, Material, int)` | 批量收购总价 |
| `calculateBuyTotal(Player, Material, int)` | 批量购买总价 |
| `canSell(Material)` | 是否可收购 |
| `canBuy(Material)` | 是否可购买 |
| `getCategories()` | 获取商品分类 |

### 每日签到
| 方法 | 说明 |
|------|------|
| `getLastDailyClaimTime(OfflinePlayer)` | 上次签到时间 |
| `hasClaimedDaily(OfflinePlayer)` | 今天是否已签到 |
| `claimDaily(Player)` | 执行签到 |

### 插件状态
| 方法 | 说明 |
|------|------|
| `getVersion()` | 获取插件版本 |
| `reload()` | 重载配置 |

---

## 注意事项

1. **软依赖**：建议使用 `softdepend` 而不是 `depend`，这样即使 EconomyPlugin 未安装，你的插件也能正常加载。

2. **空值检查**：获取 API 时始终检查是否为 null：
   ```java
   if (economy == null) {
       getLogger().warning("EconomyPlugin 未安装，经济功能不可用");
       return;
   }
   ```

3. **离线玩家支持**：所有余额操作都支持 `OfflinePlayer`，可以操作不在线的玩家。

4. **线程安全（重要）**：
   动态定价 + 异步调用是 API 的核心安全设计，已内置处理，你无需额外加锁：
   - 内部数据全部使用 `ConcurrentHashMap` / 同步集合，纯读操作（`getBalance`、`getSellPrice`、`hasBalance` 等）**可在任何线程安全调用**。
   - 写操作（`deposit`/`withdraw`/`transfer`/`setBalance`/`claimDaily`）内部**自动切换到主线程**执行，从异步线程（如 AsyncChatEvent、异步任务）调用也不会崩溃，不需要调用方做任何事。
   - `withdraw`/`transfer`/`claimDaily` 会**同步等待结果**再返回，保证你在异步代码中拿到的返回值是准确的。
   - 价格记录（`recordTransaction`）使用原子读改写（`compute`），并发下不会丢计数，动态价格不会算错。

5. **事件监听**：如果你想在 EconomyPlugin 启用/禁用时动态获取/释放 API，可以监听插件启用事件：
   ```java
   @EventHandler
   public void onPluginEnable(PluginEnableEvent event) {
       if (event.getPlugin().getName().equals("EconomyPlugin")) {
           economy = getServer().getServicesManager().load(EconomyAPI.class);
       }
   }
   ```

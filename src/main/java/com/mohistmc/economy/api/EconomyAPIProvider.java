package com.mohistmc.economy.api;

import com.mohistmc.economy.EconomyPlugin;
import com.mohistmc.economy.manager.EconomyManager;
import com.mohistmc.economy.manager.PriceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EconomyAPI 的实现类。
 *
 * 由 EconomyPlugin 在 onEnable 时注册到 Bukkit ServicesManager，
 * 其他插件通过 ServicesManager 获取此实现。
 */
public class EconomyAPIProvider implements EconomyAPI {

    private final EconomyPlugin plugin;
    private final EconomyManager economyManager;
    private final PriceManager priceManager;

    public EconomyAPIProvider(EconomyPlugin plugin) {
        this.plugin = plugin;
        this.economyManager = plugin.getEconomy();
        this.priceManager = plugin.getPrices();
    }

    /* ============================================================
     *  线程安全辅助：把任务切到主线程执行（调用方无感）
     * ============================================================ */

    /**
     * 如果当前不在主线程，则提交到主线程同步执行；
     * 如果在主线程则直接执行。返回执行结果。
     */
    private <T> T runSync(java.util.concurrent.Callable<T> task) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return task.call();
            } else {
                // 异步线程 → 主线程执行并等待结果（推荐：在异步代码中调用此 API）
                return Bukkit.getScheduler().callSyncMethod(plugin, task).get();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EconomyAPI] runSync 执行失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 如果当前不在主线程，则提交到主线程异步执行（不阻塞调用方）；
     * 如果在主线程则直接执行。
     */
    private void runAsync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /* ============================================================
     *  服务注册 / 注销
     * ============================================================ */

    /**
     * 注册 API 服务到 Bukkit ServicesManager
     */
    public void register() {
        Bukkit.getServicesManager().register(EconomyAPI.class, this, plugin, org.bukkit.plugin.ServicePriority.Highest);
        plugin.getLogger().info("[EconomyAPI] 已注册到 ServicesManager");
    }

    /**
     * 从 Bukkit ServicesManager 注销 API 服务
     */
    public void unregister() {
        Bukkit.getServicesManager().unregister(EconomyAPI.class, this);
        plugin.getLogger().info("[EconomyAPI] 已从 ServicesManager 注销");
    }

    /* ============================================================
     *  基础余额操作
     * ============================================================ */

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0;
        return economyManager.getBalance(player);
    }

    @Override
    public double getBalance(UUID uuid) {
        if (uuid == null) return 0;
        return economyManager.getBalance(uuid);
    }

    @Override
    public void setBalance(OfflinePlayer player, double amount) {
        if (player == null) return;
        if (Bukkit.isPrimaryThread()) {
            economyManager.setBalance(player, amount);
        } else {
            runAsync(() -> economyManager.setBalance(player, amount));
        }
    }

    @Override
    public void deposit(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0) return;
        if (Bukkit.isPrimaryThread()) {
            economyManager.deposit(player, amount);
        } else {
            runAsync(() -> economyManager.deposit(player, amount));
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0) return false;
        if (Bukkit.isPrimaryThread()) {
            return economyManager.withdraw(player, amount);
        } else {
            // 需要同步等待结果（是否扣款成功）
            return runSync(() -> economyManager.withdraw(player, amount)) == Boolean.TRUE;
        }
    }

    @Override
    public boolean hasBalance(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0) return true;
        // 纯读操作，可安全在任何线程调用（ConcurrentHashMap）
        return economyManager.hasBalance(player, amount);
    }

    @Override
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (from == null || to == null || amount <= 0) return false;
        if (from.getUniqueId().equals(to.getUniqueId())) return false;
        if (Bukkit.isPrimaryThread()) {
            return doTransfer(from, to, amount);
        } else {
            return runSync(() -> doTransfer(from, to, amount)) == Boolean.TRUE;
        }
    }

    private boolean doTransfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (!economyManager.hasBalance(from, amount)) return false;
        economyManager.withdraw(from, amount);
        economyManager.deposit(to, amount);

        // 如果双方都在线，发送提示
        Player onlineFrom = from.getPlayer();
        Player onlineTo = to.getPlayer();
        if (onlineFrom != null) {
            onlineFrom.sendMessage("§a✓ 已转账 " + economyManager.format(amount) + " 给 " + to.getName());
        }
        if (onlineTo != null) {
            onlineTo.sendMessage("§a✓ 收到 " + from.getName() + " 的转账: §e" + economyManager.format(amount));
        }
        return true;
    }

    /* ============================================================
     *  格式化与查询
     * ============================================================ */

    @Override
    public String format(double amount) {
        return economyManager.format(amount);
    }

    @Override
    public String getCurrencyName() {
        return economyManager.currencyName();
    }

    @Override
    public double getStartingBalance() {
        return economyManager.startingBalance();
    }

    @Override
    public double getDailyReward() {
        return economyManager.dailyReward();
    }

    /* ============================================================
     *  排行榜
     * ============================================================ */

    @Override
    public List<Map.Entry<UUID, Double>> getTop(int limit) {
        return economyManager.getTop(Math.max(1, limit));
    }

    /* ============================================================
     *  商店 / 价格系统
     * ============================================================ */

    @Override
    public double getSellPrice(Player player, Material material) {
        if (player == null || material == null) return -1;
        return priceManager.getCurrentSellPrice(player.getUniqueId(), material);
    }

    @Override
    public double getBuyPrice(Player player, Material material) {
        if (player == null || material == null) return -1;
        return priceManager.getCurrentBuyPrice(player.getUniqueId(), material);
    }

    @Override
    public double calculateSellTotal(Player player, Material material, int amount) {
        if (player == null || material == null || amount <= 0) return -1;
        return priceManager.calculateSellTotal(player.getUniqueId(), material, amount);
    }

    @Override
    public double calculateBuyTotal(Player player, Material material, int amount) {
        if (player == null || material == null || amount <= 0) return -1;
        return priceManager.calculateBuyTotal(player.getUniqueId(), material, amount);
    }

    @Override
    public boolean canSell(Material material) {
        if (material == null) return false;
        PriceManager.ShopItem item = priceManager.find(material);
        return item != null && item.canSell();
    }

    @Override
    public boolean canBuy(Material material) {
        if (material == null) return false;
        PriceManager.ShopItem item = priceManager.find(material);
        return item != null && item.canBuy();
    }

    @Override
    public List<String> getCategories() {
        return priceManager.getCategories();
    }

    /* ============================================================
     *  每日签到
     * ============================================================ */

    @Override
    public long getLastDailyClaimTime(OfflinePlayer player) {
        if (player == null) return 0;
        return economyManager.getDailyClaimTime(player);
    }

    @Override
    public boolean hasClaimedDaily(OfflinePlayer player) {
        if (player == null) return false;
        long lastClaim = economyManager.getDailyClaimTime(player);
        if (lastClaim == 0) return false;

        Calendar last = Calendar.getInstance();
        last.setTimeInMillis(lastClaim);

        Calendar now = Calendar.getInstance();

        return last.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && last.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    public boolean claimDaily(Player player) {
        if (player == null) return false;
        if (Bukkit.isPrimaryThread()) {
            return doClaimDaily(player);
        } else {
            return runSync(() -> doClaimDaily(player)) == Boolean.TRUE;
        }
    }

    private boolean doClaimDaily(Player player) {
        if (hasClaimedDaily(player)) return false;
        economyManager.setDailyClaimed(player);
        economyManager.deposit(player, economyManager.dailyReward());
        player.sendMessage("§a✓ 签到成功！获得 §e" + economyManager.format(economyManager.dailyReward()));
        return true;
    }

    /* ============================================================
     *  插件状态
     * ============================================================ */

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public void reload() {
        plugin.reload();
    }
}

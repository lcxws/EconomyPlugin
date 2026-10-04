package com.mohistmc.economy.manager;

import com.mohistmc.economy.EconomyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 经济数据管理器
 * 存储到 plugins/Economy/data.yml
 */
public class EconomyManager {

    private final EconomyPlugin plugin;
    private File dataFile;
    private YamlConfiguration data;

    /** 线程安全：API 可能被其他插件在异步线程调用 */
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Long> dailyClaims = new ConcurrentHashMap<>();

    private String currencyName;
    private double startingBalance;
    private double dailyReward;

    public EconomyManager(EconomyPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        currencyName = plugin.getConfig().getString("currency-name", "金币");
        startingBalance = plugin.getConfig().getDouble("starting-balance", 100);
        dailyReward = plugin.getConfig().getDouble("daily-reward", 30);

        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("无法创建 data.yml");
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        balances.clear();
        dailyClaims.clear();

        if (data.contains("balances")) {
            for (String key : data.getConfigurationSection("balances").getKeys(false)) {
                try {
                    balances.put(UUID.fromString(key), data.getDouble("balances." + key));
                } catch (Exception ignored) {}
            }
        }
        if (data.contains("daily")) {
            for (String key : data.getConfigurationSection("daily").getKeys(false)) {
                try {
                    dailyClaims.put(UUID.fromString(key), data.getLong("daily." + key));
                } catch (Exception ignored) {}
            }
        }

        plugin.getLogger().info("[Economy] 已加载 " + balances.size() + " 个玩家数据");
    }

    public void save() {
        if (data == null) return;
        data.set("balances", null);
        data.set("daily", null);
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            data.set("balances." + e.getKey().toString(), e.getValue());
        }
        for (Map.Entry<UUID, Long> e : dailyClaims.entrySet()) {
            data.set("daily." + e.getKey().toString(), e.getValue());
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存 data.yml");
        }
    }

    /* ============================================================
     *  Player 操作（保持原有接口兼容）
     * ============================================================ */

    public double getBalance(Player player) {
        // 只读不写：查询不会创建余额记录，防止 data.yml 无限膨胀
        return balances.getOrDefault(player.getUniqueId(), startingBalance);
    }

    public void setBalance(Player player, double amount) {
        balances.put(player.getUniqueId(), Math.max(0, amount));
        updateScoreboardSafe(player);
    }

    public boolean hasBalance(Player player, double amount) {
        return getBalance(player) >= amount;
    }

    public boolean withdraw(Player player, double amount) {
        if (amount <= 0) return false;
        // 原子读改写：避免两个线程同时扣款时丢失更新（钱凭空变多）
        boolean[] ok = {false};
        balances.compute(player.getUniqueId(), (k, bal) -> {
            double cur = bal != null ? bal : startingBalance;
            if (cur < amount) return bal; // 余额不足，不修改
            ok[0] = true;
            return cur - amount;
        });
        if (ok[0]) updateScoreboardSafe(player);
        return ok[0];
    }

    public void deposit(Player player, double amount) {
        if (amount <= 0) return;
        // 原子读改写：并发存钱不丢更新
        balances.compute(player.getUniqueId(), (k, bal) -> (bal != null ? bal : startingBalance) + amount);
        updateScoreboardSafe(player);
    }

    public long getDailyClaimTime(Player player) {
        return dailyClaims.getOrDefault(player.getUniqueId(), 0L);
    }

    public void setDailyClaimed(Player player) {
        dailyClaims.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /* ============================================================
     *  OfflinePlayer / UUID 操作（供 API 使用）
     * ============================================================ */

    public double getBalance(OfflinePlayer player) {
        // 只读不写：查询不会创建余额记录
        return balances.getOrDefault(player.getUniqueId(), startingBalance);
    }

    public double getBalance(UUID uuid) {
        // 只读不写：查询不会创建余额记录，防止 data.yml 无限膨胀
        return balances.getOrDefault(uuid, startingBalance);
    }

    public void setBalance(OfflinePlayer player, double amount) {
        balances.put(player.getUniqueId(), Math.max(0, amount));
        updateScoreboardSafe(player);
    }

    public boolean hasBalance(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0) return false;
        // 原子读改写：避免并发扣款丢更新
        boolean[] ok = {false};
        balances.compute(player.getUniqueId(), (k, bal) -> {
            double cur = bal != null ? bal : startingBalance;
            if (cur < amount) return bal; // 余额不足，不修改
            ok[0] = true;
            return cur - amount;
        });
        if (ok[0]) updateScoreboardSafe(player);
        return ok[0];
    }

    public void deposit(OfflinePlayer player, double amount) {
        if (amount <= 0) return;
        // 原子读改写：并发存钱不丢更新
        balances.compute(player.getUniqueId(), (k, bal) -> (bal != null ? bal : startingBalance) + amount);
        updateScoreboardSafe(player);
    }

    public long getDailyClaimTime(OfflinePlayer player) {
        return dailyClaims.getOrDefault(player.getUniqueId(), 0L);
    }

    public void setDailyClaimed(OfflinePlayer player) {
        dailyClaims.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public String currencyName() { return currencyName; }
    public double startingBalance() { return startingBalance; }
    public double dailyReward() { return dailyReward; }

    /* ============================================================
     *  线程安全工具：计分板必须在主线程更新
     * ============================================================ */

    private void updateScoreboardSafe(Player player) {
        if (player == null || !player.isOnline()) return;
        if (Bukkit.isPrimaryThread()) {
            plugin.getScoreboard().update(player);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) plugin.getScoreboard().update(player);
            });
        }
    }

    private void updateScoreboardSafe(OfflinePlayer player) {
        if (player == null || !player.isOnline()) return;
        updateScoreboardSafe(player.getPlayer());
    }

    public String format(double amount) {
        return String.format("%.0f %s", amount, currencyName);
    }

    public List<Map.Entry<UUID, Double>> getTop(int limit) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
}

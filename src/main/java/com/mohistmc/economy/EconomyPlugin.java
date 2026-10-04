package com.mohistmc.economy;

import com.mohistmc.economy.api.EconomyAPIProvider;
import com.mohistmc.economy.command.BalanceCommand;
import com.mohistmc.economy.command.EcoCommand;
import com.mohistmc.economy.command.PayCommand;
import com.mohistmc.economy.command.ShopCommand;
import com.mohistmc.economy.gui.ShopGUI;
import com.mohistmc.economy.listener.ScoreboardListener;
import com.mohistmc.economy.manager.EconomyManager;
import com.mohistmc.economy.manager.PriceManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class EconomyPlugin extends JavaPlugin {

    private static EconomyPlugin instance;
    private EconomyManager economyManager;
    private PriceManager priceManager;
    private ShopGUI shopGUI;
    private ScoreboardListener scoreboardListener;
    private EconomyAPIProvider apiProvider;
    private BukkitTask decayTask;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("prices.yml", false);

        economyManager    = new EconomyManager(this);
        priceManager      = new PriceManager(this);
        shopGUI           = new ShopGUI(this);
        scoreboardListener = new ScoreboardListener(this);

        getCommand("balance").setExecutor(new BalanceCommand(this));
        getCommand("pay").setExecutor(new PayCommand(this));
        getCommand("shop").setExecutor(new ShopCommand(this));
        getCommand("eco").setExecutor(new EcoCommand(this));

        Bukkit.getPluginManager().registerEvents(scoreboardListener, this);
        Bukkit.getPluginManager().registerEvents(shopGUI, this);

        // ★ 逐玩家价格衰减定时任务
        scheduleDecayTask();

        for (Player p : Bukkit.getOnlinePlayers()) {
            economyManager.getBalance(p);
            scoreboardListener.setup(p);
        }

        // ★ 注册 API 服务
        apiProvider = new EconomyAPIProvider(this);
        apiProvider.register();

        getLogger().info("[Economy] 已启动 | " + priceManager.getItemCount() + " 种商品"
                + " | 逐玩家指数定价（每物品独立变化率）"
                + " 衰减=" + priceManager.getDecayFactor() + "/"
                + getConfig().getLong("price.decay-interval-minutes", 30) + "min");
    }

    @Override
    public void onDisable() {
        if (apiProvider != null) {
            apiProvider.unregister();
        }
        economyManager.save();
        priceManager.savePlayerData();
        scoreboardListener.shutdown();
        scoreboardListener.removeAll();
        getLogger().info("[Economy] 已关闭");
    }

    public void reload() {
        priceManager.savePlayerData();
        economyManager.save();

        reloadConfig();
        economyManager.load();
        priceManager.reload();
        // 复用现有计分板监听器：只清空并重启其刷新任务，避免每次 reload 新建监听器导致泄漏
        scoreboardListener.removeAll();
        scoreboardListener.reschedule();
        for (Player p : Bukkit.getOnlinePlayers()) {
            economyManager.getBalance(p);
            scoreboardListener.setup(p);
        }
        // 按最新配置重新调度衰减任务（衰减间隔可能被修改，或设为 0 禁用）
        scheduleDecayTask();
        getLogger().info("[Economy] 已重载 | " + priceManager.getItemCount() + " 种商品");
    }

    /**
     * 按当前配置调度逐玩家价格衰减任务。
     * 间隔 < 1 分钟视为禁用衰减；重复调用会先取消旧任务。
     */
    private void scheduleDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
        long decayMinutes = getConfig().getLong("price.decay-interval-minutes", 30);
        if (decayMinutes < 1) {
            getLogger().warning("[Economy] price.decay-interval-minutes 必须 ≥ 1，已禁用价格衰减");
            return;
        }
        long ticks = decayMinutes * 60 * 20L;
        decayTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            priceManager.decayAll();
        }, ticks, ticks);
    }

    public static EconomyPlugin inst() { return instance; }
    public EconomyManager getEconomy() { return economyManager; }
    public PriceManager getPrices() { return priceManager; }
    public ShopGUI getShopGUI() { return shopGUI; }
    public ScoreboardListener getScoreboard() { return scoreboardListener; }
    public EconomyAPIProvider getAPI() { return apiProvider; }
}

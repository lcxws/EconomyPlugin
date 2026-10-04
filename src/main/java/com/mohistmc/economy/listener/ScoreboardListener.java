package com.mohistmc.economy.listener;

import com.mohistmc.economy.EconomyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * 计分板监听器
 * - 每个玩家独立计分板
 * - 只显示自己的余额
 * - 修复"一下子显示所有人"的 bug
 * - 兼容 RankPlugin：拷贝主计分板上的 rank Teams 到玩家私有计分板
 */
public class ScoreboardListener implements Listener {

    private final EconomyPlugin plugin;
    private final Map<UUID, Set<String>> playerEntries = new HashMap<>();
    private BukkitTask refreshTask;

    private static final String SEP_TOP = "§7━━━━━━━━━━━━━━━━";
    private static final String SEP_BOT = "§7━━━━━━━━━━━━━━━━";
    private static final String LINE_BAL = "§e余额";
    private static final String LINE_HELP1 = "§7/balance §8查余额";
    private static final String LINE_HELP2 = "§7/shop §8商店";
    private static final String LINE_HELP3 = "§7/pay §8转账";

    public ScoreboardListener(EconomyPlugin plugin) {
        this.plugin = plugin;
        startRefreshTask();
    }

    private void startRefreshTask() {
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                refresh(p);
            }
        }, 40L, plugin.getConfig().getLong("scoreboard.update-interval", 20));
    }

    /** 取消刷新定时任务并清空状态（插件关闭时调用，防止任务泄漏） */
    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        playerEntries.clear();
    }

    /** 按最新配置重启刷新定时任务（reload 时调用，复用同一监听器实例，避免监听器/任务泄漏） */
    public void reschedule() {
        shutdown();
        startRefreshTask();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) setup(e.getPlayer());
        }, 10L);
    }

    public void removeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = p.getScoreboard();
            // 只移除 "myeco" 相关数据，不替换整个计分板
            Objective obj = board.getObjective("myeco");
            if (obj != null) obj.unregister();
            board.resetScores(SEP_TOP);
            board.resetScores(SEP_BOT);
            board.resetScores(LINE_BAL);
            board.resetScores(LINE_HELP1);
            board.resetScores(LINE_HELP2);
            board.resetScores(LINE_HELP3);
        }
        playerEntries.clear();
    }

    /**
     * 拷贝主计分板上的 rank Teams 到玩家私有计分板。
     * RanKPlugin 把 Team（前缀/后缀）注册在主计分板上，
     * 玩家私有计分板默认没有这些 Team，需要手动同步。
     */
    private void syncRankTeams(Scoreboard playerBoard) {
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team mainTeam : mainBoard.getTeams()) {
            String teamName = mainTeam.getName();
            Team playerTeam = playerBoard.getTeam(teamName);
            if (playerTeam == null) {
                playerTeam = playerBoard.registerNewTeam(teamName);
            }
            playerTeam.setPrefix(mainTeam.getPrefix());
            playerTeam.setSuffix(mainTeam.getSuffix());
            playerTeam.setColor(mainTeam.getColor());
            // copy display options
            playerTeam.setAllowFriendlyFire(mainTeam.allowFriendlyFire());
            playerTeam.setCanSeeFriendlyInvisibles(mainTeam.canSeeFriendlyInvisibles());
            // copy member entries
            for (String entry : mainTeam.getEntries()) {
                if (!playerTeam.hasEntry(entry)) {
                    playerTeam.addEntry(entry);
                }
            }
        }
    }

    public void setup(Player player) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) return;
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        // ── 核心修复：使用玩家私有计分板，绝不替换 ──
        // RankPlugin 把 Team (前缀/后缀)注册在主计分板上
        // Spigot 1.13+ 下 player.getScoreboard() 返回的是私有计分板（不是主计分板）
        // 所以我们需要把主计分板上的 rank Teams 同步到私有计分板
        Scoreboard board = player.getScoreboard();
        if (board == null || board == mgr.getMainScoreboard()) {
            // 如果玩家没有私有计分板，创建一个新的
            board = mgr.getNewScoreboard();
        }

        // 同步 rank Teams（如果存在）
        syncRankTeams(board);

        // 如果已经存在我们的objective，直接刷新
        Objective obj = board.getObjective("myeco");
        if (obj == null) {
            String title = plugin.getConfig().getString("scoreboard.title", "&6&l我的余额")
                    .replace("&", "§");
            try {
                obj = board.registerNewObjective("myeco", "dummy", title);
            } catch (IllegalArgumentException ignored) {}
        }
        if (obj != null) {
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // 设置玩家计分板
        player.setScoreboard(board);

        // 确保 rank Teams 在设置后依然存在（setScoreboard 可能重置）
        syncRankTeams(board);

        refresh(player);
    }

    public void update(Player player) {
        refresh(player);
    }

    private void refresh(Player player) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) return;

        Scoreboard board = player.getScoreboard();
        if (board == null) {
            setup(player);
            return;
        }

        // 确保 rank Teams 在刷新时仍然存在
        syncRankTeams(board);

        Objective obj = board.getObjective("myeco");
        if (obj == null) {
            setup(player);
            return;
        }

        UUID uid = player.getUniqueId();

        // 清理自己之前设置的条目，不影响其他人
        Set<String> old = playerEntries.remove(uid);
        if (old != null) {
            for (String entry : old) {
                board.resetScores(entry);
            }
        }

        Set<String> cur = new LinkedHashSet<>();
        double bal = plugin.getEconomy().getBalance(player);

        int score = 10;
        put(obj, cur, SEP_TOP, score--);
        put(obj, cur, LINE_BAL, score--);
        put(obj, cur, " §6§l" + String.format("%.0f", bal) + " §e" + plugin.getEconomy().currencyName(), score--);
        score--; // blank
        put(obj, cur, SEP_BOT, score--);
        put(obj, cur, LINE_HELP1, score--);
        put(obj, cur, LINE_HELP2, score--);
        put(obj, cur, LINE_HELP3, score);

        playerEntries.put(uid, cur);
    }

    private void put(Objective obj, Set<String> entries, String text, int score) {
        obj.getScore(text).setScore(score);
        entries.add(text);
    }
}

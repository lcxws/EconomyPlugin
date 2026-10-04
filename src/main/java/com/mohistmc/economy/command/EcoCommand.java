package com.mohistmc.economy.command;

import com.mohistmc.economy.EconomyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class EcoCommand implements CommandExecutor, TabCompleter {

    private final EconomyPlugin plugin;

    public EcoCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("economy.admin")) {
            sender.sendMessage("§c你没有权限");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give":   return handleGive(sender, args);
            case "take":   return handleTake(sender, args);
            case "set":    return handleSet(sender, args);
            case "reload": return handleReload(sender);
            case "top":    return handleTop(sender);
            default:       sendHelp(sender); return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== 经济管理 (/eco) =====");
        sender.sendMessage("§e/eco give <玩家> <数量>  §7- 给钱");
        sender.sendMessage("§e/eco take <玩家> <数量>  §7- 扣钱");
        sender.sendMessage("§e/eco set <玩家> <数量>  §7- 设置余额");
        sender.sendMessage("§e/eco reload  §7- 重载配置");
        sender.sendMessage("§e/eco top  §7- 查看排行榜");
    }

    private Player resolvePlayer(String name) {
        Player p = Bukkit.getPlayer(name);
        if (p != null) return p;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase().startsWith(name.toLowerCase())) return online;
        }
        return null;
    }

    private double parseAmount(String s) {
        try {
            double v = Double.parseDouble(s);
            return v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /eco give <玩家> <数量>"); return true; }
        Player target = resolvePlayer(args[1]);
        if (target == null) { sender.sendMessage("§c玩家不在线: " + args[1]); return true; }
        double amount = parseAmount(args[2]);
        if (amount < 0) { sender.sendMessage("§c数量格式不正确"); return true; }
        plugin.getEconomy().deposit(target, amount);
        sender.sendMessage("§a✓ 已给予 " + target.getName() + " " + plugin.getEconomy().format(amount));
        target.sendMessage("§a你收到了 " + plugin.getEconomy().format(amount));
        return true;
    }

    private boolean handleTake(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /eco take <玩家> <数量>"); return true; }
        Player target = resolvePlayer(args[1]);
        if (target == null) { sender.sendMessage("§c玩家不在线: " + args[1]); return true; }
        double amount = parseAmount(args[2]);
        if (amount < 0) { sender.sendMessage("§c数量格式不正确"); return true; }
        if (!plugin.getEconomy().hasBalance(target, amount)) {
            sender.sendMessage("§c该玩家没有足够的" + plugin.getEconomy().currencyName());
            return true;
        }
        plugin.getEconomy().withdraw(target, amount);
        sender.sendMessage("§a✓ 已扣除 " + target.getName() + " " + plugin.getEconomy().format(amount));
        target.sendMessage("§c你被扣除了 " + plugin.getEconomy().format(amount));
        return true;
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /eco set <玩家> <数量>"); return true; }
        Player target = resolvePlayer(args[1]);
        if (target == null) { sender.sendMessage("§c玩家不在线: " + args[1]); return true; }
        double amount = parseAmount(args[2]);
        if (amount < 0) { sender.sendMessage("§c数量格式不正确"); return true; }
        plugin.getEconomy().setBalance(target, amount);
        sender.sendMessage("§a✓ 已设置 " + target.getName() + " 余额为 " + plugin.getEconomy().format(amount));
        target.sendMessage("§a你的余额已被设置为 " + plugin.getEconomy().format(amount));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage("§a✓ 已重载所有配置和数据");
        return true;
    }

    private boolean handleTop(CommandSender sender) {
        List<Map.Entry<UUID, Double>> top = plugin.getEconomy().getTop(10);
        sender.sendMessage("§6===== 财富榜 TOP 10 =====");
        if (top.isEmpty()) {
            sender.sendMessage(" §7暂无数据");
            return true;
        }
        int rank = 1;
        String[] colors = {"§e", "§7", "§6", "§f", "§f", "§f", "§f", "§f", "§f", "§f"};
        for (Map.Entry<UUID, Double> e : top) {
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (name == null) name = "§7未知";
            String medal = rank == 1 ? "§6#1" : rank == 2 ? "§e#2" : rank == 3 ? "§a#3" : "§7#" + rank;
            sender.sendMessage(" " + medal + " §f" + name + " §8- §e" + String.format("%.0f", e.getValue()));
            rank++;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("economy.admin")) return Collections.emptyList();
        if (args.length == 1) {
            return Arrays.asList("give", "take", "set", "reload", "top").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("top")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 3) {
            return Arrays.asList("10", "100", "1000", "10000").stream()
                    .filter(s -> s.startsWith(args[2])).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}

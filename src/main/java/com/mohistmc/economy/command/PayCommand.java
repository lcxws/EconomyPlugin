package com.mohistmc.economy.command;

import com.mohistmc.economy.EconomyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PayCommand implements CommandExecutor {

    private final EconomyPlugin plugin;

    public PayCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家能转账");
            return true;
        }
        Player p = (Player) sender;

        if (args.length < 2) {
            p.sendMessage("§c用法: /pay <玩家> <数量>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            p.sendMessage("§c玩家不在线: " + args[0]);
            return true;
        }

        if (target.equals(p)) {
            p.sendMessage("§c不能给自己转账");
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            p.sendMessage("§c数量格式不正确");
            return true;
        }

        if (amount <= 0) {
            p.sendMessage("§c数量必须大于0");
            return true;
        }

        if (!plugin.getEconomy().hasBalance(p, amount)) {
            p.sendMessage("§c你的" + plugin.getEconomy().currencyName() + "不足");
            return true;
        }

        plugin.getEconomy().withdraw(p, amount);
        plugin.getEconomy().deposit(target, amount);

        p.sendMessage("§a✓ 已转账 " + plugin.getEconomy().format(amount) + " 给 " + target.getName());
        target.sendMessage("§a✓ 收到 " + p.getName() + " 的转账: §e" + plugin.getEconomy().format(amount));
        return true;
    }
}

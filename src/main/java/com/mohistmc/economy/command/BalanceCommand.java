package com.mohistmc.economy.command;

import com.mohistmc.economy.EconomyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BalanceCommand implements CommandExecutor {

    private final EconomyPlugin plugin;

    public BalanceCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家能查看余额");
            return true;
        }
        Player p = (Player) sender;
        double bal = plugin.getEconomy().getBalance(p);
        p.sendMessage("§6你的余额: §e" + plugin.getEconomy().format(bal));
        p.sendMessage("§7/shop §8- 商店  §7/pay §8- 转账");
        return true;
    }
}

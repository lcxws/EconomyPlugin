package com.mohistmc.economy.command;

import com.mohistmc.economy.EconomyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ShopCommand implements CommandExecutor {

    private final EconomyPlugin plugin;

    public ShopCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家能使用商店");
            return true;
        }
        Player p = (Player) sender;

        if (args.length == 0) {
            // /shop → 打开GUI
            plugin.getShopGUI().open(p);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            // /shop reload → 重载配置 (OP)
            if (!p.hasPermission("economy.admin")) {
                p.sendMessage("§c你没有权限");
                return true;
            }
            plugin.reload();
            p.sendMessage("§a✓ 已重载商店配置和价格");
            return true;
        }

        // 默认打开GUI
        plugin.getShopGUI().open(p);
        return true;
    }
}

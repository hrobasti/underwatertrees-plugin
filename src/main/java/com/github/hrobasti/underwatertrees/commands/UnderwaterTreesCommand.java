package com.github.hrobasti.underwatertrees.commands;

import com.github.hrobasti.underwatertrees.UnderwaterTreesPlugin;
import com.github.hrobasti.turtlelib.helper.MessageService;
import com.github.hrobasti.underwatertrees.listeners.UnderwaterSaplingsListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class UnderwaterTreesCommand implements CommandExecutor {
    private final UnderwaterTreesPlugin plugin;
    private final UnderwaterSaplingsListener listener;

    public UnderwaterTreesCommand(UnderwaterTreesPlugin plugin, UnderwaterSaplingsListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageService msg = plugin.getMessages();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("underwatertrees.admin")) {
                sender.sendMessage(msg.component("command.no-permission"));
                return true;
            }
            plugin.reloadConfig();
            plugin.ensureConfigDefaults();
            listener.applyConfig(plugin.getConfig());
            plugin.reloadMessages();
            MessageService msg2 = plugin.getMessages();
            sender.sendMessage(msg2.component("command.reloaded"));
            return true;
        }

        sender.sendMessage(msg.format("command.usage", java.util.Map.of("label", label)));
        return true;
    }
}


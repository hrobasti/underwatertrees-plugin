package com.hro_basti.underwatertrees.commands;

import com.hro_basti.underwatertrees.Plugin;
import com.hro_basti.underwatertrees.i18n.Messages;
import com.hro_basti.underwatertrees.listeners.UnderwaterSaplingsListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class UnderwaterTreesCommand implements CommandExecutor {
    private final Plugin plugin;
    private final UnderwaterSaplingsListener listener;

    public UnderwaterTreesCommand(Plugin plugin, UnderwaterSaplingsListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages msg = plugin.getMessages();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("underwatertrees.reload")) {
                sender.sendMessage(Component.text(msg.get("command.no_permission")).color(NamedTextColor.RED));
                return true;
            }
            plugin.reloadConfig();
            plugin.ensureConfigDefaults();
            listener.applyConfig(plugin.getConfig());
            plugin.reloadMessages();
            // Auto-reload flag may have changed; apply update
            // (Handled inside reloadMessages via updateAutoReloadFlag)
            Messages msg2 = plugin.getMessages();
            sender.sendMessage(Component.text(msg2.get("command.reloaded")).color(NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text(msg.get("command.usage", java.util.Map.of("label", label))).color(NamedTextColor.YELLOW));
        return true;
    }
}

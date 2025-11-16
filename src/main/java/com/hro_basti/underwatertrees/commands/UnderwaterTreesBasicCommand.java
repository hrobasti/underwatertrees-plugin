package com.hro_basti.underwatertrees.commands;

import com.hro_basti.underwatertrees.Plugin;
import com.hro_basti.underwatertrees.i18n.Messages;
import com.hro_basti.underwatertrees.listeners.UnderwaterSaplingsListener;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

public class UnderwaterTreesBasicCommand implements BasicCommand {
    private final Plugin plugin;
    private final UnderwaterSaplingsListener listener;

    public UnderwaterTreesBasicCommand(Plugin plugin, UnderwaterSaplingsListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        Messages msg = plugin.getMessages();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!source.getSender().hasPermission("underwatertrees.reload")) {
                source.getSender().sendMessage(Component.text(msg.get("command.no_permission")).color(NamedTextColor.RED));
                return;
            }
            plugin.reloadConfig();
            plugin.ensureConfigDefaults();
            listener.applyConfig(plugin.getConfig());
            plugin.reloadMessages();
            Messages msg2 = plugin.getMessages();
            source.getSender().sendMessage(Component.text(msg2.get("command.reloaded")).color(NamedTextColor.GREEN));
            return;
        }
        source.getSender().sendMessage(Component.text(msg.get("command.usage", java.util.Map.of("label", "underwatertrees"))).color(NamedTextColor.YELLOW));
    }

    @Override
    public @Nullable String permission() {
        return "underwatertrees.reload";
    }
}

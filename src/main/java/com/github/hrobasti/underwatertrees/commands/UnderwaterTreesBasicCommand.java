package com.github.hrobasti.underwatertrees.commands;

import com.github.hrobasti.underwatertrees.UnderwaterTreesPlugin;
import com.github.hrobasti.turtlelib.helper.MessageService;
import com.github.hrobasti.underwatertrees.listeners.UnderwaterSaplingsListener;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public class UnderwaterTreesBasicCommand implements BasicCommand {
    private final UnderwaterTreesPlugin plugin;
    private final UnderwaterSaplingsListener listener;

    public UnderwaterTreesBasicCommand(UnderwaterTreesPlugin plugin, UnderwaterSaplingsListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        MessageService msg = plugin.getMessages();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!source.getSender().hasPermission("underwatertrees.admin")) {
                source.getSender().sendMessage(msg.component("command.no-permission"));
                return;
            }
            plugin.reloadConfig();
            plugin.ensureConfigDefaults();
            listener.applyConfig(plugin.getConfig());
            plugin.reloadMessages();
            MessageService msg2 = plugin.getMessages();
            source.getSender().sendMessage(msg2.component("command.reloaded"));
            return;
        }
        source.getSender().sendMessage(msg.format("command.usage", java.util.Map.of("label", "underwatertrees")));
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1 && source.getSender().hasPermission("underwatertrees.admin")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return "reload".startsWith(prefix) ? Collections.singletonList("reload") : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    @Override
    public @Nullable String permission() {
        return "underwatertrees.admin";
    }
}


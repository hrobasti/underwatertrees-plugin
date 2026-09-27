package net.kroet.underwatertrees.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kroet.underwatertrees.UnderwaterTreesPlugin;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

public class UnderwaterTreesBasicCommand implements BasicCommand {
    private static final String ADMIN_PERMISSION = "underwatertrees.admin";

    private final UnderwaterTreesPlugin plugin;
    // Paper doesn't pass the typed alias to a BasicCommand, so each registered
    // name gets its own instance.
    private final String label;

    public UnderwaterTreesBasicCommand(UnderwaterTreesPlugin plugin, String label) {
        this.plugin = plugin;
        this.label = label;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String subcommand = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "reload" -> handleReload(sender);
            case "version" -> handleVersion(sender);
            default -> sendUsage(sender);
        }
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.getMessages().component("command.no_permission"));
            return;
        }
        String configError = plugin.reloadAndMergeConfig();
        if (configError != null) {
            sender.sendMessage(plugin.getMessages().format("command.reload_failed", Map.of("error", configError)));
            return;
        }
        sender.sendMessage(plugin.getMessages().component("command.reloaded"));
    }

    private void handleVersion(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().format("command.version",
                Map.of("current_ver", plugin.getPluginMeta().getVersion())));
    }

    // Lists only the subcommands this sender may use.
    private void sendUsage(CommandSender sender) {
        List<String> subcommands = new ArrayList<>();
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            subcommands.add("reload");
        }
        subcommands.add("version");
        sender.sendMessage(plugin.getMessages().format("command.usage",
                Map.of("label", label, "subcommands", String.join("|", subcommands))));
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        options.add("version");
        if (source.getSender().hasPermission(ADMIN_PERMISSION)) {
            options.add("reload");
        }
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }

    @Override
    public @Nullable String permission() {
        return null; // checked per subcommand
    }
}

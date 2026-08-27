package fr.loris.servermenu;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.DialogKeys;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import io.papermc.paper.tag.TagEntry;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ServerMenuBootstrap implements PluginBootstrap {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Key MAIN_KEY_RAW = Key.key("servermenu:main");
    private static final TypedKey<Dialog> MAIN_KEY = DialogKeys.create(MAIN_KEY_RAW);

    /** dialogId -> buttonId -> (permission required, actual command to run once granted). */
    private final java.util.Map<String, java.util.Map<String, ButtonRuntime>> buttonRuntimes = new java.util.HashMap<>();

    private record ButtonRuntime(String permission, String commandToRun) {}

    /** Matches {key} tokens in a command, used for input substitution (e.g. "pay {target} {amount}"). */
    private static final java.util.regex.Pattern INPUT_TOKEN = java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_-]+)\\}");

    /**
     * Resolves a system message from config.yml's "messages:" section (MiniMessage,
     * with optional named placeholders via resolvers), falling back to the given
     * default if not configured or if parsing fails.
     */
    private Component message(String key, String fallback,
                               net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        String raw = config != null ? config.getString("messages." + key, fallback) : fallback;
        try {
            return MINI_MESSAGE.deserialize(raw, resolvers);
        } catch (RuntimeException ex) {
            return Component.text(raw);
        }
    }

    /** Kept after bootstrap() so dialogs can be rebuilt per-player (for PlaceholderAPI) when opened via command. */
    private YamlConfiguration config;
    /** Kept so /servermenu reload can re-read config.yml without needing a fresh BootstrapContext. */
    private Path configFile;

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        this.configFile = context.getDataDirectory().resolve("config.yml");
        ensureDefaultConfig(configFile);
        this.config = YamlConfiguration.loadConfiguration(configFile.toFile());
        YamlConfiguration config = this.config;

        context.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose(), event -> {
            ConfigurationSection dialogs = config.getConfigurationSection("dialogs");
            if (dialogs != null) {
                for (String id : dialogs.getKeys(false)) {
                    ConfigurationSection section = dialogs.getConfigurationSection(id);
                    if (section == null || !validId(id)) continue;
                    Key raw = Key.key("servermenu:" + id);
                    TypedKey<Dialog> key = DialogKeys.create(raw);
                    event.registry().register(key, builder -> builder
                            .base(createBase(section, id, null))
                            .type(createType(section, id, null)));
                }
            }

            // Backwards-compatible main dialog if no explicit dialogs.main exists.
            if (dialogs == null || !dialogs.isConfigurationSection("main")) {
                event.registry().register(MAIN_KEY, builder -> builder
                        .base(createBase(config, "main", null))
                        .type(createTypeFromButtons(config.getConfigurationSection("buttons"), config, "main", null)));
            }
        });

        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.TAGS.preFlatten(RegistryKey.DIALOG), event ->
                        event.registrar().addToTag(
                                DialogTagKeys.PAUSE_SCREEN_ADDITIONS,
                                List.of(TagEntry.valueEntry(MAIN_KEY))));

        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        Commands.literal("servermenu")
                                .then(Commands.literal("open")
                                        .executes(ctx -> runOpen(ctx.getSource(), "main"))
                                        .then(Commands.argument("dialog", StringArgumentType.word())
                                                .suggests(this::suggestDialogIds)
                                                .executes(ctx -> runOpen(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "dialog")))))
                                .then(Commands.literal("reload")
                                        .executes(this::runReload))
                                .then(Commands.literal("click")
                                        .then(Commands.argument("dialog", StringArgumentType.word())
                                                .then(Commands.argument("button", StringArgumentType.word())
                                                        .executes(ctx -> runClick(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "dialog"),
                                                                StringArgumentType.getString(ctx, "button"))))))
                                .executes(ctx -> runOpen(ctx.getSource(), "main"))
                                .build(),
                        "Open ServerMenu dialogs",
                        List.of("smenu")));
    }

    private int runOpen(CommandSourceStack source, String id) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only", "<red>Only players can use this command.</red>"));
            return 0;
        }
        if (!validId(id)) {
            player.sendMessage(message("invalid-dialog", "<red>Invalid dialog name.</red>"));
            return 0;
        }

        Dialog dialog = buildDialogForPlayer(id, player);
        if (dialog == null) {
            player.sendMessage(message("unknown-dialog", "<red>Unknown ServerMenu dialog: <white><dialog></white></red>",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("dialog", id)));
            return 0;
        }
        player.showDialog(dialog);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Tab-completion for /servermenu open <dialog>: suggests every dialog id
     * declared under "dialogs" in config.yml, plus "main" (always valid, even
     * when using the legacy top-level "buttons" section instead of dialogs.main).
     */
    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestDialogIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        java.util.Set<String> ids = new java.util.TreeSet<>();
        ids.add("main");
        ConfigurationSection dialogs = config != null ? config.getConfigurationSection("dialogs") : null;
        if (dialogs != null) ids.addAll(dialogs.getKeys(false));

        String remaining = builder.getRemaining().toLowerCase();
        for (String id : ids) {
            if (id.toLowerCase().startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    /**
     * Rebuilds the given dialog on demand for a specific player, resolving
     * PlaceholderAPI placeholders in titles/names/tooltips for that player.
     * This is what /servermenu open goes through, so nested menus opened via
     * dialog/back buttons always show up-to-date placeholders. The very
     * first pause-screen dialog (servermenu:main) is still shown from the
     * static registry entry by vanilla client behavior and does not go
     * through this path — see AI_HANDOFF.md.
     */
    private Dialog buildDialogForPlayer(String id, Player player) {
        ConfigurationSection dialogs = config.getConfigurationSection("dialogs");
        ConfigurationSection section = dialogs != null ? dialogs.getConfigurationSection(id) : null;

        if (section != null) {
            DialogBase base = createBase(section, id, player);
            DialogType type = createType(section, id, player);
            return Dialog.create(builder -> builder.empty().base(base).type(type));
        }

        if (id.equals("main")) {
            DialogBase base = createBase(config, "main", player);
            DialogType type = createTypeFromButtons(config.getConfigurationSection("buttons"), config, "main", player);
            return Dialog.create(builder -> builder.empty().base(base).type(type));
        }

        return null;
    }

    /**
     * Re-reads config.yml from disk into memory. This is safe and takes effect
     * immediately for anything opened via /servermenu open (all submenus,
     * since those are rebuilt fresh from `config` on every open — see
     * buildDialogForPlayer). It does NOT affect the dialogs registered in the
     * static DIALOG registry at bootstrap (dialogs.main and its pause-screen
     * entry point in particular) — Paper has no public API to re-register
     * those at runtime, so a full restart is still required for changes to
     * dialogs.main.
     */
    private int runReload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("servermenu.reload")) {
            sender.sendMessage(message("no-permission-reload", "<red>No permission.</red>"));
            return 0;
        }
        try {
            this.config = YamlConfiguration.loadConfiguration(configFile.toFile());
        } catch (Exception ex) {
            sender.sendMessage(message("reload-failed",
                    "<red>Failed to reload config.yml: <white><error></white></red>",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                            "error", String.valueOf(ex.getMessage()))));
            return 0;
        }
        sender.sendMessage(message("reload-notice",
                "<yellow>Config reloaded — submenus opened via /servermenu open now use it. "
                        + "The pause-menu main dialog still needs a full restart for its own changes.</yellow>"));
        return Command.SINGLE_SUCCESS;
    }

    private int runClick(CommandSourceStack source, String dialogId, String buttonId) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only", "<red>Only players can use this command.</red>"));
            return 0;
        }
        java.util.Map<String, ButtonRuntime> dialogButtons = buttonRuntimes.get(dialogId);
        ButtonRuntime runtime = dialogButtons == null ? null : dialogButtons.get(buttonId);
        if (runtime == null) {
            player.sendMessage(message("unknown-button", "<red>Unknown ServerMenu button.</red>"));
            return 0;
        }
        if (!player.hasPermission(runtime.permission())) {
            player.sendMessage(message("no-permission-button", "<red>You don't have permission to use this button.</red>"));
            return 0;
        }
        String command = runtime.commandToRun();
        player.performCommand(command.startsWith("/") ? command.substring(1) : command);
        return Command.SINGLE_SUCCESS;
    }

    private DialogBase createBase(YamlConfiguration config, String id, Player player) {
        return createBase((ConfigurationSection) config, id, player);
    }

    private DialogBase createBase(ConfigurationSection section, String id, Player player) {
        String title = section.getString("title", id.equals("main") ? "Server Menu" : id);
        String externalTitle = section.getString("external-title", title);
        DialogBase.Builder baseBuilder = DialogBase.builder(parseText(title, player))
                .externalTitle(parseText(externalTitle, player))
                .canCloseWithEscape(section.getBoolean("can-close-with-escape", true));

        List<DialogBody> body = createBody(section, player);
        if (!body.isEmpty()) {
            baseBuilder.body(body);
        }

        List<DialogInput> inputs = createInputs(section, player);
        if (!inputs.isEmpty()) {
            baseBuilder.inputs(inputs);
        }
        return baseBuilder.build();
    }

    /**
     * Builds body entries from a dialog's optional "body:" list. Each entry is
     * either a plain MiniMessage string (a text line, placeholders resolved via
     * PlaceholderAPI first):
     *   body:
     *     - "<gold>Top 5 Money</gold>"
     *     - "<yellow>1. %economy_top_money_1_name%</yellow>"
     * ...or a map for a Minecraft item icon:
     *     - {type: item, material: DIAMOND, description: "<gray>Top reward</gray>"}
     *   Optional on the item map: width/height (1-256, default 16),
     *   show-decorations (default true), show-tooltip (default true).
     */
    private List<DialogBody> createBody(ConfigurationSection section, Player player) {
        List<DialogBody> lines = new ArrayList<>();
        List<?> raw = section.getList("body");
        if (raw == null) return lines;

        for (Object entry : raw) {
            if (entry instanceof String text) {
                lines.add(DialogBody.plainMessage(parseText(text, player)));
            } else if (entry instanceof java.util.Map<?, ?> map) {
                DialogBody item = createItemBody(map, player);
                if (item != null) lines.add(item);
            }
        }
        return lines;
    }

    private DialogBody createItemBody(java.util.Map<?, ?> map, Player player) {
        Object typeValue = map.get("type");
        if (typeValue == null || !"item".equalsIgnoreCase(typeValue.toString())) return null;

        Object materialValue = map.get("material");
        if (materialValue == null) return null;
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialValue.toString());
        if (material == null) return null;

        org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(material);

        Object descriptionValue = map.get("description");
        io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody description = descriptionValue == null
                ? null
                : DialogBody.plainMessage(parseText(descriptionValue.toString(), player));

        boolean showDecorations = mapBoolean(map.get("show-decorations"), true);
        boolean showTooltip = mapBoolean(map.get("show-tooltip"), true);
        int width = clamp(mapInt(map.get("width"), 16), 1, 256);
        int height = clamp(mapInt(map.get("height"), 16), 1, 256);

        return DialogBody.item(stack, description, showDecorations, showTooltip, width, height);
    }

    private boolean mapBoolean(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private int mapInt(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    /**
     * Builds DialogInput fields from a dialog's optional "inputs:" section.
     * Currently only text inputs are supported:
     *   inputs:
     *     target: {type: text, label: "<aqua>Player Name</aqua>"}
     * The key ("target") is what buttons reference as {target} in their command.
     */
    private List<DialogInput> createInputs(ConfigurationSection section, Player player) {
        List<DialogInput> inputs = new ArrayList<>();
        ConfigurationSection inputsSection = section.getConfigurationSection("inputs");
        if (inputsSection == null) return inputs;

        for (String key : inputsSection.getKeys(false)) {
            ConfigurationSection input = inputsSection.getConfigurationSection(key);
            if (input == null || !validId(key)) continue;

            String inputType = input.getString("type", "text").toLowerCase();
            String label = input.getString("label", key);
            int width = clamp(input.getInt("width", 200), 1, 1024);

            if (inputType.equals("text")) {
                inputs.add(DialogInput.text(key, parseText(label, player))
                        .width(width)
                        .build());
            }
            // Other input types (bool, number-range, single-option) aren't wired up yet.
        }
        return inputs;
    }

    private DialogType createType(ConfigurationSection section, String dialogId, Player player) {
        return createTypeFromButtons(section.getConfigurationSection("buttons"), section, dialogId, player);
    }

    private DialogType createTypeFromButtons(ConfigurationSection section, ConfigurationSection parent, String dialogId, Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        int width = clamp(parent.getInt("button-width", 320), 1, 1024);
        int columns = clamp(parent.getInt("columns", 2), 1, 16);

        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection button = section.getConfigurationSection(id);
                if (button == null) continue;

                String name = button.getString("name", id);
                String tooltip = button.getString("tooltip", "");
                String type = button.getString("type", "command").toLowerCase();
                String permission = button.getString("permission", "").trim();
                DialogAction action = createAction(type, button, dialogId, id, permission, player);

                // A null action is allowed by the API; the button simply closes the dialog.
                buttons.add(ActionButton.create(
                        parseText(name, player),
                        tooltip.isBlank() ? null : parseText(tooltip, player),
                        width,
                        action));
            }
        }

        buttons.addAll(createDynamicButtons(parent, width, player));
        return DialogType.multiAction(buttons, null, columns);
    }

    /**
     * Generates one button per item in a PlaceholderAPI list placeholder, from
     * an optional "dynamic-buttons:" section on the dialog:
     *   dynamic-buttons:
     *     source: "%essentials_homes_list%"   # a PAPI placeholder returning a
     *                                          # delimited list, e.g. "base,mine,farm"
     *     separator: ","                       # default ","
     *     name: "{value}"                      # button label, {value} = list item
     *     tooltip: "Teleport to {value}"        # optional
     *     command: "home {value}"               # command to run, {value} substituted
     *     max: 20                               # safety cap on number of buttons
     *     button-width: 150                     # optional, falls back to the dialog's button-width
     * LIMITATION: needs a player to resolve the placeholder, so this only
     * works for dialogs opened via /servermenu open (same restriction as
     * {key} input buttons — doesn't work on the static dialogs.main).
     */
    private List<ActionButton> createDynamicButtons(ConfigurationSection parent, int defaultWidth, Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        ConfigurationSection dyn = parent.getConfigurationSection("dynamic-buttons");
        if (dyn == null || player == null) return buttons;

        String source = dyn.getString("source", "").trim();
        if (source.isBlank()) return buttons;

        String resolved = applyPlaceholders(source, player);
        if (resolved.equals(source)) {
            // Placeholder didn't resolve (PAPI missing, expansion not installed, or
            // the placeholder itself is unknown) — nothing safe to build buttons from.
            return buttons;
        }

        String separator = dyn.getString("separator", ",");
        String nameTemplate = dyn.getString("name", "{value}");
        String tooltipTemplate = dyn.getString("tooltip", "");
        String commandTemplate = dyn.getString("command", "").trim();
        int width = clamp(dyn.getInt("button-width", defaultWidth), 1, 1024);
        int max = clamp(dyn.getInt("max", 20), 1, 100);

        int count = 0;
        for (String rawValue : resolved.split(java.util.regex.Pattern.quote(separator))) {
            String value = rawValue.trim();
            if (value.isEmpty() || count >= max) continue;
            count++;

            String name = nameTemplate.replace("{value}", value);
            String tooltip = tooltipTemplate.replace("{value}", value);
            String command = commandTemplate.replace("{value}", value);
            if (command.startsWith("/")) command = command.substring(1);

            DialogAction action = command.isBlank() ? null
                    : DialogAction.staticAction(ClickEvent.runCommand("/" + command));

            buttons.add(ActionButton.create(
                    parseText(name, player),
                    tooltip.isBlank() ? null : parseText(tooltip, player),
                    width,
                    action));
        }
        return buttons;
    }


    /**
     * Resolves PlaceholderAPI placeholders (if the plugin is present) for the
     * given player, then parses the result as MiniMessage (gradients, hex
     * colors, formatting tags). Player may be null (e.g. during bootstrap
     * registry building) — global/non-player placeholders still resolve then.
     */
    private Component parseText(String text, Player player) {
        if (text == null || text.isEmpty()) return Component.empty();
        String resolved = applyPlaceholders(text, player);
        try {
            return MINI_MESSAGE.deserialize(resolved);
        } catch (RuntimeException ex) {
            // Keep the server alive if a config contains malformed MiniMessage.
            return Component.text(resolved);
        }
    }

    private String applyPlaceholders(String text, Player player) {
        // During the bootstrap phase (registry building), Bukkit.getServer() isn't
        // initialized yet — Bukkit.getPluginManager() itself throws an NPE, not just
        // PlaceholderAPI calls. Guard the whole thing so registry building never crashes;
        // placeholders just resolve as plain text until things are called post-startup
        // (e.g. from /servermenu open, which always runs after the server is fully up).
        try {
            if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                return text;
            }
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }

    private DialogAction createAction(String type, ConfigurationSection button, String dialogId, String buttonId, String permission, Player player) {
        return switch (type) {
            case "dialog" -> {
                String target = button.getString("dialog", "").trim();
                if (!validId(target)) yield null;
                yield DialogAction.staticAction(ClickEvent.runCommand(
                        gate("/servermenu open " + target, dialogId, buttonId, permission)));
            }
            case "back" -> {
                String target = button.getString("dialog", "main").trim();
                if (!validId(target)) target = "main";
                yield DialogAction.staticAction(ClickEvent.runCommand(
                        gate("/servermenu open " + target, dialogId, buttonId, permission)));
            }
            case "close" -> null;
            case "command" -> {
                String command = button.getString("command", "").trim();
                if (command.startsWith("/")) command = command.substring(1);
                if (command.isBlank()) yield null;

                if (INPUT_TOKEN.matcher(command).find()) {
                    yield createInputCommandAction(command, permission, player);
                }

                yield DialogAction.staticAction(ClickEvent.runCommand(
                        gate("/" + command, dialogId, buttonId, permission)));
            }
            default -> null;
        };
    }

    /**
     * Builds an action for a command containing {key} tokens (e.g. "pay {target} {amount}").
     * This needs the dialog's DialogResponseView (input values), which is only available
     * through a server-side customClick callback, not a plain ClickEvent.runCommand — so
     * permission checking happens inline here too, instead of the /servermenu click redirect
     * used for plain buttons.
     *
     * IMPORTANT: this only works for dialogs opened dynamically via /servermenu open (player
     * != null), because each open rebuilds a fresh Dialog/callback for that single showing.
     * The bootstrap-time static registry copy (player == null, shared forever) can't safely
     * host a single-use callback, so such buttons are disabled (no-op) there — see
     * AI_HANDOFF.md. In practice this means: don't put {key} buttons on dialogs.main.
     */
    private DialogAction createInputCommandAction(String command, String permission, Player player) {
        if (player == null) return null;
        String template = command;
        return DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player clicker)) return;
            if (!permission.isBlank() && !clicker.hasPermission(permission)) {
                clicker.sendMessage(message("no-permission-button", "<red>You don't have permission to use this button.</red>"));
                return;
            }
            clicker.performCommand(substituteInputs(template, view));
        }, ClickCallback.Options.builder().build());
    }

    /** Replaces every {key} token with the matching text input's value from the dialog response. */
    private String substituteInputs(String template, DialogResponseView view) {
        java.util.regex.Matcher matcher = INPUT_TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value;
            try {
                value = view.getText(key);
            } catch (RuntimeException ex) {
                value = null;
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value != null ? value : ""));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * If a button declares a permission, its click is redirected through
     * /servermenu click <dialog> <button>, which checks the permission at
     * runtime and only then runs the real command. Buttons without a
     * permission keep running their command directly (unchanged behavior).
     */
    private String gate(String actualCommand, String dialogId, String buttonId, String permission) {
        if (permission.isBlank()) return actualCommand;
        buttonRuntimes
                .computeIfAbsent(dialogId, k -> new java.util.HashMap<>())
                .put(buttonId, new ButtonRuntime(permission, actualCommand));
        return "/servermenu click " + dialogId + " " + buttonId;
    }

    private boolean validId(String id) {
        return id != null && id.matches("[a-zA-Z0-9_-]+");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void ensureDefaultConfig(Path configFile) {
        try {
            if (Files.exists(configFile)) return;
            Files.createDirectories(configFile.getParent());
            try (InputStream in = ServerMenuBootstrap.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) throw new IOException("Bundled config.yml is missing");
                Files.copy(in, configFile);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create ServerMenu config.yml", ex);
        }
    }

    @Override
    public @NotNull org.bukkit.plugin.java.JavaPlugin createPlugin(
            @NotNull io.papermc.paper.plugin.bootstrap.PluginProviderContext context) {
        return new ServerMenu();
    }
}

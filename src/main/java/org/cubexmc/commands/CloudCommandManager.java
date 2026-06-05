package org.cubexmc.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.cubexmc.RuleGems;
import org.cubexmc.commands.registrar.AdminCommandsRegistrar;
import org.cubexmc.commands.registrar.AppointCommandsRegistrar;
import org.cubexmc.commands.registrar.CommandRegistrar;
import org.cubexmc.commands.registrar.GuiCommandsRegistrar;
import org.cubexmc.commands.registrar.InfoCommandsRegistrar;
import org.cubexmc.commands.registrar.RedeemCommandsRegistrar;
import org.cubexmc.commands.registrar.SuggestionProviders;
import org.cubexmc.gui.GUIManager;
import org.cubexmc.manager.GameplayConfig;
import org.cubexmc.manager.GemManager;
import org.cubexmc.manager.LanguageManager;
import org.cubexmc.manager.RuleGemsDoctor;

/**
 * Registers all /rulegems sub-commands via the Incendo Cloud 2.0 framework.
 * Command registration is delegated to individual Registrar classes.
 */
public class CloudCommandManager {

    private final RuleGems plugin;
    private final GemManager gemManager;
    private final GameplayConfig gameplayConfig;
    private final LanguageManager languageManager;
    private final GUIManager guiManager;
    private final SuggestionProviders suggestionProviders;

    private final List<CommandRegistrar> registrars = new ArrayList<>();

    public CloudCommandManager(RuleGems plugin, GemManager gemManager, GameplayConfig gameplayConfig,
            LanguageManager languageManager, GUIManager guiManager) {
        this.plugin = plugin;
        this.gemManager = gemManager;
        this.gameplayConfig = gameplayConfig;
        this.languageManager = languageManager;
        this.guiManager = guiManager;
        this.suggestionProviders = new SuggestionProviders(plugin);

        registrars.add(new GuiCommandsRegistrar(plugin, guiManager, languageManager));
        registrars.add(new InfoCommandsRegistrar(plugin, gemManager, guiManager, gameplayConfig, languageManager));
        registrars.add(new RedeemCommandsRegistrar(gemManager, gameplayConfig, languageManager));
        registrars.add(new AdminCommandsRegistrar(plugin, gemManager, languageManager, suggestionProviders));
        registrars.add(new AppointCommandsRegistrar(plugin, gemManager, languageManager, suggestionProviders));
    }

    public void registerAll() {
        CommandManager<RuleGemsCommandActor> modernManager = tryCreateModernManager();
        if (modernManager != null) {
            configureManager(modernManager);
            plugin.getLogger().info("Using modern Paper command manager for /rulegems.");
            return;
        }

        LegacyPaperCommandManager<RuleGemsCommandActor> legacyManager = tryCreateLegacyManager();
        if (legacyManager != null) {
            configureLegacyCapabilities(legacyManager);
            configureManager(legacyManager);
            plugin.getLogger().info("Using legacy Paper command manager for /rulegems.");
            return;
        }

        plugin.getLogger().warning("Cloud command bootstrap exhausted modern and legacy paths; using Bukkit fallback.");
        registerFallbackExecutor();
    }

    private CommandManager<RuleGemsCommandActor> tryCreateModernManager() {
        if (!isModernPaperApiAvailable()) {
            plugin.getLogger().info("Modern Paper command API not detected; skipping modern Cloud bootstrap.");
            return null;
        }

        try {
            SenderMapper<?, RuleGemsCommandActor> senderMapper = createModernSenderMapper();
            Class<?> managerClass = Class.forName("org.incendo.cloud.paper.PaperCommandManager");
            Method builderMethod = managerClass.getMethod("builder", SenderMapper.class);
            Object builder = builderMethod.invoke(null, senderMapper);
            Method executionCoordinator = builder.getClass()
                    .getMethod("executionCoordinator", ExecutionCoordinator.class);
            Object coordinatedBuilder = executionCoordinator.invoke(builder,
                    ExecutionCoordinator.simpleCoordinator());
            Method buildOnEnable = coordinatedBuilder.getClass().getMethod("buildOnEnable",
                    org.bukkit.plugin.Plugin.class);
            Object manager = buildOnEnable.invoke(coordinatedBuilder, plugin);
            @SuppressWarnings("unchecked")
            CommandManager<RuleGemsCommandActor> castManager = (CommandManager<RuleGemsCommandActor>) manager;
            return castManager;
        } catch (Throwable failure) {
            plugin.getLogger()
                    .warning("Modern Paper command manager initialization failed; trying legacy bootstrap next.");
            diagnoseBootstrapFailure("modern", failure);
            return null;
        }
    }

    private LegacyPaperCommandManager<RuleGemsCommandActor> tryCreateLegacyManager() {
        try {
            return new LegacyPaperCommandManager<>(
                    plugin,
                    ExecutionCoordinator.simpleCoordinator(),
                    SenderMapper.create(RuleGemsCommandActor::legacy, RuleGemsCommandActor::sender));
        } catch (Throwable failure) {
            plugin.getLogger()
                    .warning("Legacy Paper command manager initialization failed; falling back to Bukkit bridge.");
            diagnoseLegacyInitializationFailure(failure);
            return null;
        }
    }

    private void configureLegacyCapabilities(LegacyPaperCommandManager<RuleGemsCommandActor> manager) {
        try {
            if (manager.hasCapability(org.incendo.cloud.bukkit.CloudBukkitCapabilities.BRIGADIER)) {
                manager.registerBrigadier();
            }
            if (manager.hasCapability(org.incendo.cloud.bukkit.CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
                manager.registerAsynchronousCompletions();
            }
        } catch (Exception capabilityFailure) {
            plugin.getLogger()
                    .warning("Legacy Paper command manager initialized, but optional capability setup failed: "
                            + capabilityFailure.getClass().getSimpleName() + " - "
                            + capabilityFailure.getMessage());
        }
    }

    private void configureManager(CommandManager<RuleGemsCommandActor> manager) {
        manager.exceptionController()
                .registerHandler(NoPermissionException.class,
                        ctx -> languageManager.sendMessage(ctx.context().sender().sender(),
                                "command.no_permission"))
                .registerHandler(InvalidSyntaxException.class,
                        ctx -> languageManager.sendMessage(ctx.context().sender().sender(),
                                "command.invalid_syntax"));

        for (CommandRegistrar registrar : registrars) {
            registrar.register(manager);
        }
    }

    private boolean isModernPaperApiAvailable() {
        try {
            Class.forName("io.papermc.paper.command.brigadier.CommandSourceStack");
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return false;
        }
    }

    private SenderMapper<?, RuleGemsCommandActor> createModernSenderMapper() {
        return SenderMapper.create(
                source -> RuleGemsCommandActor.modern(extractBukkitSender(source), source),
                actor -> ((RuleGemsCommandActor) actor).nativeSender());
    }

    private CommandSender extractBukkitSender(Object sourceStack) {
        try {
            Method getSender = sourceStack.getClass().getMethod("getSender");
            Object sender = getSender.invoke(sourceStack);
            if (sender instanceof CommandSender) {
                return (CommandSender) sender;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to unwrap CommandSender from modern Paper source stack", e);
        }
        throw new IllegalStateException("Modern Paper source stack did not provide a Bukkit CommandSender");
    }

    private void diagnoseBootstrapFailure(String path, Throwable original) {
        Throwable failure = unwrapInvocationTarget(original);
        plugin.getLogger().warning("Cloud " + path + " bootstrap failure on server "
                + Bukkit.getServer().getClass().getName());
        logThrowableChain(path, failure);
    }

    private Throwable unwrapInvocationTarget(Throwable original) {
        if (original instanceof InvocationTargetException && original.getCause() != null) {
            return original.getCause();
        }
        return original;
    }

    private void logThrowableChain(String label, Throwable original) {
        Throwable cursor = original;
        int depth = 0;
        while (cursor != null && depth < 5) {
            plugin.getLogger().warning("Cloud " + label + " cause[" + depth + "]: "
                    + cursor.getClass().getName() + " - " + cursor.getMessage());
            cursor = cursor.getCause();
            depth++;
        }
    }

    private void registerFallbackExecutor() {
        try {
            Object server = Bukkit.getServer();
            Method getCommandMap = server.getClass().getDeclaredMethod("getCommandMap");
            getCommandMap.setAccessible(true);
            CommandMap commandMap = (CommandMap) getCommandMap.invoke(server);

            org.bukkit.command.Command fallbackCmd = new org.bukkit.command.Command("rulegems") {
                @Override
                public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                    return executeFallback(sender, args);
                }

                @Override
                public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args)
                        throws IllegalArgumentException {
                    return completeFallback(sender, args);
                }
            };
            fallbackCmd.setAliases(java.util.Arrays.asList("rg"));
            fallbackCmd.setDescription("RuleGems fallback command bridge");

            commandMap.register(plugin.getName(), fallbackCmd);
            plugin.getLogger().warning("Registered Bukkit fallback command bridge for /rulegems dynamically.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register dynamic Bukkit fallback command: " + e.getMessage());
        }
    }

    private boolean executeFallback(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player && guiManager != null) {
                Player player = (Player) sender;
                guiManager.openMainMenu(player, player.hasPermission("rulegems.admin"));
            } else {
                InfoCommandsRegistrar infoReg = (InfoCommandsRegistrar) registrars.get(1);
                infoReg.sendHelp(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] tail = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "help":
                InfoCommandsRegistrar infoReg = (InfoCommandsRegistrar) registrars.get(1);
                infoReg.sendHelp(sender);
                return true;
            case "gui":
            case "menu":
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                if (guiManager != null) {
                    Player player = (Player) sender;
                    guiManager.openMainMenu(player, player.hasPermission("rulegems.admin"));
                }
                return true;
            case "profile":
            case "status":
                if (!requirePermission(sender, "rulegems.profile")) {
                    return true;
                }
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                if (guiManager != null) {
                    guiManager.openProfileGUI((Player) sender);
                }
                return true;
            case "cabinet":
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                openCabinetCompat((Player) sender);
                return true;
            case "rulers":
                if (!requirePermission(sender, "rulegems.rulers")) {
                    return true;
                }
                if (sender instanceof Player && guiManager != null) {
                    guiManager.openRulersGUI((Player) sender, sender.hasPermission("rulegems.admin"));
                } else {
                    Map<UUID, Set<String>> holders = gemManager.getCurrentRulers();
                    if (holders.isEmpty()) {
                        languageManager.sendMessage(sender, "command.no_rulers");
                        return true;
                    }
                    for (Map.Entry<UUID, Set<String>> e : holders.entrySet()) {
                        String name = gemManager.getCachedPlayerName(e.getKey());
                        String extra = e.getValue().contains("ALL") ? "ALL" : String.join(",", e.getValue());
                        Map<String, String> ph = new HashMap<>();
                        ph.put("player", name + " (" + extra + ")");
                        languageManager.sendMessage(sender, "command.rulers_status", ph);
                    }
                }
                return true;
            case "gems":
                if (!requirePermission(sender, "rulegems.gems")) {
                    return true;
                }
                if (sender instanceof Player && guiManager != null) {
                    guiManager.openGemsGUI((Player) sender, sender.hasPermission("rulegems.admin"));
                } else {
                    gemManager.gemStatus(sender);
                }
                return true;
            case "redeem":
                if (!requirePermission(sender, "rulegems.redeem")) {
                    return true;
                }
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                if (!gameplayConfig.isRedeemEnabled()) {
                    languageManager.sendMessage(sender, "command.redeem.disabled");
                    return true;
                }
                return new org.cubexmc.commands.sub.RedeemSubCommand(gemManager, languageManager).execute(sender, tail);
            case "redeemall":
                if (!requirePermission(sender, "rulegems.redeemall")) {
                    return true;
                }
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                if (!gameplayConfig.isFullSetGrantsAllEnabled()) {
                    languageManager.sendMessage(sender, "command.redeemall.disabled");
                    return true;
                }
                boolean ok = gemManager.redeemAll((Player) sender);
                languageManager.sendMessage(sender, ok ? "command.redeemall.success" : "command.redeemall.failed");
                return true;
            case "appoint":
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                return new org.cubexmc.commands.sub.AppointSubCommand(plugin, gemManager, languageManager)
                        .execute(sender, tail);
            case "dismiss":
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                return new org.cubexmc.commands.sub.DismissSubCommand(plugin, gemManager, languageManager)
                        .execute(sender, tail);
            case "appointees":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                return new org.cubexmc.commands.sub.AppointeesSubCommand(plugin, gemManager, languageManager)
                        .execute(sender, tail);
            case "place":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                return new org.cubexmc.commands.sub.PlaceSubCommand(gemManager, languageManager).execute(sender, tail);
            case "tp":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                return new org.cubexmc.commands.sub.TpSubCommand(plugin, gemManager, languageManager)
                        .execute(sender, tail);
            case "revoke":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                return new org.cubexmc.commands.sub.RevokeSubCommand(gemManager, languageManager).execute(sender, tail);
            case "revoke-power":
                if (!requirePermission(sender, "rulegems.revoke")) {
                    return true;
                }
                return new org.cubexmc.commands.sub.RevokePowerSubCommand(plugin, languageManager).execute(sender, tail);
            case "scatter":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                gemManager.scatterGems();
                languageManager.sendMessage(sender, "command.scatter_success");
                return true;
            case "history":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                return new org.cubexmc.commands.sub.HistorySubCommand(plugin, languageManager).execute(sender, tail);
            case "setaltar":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                return new org.cubexmc.commands.sub.SetAltarSubCommand(gemManager, languageManager)
                        .execute(sender, tail);
            case "removealtar":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                if (!(sender instanceof Player)) {
                    languageManager.sendMessage(sender, "command.player_only");
                    return true;
                }
                return new org.cubexmc.commands.sub.RemoveAltarSubCommand(gemManager, languageManager)
                        .execute(sender, tail);
            case "doctor":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                new RuleGemsDoctor(plugin).sendReport(sender);
                return true;
            case "reload":
                if (!requirePermission(sender, "rulegems.admin")) {
                    return true;
                }
                gemManager.saveGemsSync();
                plugin.loadPlugin();
                plugin.refreshAllowedCommandProxies();
                languageManager.sendMessage(sender, "command.reload_success");
                return true;
            default:
                InfoCommandsRegistrar infoReg2 = (InfoCommandsRegistrar) registrars.get(1);
                infoReg2.sendHelp(sender);
                return true;
        }
    }

    private List<String> completeFallback(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> roots = new ArrayList<>(List.of(
                    "help", "gui", "menu", "profile", "status", "cabinet", "gems", "rulers",
                    "redeem", "redeemall", "appoint", "dismiss", "appointees", "place", "tp",
                    "revoke", "revoke-power", "scatter", "history", "setaltar", "removealtar", "doctor", "reload"));
            return roots.stream()
                    .filter(value -> value.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (("appoint".equals(sub) || "dismiss".equals(sub) || "appointees".equals(sub)) && args.length == 2) {
            org.cubexmc.features.appoint.AppointFeature feature = plugin.getFeatureManager() != null
                    ? plugin.getFeatureManager().getAppointFeature()
                    : null;
            if (feature == null) {
                return java.util.Collections.emptyList();
            }
            return feature.getAppointDefinitions().keySet().stream()
                    .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        if (("appoint".equals(sub) || "dismiss".equals(sub)) && args.length == 3) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        if (("setaltar".equals(sub) || "removealtar".equals(sub)) && args.length == 2) {
            return plugin.getGemParser().getGemDefinitions().stream()
                    .map(org.cubexmc.model.GemDefinition::getGemKey)
                    .filter(key -> key != null && key.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        if ("revoke-power".equals(sub) && args.length == 2) {
            List<String> values = new ArrayList<>(List.of("list", "confirm", "cancel"));
            org.cubexmc.features.revoke.RevokeFeature feature = plugin.getFeatureManager() != null
                    ? plugin.getFeatureManager().getRevokeFeature()
                    : null;
            if (feature != null) {
                values.addAll(feature.getRules().keySet());
            }
            return values.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        return java.util.Collections.emptyList();
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (permission == null || permission.isBlank() || sender.hasPermission(permission)) {
            return true;
        }
        languageManager.sendMessage(sender, "command.no_permission");
        return false;
    }

    private void openCabinetCompat(Player player) {
        org.cubexmc.features.appoint.AppointFeature appointFeature = plugin.getFeatureManager() != null
                ? plugin.getFeatureManager().getAppointFeature()
                : null;
        if (appointFeature == null || !appointFeature.isEnabled()) {
            languageManager.sendMessage(player, "command.appoint.disabled");
            return;
        }
        if (guiManager == null || !guiManager.canOpenCabinet(player)) {
            languageManager.sendMessage(player, "command.no_permission");
            return;
        }
        guiManager.openCabinetGUI(player);
    }

    private void diagnoseLegacyInitializationFailure(Throwable original) {
        Throwable failure = unwrapInvocationTarget(original);
        plugin.getLogger().severe(
                "Cloud legacy bootstrap failure on server " + Bukkit.getServer().getClass().getName());
        logThrowableChain("legacy", failure);

        Object server = Bukkit.getServer();
        if (server == null) {
            plugin.getLogger().severe("Bukkit.getServer() returned null during Cloud initialization.");
            return;
        }

        Class<?> serverClass = server.getClass();
        try {
            Method method = serverClass.getDeclaredMethod("getCommandMap");
            method.setAccessible(true);
            Object commandMap = method.invoke(server);
            plugin.getLogger().info("Legacy Cloud preflight: server#getCommandMap() is declared on "
                    + serverClass.getName() + " and returned "
                    + (commandMap != null ? commandMap.getClass().getName() : "null"));
        } catch (Exception reflectiveFailure) {
            plugin.getLogger()
                    .severe("Legacy Cloud preflight failed to access server#getCommandMap() directly: "
                            + reflectiveFailure.getClass().getSimpleName() + " - "
                            + reflectiveFailure.getMessage());
        }

        try {
            CommandMap commandMap = (CommandMap) Bukkit.class.getMethod("getCommandMap").invoke(null);
            plugin.getLogger().info("Bukkit static getCommandMap() succeeded with: "
                    + (commandMap != null ? commandMap.getClass().getName() : "null"));
        } catch (Exception staticFailure) {
            plugin.getLogger()
                    .warning("Bukkit static getCommandMap() also failed: "
                            + staticFailure.getClass().getSimpleName() + " - "
                            + staticFailure.getMessage());
        }

        try {
            Field knownCommands = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommands.setAccessible(true);
            plugin.getLogger().info("SimpleCommandMap.knownCommands field is accessible for fallback diagnostics.");
        } catch (Exception fieldFailure) {
            plugin.getLogger()
                    .warning("SimpleCommandMap.knownCommands reflection failed: "
                            + fieldFailure.getClass().getSimpleName() + " - "
                            + fieldFailure.getMessage());
        }
    }
}

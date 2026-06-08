package org.cubexmc.commands

import org.bukkit.Bukkit
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.SimpleCommandMap
import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.commands.registrar.AdminCommandsRegistrar
import org.cubexmc.commands.registrar.AppointCommandsRegistrar
import org.cubexmc.commands.registrar.CommandRegistrar
import org.cubexmc.commands.registrar.GuiCommandsRegistrar
import org.cubexmc.commands.registrar.InfoCommandsRegistrar
import org.cubexmc.commands.registrar.RedeemCommandsRegistrar
import org.cubexmc.commands.registrar.SuggestionProviders
import org.cubexmc.commands.sub.AppointeesSubCommand
import org.cubexmc.commands.sub.AppointSubCommand
import org.cubexmc.commands.sub.DismissSubCommand
import org.cubexmc.commands.sub.HistorySubCommand
import org.cubexmc.commands.sub.PlaceSubCommand
import org.cubexmc.commands.sub.RedeemSubCommand
import org.cubexmc.commands.sub.RemoveAltarSubCommand
import org.cubexmc.commands.sub.RevokePowerSubCommand
import org.cubexmc.commands.sub.RevokeSubCommand
import org.cubexmc.commands.sub.SetAltarSubCommand
import org.cubexmc.commands.sub.TpSubCommand
import org.cubexmc.gui.GUIManager
import org.cubexmc.manager.GameplayConfig
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import org.cubexmc.manager.RuleGemsDoctor
import org.incendo.cloud.CommandManager
import org.incendo.cloud.SenderMapper
import org.incendo.cloud.exception.InvalidSyntaxException
import org.incendo.cloud.exception.NoPermissionException
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.UUID

/**
 * Registers all /rulegems sub-commands via the Incendo Cloud 2.0 framework.
 * Command registration is delegated to individual Registrar classes.
 */
class CloudCommandManager(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val gameplayConfig: GameplayConfig,
    private val languageManager: LanguageManager,
    private val guiManager: GUIManager?,
) {
    private val suggestionProviders = SuggestionProviders(plugin)
    private val registrars: MutableList<CommandRegistrar> = ArrayList()

    init {
        registrars.add(GuiCommandsRegistrar(plugin, guiManager, languageManager))
        registrars.add(InfoCommandsRegistrar(plugin, gemManager, guiManager, gameplayConfig, languageManager))
        registrars.add(RedeemCommandsRegistrar(gemManager, gameplayConfig, languageManager))
        registrars.add(AdminCommandsRegistrar(plugin, gemManager, languageManager, suggestionProviders))
        registrars.add(AppointCommandsRegistrar(plugin, gemManager, languageManager, suggestionProviders))
    }

    fun registerAll() {
        val modernManager = tryCreateModernManager()
        if (modernManager != null) {
            configureManager(modernManager)
            plugin.logger.info("Using modern Paper command manager for /rulegems.")
            return
        }

        val legacyManager = tryCreateLegacyManager()
        if (legacyManager != null) {
            configureLegacyCapabilities(legacyManager)
            configureManager(legacyManager)
            plugin.logger.info("Using legacy Paper command manager for /rulegems.")
            return
        }

        plugin.logger.warning("Cloud command bootstrap exhausted modern and legacy paths; using Bukkit fallback.")
        registerFallbackExecutor()
    }

    private fun tryCreateModernManager(): CommandManager<RuleGemsCommandActor>? {
        if (!isModernPaperApiAvailable()) {
            plugin.logger.info("Modern Paper command API not detected; skipping modern Cloud bootstrap.")
            return null
        }

        try {
            val senderMapper = createModernSenderMapper()
            val managerClass = Class.forName("org.incendo.cloud.paper.PaperCommandManager")
            val builderMethod = managerClass.getMethod("builder", SenderMapper::class.java)
            val builder = builderMethod.invoke(null, senderMapper)
            val executionCoordinator = builder.javaClass.getMethod("executionCoordinator", ExecutionCoordinator::class.java)
            val coordinatedBuilder = executionCoordinator.invoke(builder, ExecutionCoordinator.simpleCoordinator<RuleGemsCommandActor>())
            val buildOnEnable = coordinatedBuilder.javaClass.getMethod("buildOnEnable", org.bukkit.plugin.Plugin::class.java)
            @Suppress("UNCHECKED_CAST")
            return buildOnEnable.invoke(coordinatedBuilder, plugin) as CommandManager<RuleGemsCommandActor>
        } catch (failure: Throwable) {
            plugin.logger.warning("Modern Paper command manager initialization failed; trying legacy bootstrap next.")
            diagnoseBootstrapFailure("modern", failure)
            return null
        }
    }

    private fun tryCreateLegacyManager(): LegacyPaperCommandManager<RuleGemsCommandActor>? {
        try {
            return LegacyPaperCommandManager(
                plugin,
                ExecutionCoordinator.simpleCoordinator<RuleGemsCommandActor>(),
                SenderMapper.create(
                    { sender -> RuleGemsCommandActor.legacy(sender) },
                    { actor -> actor.sender() },
                ),
            )
        } catch (failure: Throwable) {
            plugin.logger.warning("Legacy Paper command manager initialization failed; falling back to Bukkit bridge.")
            diagnoseLegacyInitializationFailure(failure)
            return null
        }
    }

    private fun configureLegacyCapabilities(manager: LegacyPaperCommandManager<RuleGemsCommandActor>) {
        try {
            if (manager.hasCapability(org.incendo.cloud.bukkit.CloudBukkitCapabilities.BRIGADIER)) {
                manager.registerBrigadier()
            }
            if (manager.hasCapability(org.incendo.cloud.bukkit.CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
                manager.registerAsynchronousCompletions()
            }
        } catch (capabilityFailure: Exception) {
            plugin.logger.warning(
                "Legacy Paper command manager initialized, but optional capability setup failed: " +
                    capabilityFailure.javaClass.simpleName + " - " +
                    capabilityFailure.message,
            )
        }
    }

    private fun configureManager(manager: CommandManager<RuleGemsCommandActor>) {
        manager.exceptionController()
            .registerHandler(
                NoPermissionException::class.java,
            ) { ctx -> languageManager.sendMessage(ctx.context().sender().sender(), "command.no_permission") }
            .registerHandler(
                InvalidSyntaxException::class.java,
            ) { ctx -> languageManager.sendMessage(ctx.context().sender().sender(), "command.invalid_syntax") }

        for (registrar in registrars) {
            registrar.register(manager)
        }
    }

    private fun isModernPaperApiAvailable(): Boolean =
        try {
            Class.forName("io.papermc.paper.command.brigadier.CommandSourceStack")
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: NoClassDefFoundError) {
            false
        }

    private fun createModernSenderMapper(): SenderMapper<*, RuleGemsCommandActor> =
        SenderMapper.create(
            { source -> RuleGemsCommandActor.modern(extractBukkitSender(source), source) },
            { actor -> (actor as RuleGemsCommandActor).nativeSender() },
        )

    private fun extractBukkitSender(sourceStack: Any): CommandSender {
        try {
            val getSender = sourceStack.javaClass.getMethod("getSender")
            val sender = getSender.invoke(sourceStack)
            if (sender is CommandSender) {
                return sender
            }
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Failed to unwrap CommandSender from modern Paper source stack", e)
        }
        throw IllegalStateException("Modern Paper source stack did not provide a Bukkit CommandSender")
    }

    private fun diagnoseBootstrapFailure(path: String, original: Throwable) {
        val failure = unwrapInvocationTarget(original)
        plugin.logger.warning("Cloud $path bootstrap failure on server " + Bukkit.getServer().javaClass.name)
        logThrowableChain(path, failure)
    }

    private fun unwrapInvocationTarget(original: Throwable): Throwable {
        val cause = original.cause
        return if (original is InvocationTargetException && cause != null) cause else original
    }

    private fun logThrowableChain(label: String, original: Throwable) {
        var cursor: Throwable? = original
        var depth = 0
        while (cursor != null && depth < 5) {
            plugin.logger.warning("Cloud $label cause[$depth]: " + cursor.javaClass.name + " - " + cursor.message)
            cursor = cursor.cause
            depth++
        }
    }

    private fun registerFallbackExecutor() {
        try {
            val server = Bukkit.getServer()
            val getCommandMap = server.javaClass.getDeclaredMethod("getCommandMap")
            getCommandMap.isAccessible = true
            val commandMap = getCommandMap.invoke(server) as CommandMap

            val fallbackCommand = object : org.bukkit.command.Command("rulegems") {
                override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean =
                    executeFallback(sender, args)

                override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> =
                    completeFallback(sender, args)
            }
            fallbackCommand.aliases = listOf("rg")
            fallbackCommand.description = "RuleGems fallback command bridge"

            commandMap.register(plugin.name, fallbackCommand)
            plugin.logger.warning("Registered Bukkit fallback command bridge for /rulegems dynamically.")
        } catch (e: Exception) {
            plugin.logger.severe("Failed to register dynamic Bukkit fallback command: " + e.message)
        }
    }

    private fun executeFallback(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player && guiManager != null) {
                guiManager.openMainMenu(sender, sender.hasPermission("rulegems.admin"))
            } else {
                val infoRegistrar = registrars[1] as InfoCommandsRegistrar
                infoRegistrar.sendHelp(sender)
            }
            return true
        }

        val sub = args[0].lowercase(Locale.ROOT)
        val tail = args.copyOfRange(1, args.size)

        when (sub) {
            "help" -> {
                val infoRegistrar = registrars[1] as InfoCommandsRegistrar
                infoRegistrar.sendHelp(sender)
                return true
            }
            "gui", "menu" -> {
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                guiManager?.openMainMenu(sender, sender.hasPermission("rulegems.admin"))
                return true
            }
            "profile", "status" -> {
                if (!requirePermission(sender, "rulegems.profile")) return true
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                guiManager?.openProfileGUI(sender)
                return true
            }
            "cabinet" -> {
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                openCabinetCompat(sender)
                return true
            }
            "rulers" -> {
                if (!requirePermission(sender, "rulegems.rulers")) return true
                if (sender is Player && guiManager != null) {
                    guiManager.openRulersGUI(sender, sender.hasPermission("rulegems.admin"))
                } else {
                    val holders: Map<UUID, Set<String>> = gemManager.currentRulers
                    if (holders.isEmpty()) {
                        languageManager.sendMessage(sender, "command.no_rulers")
                        return true
                    }
                    for ((uuid, keys) in holders) {
                        val name = gemManager.getCachedPlayerName(uuid)
                        val extra = if (keys.contains("ALL")) "ALL" else keys.joinToString(",")
                        val placeholders = HashMap<String, String>()
                        placeholders["player"] = "$name ($extra)"
                        languageManager.sendMessage(sender, "command.rulers_status", placeholders)
                    }
                }
                return true
            }
            "gems" -> {
                if (!requirePermission(sender, "rulegems.gems")) return true
                if (sender is Player && guiManager != null) {
                    guiManager.openGemsGUI(sender, sender.hasPermission("rulegems.admin"))
                } else {
                    gemManager.gemStatus(sender)
                }
                return true
            }
            "redeem" -> {
                if (!requirePermission(sender, "rulegems.redeem")) return true
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                if (!gameplayConfig.isRedeemEnabled) {
                    languageManager.sendMessage(sender, "command.redeem.disabled")
                    return true
                }
                return RedeemSubCommand(gemManager, languageManager).execute(sender, tail)
            }
            "redeemall" -> {
                if (!requirePermission(sender, "rulegems.redeemall")) return true
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                if (!gameplayConfig.isFullSetGrantsAllEnabled) {
                    languageManager.sendMessage(sender, "command.redeemall.disabled")
                    return true
                }
                val ok = gemManager.redeemAll(sender)
                languageManager.sendMessage(sender, if (ok) "command.redeemall.success" else "command.redeemall.failed")
                return true
            }
            "appoint" -> {
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                return AppointSubCommand(plugin, gemManager, languageManager).execute(sender, tail)
            }
            "dismiss" -> {
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                return DismissSubCommand(plugin, gemManager, languageManager).execute(sender, tail)
            }
            "appointees" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                return AppointeesSubCommand(plugin, gemManager, languageManager).execute(sender, tail)
            }
            "place" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                return PlaceSubCommand(gemManager, languageManager).execute(sender, tail)
            }
            "tp" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                return TpSubCommand(plugin, gemManager, languageManager).execute(sender, tail)
            }
            "revoke" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                return RevokeSubCommand(gemManager, languageManager).execute(sender, tail)
            }
            "revoke-power" -> {
                if (!requirePermission(sender, "rulegems.revoke")) return true
                return RevokePowerSubCommand(plugin, languageManager).execute(sender, tail)
            }
            "scatter" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                gemManager.scatterGems()
                languageManager.sendMessage(sender, "command.scatter_success")
                return true
            }
            "history" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                return HistorySubCommand(plugin, languageManager).execute(sender, tail)
            }
            "setaltar" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                return SetAltarSubCommand(gemManager, languageManager).execute(sender, tail)
            }
            "removealtar" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                if (sender !is Player) {
                    languageManager.sendMessage(sender, "command.player_only")
                    return true
                }
                return RemoveAltarSubCommand(gemManager, languageManager).execute(sender, tail)
            }
            "doctor" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                RuleGemsDoctor(plugin).sendReport(sender)
                return true
            }
            "reload" -> {
                if (!requirePermission(sender, "rulegems.admin")) return true
                gemManager.saveGemsSync()
                plugin.loadPlugin()
                plugin.refreshAllowedCommandProxies()
                languageManager.sendMessage(sender, "command.reload_success")
                return true
            }
            else -> {
                val infoRegistrar = registrars[1] as InfoCommandsRegistrar
                infoRegistrar.sendHelp(sender)
                return true
            }
        }
    }

    private fun completeFallback(sender: CommandSender, args: Array<String>): List<String> {
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].lowercase(Locale.ROOT)
            val roots = ArrayList(
                listOf(
                    "help", "gui", "menu", "profile", "status", "cabinet", "gems", "rulers",
                    "redeem", "redeemall", "appoint", "dismiss", "appointees", "place", "tp",
                    "revoke", "revoke-power", "scatter", "history", "setaltar", "removealtar", "doctor", "reload",
                ),
            )
            return roots.filter { value -> value.startsWith(prefix) }
        }

        val sub = args[0].lowercase(Locale.ROOT)
        val prefix = args[args.size - 1].lowercase(Locale.ROOT)
        if ((sub == "appoint" || sub == "dismiss" || sub == "appointees") && args.size == 2) {
            val feature = plugin.featureManager?.appointFeature ?: return emptyList()
            return feature.getAppointDefinitions().keys
                .filter { key -> key.lowercase(Locale.ROOT).startsWith(prefix) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if ((sub == "appoint" || sub == "dismiss") && args.size == 3) {
            return Bukkit.getOnlinePlayers()
                .map { player -> player.name }
                .filter { name -> name.lowercase(Locale.ROOT).startsWith(prefix) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if ((sub == "setaltar" || sub == "removealtar") && args.size == 2) {
            return plugin.gemParser.gemDefinitions
                .map { definition -> definition.gemKey }
                .filter { key -> key.lowercase(Locale.ROOT).startsWith(prefix) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if (sub == "revoke-power" && args.size == 2) {
            val values = ArrayList(listOf("list", "confirm", "cancel"))
            val feature = plugin.featureManager?.revokeFeature
            if (feature != null) {
                values.addAll(feature.rules.keys)
            }
            return values
                .filter { value -> value.lowercase(Locale.ROOT).startsWith(prefix) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        return emptyList()
    }

    private fun requirePermission(sender: CommandSender, permission: String?): Boolean {
        if (permission.isNullOrBlank() || sender.hasPermission(permission)) {
            return true
        }
        languageManager.sendMessage(sender, "command.no_permission")
        return false
    }

    private fun openCabinetCompat(player: Player) {
        val appointFeature = plugin.featureManager?.appointFeature
        if (appointFeature == null || !appointFeature.isEnabled) {
            languageManager.sendMessage(player, "command.appoint.disabled")
            return
        }
        if (guiManager == null || !guiManager.canOpenCabinet(player)) {
            languageManager.sendMessage(player, "command.no_permission")
            return
        }
        guiManager.openCabinetGUI(player)
    }

    private fun diagnoseLegacyInitializationFailure(original: Throwable) {
        val failure = unwrapInvocationTarget(original)
        plugin.logger.severe("Cloud legacy bootstrap failure on server " + Bukkit.getServer().javaClass.name)
        logThrowableChain("legacy", failure)

        val server = Bukkit.getServer()
        val serverClass = server.javaClass
        try {
            val method = serverClass.getDeclaredMethod("getCommandMap")
            method.isAccessible = true
            val commandMap = method.invoke(server)
            plugin.logger.info(
                "Legacy Cloud preflight: server#getCommandMap() is declared on " +
                    serverClass.name + " and returned " +
                    (commandMap?.javaClass?.name ?: "null"),
            )
        } catch (reflectiveFailure: Exception) {
            plugin.logger.severe(
                "Legacy Cloud preflight failed to access server#getCommandMap() directly: " +
                    reflectiveFailure.javaClass.simpleName + " - " +
                    reflectiveFailure.message,
            )
        }

        try {
            val commandMap = Bukkit::class.java.getMethod("getCommandMap").invoke(null) as CommandMap?
            plugin.logger.info("Bukkit static getCommandMap() succeeded with: " + (commandMap?.javaClass?.name ?: "null"))
        } catch (staticFailure: Exception) {
            plugin.logger.warning(
                "Bukkit static getCommandMap() also failed: " +
                    staticFailure.javaClass.simpleName + " - " +
                    staticFailure.message,
            )
        }

        try {
            val knownCommands = SimpleCommandMap::class.java.getDeclaredField("knownCommands")
            knownCommands.isAccessible = true
            plugin.logger.info("SimpleCommandMap.knownCommands field is accessible for fallback diagnostics.")
        } catch (fieldFailure: Exception) {
            plugin.logger.warning(
                "SimpleCommandMap.knownCommands reflection failed: " +
                    fieldFailure.javaClass.simpleName + " - " +
                    fieldFailure.message,
            )
        }
    }
}

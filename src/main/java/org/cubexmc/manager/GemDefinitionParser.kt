package org.cubexmc.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.potion.PotionEffectType
import org.cubexmc.model.AllowedCommand
import org.cubexmc.model.AppointDefinition
import org.cubexmc.model.EffectConfig
import org.cubexmc.model.ExecuteConfig
import org.cubexmc.model.GemDefinition
import org.cubexmc.model.PowerCondition
import org.cubexmc.model.PowerStructure
import org.cubexmc.model.RedeemIngredient
import org.cubexmc.model.RedeemRecipe
import org.cubexmc.model.RedeemRequirements
import org.cubexmc.utils.ColorUtils
import org.cubexmc.utils.ConfigParseUtils.isTrue
import org.cubexmc.utils.ConfigParseUtils.stringOf
import org.cubexmc.utils.ConfigParseUtils.toStringList
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 宝石定义解析器 - 负责从 YAML 配置文件解析 GemDefinition 和 PowerStructure。
 */
class GemDefinitionParser(
    private val logger: Logger,
    private val languageManager: LanguageManager?,
) {
    private val powerTemplates: MutableMap<String, PowerStructure> = HashMap()
    private val rawPowerTemplates: MutableMap<String, Any> = HashMap()

    var gemDefinitions: MutableList<GemDefinition> = ArrayList()
        private set

    var requiredCount: Int = 0
        private set

    fun getPowerTemplate(name: String?): PowerStructure? = powerTemplates[name]

    fun detectLegacySyntax(config: FileConfiguration?, dataFolder: File): List<String> {
        val findings: MutableSet<String> = LinkedHashSet()
        if (config != null) {
            collectLegacyFromMap(config.getValues(false), findings)
            val redeemAll = config.getConfigurationSection("redeem_all")
            if (redeemAll != null) {
                collectLegacyFromMap(redeemAll.getValues(false), findings)
            }
        }
        collectLegacyFromYamlFolder(File(dataFolder, "gems"), findings, true)
        collectLegacyFromYamlFolder(File(dataFolder, "powers"), findings, false)
        return ArrayList(findings)
    }

    fun loadPowerTemplates(dataFolder: File) {
        powerTemplates.clear()
        rawPowerTemplates.clear()

        val powersFolder = File(dataFolder, "powers")
        if (!powersFolder.exists()) {
            powersFolder.mkdirs()
        }

        if (powersFolder.isDirectory) {
            loadPowerTemplatesFromFolder(powersFolder)
        }

        for (key in ArrayList(rawPowerTemplates.keys)) {
            if (!powerTemplates.containsKey(key)) {
                val raw = rawPowerTemplates.remove(key)
                if (raw != null) {
                    powerTemplates[key] = parsePowerStructure(raw)
                }
            }
        }
        rawPowerTemplates.clear()
        logger.info("Loaded " + powerTemplates.size + " power templates.")
    }

    private fun loadPowerTemplatesFromFolder(folder: File) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                loadPowerTemplatesFromFolder(file)
            } else if (file.isFile && file.name.lowercase(Locale.getDefault()).endsWith(".yml")) {
                loadPowerTemplatesFromFile(file)
            }
        }
    }

    private fun loadPowerTemplatesFromFile(file: File) {
        try {
            val config = YamlConfiguration.loadConfiguration(file)
            val templatesSection = config.getConfigurationSection("templates")
            if (templatesSection != null) {
                for (key in templatesSection.getKeys(false)) {
                    val templateObj = templatesSection.get(key)
                    if (templateObj is ConfigurationSection || templateObj is Map<*, *>) {
                        rawPowerTemplates[key] = templateObj
                    }
                }
            } else {
                for (key in config.getKeys(false)) {
                    val templateObj = config.get(key)
                    if (templateObj is ConfigurationSection || templateObj is Map<*, *>) {
                        powerTemplates[key] = parsePowerStructure(templateObj)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("Failed to load power templates from " + file.name + ": " + e.message)
        }
    }

    fun loadGemDefinitions(config: FileConfiguration, dataFolder: File) {
        gemDefinitions.clear()

        val gemsFolder = File(dataFolder, "gems")
        if (gemsFolder.exists() && gemsFolder.isDirectory) {
            loadGemsFromFolder(gemsFolder)
            if (gemDefinitions.isNotEmpty()) {
                logger.info("Loaded " + gemDefinitions.size + " gem definitions from gems folder")
                recalculateRequiredCount()
                return
            }
        }

        val section = config.getConfigurationSection("gems")
        if (section != null) {
            logger.warning("Legacy config format detected! Consider migrating gem configs to gems folder")
            for (key in section.getKeys(false)) {
                val sub = section.getConfigurationSection(key)
                val map: MutableMap<String, Any?> = HashMap()
                if (sub != null) {
                    for (childKey in sub.getKeys(false)) {
                        map[childKey] = sub.get(childKey)
                    }
                }
                val definition = buildGemDefinitionFromMap(key, map)
                gemDefinitions.add(definition)
            }
            recalculateRequiredCount()
            return
        }

        val list = config.getMapList("gems")
        if (list.isNotEmpty()) {
            logger.warning("Legacy list config format detected! Consider migrating gem configs to gems folder")
            var index = 0
            for (map in list) {
                val keyObj = map["key"]
                val key = if (keyObj != null) stringOf(keyObj) else index.toString()
                val definition = buildGemDefinitionFromMap(key ?: index.toString(), map)
                gemDefinitions.add(definition)
                index++
            }
        }
        recalculateRequiredCount()
    }

    private fun recalculateRequiredCount() {
        var total = 0
        for (definition in gemDefinitions) {
            total += definition.count
        }
        requiredCount = maxOf(0, total)
    }

    private fun loadGemsFromFolder(folder: File) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                loadGemsFromFolder(file)
            } else if (file.isFile && file.name.lowercase(Locale.getDefault()).endsWith(".yml")) {
                loadGemsFromFile(file)
            }
        }
    }

    private fun loadGemsFromFile(file: File) {
        try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            for (gemKey in yaml.getKeys(false)) {
                val gemSection = yaml.getConfigurationSection(gemKey) ?: continue
                val gemMap: MutableMap<String, Any?> = HashMap()
                for (key in gemSection.getKeys(false)) {
                    gemMap[key] = gemSection.get(key)
                }
                val definition = buildGemDefinitionFromMap(gemKey, gemMap)
                gemDefinitions.add(definition)
                logger.info("Loaded gem from file " + file.name + ": " + gemKey)
            }
        } catch (e: Exception) {
            logger.severe("Failed to load gem config file " + file.name + ": " + e.message)
            logger.log(Level.SEVERE, "Exception details:", e)
        }
    }

    private fun buildGemDefinitionFromMap(gemKey: String, map: Map<*, *>): GemDefinition {
        val material = parseMaterial(stringOf(map["material"]), Material.RED_STAINED_GLASS)
        val displayName = parseDisplayName(map)
        val particle = parseParticle(stringOf(map["particle"]), Particle.FLAME)
        val sound = parseSound(stringOf(map["sound"]), Sound.ENTITY_EXPERIENCE_ORB_PICKUP)
        val onPickup = parseExecuteConfig(map["on_pickup"])
        val onScatter = parseExecuteConfig(map["on_scatter"])
        val onRedeem = parseExecuteConfig(map["on_redeem"])
        val lore = toStringList(map["lore"])
        val redeemTitle = toStringList(map["redeem_title"])
        val enchanted = parseBooleanLenient(map["enchanted"])
        val mutex = toStringList(map["mutual_exclusive"])
        val count = parseIntSafe(map["count"], 1)
        val range = parseRandomPlaceRange(map, gemKey)
        val altarLocation = parseAltarLocation(map, gemKey)
        val powerStructure = resolveGemPower(map)
        val redeemRequirements = parseRedeemRequirements(map["redeem_requirements"])

        return GemDefinition.Builder(gemKey)
            .material(material)
            .displayName(displayName)
            .particle(particle)
            .sound(sound)
            .onPickup(onPickup)
            .onScatter(onScatter)
            .onRedeem(onRedeem)
            .powerStructure(powerStructure)
            .lore(lore)
            .redeemTitle(redeemTitle)
            .enchanted(enchanted)
            .mutualExclusive(mutex)
            .count(count)
            .randomPlaceCorner1(range[0])
            .randomPlaceCorner2(range[1])
            .altarLocation(altarLocation)
            .redeemRequirements(redeemRequirements)
            .build()
    }

    fun parseRedeemRequirements(obj: Any?): RedeemRequirements {
        val map = asMap(obj) ?: return RedeemRequirements.NONE
        val allowRedeemAll = if (map.containsKey("allow_redeem_all")) {
            parseBooleanLenient(map["allow_redeem_all"])
        } else {
            false
        }
        val anyOf = map["any_of"]
        val recipes: List<RedeemRecipe> = if (anyOf is List<*>) {
            if (hasTopLevelRecipeFields(map)) {
                logger.warning("redeem_requirements mixes any_of with top-level recipe fields; using any_of.")
            }
            val parsed = ArrayList<RedeemRecipe>()
            for (recipeObj in anyOf) {
                val recipe = parseRedeemRecipe(recipeObj)
                if (recipe.hasRequirements()) {
                    parsed.add(recipe)
                }
            }
            parsed
        } else {
            val recipe = parseRedeemRecipe(map)
            if (recipe.hasRequirements()) Collections.singletonList(recipe) else emptyList()
        }
        return if (recipes.isEmpty()) {
            RedeemRequirements(emptyList(), allowRedeemAll, stringOf(map["failure_message"]))
        } else {
            RedeemRequirements(recipes, allowRedeemAll, stringOf(map["failure_message"]))
        }
    }

    private fun hasTopLevelRecipeFields(map: Map<*, *>): Boolean =
        map.containsKey("requires_held") ||
            map.containsKey("requires_redeemed") ||
            map.containsKey("consumes") ||
            map.containsKey("requires_any") ||
            map.containsKey("requires_count") ||
            map.containsKey("requires_count_from")

    private fun parseRedeemRecipe(obj: Any?): RedeemRecipe {
        val map = asMap(obj)
        if (map == null) {
            logger.warning("Invalid redeem_requirements recipe entry; expected map.")
            return RedeemRecipe(emptyList(), emptyList(), emptyList(), emptyList(), 0, emptyList())
        }
        return RedeemRecipe(
            parseRedeemIngredients(map["requires_held"], "requires_held"),
            parseRedeemIngredients(map["consumes"], "consumes"),
            parseRedeemIngredients(map["requires_redeemed"], "requires_redeemed"),
            toStringList(map["requires_any"]),
            parseIntSafe(map["requires_count"], 0),
            toStringList(map["requires_count_from"]),
        )
    }

    private fun parseRedeemIngredients(obj: Any?, fieldName: String): List<RedeemIngredient> {
        if (obj == null) return emptyList()
        val raw: List<*> = if (obj is List<*>) obj else Collections.singletonList(obj)
        val ingredients = ArrayList<RedeemIngredient>()
        for (item in raw) {
            val ingredient = parseRedeemIngredient(item, fieldName)
            if (ingredient != null && ingredient.isValid()) {
                ingredients.add(ingredient)
            }
        }
        return ingredients
    }

    private fun parseRedeemIngredient(obj: Any?, fieldName: String): RedeemIngredient? {
        if (obj is String) {
            val gemKey = obj.trim()
            if (gemKey.isEmpty()) {
                logger.warning("Ignoring empty redeem_requirements.$fieldName ingredient.")
                return null
            }
            return RedeemIngredient(gemKey, 1)
        }
        val map = asMap(obj)
        if (map != null) {
            val gemKey = stringOf(map["gem"])
            if (gemKey.isNullOrBlank()) {
                logger.warning("Ignoring redeem_requirements.$fieldName ingredient without gem key.")
                return null
            }
            var amount = parseIntSafe(map["amount"], 1)
            if (amount <= 0) {
                logger.warning("redeem_requirements.$fieldName amount for '$gemKey' must be positive; using 1.")
                amount = 1
            }
            return RedeemIngredient(gemKey, amount)
        }
        logger.warning("Ignoring invalid redeem_requirements.$fieldName ingredient: $obj")
        return null
    }

    private fun parseMaterial(value: String?, fallback: Material): Material {
        if (value.isNullOrEmpty()) return fallback
        return try {
            Material.valueOf(value.uppercase(Locale.getDefault()))
        } catch (_: Exception) {
            fallback
        }
    }

    private fun parseParticle(value: String?, fallback: Particle): Particle {
        if (value.isNullOrEmpty()) return fallback
        return try {
            Particle.valueOf(value.uppercase(Locale.getDefault()))
        } catch (_: Exception) {
            fallback
        }
    }

    private fun parseSound(value: String?, fallback: Sound): Sound {
        if (value.isNullOrEmpty()) return fallback
        return try {
            Sound.valueOf(value.uppercase(Locale.getDefault()))
        } catch (_: Exception) {
            fallback
        }
    }

    private fun parseDisplayName(map: Map<*, *>): String? {
        val nameStr = stringOf(map["name"])
        val fallback = languageManager?.getMessage("messages.gem.default_display_name") ?: "&cRule Gem"
        val raw = if (!nameStr.isNullOrEmpty()) nameStr else fallback
        return ColorUtils.translateColorCodes(raw)
    }

    private fun parseExecuteConfig(obj: Any?): ExecuteConfig? {
        if (obj !is Map<*, *>) return null
        return ExecuteConfig(
            toStringList(obj["commands"]),
            stringOf(obj["sound"]),
            stringOf(obj["particle"]),
        )
    }

    private fun parseBooleanLenient(obj: Any?): Boolean {
        if (obj is Boolean) return obj
        if (obj == null) return false
        val value = obj.toString().trim()
        return value.equals("true", ignoreCase = true) ||
            value.equals("yes", ignoreCase = true) ||
            value.equals("on", ignoreCase = true)
    }

    private fun parseIntSafe(obj: Any?, fallback: Int): Int {
        if (obj == null) return fallback
        return try {
            obj.toString().toInt()
        } catch (_: Exception) {
            fallback
        }
    }

    private fun asMap(obj: Any?): Map<*, *>? = when (obj) {
        is ConfigurationSection -> obj.getValues(false)
        is Map<*, *> -> obj
        else -> null
    }

    private fun parseRandomPlaceRange(map: Map<*, *>, gemKey: String): Array<Location?> {
        var corner1: Location? = null
        var corner2: Location? = null
        val rangeObj = map["random_place_range"]
        if (rangeObj is Map<*, *>) {
            val worldName = stringOf(rangeObj["world"])
            if (!worldName.isNullOrEmpty()) {
                val world = Bukkit.getWorld(worldName)
                if (world != null) {
                    val c1Obj = rangeObj["corner1"]
                    val c2Obj = rangeObj["corner2"]
                    if (c1Obj is Map<*, *> && c2Obj is Map<*, *>) {
                        corner1 = parseLocationFromMap(c1Obj, world)
                        corner2 = parseLocationFromMap(c2Obj, world)
                    }
                } else {
                    logger.warning("World not found in spawn range config for gem $gemKey: $worldName")
                }
            }
        }
        return arrayOf(corner1, corner2)
    }

    private fun parseAltarLocation(map: Map<*, *>, gemKey: String): Location? {
        val altarObj = map["altar"]
        if (altarObj !is Map<*, *>) return null
        val altarWorldName = stringOf(altarObj["world"])
        if (altarWorldName.isNullOrEmpty()) return null
        val altarWorld = Bukkit.getWorld(altarWorldName)
        if (altarWorld == null) {
            logger.warning("World not found in altar config for gem $gemKey: $altarWorldName")
            return null
        }
        return parseLocationFromMap(altarObj, altarWorld)
    }

    private fun resolveGemPower(map: Map<*, *>): PowerStructure {
        val powerObj = map["power"]
        if (powerObj != null) {
            val powerStructure = parsePowerStructure(powerObj)
            val rootStructure = parseLegacyRootPower(map)
            if (rootStructure.hasAnyContent()) {
                logger.warning(
                    "Gem root power fields are deprecated; move permissions, command_allows, effects, " +
                        "and permission_groups under power:. future version may remove this compatibility.",
                )
                powerStructure.merge(rootStructure)
            }
            return powerStructure
        }
        val rootStructure = parseLegacyRootPower(map)
        if (rootStructure.hasAnyContent()) {
            logger.warning(
                "Gem root power fields without power: are deprecated; move them under power:. " +
                    "future version may remove this compatibility.",
            )
        }
        return rootStructure
    }

    private fun parseLegacyRootPower(map: Map<*, *>): PowerStructure =
        if (!hasPowerFields(map)) PowerStructure() else parsePowerStructure(map)

    private fun hasPowerFields(map: Map<*, *>): Boolean =
        map.containsKey("permissions") ||
            map.containsKey("command_allows") ||
            map.containsKey("allowed_commands") ||
            map.containsKey("effects") ||
            map.containsKey("vault_group") ||
            map.containsKey("vault_groups") ||
            map.containsKey("permission_group") ||
            map.containsKey("permission_groups") ||
            map.containsKey("appoints") ||
            map.containsKey("conditions")

    private fun collectLegacyFromYamlFolder(folder: File?, findings: MutableSet<String>, gemFile: Boolean) {
        if (folder == null || !folder.isDirectory) return
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectLegacyFromYamlFolder(file, findings, gemFile)
            } else if (file.isFile && file.name.lowercase(Locale.getDefault()).endsWith(".yml")) {
                val yaml = YamlConfiguration.loadConfiguration(file)
                for (key in yaml.getKeys(false)) {
                    val value = yaml.get(key)
                    val map = asMap(value) ?: continue
                    collectLegacyFromMap(map, findings)
                    if (gemFile) {
                        val power = map["power"]
                        if (power != null && hasPowerFields(map)) {
                            findings.add("gem root power fields outside power")
                        }
                        val requirements = map["redeem_requirements"]
                        val reqMap = asMap(requirements)
                        if (reqMap != null && containsMapIngredientList(reqMap)) {
                            findings.add("redeem_requirements map ingredient list")
                        }
                    }
                }
            }
        }
    }

    private fun collectLegacyFromMap(map: Map<*, *>?, findings: MutableSet<String>) {
        if (map == null) return
        if (map.containsKey("template")) findings.add("template -> base")
        if (map.containsKey("vault_group")) findings.add("vault_group -> permission_groups")
        if (map.containsKey("vault_groups")) findings.add("vault_groups -> permission_groups")
        if (map.containsKey("permission_group")) findings.add("permission_group -> permission_groups")
        val powerMap = asMap(map["power"])
        if (powerMap != null) {
            collectLegacyFromMap(powerMap, findings)
        }
        val appointsMap = asMap(map["appoints"])
        if (appointsMap != null) {
            for (appoint in appointsMap.values) {
                collectLegacyFromMap(asMap(appoint), findings)
            }
        }
    }

    private fun containsMapIngredientList(requirements: Map<*, *>): Boolean =
        containsMapItem(requirements["requires_held"]) ||
            containsMapItem(requirements["requires_redeemed"]) ||
            containsMapItem(requirements["consumes"])

    private fun containsMapItem(obj: Any?): Boolean {
        if (obj !is List<*>) return false
        for (item in obj) {
            if (asMap(item) != null) return true
        }
        return false
    }

    private fun parsePermissionGroups(map: Map<*, *>): List<String> {
        val groups: MutableSet<String> = LinkedHashSet()
        addGroups(groups, toStringList(map["permission_groups"]))

        var usedLegacy = false
        val group = stringOf(map["vault_group"])
        if (!group.isNullOrBlank()) {
            groups.add(group.trim())
            usedLegacy = true
        }
        val vaultGroups = toStringList(map["vault_groups"])
        if (vaultGroups.isNotEmpty()) {
            addGroups(groups, vaultGroups)
            usedLegacy = true
        }
        val permissionGroup = stringOf(map["permission_group"])
        if (!permissionGroup.isNullOrBlank()) {
            groups.add(permissionGroup.trim())
            usedLegacy = true
        }
        if (usedLegacy) {
            logger.warning(
                "Power group keys vault_group/vault_groups/permission_group are deprecated; " +
                    "use permission_groups. future version may remove this compatibility.",
            )
        }
        return ArrayList(groups)
    }

    private fun addGroups(groups: MutableSet<String>, values: List<String>) {
        for (value in values) {
            if (value.isNotBlank()) {
                groups.add(value.trim())
            }
        }
    }

    private fun parseLocationFromMap(map: Map<*, *>, world: World): Location? {
        return try {
            var x = 0.0
            var y = 0.0
            var z = 0.0
            val xObj = map["x"]
            val yObj = map["y"]
            val zObj = map["z"]
            if (xObj != null) x = xObj.toString().toDouble()
            if (yObj != null) y = yObj.toString().toDouble()
            if (zObj != null) z = zObj.toString().toDouble()
            Location(world, x, y, z)
        } catch (e: Exception) {
            logger.warning("Failed to parse Location: " + e.message)
            null
        }
    }

    private fun parseAllowedCommands(allowsObj: Any?): List<AllowedCommand> {
        val allowed = ArrayList<AllowedCommand>()
        if (allowsObj is List<*>) {
            for (entry in allowsObj) {
                if (entry is Map<*, *>) {
                    val commandObj = entry["command"]
                    val executeObj = entry["execute"]
                    val uses = entry["time_limit"]
                    val cooldownObj = entry["cooldown"]

                    if (commandObj == null || executeObj == null) {
                        logger.warning("command_allows config error: must contain 'command' and 'execute' fields")
                        continue
                    }

                    var useLimit = -1
                    var cooldown = 0
                    try {
                        useLimit = uses.toString().toInt()
                    } catch (_: Exception) {
                    }
                    try {
                        cooldown = cooldownObj.toString().toInt()
                    } catch (_: Exception) {
                    }

                    var command = commandObj.toString().trim()
                    if (command.startsWith("/")) {
                        command = command.substring(1)
                    }
                    val commandLabel = command.split("\\s+".toRegex())[0].lowercase(Locale.getDefault())
                    val executeCommands = toStringList(executeObj)
                    allowed.add(AllowedCommand(commandLabel, useLimit, executeCommands, cooldown))
                }
            }
        }
        return allowed
    }

    private fun parseEffects(effectsObj: Any?): List<EffectConfig> {
        val effects = ArrayList<EffectConfig>()
        if (effectsObj == null) return effects

        if (effectsObj is List<*>) {
            for (entry in effectsObj) {
                if (entry is String) {
                    val typeName = entry.uppercase(Locale.getDefault()).trim()
                    val type = PotionEffectType.getByName(typeName)
                    if (type != null) {
                        effects.add(EffectConfig(type))
                    } else {
                        logger.warning("Unknown potion effect type: $typeName")
                    }
                } else if (entry is Map<*, *>) {
                    val typeName = stringOf(entry["type"])
                    if (typeName.isNullOrEmpty()) {
                        logger.warning("effects config error: missing 'type' field")
                        continue
                    }

                    val type = PotionEffectType.getByName(typeName.uppercase(Locale.getDefault()).trim())
                    if (type == null) {
                        logger.warning("Unknown potion effect type: $typeName")
                        continue
                    }

                    var amplifier = 0
                    val ampObj = entry["amplifier"]
                    if (ampObj != null) {
                        try {
                            amplifier = ampObj.toString().toInt()
                        } catch (_: Exception) {
                        }
                    }

                    var ambient = false
                    val ambientObj = entry["ambient"]
                    if (ambientObj is Boolean) {
                        ambient = ambientObj
                    } else if (ambientObj != null) {
                        ambient = "true".equals(ambientObj.toString(), ignoreCase = true)
                    }

                    var particles = true
                    val particlesObj = entry["particles"]
                    if (particlesObj is Boolean) {
                        particles = particlesObj
                    } else if (particlesObj != null) {
                        particles = !"false".equals(particlesObj.toString(), ignoreCase = true)
                    }

                    var icon = true
                    val iconObj = entry["icon"]
                    if (iconObj is Boolean) {
                        icon = iconObj
                    } else if (iconObj != null) {
                        icon = !"false".equals(iconObj.toString(), ignoreCase = true)
                    }

                    effects.add(EffectConfig(type, amplifier, ambient, particles, icon))
                }
            }
        }
        return effects
    }

    fun parsePowerStructure(obj: Any?): PowerStructure {
        if (obj == null) return PowerStructure()

        if (obj is String) {
            val templateName = obj
            var template = powerTemplates[templateName]
            if (template == null && rawPowerTemplates.containsKey(templateName)) {
                val raw = rawPowerTemplates.remove(templateName)
                if (raw != null) {
                    template = parsePowerStructure(raw)
                    powerTemplates[templateName] = template
                }
            }
            return if (template != null) {
                template.copy()
            } else {
                logger.warning("Unknown power template: $templateName")
                PowerStructure()
            }
        }

        if (obj is List<*>) {
            val combined = PowerStructure()
            for (item in obj) {
                if (item is String) {
                    val templateName = item
                    var template = powerTemplates[templateName]
                    if (template == null && rawPowerTemplates.containsKey(templateName)) {
                        val raw = rawPowerTemplates.remove(templateName)
                        if (raw != null) {
                            template = parsePowerStructure(raw)
                            powerTemplates[templateName] = template
                        }
                    }
                    if (template != null) {
                        combined.merge(template)
                    } else {
                        logger.warning("Unknown power template in list: $templateName")
                    }
                } else if (item is Map<*, *> || item is ConfigurationSection) {
                    val inline = parsePowerStructure(item)
                    combined.merge(inline)
                }
            }
            return combined
        }

        if (obj is Map<*, *> || obj is ConfigurationSection) {
            val map = asMap(obj) ?: return PowerStructure()
            var structure = PowerStructure()

            var baseObj = map["base"]
            if (baseObj == null && map.containsKey("template")) {
                baseObj = map["template"]
                logger.warning(
                    "Power field 'template' is deprecated; use 'base' instead. " +
                        "future version may remove this compatibility.",
                )
            }

            if (baseObj is String) {
                val template = parsePowerStructure(baseObj)
                if (template.hasAnyContent()) {
                    structure = template
                }
            } else if (baseObj is List<*>) {
                for (item in baseObj) {
                    if (item is String) {
                        val template = parsePowerStructure(item)
                        if (template.hasAnyContent()) {
                            structure.merge(template)
                        }
                    }
                }
            }

            val permissions = toStringList(map["permissions"])
            if (permissions.isNotEmpty()) {
                structure.permissions.addAll(permissions)
            }

            val groups = parsePermissionGroups(map)
            if (groups.isNotEmpty()) {
                for (group in groups) {
                    if (!structure.vaultGroups.contains(group)) {
                        structure.vaultGroups.add(group)
                    }
                }
            }

            val allowed = parseAllowedCommands(map["command_allows"])
            if (allowed.isNotEmpty()) {
                structure.allowedCommands.addAll(allowed)
            }

            val effects = parseEffects(map["effects"])
            if (effects.isNotEmpty()) {
                structure.effects.addAll(effects)
            }

            val appointsObj = map["appoints"]
            if (appointsObj is Map<*, *> || appointsObj is ConfigurationSection) {
                val appointsMap = if (appointsObj is ConfigurationSection) {
                    appointsObj.getValues(false)
                } else {
                    appointsObj as Map<*, *>
                }
                for ((rawKey, value) in appointsMap) {
                    val key = rawKey.toString()
                    val definition = parseAppointDefinition(key, value)
                    if (definition != null) {
                        structure.appoints[key] = definition
                    }
                }
            }

            val condObj = map["conditions"]
            if (condObj is Map<*, *> || condObj is ConfigurationSection) {
                val condition = parseCondition(condObj)
                structure.setCondition(condition)
            }

            return structure
        }

        return PowerStructure()
    }

    private fun parseAppointDefinition(key: String, obj: Any?): AppointDefinition? {
        val map = asMap(obj) ?: return null
        val definition = AppointDefinition(key)

        definition.displayName = stringOf(map["display_name"])
        definition.description = stringOf(map["description"])

        val maxObj = map["max_count"]
        if (maxObj != null) {
            try {
                definition.maxAppointments = maxObj.toString().toInt()
            } catch (_: Exception) {
            }
        }

        definition.appointSound = stringOf(map["appoint_sound"])
        definition.revokeSound = stringOf(map["revoke_sound"])
        definition.onAppoint = toStringList(map["on_appoint"])
        definition.onRevoke = toStringList(map["on_revoke"])

        var power: PowerStructure? = null
        val powerObj = map["power"]
        if (powerObj != null) {
            power = parsePowerStructure(powerObj)
        }

        if (power == null) {
            val refObj = map["ref"]
            if (refObj != null) {
                power = parsePowerStructure(refObj)
            }
        }

        if (map.containsKey("permissions") ||
            map.containsKey("allowed_commands") ||
            map.containsKey("effects") ||
            map.containsKey("command_allows") ||
            map.containsKey("vault_groups") ||
            map.containsKey("vault_group") ||
            map.containsKey("permission_group") ||
            map.containsKey("permission_groups")
        ) {
            val implicit = parsePowerStructure(map)
            if (power == null) {
                power = implicit
            } else {
                power.merge(implicit)
            }
        }

        if (power != null) {
            definition.powerStructure = power
        }
        return definition
    }

    private fun parseCondition(obj: Any?): PowerCondition {
        val condition = PowerCondition()
        val map = asMap(obj) ?: return condition

        val timeObj = map["time"]
        if (timeObj is Map<*, *> || timeObj is ConfigurationSection) {
            val timeMap = asMap(timeObj)
            if (timeMap != null && isTrue(timeMap["enabled"])) {
                condition.isTimeEnabled = true
                val typeStr = stringOf(timeMap["type"])
                try {
                    if (typeStr != null) {
                        condition.timeType = PowerCondition.TimeType.valueOf(typeStr.uppercase(Locale.getDefault()))
                    }
                } catch (_: Exception) {
                }

                val fromObj = timeMap["from"]
                val toObj = timeMap["to"]
                if (fromObj is Number) condition.timeFrom = fromObj.toLong()
                if (toObj is Number) condition.timeTo = toObj.toLong()
            }
        }

        val worldObj = map["worlds"]
        if (worldObj is Map<*, *> || worldObj is ConfigurationSection) {
            val worldMap = asMap(worldObj)
            if (worldMap != null && isTrue(worldMap["enabled"])) {
                condition.isWorldEnabled = true
                val modeStr = stringOf(worldMap["mode"])
                try {
                    if (modeStr != null) {
                        condition.worldMode = PowerCondition.WorldMode.valueOf(modeStr.uppercase(Locale.getDefault()))
                    }
                } catch (_: Exception) {
                }

                condition.worldList = toStringList(worldMap["list"])
            }
        }

        return condition
    }
}

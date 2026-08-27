package org.cubexmc.manager

import org.cubexmc.model.CommandArgumentConstraints
import org.cubexmc.model.CommandArgumentConstraints.Rule
import org.cubexmc.model.CommandArgumentConstraints.Suggestions
import org.cubexmc.model.CommandArgumentConstraints.Type
import java.math.BigDecimal
import java.util.Locale
import java.util.logging.Logger

/** Keeps malformed safety rules attached to the command as a blocking error. */
internal object CommandArgumentParser {
    fun parse(entry: Map<*, *>, label: String, logger: Logger): CommandArgumentConstraints {
        if (!entry.containsKey("args")) return CommandArgumentConstraints()
        return try {
            val args = entry["args"] as? Map<*, *> ?: error("args must be a mapping")
            CommandArgumentConstraints(args.map { (key, value) -> parseRule(key, value) })
        } catch (exception: IllegalArgumentException) {
            invalid(label, logger, exception)
        } catch (exception: IllegalStateException) {
            invalid(label, logger, exception)
        }
    }

    private fun invalid(label: String, logger: Logger, exception: RuntimeException): CommandArgumentConstraints {
        val reason = exception.message ?: "invalid args"
        logger.warning("command_allows /$label: $reason; execution blocked until args are fixed.")
        return CommandArgumentConstraints(configurationError = reason)
    }

    private fun parseRule(key: Any?, value: Any?): Rule {
        val name = key as? String ?: error("args keys must be arg1, arg2, ...")
        require(ARGUMENT_KEY.matches(name)) { "Invalid argument key: $name" }
        val index = name.removePrefix("arg").toIntOrNull() ?: error("Argument index too large: $name")
        val settings = value as? Map<*, *> ?: error("$name must be a mapping")
        require(settings.keys.all { it in RULE_KEYS }) {
            "Unknown constraint in $name (expected type, required, min, max, suggestions)"
        }
        val type = if (settings.containsKey("type")) {
            Type.valueOf(settings["type"].toString().uppercase(Locale.ROOT))
        } else {
            Type.STRING
        }
        val required = if (settings.containsKey("required")) {
            settings["required"] as? Boolean ?: error("$name.required must be true or false")
        } else {
            true
        }
        val min = bound(settings, "min", name)
        val max = bound(settings, "max", name)
        require(type != Type.STRING || (min == null && max == null)) { "$name: min/max require number or integer type" }
        require(min == null || max == null || min <= max) { "$name: min must not exceed max" }
        return Rule(index, type, required, min, max, parseSuggestions(settings, type, name))
    }

    private fun parseSuggestions(settings: Map<*, *>, type: Type, name: String): Suggestions? {
        if (!settings.containsKey("suggestions")) return null
        return when (val value = settings["suggestions"]) {
            "online_players" -> {
                require(type == Type.STRING) { "$name: online_players suggestions require string type" }
                Suggestions(true, emptyList())
            }
            is List<*> -> {
                val values = value.map { item ->
                    require(item is String || item is Number) { "$name.suggestions must contain strings or numbers" }
                    val text = item.toString()
                    require(text.isNotEmpty() && text.none { it.isWhitespace() }) {
                        "$name.suggestions must contain single argument values"
                    }
                    text
                }
                Suggestions(false, values)
            }
            else -> error("$name.suggestions must be online_players or a list")
        }
    }

    private fun bound(settings: Map<*, *>, key: String, name: String): BigDecimal? {
        if (!settings.containsKey(key)) return null
        val value = settings[key].toString()
        require(value.length <= MAX_BOUND_LENGTH) { "$name.$key is too long" }
        val number = value.toBigDecimalOrNull() ?: error("$name.$key must be a finite number")
        require(number.scale() in MIN_BOUND_SCALE..MAX_BOUND_SCALE) { "$name.$key has an unsupported scale" }
        require(number.toDouble().isFinite() && (number.signum() == 0 || number.toDouble() != 0.0)) {
            "$name.$key is outside the supported numeric range"
        }
        return number
    }

    private const val MAX_BOUND_LENGTH = 128
    private const val MIN_BOUND_SCALE = -308
    private const val MAX_BOUND_SCALE = 324
    private val ARGUMENT_KEY = Regex("arg[1-9][0-9]*")
    private val RULE_KEYS = setOf("type", "required", "min", "max", "suggestions")
}

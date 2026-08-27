package org.cubexmc.model

import java.math.BigDecimal
import java.util.Collections

/** Input constraints for a configured allowance, before placeholders or side effects. */
class CommandArgumentConstraints(
    rules: List<Rule> = emptyList(),
    val configurationError: String? = null,
) {
    private val rules = rules.sortedBy { it.index }
    private val suggestions = this.rules.associate { rule ->
        rule.index to rule.suggestions?.let { configured ->
            val values = configured.values.filter { validateRule(rule, it) == null }.distinct()
            Suggestions(configured.onlinePlayers, values)
        }
    }

    enum class Type { STRING, NUMBER, INTEGER }

    class Rule @JvmOverloads constructor(
        val index: Int,
        val type: Type,
        val required: Boolean,
        val min: BigDecimal?,
        val max: BigDecimal?,
        val suggestions: Suggestions? = null,
    )

    class Suggestions(val onlinePlayers: Boolean, values: List<String>) {
        val values: List<String> = Collections.unmodifiableList(ArrayList(values))
    }

    fun suggestionsFor(index: Int): Suggestions? = if (configurationError == null) suggestions[index] else null

    class Failure(val messageKey: String, val placeholders: Map<String, String>)

    fun validate(args: Array<String>): Failure? {
        if (configurationError != null) return Failure("allowance.args_config_error", emptyMap())
        return rules.firstNotNullOfOrNull { rule -> validateRule(rule, args.getOrNull(rule.index - 1)) }
    }

    private fun validateRule(rule: Rule, value: String?): Failure? = when {
        value.isNullOrBlank() -> if (rule.required) {
            Failure("allowance.args_required", mapOf("argument" to "arg${rule.index}"))
        } else {
            null
        }
        rule.type == Type.STRING -> null
        else -> validateNumber(rule, value)
    }

    private fun validateNumber(rule: Rule, value: String): Failure? {
        val placeholders = mapOf("argument" to "arg${rule.index}")
        val pattern = if (rule.type == Type.INTEGER) INTEGER_PATTERN else NUMBER_PATTERN
        val number = if (value.length <= MAX_NUMBER_LENGTH && pattern.matches(value)) {
            value.toBigDecimalOrNull()
        } else {
            null
        }
        return when {
            number == null || !number.toDouble().isFinite() -> {
                val key = if (rule.type == Type.INTEGER) "allowance.args_integer" else "allowance.args_number"
                Failure(key, placeholders)
            }
            rule.min != null && number < rule.min ->
                Failure("allowance.args_min", placeholders + ("min" to rule.min.toPlainString()))
            rule.max != null && number > rule.max ->
                Failure("allowance.args_max", placeholders + ("max" to rule.max.toPlainString()))
            else -> null
        }
    }

    companion object {
        private const val MAX_NUMBER_LENGTH = 128
        private val INTEGER_PATTERN = Regex("[+-]?[0-9]+")
        private val NUMBER_PATTERN = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")
    }
}

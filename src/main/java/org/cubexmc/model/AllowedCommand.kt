package org.cubexmc.model

import java.util.Collections
import kotlin.math.max

/**
 * AllowedCommand 描述一条玩家可用的指令以及可用次数。
 */
class AllowedCommand(
    val label: String,
    uses: Int,
    executeCommands: List<String>?,
    cooldown: Int,
) {
    val uses: Int = max(-1, uses)
    private val executeCommands: List<String> =
        if (!executeCommands.isNullOrEmpty()) executeCommands else Collections.singletonList(label)
    val cooldown: Int = max(0, cooldown)

    fun getCommands(): List<String> = executeCommands

    fun isSimpleCommand(): Boolean =
        executeCommands.size == 1 &&
            !executeCommands[0].startsWith("console:") &&
            !executeCommands[0].startsWith("player:") &&
            !executeCommands[0].startsWith("player-op:") &&
            executeCommands[0].equals(label, ignoreCase = true)

    companion object {
        @JvmStatic
        fun parseExecutor(commandLine: String?): Array<String> {
            if (commandLine.isNullOrEmpty()) {
                return arrayOf("player-op", "")
            }
            val trimmed = commandLine.trim()
            return when {
                trimmed.startsWith("console:") -> arrayOf("console", trimmed.substring(8).trim())
                trimmed.startsWith("player:") -> arrayOf("player", trimmed.substring(7).trim())
                trimmed.startsWith("player-op:") -> arrayOf("player-op", trimmed.substring(10).trim())
                else -> arrayOf("player-op", trimmed)
            }
        }
    }
}

package org.cubexmc.manager

import org.bukkit.entity.Player
import org.cubexmc.RuleGems
import org.cubexmc.utils.ColorUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryLogger(
    private val plugin: RuleGems,
    private val languageManager: LanguageManager?,
) {
    private val logsDirectory = File(plugin.dataFolder, "history")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private val fileNameFormat = SimpleDateFormat("yyyy-MM")

    init {
        if (!logsDirectory.exists()) {
            logsDirectory.mkdirs()
        }
    }

    fun logGemRedeem(
        player: Player,
        gemKey: String,
        gemDisplayName: String?,
        permissions: List<String>?,
        vaultGroup: String?,
        previousOwner: String?,
    ) {
        val timestamp = dateFormat.format(Date())
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["player"] = player.name
        placeholders["player_uuid"] = player.uniqueId.toString()
        val gemName = gemDisplayName ?: gemKey
        placeholders["gem_name"] = gemName
        placeholders["gem_key"] = gemKey

        var previousOwnerSection = ""
        if (!previousOwner.isNullOrEmpty()) {
            previousOwnerSection = formatHistoryMessage(
                "history.redeem.previous_owner",
                mapOf("previous_owner" to previousOwner),
            )
            if (previousOwnerSection.isEmpty()) {
                previousOwnerSection = " | 前任: $previousOwner"
            }
        }

        var permissionsSection = ""
        if (!permissions.isNullOrEmpty()) {
            val joined = permissions.joinToString(", ")
            permissionsSection = formatHistoryMessage("history.redeem.permissions", mapOf("permissions" to joined))
            if (permissionsSection.isEmpty()) {
                permissionsSection = " | Permissions: [$joined]"
            }
        }

        var vaultGroupSection = ""
        if (!vaultGroup.isNullOrEmpty()) {
            vaultGroupSection = formatHistoryMessage("history.redeem.vault_group", mapOf("vault_group" to vaultGroup))
            if (vaultGroupSection.isEmpty()) {
                vaultGroupSection = " | Vault group: $vaultGroup"
            }
        }

        placeholders["previous_owner_section"] = previousOwnerSection
        placeholders["permissions_section"] = permissionsSection
        placeholders["vault_group_section"] = vaultGroupSection

        var message = formatHistoryMessage("history.redeem.entry", placeholders)
        if (message.isEmpty()) {
            message = buildFallbackRedeem(player, gemKey, gemDisplayName, permissions, vaultGroup, previousOwner)
        }

        writeLog("[$timestamp] $message")
    }

    fun logPermissionRevoke(
        playerUuid: String,
        playerName: String?,
        gemKey: String,
        gemDisplayName: String?,
        permissions: List<String>?,
        vaultGroup: String?,
        reason: String?,
    ) {
        val timestamp = dateFormat.format(Date())
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["player_name"] = playerName ?: "Unknown"
        placeholders["player_uuid"] = playerUuid
        val gemName = gemDisplayName ?: gemKey
        placeholders["gem_name"] = gemName
        placeholders["gem_key"] = gemKey

        var reasonSection = ""
        if (!reason.isNullOrEmpty()) {
            reasonSection = formatHistoryMessage("history.revoke.reason", mapOf("reason" to reason))
            if (reasonSection.isEmpty()) {
                reasonSection = " | 原因: $reason"
            }
        }

        var permissionsSection = ""
        if (!permissions.isNullOrEmpty()) {
            val joined = permissions.joinToString(", ")
            permissionsSection = formatHistoryMessage("history.revoke.permissions", mapOf("permissions" to joined))
            if (permissionsSection.isEmpty()) {
                permissionsSection = " | Revoked permissions: [$joined]"
            }
        }

        var vaultGroupSection = ""
        if (!vaultGroup.isNullOrEmpty()) {
            vaultGroupSection = formatHistoryMessage("history.revoke.vault_group", mapOf("vault_group" to vaultGroup))
            if (vaultGroupSection.isEmpty()) {
                vaultGroupSection = " | Revoked group: $vaultGroup"
            }
        }

        placeholders["reason_section"] = reasonSection
        placeholders["permissions_section"] = permissionsSection
        placeholders["vault_group_section"] = vaultGroupSection

        var message = formatHistoryMessage("history.revoke.entry", placeholders)
        if (message.isEmpty()) {
            message = buildFallbackRevoke(playerUuid, playerName, gemKey, gemDisplayName, permissions, vaultGroup, reason)
        }

        writeLog("[$timestamp] $message")
    }

    fun logFullSetRedeem(player: Player, gemCount: Int, permissions: List<String>?, previousFullSetOwner: String?) {
        val timestamp = dateFormat.format(Date())
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["player"] = player.name
        placeholders["player_uuid"] = player.uniqueId.toString()
        placeholders["gem_count"] = gemCount.toString()

        var previousOwnerSection = ""
        if (!previousFullSetOwner.isNullOrEmpty()) {
            previousOwnerSection = formatHistoryMessage(
                "history.full_set.previous_owner",
                mapOf("previous_owner" to previousFullSetOwner),
            )
            if (previousOwnerSection.isEmpty()) {
                previousOwnerSection = " | 前任统治者: $previousFullSetOwner"
            }
        }

        var permissionsSection = ""
        if (!permissions.isNullOrEmpty()) {
            val joined = permissions.joinToString(", ")
            permissionsSection = formatHistoryMessage("history.full_set.permissions", mapOf("permissions" to joined))
            if (permissionsSection.isEmpty()) {
                permissionsSection = " | Total permissions: [$joined]"
            }
        }

        placeholders["previous_owner_section"] = previousOwnerSection
        placeholders["permissions_section"] = permissionsSection

        var message = formatHistoryMessage("history.full_set.entry", placeholders)
        if (message.isEmpty()) {
            message = buildFallbackFullSet(player, gemCount, permissions, previousFullSetOwner)
        }

        writeLog("[$timestamp] $message")
    }

    fun logGemPlace(player: Player, gemKey: String, location: String) {
        val timestamp = dateFormat.format(Date())
        val placeholders = mapOf(
            "player" to player.name,
            "gem_key" to gemKey,
            "location" to location,
        )
        var message = formatHistoryMessage("history.place.entry", placeholders)
        if (message.isEmpty()) {
            message = buildFallbackPlace(player, gemKey, location)
        }
        writeLog("[$timestamp] $message")
    }

    fun logGemBreak(player: Player, gemKey: String, location: String) {
        val timestamp = dateFormat.format(Date())
        val placeholders = mapOf(
            "player" to player.name,
            "gem_key" to gemKey,
            "location" to location,
        )
        var message = formatHistoryMessage("history.break.entry", placeholders)
        if (message.isEmpty()) {
            message = buildFallbackBreak(player, gemKey, location)
        }
        writeLog("[$timestamp] $message")
    }

    private fun formatHistoryMessage(key: String, placeholders: Map<String, String>?): String {
        val manager = languageManager ?: return ""
        var template = manager.getMessage(key)
        if (template.startsWith("Missing message")) {
            return ""
        }
        if (!placeholders.isNullOrEmpty()) {
            template = manager.formatText(template, placeholders) ?: ""
        }
        return ColorUtils.translateColorCodes(template) ?: ""
    }

    private fun buildFallbackRedeem(
        player: Player,
        gemKey: String,
        gemDisplayName: String?,
        permissions: List<String>?,
        vaultGroup: String?,
        previousOwner: String?,
    ): String {
        val builder = StringBuilder("§e[Gem Redeem] ")
        builder.append("Player: ").append(player.name).append(" (").append(player.uniqueId).append(") ")
        builder.append("| Gem: ").append(gemDisplayName ?: gemKey).append(" (").append(gemKey).append(")")
        if (!previousOwner.isNullOrEmpty()) {
            builder.append(" | Previous: ").append(previousOwner)
        }
        if (!permissions.isNullOrEmpty()) {
            builder.append(" | Permissions: [").append(permissions.joinToString(", ")).append("]")
        }
        if (!vaultGroup.isNullOrEmpty()) {
            builder.append(" | Vault group: ").append(vaultGroup)
        }
        return builder.toString()
    }

    private fun buildFallbackRevoke(
        playerUuid: String,
        playerName: String?,
        gemKey: String,
        gemDisplayName: String?,
        permissions: List<String>?,
        vaultGroup: String?,
        reason: String?,
    ): String {
        val builder = StringBuilder("§c[Permission Revoke] ")
        builder.append("Player: ").append(playerName ?: "Unknown").append(" (").append(playerUuid).append(") ")
        builder.append("| Gem: ").append(gemDisplayName ?: gemKey).append(" (").append(gemKey).append(")")
        if (!reason.isNullOrEmpty()) {
            builder.append(" | Reason: ").append(reason)
        }
        if (!permissions.isNullOrEmpty()) {
            builder.append(" | Revoked permissions: [").append(permissions.joinToString(", ")).append("]")
        }
        if (!vaultGroup.isNullOrEmpty()) {
            builder.append(" | Revoked group: ").append(vaultGroup)
        }
        return builder.toString()
    }

    private fun buildFallbackFullSet(player: Player, gemCount: Int, permissions: List<String>?, previousFullSetOwner: String?): String {
        val builder = StringBuilder("§6[Full Set Redeem] ")
        builder.append("Player: ").append(player.name).append(" (").append(player.uniqueId).append(") ")
        builder.append("| Gem count: ").append(gemCount)
        if (!previousFullSetOwner.isNullOrEmpty()) {
            builder.append(" | Previous ruler: ").append(previousFullSetOwner)
        }
        if (!permissions.isNullOrEmpty()) {
            builder.append(" | Total permissions: [").append(permissions.joinToString(", ")).append("]")
        }
        return builder.toString()
    }

    private fun buildFallbackPlace(player: Player, gemKey: String, location: String): String =
        "§a[Gem Placed] Player: " + player.name + " | Gem: " + gemKey + " | Location: " + location

    private fun buildFallbackBreak(player: Player, gemKey: String, location: String): String =
        "§c[Gem Broken] Player: " + player.name + " | Gem: " + gemKey + " | Location: " + location

    private fun writeLog(logEntry: String) {
        val fileName = fileNameFormat.format(Date()) + ".log"
        val logFile = File(logsDirectory, fileName)

        try {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                val cleanEntry = logEntry.replace(Regex("§[0-9a-fk-or]"), "")
                writer.write(cleanEntry)
                writer.newLine()
            }
        } catch (e: IOException) {
            plugin.logger.warning("Failed to write history log: " + e.message)
        }
    }

    fun getRecentHistoryPage(page: Int, pageSize: Int): HistoryPage {
        val entries: MutableList<String> = ArrayList()
        var total = 0
        val startIndex = maxOf(0, (page - 1) * pageSize)
        val endIndex = startIndex + pageSize

        try {
            val logFiles = logsDirectory.listFiles { _, name -> name.endsWith(".log") }
            if (logFiles.isNullOrEmpty()) {
                return HistoryPage(entries, total)
            }

            logFiles.sortWith { a, b -> b.name.compareTo(a.name) }
            for (logFile in logFiles) {
                val fileLines = try {
                    Files.lines(logFile.toPath()).use { stream -> stream.toList() }
                } catch (e: IOException) {
                    plugin.logger.warning("Failed to read log file: " + logFile.name)
                    continue
                }

                for (i in fileLines.indices.reversed()) {
                    val line = fileLines[i]
                    if (total >= startIndex && total < endIndex) {
                        entries.add(line)
                    }
                    total++
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to read history: " + e.message)
        }

        return HistoryPage(entries, total)
    }

    fun getPlayerHistoryPage(playerName: String, page: Int, pageSize: Int): HistoryPage {
        val entries: MutableList<String> = ArrayList()
        var total = 0
        val startIndex = maxOf(0, (page - 1) * pageSize)
        val endIndex = startIndex + pageSize
        val lowerPlayer = playerName.lowercase(Locale.ROOT)

        try {
            val logFiles = logsDirectory.listFiles { _, name -> name.endsWith(".log") }
            if (logFiles.isNullOrEmpty()) {
                return HistoryPage(entries, total)
            }

            logFiles.sortWith { a, b -> b.name.compareTo(a.name) }
            for (logFile in logFiles) {
                val fileLines = try {
                    Files.lines(logFile.toPath()).use { stream -> stream.toList() }
                } catch (e: IOException) {
                    plugin.logger.warning("Failed to read log file: " + logFile.name)
                    continue
                }

                for (i in fileLines.indices.reversed()) {
                    val line = fileLines[i]
                    if (!lineMatchesPlayer(line, lowerPlayer)) {
                        continue
                    }
                    if (total >= startIndex && total < endIndex) {
                        entries.add(line)
                    }
                    total++
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to read player history: " + e.message)
        }

        return HistoryPage(entries, total)
    }

    fun getRecentHistory(lines: Int): List<String> = getRecentHistoryPage(1, maxOf(1, lines)).entries

    fun getPlayerHistory(playerName: String, lines: Int): List<String> = getPlayerHistoryPage(playerName, 1, maxOf(1, lines)).entries

    private fun lineMatchesPlayer(line: String?, lowerPlayer: String): Boolean {
        if (line.isNullOrEmpty()) {
            return false
        }
        val lowerLine = line.lowercase(Locale.ROOT)
        return lowerLine.contains("player: $lowerPlayer") ||
            lowerLine.contains("$lowerPlayer (") ||
            lowerLine.contains("| $lowerPlayer |") ||
            lowerLine.contains("玩家: $lowerPlayer")
    }

    class HistoryPage(val entries: List<String>, val totalCount: Int)
}

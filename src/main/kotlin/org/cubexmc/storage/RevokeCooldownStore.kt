package org.cubexmc.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.AtomicYamlFiles
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import java.io.File
import java.util.UUID

/** Keeps the legacy cooldown schema; writes precede publication and failed reads preserve the snapshot. */
class RevokeCooldownStore(private val file: File) : Reloadable, Terminable {
    private var values: Map<UUID, Map<String, Long>> = emptyMap()
    private var loaded = false

    override fun reload() {
        check(!loaded || values.isEmpty() || file.exists()) { "Revoke cooldown file disappeared: $file" }
        val data = AtomicYamlFiles.read(file)
        require(!data.contains("cooldowns") || data.isConfigurationSection("cooldowns")) { "Invalid cooldowns section" }
        val next = LinkedHashMap<UUID, Map<String, Long>>()
        val section = data.getConfigurationSection("cooldowns")
        for (key in section?.getKeys(false).orEmpty()) {
            val player = requireNotNull(section?.getConfigurationSection(key)) { "Invalid cooldown player: $key" }
            val entries = player.getKeys(false).associateWith { rule ->
                require(player.isLong(rule) || player.isInt(rule)) { "Invalid cooldown timestamp: $key/$rule" }
                player.getLong(rule)
            }
            next[UUID.fromString(key)] = entries
        }
        values = next
        loaded = true
    }

    fun until(player: UUID, rule: String): Long = values[player]?.get(rule) ?: 0L

    fun setUntil(player: UUID, rule: String, timestamp: Long) {
        check(loaded) { "Revoke cooldown store was not loaded" }
        val byRule = values[player].orEmpty().toMutableMap()
        if (timestamp > 0L) byRule[rule] = timestamp else byRule.remove(rule)
        val next = values.toMutableMap()
        if (byRule.isEmpty()) next.remove(player) else next[player] = byRule
        val yaml = YamlConfiguration()
        for ((id, entries) in next) {
            for ((key, until) in entries) yaml["cooldowns.$id.$key"] = until
        }
        AtomicYamlFiles.write(file, yaml)
        values = next
    }

    // Every mutation is durable; closing an uninitialized or failed store must never write empty state.
    override fun close() = Unit
}

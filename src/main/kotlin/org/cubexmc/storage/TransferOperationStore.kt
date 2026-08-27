package org.cubexmc.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.AtomicYamlFiles
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import java.io.File
import java.util.UUID

/** Durable retry guards, not an economy transaction engine. Never replays or refunds money. */
class TransferOperationStore(private val file: File) : Reloadable, Terminable {
    data class Operation(
        val id: UUID,
        val playerId: UUID,
        val source: String,
        val label: String,
        val createdAt: Long,
        val status: String = "EXECUTING",
        val commands: List<String> = emptyList(),
    )

    private var operations: Map<UUID, Operation> = emptyMap()
    private var loaded = false

    @Synchronized
    override fun reload() {
        check(!loaded || file.exists() || operations.isEmpty()) { "Active transfer operation file is missing" }
        val yaml = AtomicYamlFiles.read(file)
        val parsed = LinkedHashMap<UUID, Operation>()
        val section = yaml.getConfigurationSection("operations")
        check(!yaml.contains("operations") || section != null) { "Invalid transfer operations section" }
        for (key in section?.getKeys(false).orEmpty()) {
            val entry = requireNotNull(section?.getConfigurationSection(key)) { "Invalid operation $key" }
            val operation = Operation(
                UUID.fromString(key),
                UUID.fromString(entry.getString("player")),
                requireNotNull(entry.getString("source")),
                requireNotNull(entry.getString("label")),
                entry.getLong("created_at"),
                requireNotNull(entry.getString("status")),
                entry.getStringList("commands"),
            )
            require(operation.source.isNotBlank() && operation.label.isNotBlank()) { "Invalid operation identity" }
            parsed[operation.id] = operation
        }
        operations = parsed
        loaded = true
    }

    @Synchronized
    fun pending(playerId: UUID, source: String): Operation? =
        operations.values.firstOrNull { it.playerId == playerId && it.source == source }

    @Synchronized
    fun all(): List<Operation> = operations.values.sortedBy { it.createdAt }

    @Synchronized
    @JvmOverloads
    fun begin(playerId: UUID, source: String, label: String, commands: List<String> = emptyList()): Operation {
        check(loaded) { "Transfer operations are not loaded" }
        check(pending(playerId, source) == null) { "Source already has an unfinished transfer" }
        val operation = Operation(
            UUID.randomUUID(), playerId, source, label, System.currentTimeMillis(), commands = commands.toList(),
        )
        publish(operations + (operation.id to operation))
        return operation
    }

    @Synchronized
    fun hold(id: UUID, status: String) {
        val existing = requireNotNull(operations[id]) { "Unknown operation $id" }
        publish(operations + (id to existing.copy(status = status)))
    }

    @Synchronized
    fun complete(id: UUID) {
        check(loaded) { "Transfer operations are not loaded" }
        publish(operations - id)
    }

    /** Archive acknowledgement before removing the guard; it does not alter balances or uses. */
    @Synchronized
    fun resolve(id: UUID, actor: String, note: String): Boolean {
        val operation = operations[id] ?: return false
        require(note.isNotBlank()) { "A reconciliation note is required" }
        val archive = serialize(mapOf(id to operation))
        archive["resolved_by"] = actor
        archive["resolved_at"] = System.currentTimeMillis()
        archive["note"] = note
        val destination = File(file.parentFile, "transfer-reviews/$id-${UUID.randomUUID()}.yml")
        AtomicYamlFiles.write(destination, archive)
        complete(id)
        return true
    }

    // Every mutation is persisted before publishing. Failed initialization must not write empty data.
    override fun close() = Unit

    private fun publish(next: Map<UUID, Operation>) {
        AtomicYamlFiles.write(file, serialize(next))
        operations = next
    }

    private fun serialize(values: Map<UUID, Operation>): YamlConfiguration = YamlConfiguration().apply {
        set("version", 1)
        createSection("operations")
        for (operation in values.values) {
            val path = "operations.${operation.id}"
            set("$path.player", operation.playerId.toString())
            set("$path.source", operation.source)
            set("$path.label", operation.label)
            set("$path.created_at", operation.createdAt)
            set("$path.status", operation.status)
            set("$path.commands", operation.commands)
        }
    }
}

package org.cubexmc.storage

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.AtomicYamlFiles
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import org.cubexmc.features.appoint.Appointment
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Appointment mutations are published only after the complete legacy YAML snapshot has been saved. */
class AppointmentStore(private val file: File, private val legacyFile: File) : Reloadable, Terminable {
    private var data = YamlConfiguration()
    private var loaded = false
    var appointments: Map<String, Map<UUID, Appointment>> = emptyMap()
        private set
    var toggles: Map<UUID, Set<String>> = emptyMap()
        private set

    override fun reload() {
        check(!loaded || appointments.isEmpty() && toggles.isEmpty() || file.exists()) {
            "Appointment data file disappeared: $file"
        }
        val source = if (!file.exists() && legacyFile.exists()) legacyFile else file
        val candidate = AtomicYamlFiles.read(source)
        val nextAppointments = readAppointments(appointmentSection(candidate, "appointments"))
        val nextToggles = readToggles(appointmentSection(candidate, "toggled_off_appointments"))
        if (source == legacyFile) {
            Files.createDirectories(file.toPath().toAbsolutePath().parent)
            Files.move(source.toPath(), file.toPath())
        }
        publish(candidate, nextAppointments, nextToggles)
        loaded = true
    }

    fun add(appointment: Appointment) {
        val next = appointments.toMutableMap()
        next[appointment.permSetKey] = next[appointment.permSetKey].orEmpty() +
            (appointment.appointeeUuid to appointment)
        commit(next, toggles)
    }

    fun remove(key: String, players: Collection<UUID>) {
        val next = appointments.toMutableMap()
        val byPlayer = next[key].orEmpty() - players.toSet()
        if (byPlayer.isEmpty()) next.remove(key) else next[key] = byPlayer
        val nextToggles = toggles.toMutableMap()
        for (player in players) {
            val keys = nextToggles[player].orEmpty() - normalizeAppointmentKey(key)
            if (keys.isEmpty()) nextToggles.remove(player) else nextToggles[player] = keys
        }
        commit(next, nextToggles)
    }

    fun setEnabled(player: UUID, key: String, enabled: Boolean) {
        val next = toggles.toMutableMap()
        val keys = next[player].orEmpty()
        val changed = if (enabled) keys - normalizeAppointmentKey(key) else keys + normalizeAppointmentKey(key)
        if (changed.isEmpty()) next.remove(player) else next[player] = changed
        commit(appointments, next)
    }

    /** No dirty snapshot is published: the reload barrier only needs to require successful initialization. */
    fun flush() {
        check(loaded) { "Appointment data was never loaded successfully" }
    }

    override fun close() = Unit

    private fun commit(next: Map<String, Map<UUID, Appointment>>, nextToggles: Map<UUID, Set<String>>) {
        flush()
        val candidate = YamlConfiguration().apply { loadFromString(data.saveToString()) }
        candidate["appointments"] = null
        candidate["toggled_off_appointments"] = null
        for ((key, byPlayer) in next) {
            for ((id, appointment) in byPlayer) {
                val path = "appointments.$key.$id"
                candidate["$path.appointed_by"] = appointment.appointerUuid?.toString()
                candidate["$path.appointed_at"] = appointment.appointedAt
            }
        }
        for ((id, keys) in nextToggles) {
            if (keys.isNotEmpty()) candidate["toggled_off_appointments.$id"] = keys.sorted()
        }
        AtomicYamlFiles.write(file, candidate)
        publish(candidate, next, nextToggles)
    }

    private fun publish(
        candidate: YamlConfiguration,
        next: Map<String, Map<UUID, Appointment>>,
        nextToggles: Map<UUID, Set<String>>,
    ) {
        data = candidate
        appointments = Collections.unmodifiableMap(next.mapValues { Collections.unmodifiableMap(HashMap(it.value)) })
        toggles = Collections.unmodifiableMap(nextToggles.mapValues { Collections.unmodifiableSet(HashSet(it.value)) })
    }

    private fun readAppointments(root: ConfigurationSection?): Map<String, Map<UUID, Appointment>> =
        root?.getKeys(false).orEmpty().associateWith { key ->
            val byPlayer = requireNotNull(appointmentSection(root, key)) { "Invalid appointment set: $key" }
            byPlayer.getKeys(false).associate { id ->
                val entry = requireNotNull(appointmentSection(byPlayer, id)) { "Invalid appointment: $key/$id" }
                val player = UUID.fromString(id)
                val appointer = entry.getString("appointed_by")?.let { UUID.fromString(it) }
                val validTimestamp = !entry.contains("appointed_at") ||
                    entry.isLong("appointed_at") || entry.isInt("appointed_at")
                require(validTimestamp) {
                    "Invalid appointment timestamp: $key/$id"
                }
                player to Appointment(player, key, appointer, entry.getLong("appointed_at", System.currentTimeMillis()))
            }
        }

    private fun readToggles(root: ConfigurationSection?): Map<UUID, Set<String>> =
        root?.getKeys(false).orEmpty().associate { id ->
            val raw = requireNotNull(root?.getList(id)) { "Invalid appointment toggles: $id" }
            require(raw.all { it is String }) { "Expected appointment names in toggles: $id" }
            UUID.fromString(id) to raw.map { normalizeAppointmentKey(it as String) }.filter { it.isNotBlank() }.toSet()
        }
}

private fun normalizeAppointmentKey(key: String): String = key.trim().lowercase(Locale.ROOT)

private fun appointmentSection(root: ConfigurationSection?, path: String): ConfigurationSection? {
    require(root == null || !root.contains(path) || root.isConfigurationSection(path)) { "Invalid section: $path" }
    return root?.getConfigurationSection(path)
}

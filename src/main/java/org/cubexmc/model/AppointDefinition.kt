package org.cubexmc.model

/**
 * 委任定义模型。
 */
class AppointDefinition(val key: String) {
    var displayName: String? = key
    var description: String? = ""
    var maxAppointments: Int = -1
    var powerStructure: PowerStructure = PowerStructure()
        set(value) {
            field = value
        }
    var appointSound: String? = "ENTITY_PLAYER_LEVELUP"
    var revokeSound: String? = "ENTITY_ITEM_BREAK"
    var onAppoint: List<String> = ArrayList()
        set(value) {
            field = value ?: ArrayList()
        }
    var onRevoke: List<String> = ArrayList()
        set(value) {
            field = value ?: ArrayList()
        }
}

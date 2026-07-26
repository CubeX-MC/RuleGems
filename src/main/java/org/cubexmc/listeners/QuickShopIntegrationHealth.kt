package org.cubexmc.listeners

enum class QuickShopHealthStatus {
    ABSENT,
    ACTIVE,
    UNSUPPORTED,
    FAILED,
}

data class QuickShopIntegrationHealth(
    val status: QuickShopHealthStatus,
    val detail: String,
) {
    val releaseBlocking: Boolean
        get() = status == QuickShopHealthStatus.UNSUPPORTED || status == QuickShopHealthStatus.FAILED

    companion object {
        @JvmStatic
        fun absent(): QuickShopIntegrationHealth =
            QuickShopIntegrationHealth(QuickShopHealthStatus.ABSENT, "QuickShop-Hikari is not installed")
    }
}

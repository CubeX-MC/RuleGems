package org.cubexmc.economy

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Vault 经济钩子，提供账户间的补偿式转账。
 *
 * transfer: 指令接收的是经济账户名，而不是 Bukkit 玩家 UUID。
 * Vault 的 withdraw/deposit 是两个独立操作，不构成跨账户数据库事务。此处按
 * 账户对串行化、在锁内复核余额，并在存款失败时验证补偿回滚结果。
 */
class EconomyProvider private constructor(private val economy: Economy) {

    enum class Result { SUCCESS, NO_ECONOMY, INVALID_AMOUNT, INSUFFICIENT, ROLLBACK_FAILED, FAILED }

    /**
     * 在两个账户之间执行串行化的补偿式转账：复核余额 -> 扣款 -> 存款。
     * 存款失败时尝试退款；退款本身仍可能被第三方经济插件拒绝。
     *
     * 注意：应在主线程/全局线程调用（命令预处理事件即在此线程）。
     */
    @Suppress("DEPRECATION") // Vault 的命名账户 API 才能正确命中 CMI/Lands 维护的虚拟账户。
    fun transfer(fromName: String, toName: String, amount: Double): Result {
        if (!amount.isFinite() || amount <= 0.0) return Result.INVALID_AMOUNT

        val fromAccounts = resolveAccounts(fromName)
        val toAccount = resolvePreferredAccount(toName) ?: return Result.FAILED
        if (fromAccounts.isEmpty()) return Result.FAILED

        val pairKey = normalizedPairKey(fromName, toName)
        return transferLocks.computeIfAbsent(pairKey) { ReentrantLock() }.withLock {
            val fromAccount = fromAccounts.firstOrNull { has(it, amount) }
                ?: return@withLock Result.INSUFFICIENT
            val withdraw = withdraw(fromAccount, amount)
            if (!withdraw.transactionSuccess()) return@withLock Result.FAILED

            val deposit = deposit(toAccount, amount)
            if (!deposit.transactionSuccess()) {
                val rollback = deposit(fromAccount, amount)
                return@withLock if (rollback.transactionSuccess()) Result.FAILED else Result.ROLLBACK_FAILED
            }
            Result.SUCCESS
        }
    }

    private fun normalizedPairKey(first: String, second: String): String {
        val a = first.trim().lowercase()
        val b = second.trim().lowercase()
        return if (a <= b) "$a\u0000$b" else "$b\u0000$a"
    }

    @Suppress("DEPRECATION")
    private fun resolveAccounts(accountName: String): List<AccountRef> {
        val normalized = accountName.trim()
        if (normalized.isEmpty()) return emptyList()

        val explicitUuid = parseExplicitUuid(normalized)
        if (explicitUuid != null) {
            return listOf(AccountRef(normalized, Bukkit.getOfflinePlayer(explicitUuid)))
        }

        val accounts = ArrayList<AccountRef>()
        val seenPlayers = HashSet<UUID>()

        val online = Bukkit.getPlayerExact(normalized)
        if (online != null && online.uniqueId.isVersion4()) {
            accounts.add(AccountRef(normalized, online))
            seenPlayers.add(online.uniqueId)
        }

        val knownPlayers = Bukkit.getOfflinePlayers()
            .filter { offline -> offline.name?.equals(normalized, ignoreCase = true) == true }
            .sortedWith(
                compareByDescending<OfflinePlayer> { offline -> offline.uniqueId.isVersion4() }
                    .thenBy { offline -> offline.uniqueId.toString() },
            )

        for (offline in knownPlayers) {
            if (seenPlayers.add(offline.uniqueId)) {
                accounts.add(AccountRef(normalized, offline))
            }
        }

        if (online != null && seenPlayers.add(online.uniqueId)) {
            accounts.add(AccountRef(normalized, online))
        }

        val generated = Bukkit.getOfflinePlayer(normalized)
        if (seenPlayers.add(generated.uniqueId)) {
            accounts.add(AccountRef(normalized, generated))
        }

        accounts.add(AccountRef(normalized, null))
        return accounts
    }

    private fun parseExplicitUuid(accountName: String): UUID? {
        val value = if (accountName.startsWith("uuid:", ignoreCase = true)) {
            accountName.substring("uuid:".length)
        } else {
            accountName
        }
        return try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun UUID.isVersion4(): Boolean = version() == 4

    private fun resolvePreferredAccount(accountName: String): AccountRef? = resolveAccounts(accountName).firstOrNull()

    @Suppress("DEPRECATION")
    private fun has(account: AccountRef, amount: Double): Boolean =
        if (account.player != null) {
            economy.has(account.player, amount)
        } else {
            economy.has(account.name, amount)
        }

    @Suppress("DEPRECATION")
    private fun withdraw(account: AccountRef, amount: Double): EconomyResponse =
        if (account.player != null) {
            economy.withdrawPlayer(account.player, amount)
        } else {
            economy.withdrawPlayer(account.name, amount)
        }

    @Suppress("DEPRECATION")
    private fun deposit(account: AccountRef, amount: Double): EconomyResponse =
        if (account.player != null) {
            economy.depositPlayer(account.player, amount)
        } else {
            economy.depositPlayer(account.name, amount)
        }

    private data class AccountRef(val name: String, val player: OfflinePlayer?)

    companion object {
        private val transferLocks: MutableMap<String, ReentrantLock> = ConcurrentHashMap()

        /** 从 Vault 服务注册中获取 Economy provider；Vault 或经济插件缺失时返回 null。 */
        @JvmStatic
        fun hook(plugin: Plugin): EconomyProvider? {
            if (plugin.server.pluginManager.getPlugin("Vault") == null) return null
            val registration = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return null
            return EconomyProvider(registration.provider)
        }
    }
}

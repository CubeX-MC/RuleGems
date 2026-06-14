package org.cubexmc.economy

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin

/**
 * Vault 经济钩子，提供账户间的离线安全原子转账。
 *
 * Vault 的 Economy API 基于 OfflinePlayer(UUID) 设计，原生支持离线账户的余额查询与
 * 扣款。配合 CMI 等 provider（已开启 OfflinePayments），即可对离线的金库账户做
 * 「校验余额 -> 扣款 -> 存款」的原子操作，余额不足时整笔取消，绝不凭空造钱。
 */
class EconomyProvider private constructor(private val economy: Economy) {

    enum class Result { SUCCESS, NO_ECONOMY, INVALID_AMOUNT, INSUFFICIENT, FAILED }

    /**
     * 在两个账户之间原子转账：校验 from 余额 -> 从 from 扣款 -> 给 to 存款。
     * 任一步失败都会回滚，保证不会出现单边扣款或单边发钱。
     *
     * 注意：应在主线程/全局线程调用（命令预处理事件即在此线程）。
     */
    @Suppress("DEPRECATION") // 按名字解析离线账户；金库账户由 Lands/CMI 维护，通常已缓存
    fun transfer(fromName: String, toName: String, amount: Double): Result {
        if (!amount.isFinite() || amount <= 0.0) return Result.INVALID_AMOUNT

        val from: OfflinePlayer = Bukkit.getOfflinePlayer(fromName)
        val to: OfflinePlayer = Bukkit.getOfflinePlayer(toName)

        if (!economy.has(from, amount)) return Result.INSUFFICIENT

        val withdraw = economy.withdrawPlayer(from, amount)
        if (!withdraw.transactionSuccess()) return Result.FAILED

        val deposit = economy.depositPlayer(to, amount)
        if (!deposit.transactionSuccess()) {
            // 存款失败则回滚扣款，避免金库无故减少
            economy.depositPlayer(from, amount)
            return Result.FAILED
        }
        return Result.SUCCESS
    }

    companion object {
        /** 从 Vault 服务注册中获取 Economy provider；Vault 或经济插件缺失时返回 null。 */
        @JvmStatic
        fun hook(plugin: Plugin): EconomyProvider? {
            if (plugin.server.pluginManager.getPlugin("Vault") == null) return null
            val registration = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return null
            return EconomyProvider(registration.provider)
        }
    }
}

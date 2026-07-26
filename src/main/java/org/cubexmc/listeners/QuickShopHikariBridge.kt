package org.cubexmc.listeners

import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.EventExecutor
import org.cubexmc.RuleGems
import org.cubexmc.manager.GemManager
import org.cubexmc.manager.LanguageManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Optional
import java.util.logging.Level

/**
 * Optional QuickShop-Hikari integration without a binary dependency on QuickShop.
 *
 * RuleGems still targets Spigot 1.16.5/Java 17, while current QuickShop builds target
 * newer server APIs. Loading event classes from QuickShop's own class loader keeps
 * this plugin loadable when QuickShop is absent and avoids linking newer API types.
 */
class QuickShopHikariBridge(
    private val plugin: RuleGems,
    private val gemManager: GemManager,
    private val languageManager: LanguageManager,
) : Listener {
    fun register(): QuickShopIntegrationHealth {
        val quickShop = plugin.server.pluginManager.getPlugin(QUICKSHOP_PLUGIN_NAME)
            ?: return QuickShopIntegrationHealth.absent()
        if (!quickShop.isEnabled) {
            return QuickShopIntegrationHealth(
                QuickShopHealthStatus.FAILED,
                "QuickShop-Hikari is installed but not enabled",
            )
        }

        val loader = quickShop.javaClass.classLoader
        val contractFailure = validateContract(loader)
        if (contractFailure != null) {
            plugin.logger.severe("QuickShop-Hikari protection contract is unsupported: $contractFailure")
            return QuickShopIntegrationHealth(QuickShopHealthStatus.UNSUPPORTED, contractFailure)
        }
        val purchaseRegistered = registerEvent(loader, PURCHASE_EVENT_CLASS, ::handlePurchaseEvent)
        val createRegistered = registerEvent(loader, CREATE_EVENT_CLASS, ::handleCreateEvent)

        if (purchaseRegistered && createRegistered) {
            plugin.logger.info("QuickShop-Hikari protection enabled for shop purchases and creation.")
            return QuickShopIntegrationHealth(
                QuickShopHealthStatus.ACTIVE,
                "QuickShop-Hikari purchase, sale, and creation protection is active",
            )
        }
        val detail =
            "QuickShop-Hikari is installed, but RuleGems could not register every required safety hook. " +
                "Rule Gems must not be traded."
        plugin.logger.severe(detail)
        return QuickShopIntegrationHealth(
            QuickShopHealthStatus.FAILED,
            detail,
        )
    }

    /**
     * ShopPurchaseEvent is fired before QuickShop commits either the economy transaction
     * or the item transfer. Cancelling here prevents both buy-from-shop and sell-to-shop.
     */
    fun handlePurchaseEvent(event: Event): Boolean = blockIfShopItemContainsGem(event, false)

    /**
     * ShopCreateEvent is phased. Only PRE_CANCELLABLE may be cancelled; MAIN/POST must
     * be ignored because QuickShop deliberately throws if cancellation is attempted there.
     */
    fun handleCreateEvent(event: Event): Boolean = blockIfShopItemContainsGem(event, true)

    private fun registerEvent(
        loader: ClassLoader,
        className: String,
        handler: (Event) -> Boolean,
    ): Boolean {
        val eventClass = try {
            loader.loadClass(className).asSubclass(Event::class.java)
        } catch (error: Throwable) {
            plugin.logger.log(Level.WARNING, "QuickShop event class unavailable: $className", error)
            return false
        }

        return try {
            plugin.server.pluginManager.registerEvent(
                eventClass,
                this,
                EventPriority.HIGHEST,
                EventExecutor { _, event -> handler(event) },
                plugin,
                true,
            )
            true
        } catch (error: Throwable) {
            plugin.logger.log(Level.WARNING, "Unable to register QuickShop safety hook: $className", error)
            false
        }
    }

    private fun validateContract(loader: ClassLoader): String? {
        val purchaseEvent = loadEventClass(loader, PURCHASE_EVENT_CLASS)
            ?: return "missing or invalid event class $PURCHASE_EVENT_CLASS"
        val createEvent = loadEventClass(loader, CREATE_EVENT_CLASS)
            ?: return "missing or invalid event class $CREATE_EVENT_CLASS"
        val shopClass = try {
            loader.loadClass(SHOP_CLASS)
        } catch (_: Throwable) {
            return "missing shop API class $SHOP_CLASS"
        }

        if (!Cancellable::class.java.isAssignableFrom(purchaseEvent)) {
            return "$PURCHASE_EVENT_CLASS is not cancellable"
        }
        if (!Cancellable::class.java.isAssignableFrom(createEvent)) {
            return "$CREATE_EVENT_CLASS is not cancellable"
        }
        if (!hasAnyMethod(purchaseEvent, "getShop", "shop")) {
            return "$PURCHASE_EVENT_CLASS has no supported shop accessor"
        }
        if (!hasAnyMethod(createEvent, "getShop", "shop")) {
            return "$CREATE_EVENT_CLASS has no supported shop accessor"
        }
        if (findMethod(shopClass, "getItem")?.returnType?.let {
                ItemStack::class.java.isAssignableFrom(it)
            } != true
        ) {
            return "$SHOP_CLASS#getItem does not return ItemStack"
        }
        val phaseMethod = findMethod(createEvent, "phase") ?: findMethod(createEvent, "getPhase")
            ?: return "$CREATE_EVENT_CLASS has no supported phase accessor"
        val phaseClass = phaseMethod.returnType
        val cancellablePhaseMethod = findMethod(phaseClass, "cancellable")
            ?: findMethod(phaseClass, "isCancellable")
            ?: return "${phaseClass.name} has no cancellable phase query"
        if (cancellablePhaseMethod.returnType != Boolean::class.javaPrimitiveType &&
            cancellablePhaseMethod.returnType != Boolean::class.java
        ) {
            return "${phaseClass.name} cancellable phase query is not boolean"
        }
        val supportsReasonCancellation =
            findMethod(purchaseEvent, "setCancelled", Boolean::class.javaPrimitiveType!!, String::class.java) != null &&
                findMethod(createEvent, "setCancelled", Boolean::class.javaPrimitiveType!!, String::class.java) != null
        if (!supportsReasonCancellation &&
            (!Cancellable::class.java.isAssignableFrom(purchaseEvent) ||
                !Cancellable::class.java.isAssignableFrom(createEvent))
        ) {
            return "QuickShop events expose no supported cancellation method"
        }
        return null
    }

    private fun loadEventClass(loader: ClassLoader, className: String): Class<out Event>? =
        try {
            loader.loadClass(className).asSubclass(Event::class.java)
        } catch (_: Throwable) {
            null
        }

    private fun hasAnyMethod(type: Class<*>, vararg names: String): Boolean =
        names.any { name -> findMethod(type, name) != null }

    private fun blockIfShopItemContainsGem(event: Event, phased: Boolean): Boolean {
        if (event is Cancellable && event.isCancelled) return false
        if (phased && !isCancellablePhase(event)) return false

        val shop = extractShop(event) ?: return false
        val item = invokeNoArgs(shop, "getItem") as? ItemStack ?: return false
        if (!gemManager.containsGem(item)) return false

        return cancel(event)
    }

    private fun extractShop(event: Event): Any? {
        val raw = invokeNoArgs(event, "getShop") ?: invokeNoArgs(event, "shop") ?: return null
        return if (raw is Optional<*>) raw.orElse(null) else raw
    }

    private fun isCancellablePhase(event: Event): Boolean {
        val phase = invokeNoArgs(event, "phase") ?: invokeNoArgs(event, "getPhase") ?: return true
        return (invokeNoArgs(phase, "cancellable") as? Boolean)
            ?: (invokeNoArgs(phase, "isCancellable") as? Boolean)
            ?: true
    }

    private fun cancel(event: Event): Boolean {
        val reason = languageManager.getMessage("messages.inventory.container_denied")
        val reasonMethod = findMethod(event.javaClass, "setCancelled", Boolean::class.javaPrimitiveType!!, String::class.java)
        if (reasonMethod != null) {
            try {
                reasonMethod.invoke(event, true, reason)
                return true
            } catch (error: InvocationTargetException) {
                if (error.cause is IllegalStateException) return false
                plugin.logger.fine("QuickShop rejected its cancellation reason: " + error.cause?.message)
            } catch (error: ReflectiveOperationException) {
                plugin.logger.fine("Unable to attach a QuickShop cancellation reason: " + error.message)
            }
        }

        val cancellable = event as? Cancellable ?: return false
        return try {
            cancellable.isCancelled = true
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun invokeNoArgs(target: Any, methodName: String): Any? {
        val method = findMethod(target.javaClass, methodName) ?: return null
        return try {
            method.invoke(target)
        } catch (error: ReflectiveOperationException) {
            plugin.logger.fine(
                "QuickShop compatibility call ${target.javaClass.name}#$methodName failed: " + error.message,
            )
            null
        }
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method? {
        return try {
            type.getMethod(name, *parameters).also { it.isAccessible = true }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private const val QUICKSHOP_PLUGIN_NAME = "QuickShop-Hikari"
        private const val PURCHASE_EVENT_CLASS = "com.ghostchu.quickshop.api.event.economy.ShopPurchaseEvent"
        private const val CREATE_EVENT_CLASS = "com.ghostchu.quickshop.api.event.management.ShopCreateEvent"
        private const val SHOP_CLASS = "com.ghostchu.quickshop.api.shop.Shop"
    }
}

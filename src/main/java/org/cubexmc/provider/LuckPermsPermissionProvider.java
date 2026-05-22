package org.cubexmc.provider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.cubexmc.RuleGems;

/**
 * Optional LuckPerms bridge implemented through reflection so RuleGems can run
 * without a hard LuckPerms API dependency.
 */
public class LuckPermsPermissionProvider implements PermissionProvider {

    private final RuleGems plugin;
    private final Object luckPerms;
    private final Object userManager;
    private final Class<?> nodeClass;
    private final Class<?> inheritanceNodeClass;

    public LuckPermsPermissionProvider(RuleGems plugin) {
        this.plugin = plugin;
        Object loadedLuckPerms = null;
        Object loadedUserManager = null;
        Class<?> loadedNodeClass = null;
        Class<?> loadedInheritanceNodeClass = null;
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            loadedLuckPerms = providerClass.getMethod("get").invoke(null);
            loadedUserManager = loadedLuckPerms.getClass().getMethod("getUserManager").invoke(loadedLuckPerms);
            loadedNodeClass = Class.forName("net.luckperms.api.node.Node");
            loadedInheritanceNodeClass = Class.forName("net.luckperms.api.node.types.InheritanceNode");
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().fine("LuckPerms API is not available: " + e.getMessage());
        }
        this.luckPerms = loadedLuckPerms;
        this.userManager = loadedUserManager;
        this.nodeClass = loadedNodeClass;
        this.inheritanceNodeClass = loadedInheritanceNodeClass;
    }

    @Override
    public boolean isAvailable() {
        return luckPerms != null && userManager != null && nodeClass != null && inheritanceNodeClass != null;
    }

    @Override
    public boolean supportsContext() {
        return isAvailable();
    }

    @Override
    public void addPermission(Player player, String permission) {
        setPermission(player, permission, null, true);
    }

    @Override
    public void removePermission(Player player, String permission) {
        setPermission(player, permission, null, false);
    }

    @Override
    public void addGroup(Player player, String group) {
        modifyUser(player, data -> addNode(data, buildInheritanceNode(group)));
    }

    @Override
    public void removeGroup(Player player, String group) {
        modifyUser(player, data -> removeNode(data, buildInheritanceNode(group)));
    }

    @Override
    public boolean setPermission(Player player, String permission, Map<String, String> context, boolean value) {
        if (!isAvailable() || player == null || isBlank(permission)) {
            return false;
        }
        Object node = buildPermissionNode(permission.trim(), context);
        if (node == null) {
            return false;
        }
        modifyUser(player, data -> {
            if (value) {
                addNode(data, node);
            } else {
                removeNode(data, node);
            }
        });
        return true;
    }

    @Override
    public String getName() {
        return "LuckPerms";
    }

    private void modifyUser(Player player, Consumer<Object> dataOperation) {
        if (!isAvailable() || player == null || dataOperation == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        try {
            Method modifyUser = userManager.getClass().getMethod("modifyUser", UUID.class, Consumer.class);
            modifyUser.invoke(userManager, playerId, (Consumer<Object>) user -> dataOperation.accept(userData(user)));
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to modify LuckPerms user '" + player.getName() + "': " + e.getMessage());
        }
    }

    private Object userData(Object user) {
        try {
            return user.getClass().getMethod("data").invoke(user);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Failed to access LuckPerms user data", e);
        }
    }

    private Object buildPermissionNode(String permission, Map<String, String> context) {
        try {
            Object builder = nodeClass.getMethod("builder", String.class).invoke(null, permission);
            builder = applyContext(builder, context);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to build LuckPerms permission node '" + permission + "': " + e.getMessage());
            return null;
        }
    }

    private Object buildInheritanceNode(String group) {
        if (isBlank(group)) {
            return null;
        }
        try {
            Object builder = inheritanceNodeClass.getMethod("builder", String.class).invoke(null, group.trim());
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to build LuckPerms group node '" + group + "': " + e.getMessage());
            return null;
        }
    }

    private Object applyContext(Object builder, Map<String, String> context) throws ReflectiveOperationException {
        if (builder == null || context == null || context.isEmpty()) {
            return builder;
        }
        Method withContext = builder.getClass().getMethod("withContext", String.class, String.class);
        Object current = builder;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (isBlank(entry.getKey()) || isBlank(entry.getValue())) {
                continue;
            }
            Object next = withContext.invoke(current, entry.getKey().trim(), entry.getValue().trim());
            if (next != null) {
                current = next;
            }
        }
        return current;
    }

    private void addNode(Object data, Object node) {
        invokeDataMutation(data, "add", node);
    }

    private void removeNode(Object data, Object node) {
        invokeDataMutation(data, "remove", node);
    }

    private void invokeDataMutation(Object data, String methodName, Object node) {
        if (data == null || node == null) {
            return;
        }
        try {
            Method method = data.getClass().getMethod(methodName, nodeClass);
            method.invoke(data, node);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to mutate LuckPerms node: " + e.getMessage());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

package org.cubexmc.model;

import java.util.HashSet;
import java.util.Set;

/**
 * 离线撤销队列的聚合数据结构。
 * 合并了原先分散的 permissions / groups / keys / effects 四个 Map。
 */
public class PendingRevoke {
    private final Set<String> permissions;
    private final Set<String> groups;
    private final Set<String> keys;
    private final Set<String> effects;

    public PendingRevoke() {
        this.permissions = new HashSet<>();
        this.groups = new HashSet<>();
        this.keys = new HashSet<>();
        this.effects = new HashSet<>();
    }

    public PendingRevoke(Set<String> permissions, Set<String> groups, Set<String> keys, Set<String> effects) {
        this.permissions = permissions != null ? permissions : new HashSet<>();
        this.groups = groups != null ? groups : new HashSet<>();
        this.keys = keys != null ? keys : new HashSet<>();
        this.effects = effects != null ? effects : new HashSet<>();
    }

    public Set<String> getPermissions() { return permissions; }
    public Set<String> getGroups() { return groups; }
    public Set<String> getKeys() { return keys; }
    public Set<String> getEffects() { return effects; }

    public boolean isEmpty() {
        return permissions.isEmpty() && groups.isEmpty() && keys.isEmpty() && effects.isEmpty();
    }
}

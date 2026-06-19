package de.eternal.core.integration;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Thin reflective bridge to the CloudNet 4 driver's permission API.
 * We intentionally avoid a compile-time dependency: CloudNet versions and
 * coordinates drift, but the public type names are stable enough to call by
 * reflection. If CloudNet isn't present (or the API changed), available()
 * returns false and the integration falls back to "no extra groups".
 */
public final class CloudPermsAccess {

    private static final String[] MANAGEMENT_TYPES = {
            "eu.cloudnetservice.driver.permission.PermissionManagement",
            "de.dytanic.cloudnet.driver.permission.IPermissionManagement"
    };

    private static final String[] INJECTION_HOLDERS = {
            "eu.cloudnetservice.driver.inject.InjectionLayer",
            "eu.cloudnetservice.driver.CloudNetDriver",
            "de.dytanic.cloudnet.driver.CloudNetDriver"
    };

    private final Logger logger;
    private Object management;
    private Method userMethod;
    private Method groupNamesMethod;

    public CloudPermsAccess(@NotNull Logger logger) {
        this.logger = logger;
        bootstrap();
    }

    public boolean available() {
        return management != null && userMethod != null && groupNamesMethod != null;
    }

    /**
     * Highest sort-id / potency across the user's CloudNet groups.
     * Returns empty when CloudNet isn't reachable or the user is unknown —
     * callers should treat that as "no CloudNet tier known" and fall back to
     * permission-based tier resolution.
     *
     * <p>Looks up each group of the user, reads its potency/sortId via
     * reflection, and returns the maximum. Method names are tried in the order
     * sortId() → getSortId() → potency() → getPotency() so we work across
     * CN3 and CN4.</p>
     */
    public @NotNull OptionalInt sortIdOf(@NotNull UUID uuid) {
        if (!available()) return OptionalInt.empty();
        try {
            Object user = userMethod.invoke(management, uuid);
            if (user == null) return OptionalInt.empty();
            Object groupsRaw = groupNamesMethod.invoke(user);
            if (!(groupsRaw instanceof java.util.Collection<?> col) || col.isEmpty()) return OptionalInt.empty();

            // Resolve group object for each name. Method names differ across CN versions.
            Method groupByName = null;
            for (String mname : new String[]{"group", "getGroup"}) {
                try { groupByName = management.getClass().getMethod(mname, String.class); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (groupByName == null) return OptionalInt.empty();

            int best = Integer.MIN_VALUE;
            for (Object name : col) {
                Object group = groupByName.invoke(management, String.valueOf(name));
                if (group == null) continue;
                int potency = readPotency(group);
                if (potency > best) best = potency;
            }
            return best == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
        } catch (Throwable t) {
            logger.warning("CloudPerms sortId lookup failed for " + uuid + ": " + t.getMessage());
            return OptionalInt.empty();
        }
    }

    private static int readPotency(@NotNull Object group) {
        for (String mname : new String[]{"sortId", "getSortId", "potency", "getPotency"}) {
            try {
                Method m = group.getClass().getMethod(mname);
                Object val = m.invoke(group);
                if (val instanceof Number n) return n.intValue();
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                // try next name
            }
        }
        return 0;
    }

    /**
     * A CloudNet group's OWN permission nodes as {@code name → granted}.
     * {@code granted = potency >= 0} (CloudNet's negative potency = denied node).
     * Reflective across CN3/CN4: resolve the group, read its permission
     * collection ({@code permissions()} / {@code getPermissions()}), then each
     * entry's name + potency. Group INHERITANCE is not resolved — only the
     * group's own nodes. Empty when CloudNet isn't reachable / group unknown.
     */
    public @NotNull java.util.Map<String, Boolean> groupPermissions(@NotNull String name) {
        if (!available()) return Collections.emptyMap();
        try {
            Method groupByName = null;
            for (String mname : new String[]{"group", "getGroup"}) {
                try { groupByName = management.getClass().getMethod(mname, String.class); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (groupByName == null) return Collections.emptyMap();
            Object group = groupByName.invoke(management, name);
            if (group == null) return Collections.emptyMap();

            Object permsRaw = null;
            for (String mname : new String[]{"permissions", "getPermissions"}) {
                try {
                    permsRaw = group.getClass().getMethod(mname).invoke(group);
                    if (permsRaw != null) break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (!(permsRaw instanceof java.util.Collection<?> col)) return Collections.emptyMap();

            java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            for (Object perm : col) {
                if (perm == null) continue;
                String pname = readPermissionName(perm);
                if (pname == null || pname.isEmpty()) continue;
                out.put(pname, readPermissionPotency(perm) >= 0);
            }
            return out;
        } catch (Throwable t) {
            logger.warning("CloudPerms groupPermissions failed for " + name + ": " + t.getMessage());
            return Collections.emptyMap();
        }
    }

    private static @org.jetbrains.annotations.Nullable String readPermissionName(@NotNull Object perm) {
        for (String mname : new String[]{"name", "getName"}) {
            try {
                Object v = perm.getClass().getMethod(mname).invoke(perm);
                if (v != null) return String.valueOf(v);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored2) { /* try next */ }
        }
        return null;
    }

    private static int readPermissionPotency(@NotNull Object perm) {
        for (String mname : new String[]{"potency", "getPotency"}) {
            try {
                Object v = perm.getClass().getMethod(mname).invoke(perm);
                if (v instanceof Number n) return n.intValue();
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored2) { /* try next */ }
        }
        return 0;
    }

    /* --- permission WRITE (web → CloudPerms) ---------------------------- */

    /** Set a permission node on a CloudNet USER (potency 1 = grant, -1 = deny),
     *  persisted centrally so it applies to the offline player too. */
    public boolean setUserPermission(@NotNull UUID uuid, @NotNull String node, boolean granted) {
        if (!available()) return false;
        try {
            Object user = userMethod.invoke(management, uuid);
            if (user == null) return false;
            if (!writePermission(user, node, granted)) return false;
            return pushUserUpdate(user);
        } catch (Throwable t) {
            logger.warning("setUserPermission failed for " + uuid + " " + node + ": " + t.getMessage());
            return false;
        }
    }

    /** Remove a permission node from a CloudNet USER. */
    public boolean removeUserPermission(@NotNull UUID uuid, @NotNull String node) {
        if (!available()) return false;
        try {
            Object user = userMethod.invoke(management, uuid);
            if (user == null) return false;
            invokeOneArg(user, "removePermission", node);
            return pushUserUpdate(user);
        } catch (Throwable t) {
            logger.warning("removeUserPermission failed for " + uuid + " " + node + ": " + t.getMessage());
            return false;
        }
    }

    /** Set a permission node on a CloudNet GROUP (affects every member). */
    public boolean setGroupPermission(@NotNull String groupName, @NotNull String node, boolean granted) {
        if (!available()) return false;
        try {
            Object group = resolveGroup(groupName);
            if (group == null) return false;
            if (!writePermission(group, node, granted)) return false;
            return pushGroupUpdate(group);
        } catch (Throwable t) {
            logger.warning("setGroupPermission failed for " + groupName + " " + node + ": " + t.getMessage());
            return false;
        }
    }

    /** Remove a permission node from a CloudNet GROUP. */
    public boolean removeGroupPermission(@NotNull String groupName, @NotNull String node) {
        if (!available()) return false;
        try {
            Object group = resolveGroup(groupName);
            if (group == null) return false;
            invokeOneArg(group, "removePermission", node);
            return pushGroupUpdate(group);
        } catch (Throwable t) {
            logger.warning("removeGroupPermission failed for " + groupName + " " + node + ": " + t.getMessage());
            return false;
        }
    }

    private @org.jetbrains.annotations.Nullable Object resolveGroup(@NotNull String name) {
        for (String mname : new String[]{"group", "getGroup"}) {
            try { return management.getClass().getMethod(mname, String.class).invoke(management, name); }
            catch (NoSuchMethodException ignored) {}
            catch (Throwable t) { return null; }
        }
        return null;
    }

    /** Writes a permission onto a permissible (user/group): builds a Permission
     *  with the right potency for deny-support, falling back to the String
     *  overload (grant only) when no Permission object could be built. */
    private static boolean writePermission(@NotNull Object permissible, @NotNull String node, boolean granted) {
        Object perm = buildPermission(node, granted ? 1 : -1);
        if (perm != null && invokeOneArg(permissible, "addPermission", perm)) return true;
        return granted && invokeOneArg(permissible, "addPermission", node);
    }

    /** Reflectively build a CloudNet {@code Permission} (CN4 builder → CN3 ctor
     *  → CN4 {@code of(String)}). Null when the class/shape isn't found. */
    private static @org.jetbrains.annotations.Nullable Object buildPermission(@NotNull String node, int potency) {
        Class<?> permClass = null;
        for (String fqn : new String[]{
                "eu.cloudnetservice.driver.permission.Permission",
                "de.dytanic.cloudnet.driver.permission.Permission"}) {
            try { permClass = Class.forName(fqn); break; } catch (ClassNotFoundException ignored) {}
        }
        if (permClass == null) return null;
        try {
            Object builder = permClass.getMethod("builder").invoke(null);
            invokeOneArg(builder, "name", node);
            invokeOneArg(builder, "potency", potency);
            Object p = builder.getClass().getMethod("build").invoke(builder);
            if (p != null) return p;
        } catch (Throwable ignored) { /* try next shape */ }
        try {
            return permClass.getConstructor(String.class, int.class).newInstance(node, potency);
        } catch (Throwable ignored) { /* try next shape */ }
        try {
            return permClass.getMethod("of", String.class).invoke(null, node);
        } catch (Throwable ignored) { /* give up */ }
        return null;
    }

    /** Invoke the first single-arg method named {@code name} whose parameter the
     *  arg fits (handles int/Integer autobox). Returns true on a successful call. */
    private static boolean invokeOneArg(@NotNull Object target, @NotNull String name, @NotNull Object arg) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
            Class<?> pt = m.getParameterTypes()[0];
            boolean fits = pt.isInstance(arg)
                    || ((pt == int.class || pt == Integer.class) && arg instanceof Integer);
            if (!fits) continue;
            try { m.invoke(target, arg); return true; }
            catch (Throwable t) { return false; }
        }
        return false;
    }

    /** Persists a modified PermissionGroup back to CloudNet (CN3 + CN4). */
    private boolean pushGroupUpdate(@NotNull Object group) {
        for (String mname : new String[]{"updateGroup", "updatePermissionGroup"}) {
            for (Method m : management.getClass().getMethods()) {
                if (!m.getName().equals(mname) || m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(group)) continue;
                try { m.invoke(management, group); return true; }
                catch (Throwable t) {
                    logger.warning("pushGroupUpdate via " + mname + " failed: " + t.getMessage());
                    return false;
                }
            }
        }
        logger.warning("pushGroupUpdate: no updateGroup method matched the group type");
        return false;
    }

    /**
     * Adds {@code group} to the CloudNet permission user. Works for
     * offline players too — CloudNet's permission store is central, not
     * per-server. Returns true on success.
     *
     * <p>Flow (reflective, CN3 + CN4): resolve the PermissionUser,
     * call {@code addGroup(String)} on it, then {@code updateUser(user)}
     * to persist. Both versions share this shape; we tolerate the
     * method-name drift the same way the read path does.</p>
     */
    public boolean addUserGroup(@NotNull UUID uuid, @NotNull String group) {
        return mutateUserGroups(uuid, "addGroup", group);
    }

    /** Removes {@code group} from the CloudNet permission user. */
    public boolean removeUserGroup(@NotNull UUID uuid, @NotNull String group) {
        return mutateUserGroups(uuid, "removeGroup", group);
    }

    /**
     * "Change rank" in the intuitive sense: drop every group the user is
     * currently in and put them into exactly {@code group}. Implemented
     * as removeGroup(each existing) + addGroup(group) on a single user
     * object, then one updateUser. Returns true when the update was
     * pushed (even if the user was already only in that group).
     */
    public boolean setPrimaryGroup(@NotNull UUID uuid, @NotNull String group) {
        if (!available()) return false;
        try {
            Object user = userMethod.invoke(management, uuid);
            if (user == null) {
                logger.warning("setPrimaryGroup: CloudNet has no user row for " + uuid);
                return false;
            }
            // Current groups → remove all, then add the new one.
            Object groupsRaw = groupNamesMethod.invoke(user);
            Method removeGroup = findMethod(user.getClass(), "removeGroup", String.class);
            Method addGroup = findMethod(user.getClass(), "addGroup", String.class);
            if (removeGroup == null || addGroup == null) {
                logger.warning("setPrimaryGroup: PermissionUser lacks add/removeGroup methods");
                return false;
            }
            if (groupsRaw instanceof java.util.Collection<?> col) {
                for (Object g : new java.util.ArrayList<>(col)) {
                    if (!String.valueOf(g).equalsIgnoreCase(group)) {
                        removeGroup.invoke(user, String.valueOf(g));
                    }
                }
            }
            addGroup.invoke(user, group);
            return pushUserUpdate(user);
        } catch (Throwable t) {
            logger.warning("setPrimaryGroup failed for " + uuid + ": " + t.getMessage());
            return false;
        }
    }

    /** Shared add/remove helper. {@code op} is "addGroup" or
     *  "removeGroup". Resolves the user, invokes the op, pushes the
     *  update back. */
    private boolean mutateUserGroups(@NotNull UUID uuid, @NotNull String op, @NotNull String group) {
        if (!available()) return false;
        try {
            Object user = userMethod.invoke(management, uuid);
            if (user == null) {
                logger.warning(op + ": CloudNet has no user row for " + uuid);
                return false;
            }
            Method m = findMethod(user.getClass(), op, String.class);
            if (m == null) {
                logger.warning(op + ": PermissionUser has no " + op + "(String) method");
                return false;
            }
            m.invoke(user, group);
            return pushUserUpdate(user);
        } catch (Throwable t) {
            logger.warning(op + " failed for " + uuid + ": " + t.getMessage());
            return false;
        }
    }

    /** Persists a modified PermissionUser back to CloudNet. CN3 + CN4
     *  both expose {@code updateUser(user)}; CN4 also has an async
     *  variant we don't need. */
    private boolean pushUserUpdate(@NotNull Object user) {
        for (String mname : new String[]{"updateUser", "updatePermissionUser"}) {
            try {
                Method m = management.getClass().getMethod(mname, user.getClass().getInterfaces().length > 0
                        ? user.getClass().getInterfaces()[0] : user.getClass());
                m.invoke(management, user);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Fall through to the param-scan variant below.
            } catch (Throwable t) {
                logger.warning("pushUserUpdate via " + mname + " failed: " + t.getMessage());
                return false;
            }
        }
        // Last resort: scan for any single-arg method named updateUser
        // whose parameter the user is assignable to (covers interface
        // vs impl-class mismatches across CN versions).
        for (Method m : management.getClass().getMethods()) {
            if (!m.getName().equals("updateUser") || m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isInstance(user)) continue;
            try {
                m.invoke(management, user);
                return true;
            } catch (Throwable t) {
                logger.warning("pushUserUpdate scan failed: " + t.getMessage());
                return false;
            }
        }
        logger.warning("pushUserUpdate: no updateUser method matched the user type");
        return false;
    }

    private static @org.jetbrains.annotations.Nullable Method findMethod(
            @NotNull Class<?> type, @NotNull String name, @NotNull Class<?>... params) {
        try { return type.getMethod(name, params); }
        catch (NoSuchMethodException ex) { return null; }
    }

    public @NotNull List<String> groupsOf(@NotNull UUID uuid) {
        if (!available()) return Collections.emptyList();
        try {
            Object user = userMethod.invoke(management, uuid);
            if (user == null) return Collections.emptyList();
            Object groups = groupNamesMethod.invoke(user);
            if (groups instanceof java.util.Collection<?> col) {
                List<String> out = new java.util.ArrayList<>(col.size());
                for (Object o : col) out.add(String.valueOf(o));
                return out;
            }
        } catch (Throwable t) {
            logger.warning("CloudPerms lookup failed for " + uuid + ": " + t.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Every group defined in CloudNet, as {@code (name, sortId, color)}
     * triples, sorted by ASCENDING sortId — CloudNet's convention is
     * lower sortId = higher rank, so the highest rank (e.g. Owner) comes
     * first. Empty when CloudNet isn't reachable.
     *
     * <p>Reflective: tries {@code groups()} (CN4) then {@code getGroups()}
     * (CN3), each returning a {@code Collection<PermissionGroup>}; reads
     * name + potency + colour off each element.</p>
     */
    public @NotNull List<GroupInfo> allGroups() {
        if (!available()) return Collections.emptyList();
        Object groupsRaw = null;
        for (String mname : new String[]{"groups", "getGroups"}) {
            try {
                Method m = management.getClass().getMethod(mname);
                groupsRaw = m.invoke(management);
                if (groupsRaw != null) break;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                logger.warning("CloudPerms allGroups via " + mname + " failed: " + t.getMessage());
            }
        }
        if (!(groupsRaw instanceof java.util.Collection<?> col)) return Collections.emptyList();
        List<GroupInfo> out = new java.util.ArrayList<>(col.size());
        for (Object group : col) {
            if (group == null) continue;
            String name = readGroupName(group);
            if (name == null || name.isEmpty()) continue;
            out.add(new GroupInfo(name, readPotency(group), readGroupColor(group)));
        }
        // Ascending sortId — lowest number is the highest rank, first.
        out.sort((a, b) -> Integer.compare(a.sortId(), b.sortId()));
        return out;
    }

    /** A CloudNet group surfaced to the dashboard. {@code color} is a
     *  Minecraft {@code &}-code derived from the group's colour/prefix,
     *  or empty when none could be read. */
    public record GroupInfo(@NotNull String name, int sortId, @NotNull String color) {
        /** Back-compat 2-arg constructor — colour defaults to empty. */
        public GroupInfo(@NotNull String name, int sortId) { this(name, sortId, ""); }
    }

    /** Pulls a usable &amp;-colour code off a CloudNet group. Tries the
     *  explicit {@code color()} first, then sniffs the last colour code
     *  out of {@code prefix()} (e.g. "&4&lOwner " → "&4"). Empty when
     *  neither yields something. */
    private static @NotNull String readGroupColor(@NotNull Object group) {
        // 1) explicit color() / getColor() — usually already an &-code.
        for (String mname : new String[]{"color", "getColor"}) {
            try {
                Object v = group.getClass().getMethod(mname).invoke(group);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty()) return s.startsWith("&") || s.startsWith("§")
                            ? s.replace('§', '&') : "&" + s;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored2) { /* try next */ }
        }
        // 2) last colour code in the prefix.
        for (String mname : new String[]{"prefix", "getPrefix"}) {
            try {
                Object v = group.getClass().getMethod(mname).invoke(group);
                if (v == null) continue;
                String prefix = String.valueOf(v).replace('§', '&');
                int last = prefix.lastIndexOf('&');
                if (last >= 0 && last + 1 < prefix.length()) {
                    char c = Character.toLowerCase(prefix.charAt(last + 1));
                    if ("0123456789abcdef".indexOf(c) >= 0) return "&" + c;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored2) { /* try next */ }
        }
        return "";
    }

    private static @org.jetbrains.annotations.Nullable String readGroupName(@NotNull Object group) {
        for (String mname : new String[]{"name", "getName"}) {
            try {
                Object v = group.getClass().getMethod(mname).invoke(group);
                if (v != null) return String.valueOf(v);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored2) { /* try next */ }
        }
        return null;
    }

    private void bootstrap() {
        Class<?> managementType = firstClass(MANAGEMENT_TYPES);
        if (managementType == null) {
            logger.info("CloudPerms PermissionManagement type not found — running without CloudNet bridge.");
            return;
        }
        Object mgmt = locateManagement(managementType);
        if (mgmt == null) {
            logger.warning("CloudPerms PermissionManagement instance could not be located via injection layer.");
            return;
        }
        try {
            // user(UUID) on CN4, getUser(UUID) on CN3
            Method user;
            try { user = managementType.getMethod("user", UUID.class); }
            catch (NoSuchMethodException ex) { user = managementType.getMethod("getUser", UUID.class); }

            Class<?> userType = user.getReturnType();
            // groupNames() on CN4, getGroupNames() on CN3
            Method names;
            try { names = userType.getMethod("groupNames"); }
            catch (NoSuchMethodException ex) { names = userType.getMethod("getGroupNames"); }

            this.management = mgmt;
            this.userMethod = user;
            this.groupNamesMethod = names;
            logger.info("CloudPerms-Bruecke aktiv (" + managementType.getName() + ").");
        } catch (Throwable t) {
            logger.warning("CloudPerms reflection setup failed: " + t.getMessage());
        }
    }

    private static Class<?> firstClass(@NotNull String[] names) {
        for (String n : names) {
            try { return Class.forName(n); }
            catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static Object locateManagement(@NotNull Class<?> managementType) {
        for (String holderName : INJECTION_HOLDERS) {
            try {
                Class<?> holder = Class.forName(holderName);
                // CN4: InjectionLayer.boot().instance(PermissionManagement.class)
                if (holderName.endsWith("InjectionLayer")) {
                    Method boot = holder.getMethod("boot");
                    Object layer = boot.invoke(null);
                    Method instance = layer.getClass().getMethod("instance", Class.class);
                    return instance.invoke(layer, managementType);
                }
                // CN3/CN4 driver instance
                Method getInstance;
                try { getInstance = holder.getMethod("instance"); }
                catch (NoSuchMethodException ex) { getInstance = holder.getMethod("getInstance"); }
                Object driver = getInstance.invoke(null);
                Method getPerms;
                try { getPerms = driver.getClass().getMethod("permissionManagement"); }
                catch (NoSuchMethodException ex) { getPerms = driver.getClass().getMethod("getPermissionManagement"); }
                return getPerms.invoke(driver);
            } catch (Throwable ignored) {
                // try next strategy
            }
        }
        return null;
    }
}

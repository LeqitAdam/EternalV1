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

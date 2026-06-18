package de.eternal.autonicker.nick;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

/**
 * Applies a name + skin change to a live player by rewriting the server-side
 * {@code GameProfile} reflectively (no compile-time NMS/authlib dependency, in
 * the spirit of {@code CloudPermsAccess}) and refreshing the entity for nearby
 * players via Bukkit hide/show — so everyone re-receives them with the new
 * identity. The nicked player's own first-person view is best-effort nudged via
 * ProtocolLib when it is installed; it is never required (the disguise is fully
 * visible to everyone else regardless).
 *
 * <p>Every reflective step is guarded: if the server's internals don't match,
 * {@link #apply} returns {@code false} and the caller falls back to a name-only
 * disguise (display + tab list name).</p>
 */
public final class NickApplier {

    private final Plugin plugin;

    public NickApplier(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /** Rewrites the profile to {@code name}/{@code skin} and refreshes viewers.
     *  Returns false if the reflective rewrite failed. */
    public boolean apply(@NotNull Player player, @NotNull String name,
                         @Nullable String skinValue, @Nullable String skinSignature) {
        boolean ok = setProfile(player, name, skinValue, skinSignature);
        if (ok) refresh(player);
        return ok;
    }

    /** Reads the player's current skin texture (value, signature) for later
     *  restoration. Returns null when none could be read. */
    public @Nullable String[] currentTextures(@NotNull Player player) {
        try {
            Object profile = profileFromHandle(invoke(player, "getHandle"));
            Object props = invoke(profile, "getProperties");
            Object textures = props.getClass().getMethod("get", Object.class).invoke(props, "textures");
            if (!(textures instanceof Collection<?> col) || col.isEmpty()) return null;
            Object prop = col.iterator().next();
            String value = String.valueOf(firstNonNull(prop, "getValue", "value"));
            Object sigObj = tryFirst(prop, "getSignature", "signature");
            return new String[]{value, sigObj == null ? null : String.valueOf(sigObj)};
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean setProfile(@NotNull Player player, @NotNull String name,
                               @Nullable String value, @Nullable String signature) {
        try {
            Object handle = invoke(player, "getHandle");
            Object profile = profileFromHandle(handle);
            UUID id = (UUID) firstNonNull(profile, "getId", "id");
            Class<?> gp = profile.getClass();
            Object newProfile = gp.getConstructor(UUID.class, String.class).newInstance(id, name);
            if (value != null) {
                Object props = invoke(newProfile, "getProperties");
                Class<?> propClass = Class.forName("com.mojang.authlib.properties.Property");
                Object property;
                try {
                    property = propClass.getConstructor(String.class, String.class, String.class)
                            .newInstance("textures", value, signature);
                } catch (NoSuchMethodException ex) {
                    property = propClass.getConstructor(String.class, String.class)
                            .newInstance("textures", value);
                }
                props.getClass().getMethod("put", Object.class, Object.class)
                        .invoke(props, "textures", property);
            }
            setHandleProfile(handle, newProfile, gp);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Autonick profile rewrite failed (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + ") — falling back to name-only.");
            return false;
        }
    }

    private Object profileFromHandle(@NotNull Object handle) throws Exception {
        for (String m : new String[]{"getGameProfile", "gameProfile", "getProfile"}) {
            try {
                Object r = handle.getClass().getMethod(m).invoke(handle);
                if (r != null && r.getClass().getName().endsWith("GameProfile")) return r;
            } catch (NoSuchMethodException ignored) {
                // try next
            }
        }
        Class<?> c = handle.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().endsWith("GameProfile")) {
                    f.setAccessible(true);
                    Object r = f.get(handle);
                    if (r != null) return r;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchFieldException("GameProfile not found on " + handle.getClass());
    }

    private void setHandleProfile(@NotNull Object handle, @NotNull Object newProfile, @NotNull Class<?> gp) throws Exception {
        Class<?> c = handle.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().endsWith("GameProfile") && f.getType().isAssignableFrom(gp)) {
                    f.setAccessible(true);
                    f.set(handle, newProfile); // non-static final instance field: allowed after setAccessible
                    return;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchFieldException("No settable GameProfile field on " + handle.getClass());
    }

    /** Force nearby clients to re-receive the player (new name + skin) via a
     *  hide/show cycle, then best-effort nudge the player's own view. */
    private void refresh(@NotNull Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) other.hidePlayer(plugin, player);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player)) other.showPlayer(plugin, player);
            }
            selfNudge(player);
        }, 2L);
    }

    /** Optional: if ProtocolLib is installed, resend the player's entity to
     *  themselves so their own view updates too. Reflective + fully guarded —
     *  no compile-time ProtocolLib dependency, no-op when it's absent. */
    private void selfNudge(@NotNull Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) return;
        try {
            Class<?> lib = Class.forName("com.comphenix.protocol.ProtocolLibrary");
            Object pm = lib.getMethod("getProtocolManager").invoke(null);
            pm.getClass().getMethod("updateEntity", org.bukkit.entity.Entity.class, java.util.List.class)
                    .invoke(pm, player, java.util.List.of(player));
        } catch (Throwable ignored) {
            // ProtocolLib API drift — the self-view nudge is purely cosmetic.
        }
    }

    /* --- tiny reflection helpers -------------------------------------- */

    private static Object invoke(@NotNull Object o, @NotNull String name) throws Exception {
        return o.getClass().getMethod(name).invoke(o);
    }

    private static Object firstNonNull(@NotNull Object o, @NotNull String... names) throws Exception {
        for (String n : names) {
            try {
                return o.getClass().getMethod(n).invoke(o);
            } catch (NoSuchMethodException ignored) {
                // try next
            }
        }
        throw new NoSuchMethodException(String.join("/", names));
    }

    private static @Nullable Object tryFirst(@NotNull Object o, @NotNull String... names) {
        for (String n : names) {
            try {
                return o.getClass().getMethod(n).invoke(o);
            } catch (Throwable ignored) {
                // try next
            }
        }
        return null;
    }
}

package de.eternal.core.storage.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.eternal.core.base.BaseStorage;
import de.eternal.core.chatlog.ChatLogStorage;
import de.eternal.core.config.DatabaseConfig;
import de.eternal.core.model.ChatLogEntry;
import de.eternal.core.model.ChatLogKind;
import de.eternal.core.model.Friend;
import de.eternal.core.model.Loc;
import de.eternal.core.model.FriendRequest;
import de.eternal.core.model.NickSession;
import de.eternal.core.model.Party;
import de.eternal.core.model.PartyInvite;
import de.eternal.core.model.PartyMember;
import de.eternal.core.model.PermissionGrant;
import de.eternal.core.model.PlayerPrefs;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.model.ActionEntry;
import de.eternal.core.model.LinkCode;
import de.eternal.core.model.LoginSession;
import de.eternal.core.model.ReportEntry;
import de.eternal.core.model.ReportStatus;
import de.eternal.core.model.Role;
import de.eternal.core.model.Session;
import de.eternal.core.model.StaffStat;
import de.eternal.core.model.UnbanAppeal;
import de.eternal.core.permission.PermissionStorage;
import de.eternal.core.social.SocialStorage;
import de.eternal.core.storage.EternalStorage;
import de.eternal.core.storage.StorageException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single SQL backend that handles both SQLite and MySQL via dialect switches.
 * Schema is identical except for AUTOINCREMENT vs AUTO_INCREMENT.
 */
public final class SqlStorage implements EternalStorage, PermissionStorage, SocialStorage, BaseStorage, ChatLogStorage {

    private final DatabaseConfig config;
    private HikariDataSource dataSource;

    public SqlStorage(@NotNull DatabaseConfig config) {
        this.config = config;
    }

    private boolean isSqlite() {
        return config.type() == DatabaseConfig.Type.SQLITE;
    }

    @Override
    public void init() {
        HikariConfig hc = new HikariConfig();
        if (isSqlite()) {
            try {
                Files.createDirectories(config.sqliteFile().getParent());
            } catch (Exception ex) {
                throw new StorageException("Could not create data folder for SQLite", ex);
            }
            hc.setJdbcUrl("jdbc:sqlite:" + config.sqliteFile().toAbsolutePath());
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setMaximumPoolSize(1);
        } else {
            hc.setJdbcUrl("jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
                    + "?useSSL=false&useUnicode=true&characterEncoding=utf8");
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hc.setUsername(config.username());
            hc.setPassword(config.password());
            hc.setMaximumPoolSize(config.poolSize());
        }
        hc.setPoolName("eternal-pool");
        this.dataSource = new HikariDataSource(hc);

        createSchema();
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private Connection conn() throws SQLException {
        return dataSource.getConnection();
    }

    private String pk() {
        return isSqlite() ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    private String textType() {
        return isSqlite() ? "TEXT" : "VARCHAR(255)";
    }

    private String longTextType() {
        return isSqlite() ? "TEXT" : "TEXT";
    }

    private void createSchema() {
        String pk = pk();
        String s = textType();
        String t = longTextType();

        String profiles = """
                CREATE TABLE IF NOT EXISTS eternal_profiles (
                    uuid %1$s PRIMARY KEY NOT NULL,
                    name %1$s NOT NULL,
                    first_seen BIGINT NOT NULL,
                    last_seen BIGINT NOT NULL,
                    last_address %1$s NOT NULL,
                    last_tier INTEGER NOT NULL DEFAULT 0,
                    last_group_name %1$s NOT NULL DEFAULT ''
                )""".formatted(s);

        String punishments = """
                CREATE TABLE IF NOT EXISTS eternal_punishments (
                    id %1$s,
                    type %2$s NOT NULL,
                    target_uuid %2$s NOT NULL,
                    target_name %2$s NOT NULL,
                    issuer_uuid %2$s,
                    issuer_name %2$s NOT NULL,
                    reason_id %2$s NOT NULL,
                    reason_label %2$s NOT NULL,
                    public_message %3$s NOT NULL,
                    issued_at BIGINT NOT NULL,
                    expires_at BIGINT,
                    active INTEGER NOT NULL,
                    pardon_issuer_uuid %2$s,
                    pardon_issuer_name %2$s,
                    pardon_reason %3$s,
                    pardoned_at BIGINT
                )""".formatted(pk, s, t);

        String reports = """
                CREATE TABLE IF NOT EXISTS eternal_reports (
                    id %1$s,
                    reporter_uuid %2$s NOT NULL,
                    reporter_name %2$s NOT NULL,
                    target_uuid %2$s NOT NULL,
                    target_name %2$s NOT NULL,
                    reason_id %2$s NOT NULL,
                    reason_label %2$s NOT NULL,
                    comment %3$s,
                    server_name %2$s NOT NULL,
                    created_at BIGINT NOT NULL,
                    status %2$s NOT NULL,
                    handler_uuid %2$s,
                    handler_name %2$s,
                    claimed_at BIGINT,
                    closed_at BIGINT,
                    resolution %3$s
                )""".formatted(pk, s, t);

        String linkCodes = """
                CREATE TABLE IF NOT EXISTS eternal_link_codes (
                    code %1$s PRIMARY KEY NOT NULL,
                    link_token %1$s NOT NULL,
                    status %1$s NOT NULL,
                    confirmed_uuid %1$s,
                    confirmed_name %1$s,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL
                )""".formatted(s);

        String sessions = """
                CREATE TABLE IF NOT EXISTS eternal_sessions (
                    token %1$s PRIMARY KEY NOT NULL,
                    user_uuid %1$s NOT NULL,
                    user_name %1$s NOT NULL,
                    role %1$s NOT NULL,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL
                )""".formatted(s);

        String actions = """
                CREATE TABLE IF NOT EXISTS eternal_actions (
                    id %1$s,
                    type %2$s NOT NULL,
                    target_staff_uuid %2$s NOT NULL,
                    payload %3$s NOT NULL,
                    created_at BIGINT NOT NULL,
                    consumed_at BIGINT
                )""".formatted(pk, s, t);

        String appeals = """
                CREATE TABLE IF NOT EXISTS eternal_unban_appeals (
                    id %1$s,
                    ban_id BIGINT NOT NULL,
                    applicant_uuid %2$s NOT NULL,
                    applicant_name %2$s NOT NULL,
                    text %3$s NOT NULL,
                    status %2$s NOT NULL,
                    created_at BIGINT NOT NULL,
                    reviewer_uuid %2$s,
                    reviewer_name %2$s,
                    reviewed_at BIGINT,
                    decision_reason %3$s
                )""".formatted(pk, s, t);

        String loginSessions = """
                CREATE TABLE IF NOT EXISTS eternal_login_sessions (
                    id %1$s,
                    uuid %2$s NOT NULL,
                    name %2$s NOT NULL,
                    ip %2$s NOT NULL,
                    login_at BIGINT NOT NULL,
                    logout_at BIGINT
                )""".formatted(pk, s);

        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute(profiles);
            st.execute(punishments);
            st.execute(reports);
            st.execute(linkCodes);
            st.execute(sessions);
            st.execute(actions);
            st.execute(appeals);
            st.execute(loginSessions);
            st.execute("CREATE INDEX IF NOT EXISTS idx_punishments_target_type_active ON eternal_punishments(target_uuid, type, active)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_reports_status ON eternal_reports(status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_link_token ON eternal_link_codes(link_token)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_actions_target ON eternal_actions(target_staff_uuid, consumed_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_appeals_status ON eternal_unban_appeals(status, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_appeals_applicant ON eternal_unban_appeals(applicant_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_login_sessions_uuid ON eternal_login_sessions(uuid, login_at DESC)");
            migrateAddLastTier(c);
            migrateAddLastGroupName(c);
            migrateAddReportBanId(c);
            migrateAddLastDisplayName(c);
            migrateAddPunishmentModified(c);
            migrateAddPunishmentHidden(c);
            migrateAddReportHidden(c);
            migrateAddPunishmentAppealCols(c);
            migrateAddAppealDecisionMessage(c);
            migrateAddReportReplayId(c);
            migrateAddReportChatHistory(c);
            createPermissionTables(c);
            seedAdminRoleGrant(c);
            createConsentTable(c);
            createCloudGroupsTable(c);
            migrateAddProfileGroups(c);
            createSocialTables(c);
            createBaseTables(c);
            createChatLogTables(c);
        } catch (SQLException ex) {
            throw new StorageException("Could not create schema", ex);
        }
    }

    @Override
    public void recordProfile(@NotNull UUID uuid, @NotNull String name, @NotNull String address,
                              int lastTier, @NotNull String lastGroupName, @NotNull String displayName) {
        long now = System.currentTimeMillis();
        // Empty displayName means "call site didn't have one"; preserve the
        // previously stored value via COALESCE so callers from non-Bukkit
        // contexts (API, Bungee) don't wipe the cached rank-coloured form.
        String sqlite = """
                INSERT INTO eternal_profiles (uuid, name, first_seen, last_seen, last_address, last_tier, last_group_name, last_display_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  name=excluded.name, last_seen=excluded.last_seen,
                  last_address=excluded.last_address, last_tier=excluded.last_tier,
                  last_group_name=excluded.last_group_name,
                  last_display_name=CASE WHEN excluded.last_display_name = '' THEN eternal_profiles.last_display_name ELSE excluded.last_display_name END
                """;
        String mysql = """
                INSERT INTO eternal_profiles (uuid, name, first_seen, last_seen, last_address, last_tier, last_group_name, last_display_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen=VALUES(last_seen),
                  last_address=VALUES(last_address), last_tier=VALUES(last_tier),
                  last_group_name=VALUES(last_group_name),
                  last_display_name=CASE WHEN VALUES(last_display_name) = '' THEN last_display_name ELSE VALUES(last_display_name) END
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(isSqlite() ? sqlite : mysql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setString(5, address);
            ps.setInt(6, lastTier);
            ps.setString(7, lastGroupName);
            ps.setString(8, displayName);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("recordProfile failed", ex);
        }
    }

    @Override
    public void updateProfileGroup(@NotNull UUID uuid, @NotNull String groupName) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_profiles SET last_group_name = ? WHERE uuid = ?")) {
            ps.setString(1, groupName);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("updateProfileGroup failed", ex);
        }
    }

    @Override
    public void updateProfileGroups(@NotNull UUID uuid, @NotNull List<String> groups) {
        // Comma-join. Group names never contain commas in CloudNet, so a
        // plain join is safe and keeps the column human-readable.
        String joined = String.join(",", groups);
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_profiles SET last_groups = ? WHERE uuid = ?")) {
            ps.setString(1, joined);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            System.err.println("[Eternal-SqlStorage] updateProfileGroups failed: " + ex.getMessage());
        }
    }

    @Override
    public @NotNull List<String> profileGroups(@NotNull UUID uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_groups FROM eternal_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();
                String joined = rs.getString("last_groups");
                if (joined == null || joined.isBlank()) return List.of();
                return java.util.Arrays.stream(joined.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
        } catch (SQLException ex) {
            // Column may not exist on a half-migrated DB — treat as empty.
            return List.of();
        }
    }

    @Override
    public void replaceCloudGroups(
            @NotNull List<de.eternal.core.integration.CloudPermsAccess.GroupInfo> groups) {
        long now = System.currentTimeMillis();
        try (Connection c = conn()) {
            // Full replace: clear then insert. Group set is small (dozens),
            // so a truncate+insert is simpler than a diff and keeps the
            // table exactly in sync with CloudNet each cycle.
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM eternal_cloud_groups");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eternal_cloud_groups (name, sort_id, color, synced_at) VALUES (?, ?, ?, ?)")) {
                for (var g : groups) {
                    ps.setString(1, g.name());
                    ps.setInt(2, g.sortId());
                    ps.setString(3, g.color());
                    ps.setLong(4, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException ex) {
            System.err.println("[Eternal-SqlStorage] replaceCloudGroups failed: " + ex.getMessage());
        }
    }

    @Override
    public @NotNull List<de.eternal.core.integration.CloudPermsAccess.GroupInfo> listCloudGroups() {
        // Ascending sortId — lowest = highest rank, shown first.
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, sort_id, color FROM eternal_cloud_groups ORDER BY sort_id ASC, name ASC");
             ResultSet rs = ps.executeQuery()) {
            List<de.eternal.core.integration.CloudPermsAccess.GroupInfo> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new de.eternal.core.integration.CloudPermsAccess.GroupInfo(
                        rs.getString("name"), rs.getInt("sort_id"),
                        rs.getString("color") == null ? "" : rs.getString("color")));
            }
            return out;
        } catch (SQLException ex) {
            return List.of();
        }
    }

    @Override
    public void replaceCloudGroupPerms(@NotNull Map<String, Map<String, Boolean>> byGroup) {
        try (Connection c = conn()) {
            // Full replace so the table stays exactly in sync with CloudNet each
            // cycle (group×node set is small — dozens of groups, a few nodes).
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM eternal_cloud_group_perms");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eternal_cloud_group_perms (group_name, permission_key, granted) VALUES (?, ?, ?)")) {
                for (var ge : byGroup.entrySet()) {
                    for (var pe : ge.getValue().entrySet()) {
                        ps.setString(1, ge.getKey());
                        ps.setString(2, pe.getKey());
                        ps.setInt(3, pe.getValue() ? 1 : 0);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
        } catch (SQLException ex) {
            System.err.println("[Eternal-SqlStorage] replaceCloudGroupPerms failed: " + ex.getMessage());
        }
    }

    @Override
    public @NotNull Map<String, PermissionGrant> cloudGroupPermissions(@NotNull String group) {
        // Case-insensitive — profile.lastGroupName and the synced group name both
        // come from CloudNet but casing can drift across versions.
        String sql = "SELECT group_name, permission_key, granted "
                + "FROM eternal_cloud_group_perms WHERE LOWER(group_name) = LOWER(?)";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, PermissionGrant> out = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString("permission_key"), new PermissionGrant(
                            rs.getString("group_name"),
                            rs.getString("permission_key"),
                            rs.getInt("granted") != 0,
                            Instant.EPOCH,   // synced rows carry no timestamp
                            "cloudnet",
                            null));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("cloudGroupPermissions failed", ex);
        }
    }

    private void createCloudGroupsTable(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eternal_cloud_groups (
                      name VARCHAR(64) PRIMARY KEY,
                      sort_id INTEGER NOT NULL DEFAULT 0,
                      color VARCHAR(16) NOT NULL DEFAULT '',
                      synced_at BIGINT NOT NULL
                    )
                    """);
            // eternal_cloud_group_perms — mirror of each CloudNet group's own
            // permission nodes (synced from in-game by the proxy). Read during
            // resolution, prioritized over the web role grants.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eternal_cloud_group_perms (
                      group_name VARCHAR(64) NOT NULL,
                      permission_key VARCHAR(128) NOT NULL,
                      granted INTEGER NOT NULL DEFAULT 1,
                      PRIMARY KEY (group_name, permission_key)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_cloud_group_perms_group ON eternal_cloud_group_perms(group_name)");
        }
        // Idempotent add for DBs created before the colour column existed.
        runIdempotent(c, "ALTER TABLE eternal_cloud_groups ADD COLUMN color VARCHAR(16) NOT NULL DEFAULT ''");
    }

    private void migrateAddProfileGroups(@NotNull Connection c) {
        runIdempotent(c, isSqlite()
                ? "ALTER TABLE eternal_profiles ADD COLUMN last_groups TEXT NOT NULL DEFAULT ''"
                : "ALTER TABLE eternal_profiles ADD COLUMN last_groups TEXT");
    }

    /** Idempotent ALTER for legacy DBs that pre-date tier tracking. */
    private void migrateAddLastTier(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE eternal_profiles ADD COLUMN last_tier INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ignored) { /* already exists */ }
    }

    /** Idempotent ALTER for legacy DBs that pre-date group tracking. */
    private void migrateAddLastGroupName(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            String col = isSqlite()
                    ? "ALTER TABLE eternal_profiles ADD COLUMN last_group_name TEXT NOT NULL DEFAULT ''"
                    : "ALTER TABLE eternal_profiles ADD COLUMN last_group_name VARCHAR(255) NOT NULL DEFAULT ''";
            st.execute(col);
        } catch (SQLException ignored) { /* already exists */ }
    }

    /**
     *  Bootstraps the permission-engine tables. Three rows:
     *  <ul>
     *   <li>{@code eternal_roles} — Web-side roles. Each row links to a
     *       CloudNet group name so we can map "this player is in
     *       cloudnet group 'admin'" → "this player has the 'admin' web
     *       role". {@code sort_order} provides the tier ranking (max
     *       wins); {@code color} is the chat-colour code shown in the
     *       dashboard.</li>
     *   <li>{@code eternal_role_permissions} — per-role grants. A row
     *       overrides the hardcoded {@code eternal.*} default for
     *       members of that role.</li>
     *   <li>{@code eternal_user_permissions} — per-user grants. Takes
     *       precedence over the role's setting AND the hardcoded
     *       default, so "this one mod cannot use the hacking ban"
     *       can be expressed by a single row with granted=false.</li>
     *  </ul>
     *
     *  All three are created via {@code CREATE TABLE IF NOT EXISTS}, so
     *  re-running the bootstrap is harmless. We do NOT pre-populate
     *  default roles here — the seeding lives in {@code Migrations}-
     *  level code on first start, where the api can also pull the
     *  current CloudNet group list.
     */
    private void createPermissionTables(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            // eternal_roles — web-side roles tied to a CN group name.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eternal_roles (
                      name VARCHAR(64) PRIMARY KEY,
                      display_name VARCHAR(128) NOT NULL,
                      mc_group_name VARCHAR(64) NOT NULL,
                      sort_order INTEGER NOT NULL DEFAULT 0,
                      color VARCHAR(16) NOT NULL DEFAULT '&7',
                      created_at BIGINT NOT NULL
                    )
                    """);
            // Index the CN group name so the auth-resolver can quickly
            // map "this player is in CN group X" → role row.
            st.execute("CREATE INDEX IF NOT EXISTS idx_roles_mc_group ON eternal_roles(mc_group_name)");

            // eternal_role_permissions — composite PK on (role, key).
            // granted is INTEGER (0/1) rather than BOOLEAN so SQLite
            // and MySQL agree on storage representation.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eternal_role_permissions (
                      role_name VARCHAR(64) NOT NULL,
                      permission_key VARCHAR(128) NOT NULL,
                      granted INTEGER NOT NULL DEFAULT 1,
                      updated_at BIGINT NOT NULL,
                      updated_by VARCHAR(64),
                      PRIMARY KEY (role_name, permission_key)
                    )
                    """);

            // eternal_user_permissions — user-level overrides. expires_at is
            // set by approved access-requests with a duration (NULL = permanent).
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eternal_user_permissions (
                      user_uuid VARCHAR(36) NOT NULL,
                      permission_key VARCHAR(128) NOT NULL,
                      granted INTEGER NOT NULL DEFAULT 1,
                      updated_at BIGINT NOT NULL,
                      updated_by VARCHAR(64),
                      expires_at BIGINT,
                      PRIMARY KEY (user_uuid, permission_key)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_user_perms_uuid ON eternal_user_permissions(user_uuid)");

            // eternal_permission_requests — self-service access requests. A team
            // member asks for one key, an admin approves (→ user-override grant,
            // optionally expiring) or denies.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eternal_permission_requests (
                      id %1$s,
                      requester_uuid VARCHAR(36) NOT NULL,
                      requester_name VARCHAR(64) NOT NULL,
                      permission_key VARCHAR(128) NOT NULL,
                      justification TEXT,
                      status VARCHAR(16) NOT NULL,
                      created_at BIGINT NOT NULL,
                      decided_by_uuid VARCHAR(36),
                      decided_by_name VARCHAR(64),
                      decided_at BIGINT,
                      decision_note TEXT,
                      expires_at BIGINT
                    )
                    """.formatted(pk()));
            st.execute("CREATE INDEX IF NOT EXISTS idx_perm_requests_status ON eternal_permission_requests(status, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_perm_requests_requester ON eternal_permission_requests(requester_uuid)");
        }
        // Idempotent add for DBs created before the expiry column existed.
        runIdempotent(c, "ALTER TABLE eternal_user_permissions ADD COLUMN expires_at BIGINT");
    }

    /**
     * One-time bootstrap so removing the legacy ADMIN tier can't lock the owner
     * out of the dashboard. Authorization is now purely permission-based, but
     * the standalone API can't see the in-game {@code *} of the owner group —
     * it only reads grants from the DB. So if NO role currently carries a
     * wildcard / admin grant, give {@code *} to the highest-ranked role
     * (smallest {@code sort_order}, e.g. Owner). The admin can refine grants per
     * role afterwards in the dashboard editor.
     *
     * <p>Idempotent: once any role has {@code * / eternal.* / eternal.web.* /
     * eternal.web.admin} granted, this does nothing. Runs on the same
     * connection as schema creation (the SQLite pool has a single connection,
     * so we must not open another here).</p>
     */
    private void seedAdminRoleGrant(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM eternal_role_permissions WHERE granted = 1 "
                    + "AND permission_key IN ('*','eternal.*','eternal.web.*','eternal.web.admin')")) {
                if (rs.next() && rs.getInt(1) > 0) return; // already bootstrapped
            }
            String topRole;
            try (ResultSet rs = st.executeQuery(
                    "SELECT name FROM eternal_roles ORDER BY sort_order ASC LIMIT 1")) {
                if (!rs.next()) return; // no roles yet — CloudNet sync seeds them later; nothing to do
                topRole = rs.getString(1);
            }
            // DELETE+INSERT instead of a dialect-specific upsert so a pre-existing
            // (denied) `*` row can't trip the composite PK.
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM eternal_role_permissions WHERE role_name = ? AND permission_key = '*'")) {
                del.setString(1, topRole);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO eternal_role_permissions (role_name, permission_key, granted, updated_at, updated_by) "
                    + "VALUES (?, '*', 1, ?, 'bootstrap')")) {
                ins.setString(1, topRole);
                ins.setLong(2, System.currentTimeMillis());
                ins.executeUpdate();
            }
        }
    }

    /** Idempotent ALTER für die Replay-ID auf eternal_reports.
     *  ReplayBridge.endCaptureForReport schreibt hier rein, sobald das
     *  Replay-File persistiert ist — damit /history die Replay-ID
     *  zeigen und {@code /replay play <id>} sie wieder abspielen kann. */
    private void migrateAddReportReplayId(@NotNull Connection c) {
        runIdempotent(c, "ALTER TABLE eternal_reports ADD COLUMN replay_id BIGINT");
    }

    /** Idempotent ALTER für die Chat-Verlaufs-Spalte auf eternal_reports.
     *  Speichert einen JSON-Snapshot der Chat-Logs rund um den Report,
     *  sobald das Nachher-Fenster geschlossen ist (siehe
     *  {@link #linkReportChatHistory}). readReport toleriert die fehlende
     *  Spalte auf nicht-migrierten DBs. */
    private void migrateAddReportChatHistory(@NotNull Connection c) {
        runIdempotent(c, "ALTER TABLE eternal_reports ADD COLUMN chat_history " + longTextType());
    }

    /** Idempotent ALTER fuer ban_id-Verlinkung in eternal_reports. */
    private void migrateAddReportBanId(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE eternal_reports ADD COLUMN ban_id BIGINT");
        } catch (SQLException ignored) { /* already exists */ }
    }

    /** Idempotent ALTER fuer modify-Tracking auf Punishments. */
    private void migrateAddPunishmentModified(@NotNull Connection c) throws SQLException {
        for (String stmt : new String[]{
                "ALTER TABLE eternal_punishments ADD COLUMN modified_at BIGINT",
                isSqlite()
                        ? "ALTER TABLE eternal_punishments ADD COLUMN modified_by_uuid TEXT"
                        : "ALTER TABLE eternal_punishments ADD COLUMN modified_by_uuid VARCHAR(36)",
                isSqlite()
                        ? "ALTER TABLE eternal_punishments ADD COLUMN modified_by_name TEXT"
                        : "ALTER TABLE eternal_punishments ADD COLUMN modified_by_name VARCHAR(255)"
        }) {
            try (Statement st = c.createStatement()) { st.execute(stmt); }
            catch (SQLException ignored) { /* already exists */ }
        }
    }

    /** Idempotent ALTER fuer das hidden-Flag (soft-delete) auf Punishments. */
    private void migrateAddPunishmentHidden(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE eternal_punishments ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ignored) { /* already exists */ }
    }

    /** Idempotent ALTER fuer das hidden-Flag auf Reports. */
    private void migrateAddReportHidden(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE eternal_reports ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ignored) { /* already exists */ }
    }

    /** Idempotent ALTER fuer Appeal-bezogene Felder am Punishment (Nachricht + originale Dauer). */
    private void migrateAddPunishmentAppealCols(@NotNull Connection c) throws SQLException {
        for (String stmt : new String[]{
                isSqlite()
                        ? "ALTER TABLE eternal_punishments ADD COLUMN last_appeal_message TEXT"
                        : "ALTER TABLE eternal_punishments ADD COLUMN last_appeal_message TEXT NULL",
                "ALTER TABLE eternal_punishments ADD COLUMN original_expires_at BIGINT"
        }) {
            try (Statement st = c.createStatement()) { st.execute(stmt); }
            catch (SQLException ignored) { /* already exists */ }
        }
    }

    /** Idempotent ALTER fuer die Decision-Message + shortened-to-seconds am Appeal. */
    private void migrateAddAppealDecisionMessage(@NotNull Connection c) throws SQLException {
        // Loggen statt stummem catch — wenn die Migration mal aus anderen
        // Gruenden scheitert (Permissions, syntax, falsche Tabelle) wollen
        // wir das im Server-Log sehen, nicht erst beim naechsten UPDATE.
        runIdempotent(c, "ALTER TABLE eternal_unban_appeals ADD COLUMN decision_message "
                + (isSqlite() ? "TEXT" : "TEXT NULL"));
        runIdempotent(c, "ALTER TABLE eternal_unban_appeals ADD COLUMN shortened_to_seconds BIGINT");
    }

    /** Idempotent migration helper: runs the statement, swallows "duplicate
     *  column" errors (the normal idempotent case), and logs anything
     *  else so a broken migration is visible at startup instead of
     *  surfacing later as a cryptic UPDATE failure. */
    private void runIdempotent(@NotNull Connection c, @NotNull String stmt) {
        try (Statement st = c.createStatement()) { st.execute(stmt); }
        catch (SQLException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            boolean dupColumn = msg.contains("duplicate column")
                    || msg.contains("already exists")
                    || msg.contains("duplicate key name");
            if (!dupColumn) {
                System.err.println("[Eternal-Migration] " + stmt + " -> " + ex.getMessage());
            }
        }
    }

    /** Idempotent ALTER fuer den letzten gesehenen DisplayName (rang-formatiert). */
    private void migrateAddLastDisplayName(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            String col = isSqlite()
                    ? "ALTER TABLE eternal_profiles ADD COLUMN last_display_name TEXT NOT NULL DEFAULT ''"
                    : "ALTER TABLE eternal_profiles ADD COLUMN last_display_name VARCHAR(255) NOT NULL DEFAULT ''";
            st.execute(col);
        } catch (SQLException ignored) { /* already exists */ }
    }

    @Override
    public @NotNull Optional<PlayerProfile> findProfile(@NotNull UUID uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, first_seen, last_seen, last_address, last_tier, last_group_name, last_display_name FROM eternal_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readProfile(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findProfile failed", ex);
        }
    }

    @Override
    public @NotNull Optional<PlayerProfile> findProfileByName(@NotNull String name) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, first_seen, last_seen, last_address, last_tier, last_group_name, last_display_name FROM eternal_profiles WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readProfile(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findProfileByName failed", ex);
        }
    }

    private PlayerProfile readProfile(ResultSet rs) throws SQLException {
        String group = rs.getString("last_group_name");
        String display = rs.getString("last_display_name");
        return new PlayerProfile(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                Instant.ofEpochMilli(rs.getLong("first_seen")),
                Instant.ofEpochMilli(rs.getLong("last_seen")),
                rs.getString("last_address"),
                rs.getInt("last_tier"),
                group == null ? "" : group,
                display == null ? "" : display
        );
    }

    @Override
    public long insertPunishment(@NotNull PunishmentEntry e) {
        String sql = """
                INSERT INTO eternal_punishments
                  (type, target_uuid, target_name, issuer_uuid, issuer_name, reason_id, reason_label,
                   public_message, issued_at, expires_at, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.type().name());
            ps.setString(2, e.targetUuid().toString());
            ps.setString(3, e.targetName());
            if (e.issuerUuid() == null) ps.setNull(4, java.sql.Types.VARCHAR);
            else ps.setString(4, e.issuerUuid().toString());
            ps.setString(5, e.issuerName());
            ps.setString(6, e.reasonId());
            ps.setString(7, e.reasonLabel());
            ps.setString(8, e.publicMessage());
            ps.setLong(9, e.issuedAt().toEpochMilli());
            if (e.expiresAt() == null) ps.setNull(10, java.sql.Types.BIGINT);
            else ps.setLong(10, e.expiresAt().toEpochMilli());
            ps.setInt(11, e.active() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException ex) {
            throw new StorageException("insertPunishment failed", ex);
        }
    }

    @Override
    public @NotNull Optional<PunishmentEntry> findActivePunishment(@NotNull UUID target, @NotNull PunishmentType type) {
        long now = System.currentTimeMillis();
        String sql = """
                SELECT * FROM eternal_punishments
                WHERE target_uuid = ? AND type = ? AND active = 1
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY issued_at DESC LIMIT 1
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setString(2, type.name());
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPunishment(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findActivePunishment failed", ex);
        }
    }

    @Override
    public @NotNull List<PunishmentEntry> findPunishmentHistory(@NotNull UUID target, @Nullable PunishmentType type) {
        String sql = type == null
                ? "SELECT * FROM eternal_punishments WHERE target_uuid = ? AND hidden = 0 ORDER BY issued_at DESC"
                : "SELECT * FROM eternal_punishments WHERE target_uuid = ? AND type = ? AND hidden = 0 ORDER BY issued_at DESC";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            if (type != null) ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<PunishmentEntry> out = new ArrayList<>();
                while (rs.next()) out.add(readPunishment(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findPunishmentHistory failed", ex);
        }
    }

    @Override
    public @NotNull List<PunishmentEntry> findAllActive(@NotNull PunishmentType type) {
        long now = System.currentTimeMillis();
        String sql = """
                SELECT * FROM eternal_punishments
                WHERE type = ? AND active = 1 AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY issued_at DESC
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<PunishmentEntry> out = new ArrayList<>();
                while (rs.next()) out.add(readPunishment(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findAllActive failed", ex);
        }
    }

    @Override
    public @NotNull Optional<PunishmentEntry> findPunishmentById(long id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_punishments WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPunishment(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findPunishmentById failed", ex);
        }
    }

    @Override
    public boolean pardonPunishment(long id, @Nullable UUID issuerUuid, @NotNull String issuerName, @NotNull String reason) {
        String sql = """
                UPDATE eternal_punishments
                SET active = 0, pardon_issuer_uuid = ?, pardon_issuer_name = ?, pardon_reason = ?, pardoned_at = ?
                WHERE id = ? AND active = 1
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (issuerUuid == null) ps.setNull(1, java.sql.Types.VARCHAR);
            else ps.setString(1, issuerUuid.toString());
            ps.setString(2, issuerName);
            ps.setString(3, reason);
            ps.setLong(4, System.currentTimeMillis());
            ps.setLong(5, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("pardonPunishment failed", ex);
        }
    }

    private PunishmentEntry readPunishment(ResultSet rs) throws SQLException {
        long expires = rs.getLong("expires_at");
        boolean expiresNull = rs.wasNull();
        long pardoned = rs.getLong("pardoned_at");
        boolean pardonedNull = rs.wasNull();
        String issuerUuidStr = rs.getString("issuer_uuid");
        String pardonIssuer = rs.getString("pardon_issuer_uuid");

        // modify-tracking columns are added by migration; tolerate absence so
        // mid-migration starts don't NPE.
        long modAt = 0; boolean modAtNull = true;
        String modByUuid = null; String modByName = null;
        String appealMsg = null;
        long origExpires = 0; boolean origExpiresNull = true;
        try {
            modAt = rs.getLong("modified_at");
            modAtNull = rs.wasNull();
            modByUuid = rs.getString("modified_by_uuid");
            modByName = rs.getString("modified_by_name");
        } catch (SQLException ignored) { /* column not yet present */ }
        try {
            appealMsg = rs.getString("last_appeal_message");
            origExpires = rs.getLong("original_expires_at");
            origExpiresNull = rs.wasNull();
        } catch (SQLException ignored) { /* columns not yet present */ }

        return new PunishmentEntry(
                rs.getLong("id"),
                PunishmentType.valueOf(rs.getString("type")),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                issuerUuidStr == null ? null : UUID.fromString(issuerUuidStr),
                rs.getString("issuer_name"),
                rs.getString("reason_id"),
                rs.getString("reason_label"),
                rs.getString("public_message"),
                Instant.ofEpochMilli(rs.getLong("issued_at")),
                expiresNull ? null : Instant.ofEpochMilli(expires),
                rs.getInt("active") == 1,
                pardonIssuer == null ? null : UUID.fromString(pardonIssuer),
                rs.getString("pardon_issuer_name"),
                rs.getString("pardon_reason"),
                pardonedNull ? null : Instant.ofEpochMilli(pardoned),
                modAtNull ? null : Instant.ofEpochMilli(modAt),
                modByUuid == null ? null : UUID.fromString(modByUuid),
                modByName,
                appealMsg,
                origExpiresNull ? null : Instant.ofEpochMilli(origExpires)
        );
    }

    @Override
    public int countPriorOffenses(@NotNull UUID target, @NotNull String reasonId, @NotNull PunishmentType type) {
        // Counts only entries that are NO LONGER active (expired or pardoned) —
        // those are the "completed" prior offenses on which the escalation
        // ladder steps up.
        String sql = """
                SELECT COUNT(*) FROM eternal_punishments
                WHERE target_uuid = ? AND type = ? AND reason_id = ?
                  AND active = 0 AND hidden = 0
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setString(2, type.name());
            ps.setString(3, reasonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new StorageException("countPriorOffenses failed", ex);
        }
    }

    @Override
    public boolean modifyPunishmentDuration(long id, @Nullable UUID modifierUuid, @NotNull String modifierName,
                                             @Nullable Instant newExpires) {
        return modifyPunishmentDuration(id, modifierUuid, modifierName, newExpires, null);
    }

    /**
     * Same as {@link #modifyPunishmentDuration(long, UUID, String, Instant)}
     * but also stamps a user-visible appeal message into the row when
     * non-null. Used by the appeal-shortening flow so the kick screen and
     * the user's appeal page can show the same explanation.
     *
     * <p>Side effects beyond the obvious update:</p>
     * <ul>
     *     <li>If {@code original_expires_at} is still NULL on the row, the
     *         CURRENT {@code expires_at} is copied there first — so /history
     *         can show "ursprünglich bis X → jetzt bis Y" forever.</li>
     *     <li>If the row is currently {@code active = 0} and the new expiry
     *         is in the future (or permanent), the row is REACTIVATED:
     *         active=1 + pardon-fields cleared. This is the "/modify of an
     *         already-pardoned ban brings it back" semantics we want.</li>
     * </ul>
     */
    /** Sets ONLY the {@code last_appeal_message} on a punishment row,
     *  without touching duration, active state, or anything else. Used
     *  by appeal-deny so the player sees the rejection note on their
     *  next kick screen — but the underlying ban stays exactly as it
     *  was. Returns true when the row existed and got updated. */
    public boolean setLastAppealMessage(long id, @NotNull String message) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_punishments SET last_appeal_message = ? WHERE id = ?")) {
            ps.setString(1, message);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            System.err.println("[Eternal-SqlStorage] setLastAppealMessage failed: " + ex.getMessage());
            return false;
        }
    }

    public boolean modifyPunishmentDuration(long id, @Nullable UUID modifierUuid, @NotNull String modifierName,
                                             @Nullable Instant newExpires, @Nullable String appealMessage) {
        long now = System.currentTimeMillis();
        boolean shouldBeActive = (newExpires == null) || newExpires.toEpochMilli() > now;

        // Two-phase: first copy expires_at into original_expires_at IFF
        // original_expires_at is still NULL (i.e. never modified). Then do the
        // actual update. Done as a single statement using CASE for atomicity.
        String sql = """
                UPDATE eternal_punishments
                SET expires_at = ?,
                    original_expires_at = COALESCE(original_expires_at, expires_at),
                    modified_at = ?, modified_by_uuid = ?, modified_by_name = ?,
                    last_appeal_message = CASE WHEN ? IS NULL THEN last_appeal_message ELSE ? END,
                    active = ?,
                    pardon_issuer_uuid = CASE WHEN ? = 1 THEN NULL ELSE pardon_issuer_uuid END,
                    pardon_issuer_name = CASE WHEN ? = 1 THEN NULL ELSE pardon_issuer_name END,
                    pardon_reason      = CASE WHEN ? = 1 THEN NULL ELSE pardon_reason END,
                    pardoned_at        = CASE WHEN ? = 1 THEN NULL ELSE pardoned_at END
                WHERE id = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (newExpires == null) ps.setNull(i++, java.sql.Types.BIGINT);
            else ps.setLong(i++, newExpires.toEpochMilli());
            ps.setLong(i++, now);
            if (modifierUuid == null) ps.setNull(i++, java.sql.Types.VARCHAR);
            else ps.setString(i++, modifierUuid.toString());
            ps.setString(i++, modifierName);
            if (appealMessage == null) { ps.setNull(i++, java.sql.Types.VARCHAR); ps.setNull(i++, java.sql.Types.VARCHAR); }
            else { ps.setString(i++, appealMessage); ps.setString(i++, appealMessage); }
            int active = shouldBeActive ? 1 : 0;
            ps.setInt(i++, active);
            ps.setInt(i++, active); // pardon_issuer_uuid CASE
            ps.setInt(i++, active); // pardon_issuer_name CASE
            ps.setInt(i++, active); // pardon_reason CASE
            ps.setInt(i++, active); // pardoned_at CASE
            ps.setLong(i, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("modifyPunishmentDuration failed", ex);
        }
    }

    @Override
    public boolean modifyPunishmentReason(long id, @Nullable UUID modifierUuid, @NotNull String modifierName,
                                           @NotNull String newReasonId, @NotNull String newReasonLabel) {
        String sql = """
                UPDATE eternal_punishments
                SET reason_id = ?, reason_label = ?, modified_at = ?, modified_by_uuid = ?, modified_by_name = ?
                WHERE id = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newReasonId);
            ps.setString(2, newReasonLabel);
            ps.setLong(3, System.currentTimeMillis());
            if (modifierUuid == null) ps.setNull(4, java.sql.Types.VARCHAR);
            else ps.setString(4, modifierUuid.toString());
            ps.setString(5, modifierName);
            ps.setLong(6, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("modifyPunishmentReason failed", ex);
        }
    }

    @Override
    public int resetPunishmentHistory(@NotNull UUID target, boolean hard) {
        String sql = hard
                ? "DELETE FROM eternal_punishments WHERE target_uuid = ?"
                : "UPDATE eternal_punishments SET hidden = 1 WHERE target_uuid = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("resetPunishmentHistory failed", ex);
        }
    }

    @Override
    public int resetReportHistory(@NotNull UUID target, boolean hard) {
        String sql = hard
                ? "DELETE FROM eternal_reports WHERE target_uuid = ?"
                : "UPDATE eternal_reports SET hidden = 1 WHERE target_uuid = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("resetReportHistory failed", ex);
        }
    }

    @Override
    public long insertReport(@NotNull ReportEntry e) {
        String sql = """
                INSERT INTO eternal_reports
                  (reporter_uuid, reporter_name, target_uuid, target_name, reason_id, reason_label,
                   comment, server_name, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.reporterUuid().toString());
            ps.setString(2, e.reporterName());
            ps.setString(3, e.targetUuid().toString());
            ps.setString(4, e.targetName());
            ps.setString(5, e.reasonId());
            ps.setString(6, e.reasonLabel());
            ps.setString(7, e.comment());
            ps.setString(8, e.serverName());
            ps.setLong(9, e.createdAt().toEpochMilli());
            ps.setString(10, e.status().name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException ex) {
            throw new StorageException("insertReport failed", ex);
        }
    }

    @Override
    public @NotNull List<ReportEntry> findOpenReports() {
        String sql = "SELECT * FROM eternal_reports WHERE status IN ('OPEN','CLAIMED') ORDER BY created_at ASC";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<ReportEntry> out = new ArrayList<>();
            while (rs.next()) out.add(readReport(rs));
            return out;
        } catch (SQLException ex) {
            throw new StorageException("findOpenReports failed", ex);
        }
    }

    @Override
    public @NotNull List<ReportEntry> findReports(@NotNull java.util.Collection<ReportStatus> statuses,
                                                   int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        int safeOffset = Math.max(0, offset);
        StringBuilder sb = new StringBuilder("SELECT * FROM eternal_reports");
        if (!statuses.isEmpty()) {
            sb.append(" WHERE status IN (");
            for (int i = 0; i < statuses.size(); i++) sb.append(i == 0 ? "?" : ",?");
            sb.append(")");
        }
        sb.append(" ORDER BY created_at DESC LIMIT ").append(safeLimit).append(" OFFSET ").append(safeOffset);
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            int idx = 1;
            for (ReportStatus s : statuses) ps.setString(idx++, s.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<ReportEntry> out = new ArrayList<>();
                while (rs.next()) out.add(readReport(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findReports failed", ex);
        }
    }

    @Override
    public long countReports(@NotNull java.util.Collection<ReportStatus> statuses) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM eternal_reports");
        if (!statuses.isEmpty()) {
            sb.append(" WHERE status IN (");
            for (int i = 0; i < statuses.size(); i++) sb.append(i == 0 ? "?" : ",?");
            sb.append(")");
        }
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            int idx = 1;
            for (ReportStatus s : statuses) ps.setString(idx++, s.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new StorageException("countReports failed", ex);
        }
    }

    @Override
    public @NotNull List<ReportEntry> findReportsByTarget(@NotNull UUID target) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_reports WHERE target_uuid = ? AND hidden = 0 ORDER BY created_at DESC")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<ReportEntry> out = new ArrayList<>();
                while (rs.next()) out.add(readReport(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findReportsByTarget failed", ex);
        }
    }

    @Override
    public @NotNull Optional<ReportEntry> findReport(long id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_reports WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readReport(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findReport failed", ex);
        }
    }

    @Override
    public boolean claimReport(long id, @NotNull UUID handlerUuid, @NotNull String handlerName) {
        String sql = "UPDATE eternal_reports SET status='CLAIMED', handler_uuid=?, handler_name=?, claimed_at=? WHERE id=? AND status='OPEN'";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, handlerUuid.toString());
            ps.setString(2, handlerName);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("claimReport failed", ex);
        }
    }

    @Override
    public boolean takeOverReport(long id, @NotNull UUID handlerUuid, @NotNull String handlerName) {
        String sql = "UPDATE eternal_reports SET status='CLAIMED', handler_uuid=?, handler_name=?, claimed_at=? WHERE id=? AND status<>'CLOSED'";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, handlerUuid.toString());
            ps.setString(2, handlerName);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("takeOverReport failed", ex);
        }
    }

    @Override
    public boolean closeReport(long id, @NotNull String resolution) {
        String sql = "UPDATE eternal_reports SET status='CLOSED', closed_at=?, resolution=? WHERE id=? AND status<>'CLOSED'";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, resolution);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("closeReport failed", ex);
        }
    }

    private ReportEntry readReport(ResultSet rs) throws SQLException {
        String handlerUuid = rs.getString("handler_uuid");
        long claimed = rs.getLong("claimed_at");
        boolean claimedNull = rs.wasNull();
        long closed = rs.getLong("closed_at");
        boolean closedNull = rs.wasNull();
        // Replay-Id ist nullable + by migration kann die Spalte auf
        // älteren DBs noch fehlen — also try/catch um getLong(...) und
        // immer wasNull() prüfen. Reihenfolge folgt dem Pattern aus
        // tolerate the column being absent on databases that haven't
        // run the migration yet — schema upgrades are idempotent.
        Long replayId = null;
        try {
            long rid = rs.getLong("replay_id");
            if (!rs.wasNull()) replayId = rid;
        } catch (SQLException ignored) { /* column not yet present */ }

        // chat_history is added by migrateAddReportChatHistory; tolerate the
        // column being absent on databases that haven't run it yet.
        String chatHistory = null;
        try {
            chatHistory = rs.getString("chat_history");
        } catch (SQLException ignored) { /* column not yet present */ }

        return new ReportEntry(
                rs.getLong("id"),
                UUID.fromString(rs.getString("reporter_uuid")),
                rs.getString("reporter_name"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("reason_id"),
                rs.getString("reason_label"),
                rs.getString("comment"),
                rs.getString("server_name"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                ReportStatus.valueOf(rs.getString("status")),
                handlerUuid == null ? null : UUID.fromString(handlerUuid),
                rs.getString("handler_name"),
                claimedNull ? null : Instant.ofEpochMilli(claimed),
                closedNull ? null : Instant.ofEpochMilli(closed),
                rs.getString("resolution"),
                replayId,
                chatHistory
        );
    }

    /** Writes the replay-id back onto an existing report row. Called by
     *  the replay bridge after the in-flight capture has been persisted,
     *  so /history can show the recording link. Returns false when the
     *  report doesn't exist. */
    public boolean linkReportToReplay(long reportId, long replayId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_reports SET replay_id = ? WHERE id = ?")) {
            ps.setLong(1, replayId);
            ps.setLong(2, reportId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            System.err.println("[Eternal-SqlStorage] linkReportToReplay failed: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public void linkReportChatHistory(long reportId, @Nullable String chatHistoryJson) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_reports SET chat_history = ? WHERE id = ?")) {
            ps.setString(1, chatHistoryJson);
            ps.setLong(2, reportId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("linkReportChatHistory failed", ex);
        }
    }

    @Override
    public @NotNull List<StaffStat> staffStats() {
        // Aggregate punishments-issued and reports-handled per staff member.
        String punishmentsSql = """
                SELECT issuer_uuid, issuer_name, type, COUNT(*) AS cnt
                FROM eternal_punishments
                WHERE issuer_uuid IS NOT NULL
                GROUP BY issuer_uuid, issuer_name, type
                """;
        String reportsSql = """
                SELECT handler_uuid, handler_name, COUNT(*) AS cnt
                FROM eternal_reports
                WHERE handler_uuid IS NOT NULL AND status = 'CLOSED'
                GROUP BY handler_uuid, handler_name
                """;

        java.util.Map<UUID, long[]> tally = new java.util.LinkedHashMap<>(); // [bans, mutes, reports]
        java.util.Map<UUID, String> names = new java.util.HashMap<>();

        try (Connection c = conn()) {
            try (PreparedStatement ps = c.prepareStatement(punishmentsSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID u = UUID.fromString(rs.getString("issuer_uuid"));
                    names.put(u, rs.getString("issuer_name"));
                    long cnt = rs.getLong("cnt");
                    long[] arr = tally.computeIfAbsent(u, k -> new long[3]);
                    if ("BAN".equals(rs.getString("type"))) arr[0] += cnt;
                    else if ("MUTE".equals(rs.getString("type"))) arr[1] += cnt;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(reportsSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID u = UUID.fromString(rs.getString("handler_uuid"));
                    names.putIfAbsent(u, rs.getString("handler_name"));
                    long[] arr = tally.computeIfAbsent(u, k -> new long[3]);
                    arr[2] += rs.getLong("cnt");
                }
            }
        } catch (SQLException ex) {
            throw new StorageException("staffStats failed", ex);
        }

        List<StaffStat> out = new ArrayList<>(tally.size());
        for (var e : tally.entrySet()) {
            long[] a = e.getValue();
            out.add(new StaffStat(e.getKey(), names.getOrDefault(e.getKey(), "?"), a[0], a[1], a[2]));
        }
        return out;
    }

    /* --- Account-link / sessions / actions ----------------------------- */

    @Override
    public void createLinkCode(@NotNull LinkCode code) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO eternal_link_codes (code, link_token, status, created_at, expires_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, code.code());
            ps.setString(2, code.linkToken());
            ps.setString(3, code.status().name());
            ps.setLong(4, code.createdAt().toEpochMilli());
            ps.setLong(5, code.expiresAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("createLinkCode failed", ex);
        }
    }

    @Override
    public @NotNull Optional<LinkCode> findLinkByCode(@NotNull String code) {
        return findLink("code", code);
    }

    @Override
    public @NotNull Optional<LinkCode> findLinkByToken(@NotNull String linkToken) {
        return findLink("link_token", linkToken);
    }

    private Optional<LinkCode> findLink(@NotNull String column, @NotNull String value) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_link_codes WHERE " + column + " = ?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String uuidStr = rs.getString("confirmed_uuid");
                return Optional.of(new LinkCode(
                        rs.getString("code"),
                        rs.getString("link_token"),
                        LinkCode.Status.valueOf(rs.getString("status")),
                        uuidStr == null ? null : UUID.fromString(uuidStr),
                        rs.getString("confirmed_name"),
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        Instant.ofEpochMilli(rs.getLong("expires_at"))
                ));
            }
        } catch (SQLException ex) {
            throw new StorageException("findLink failed", ex);
        }
    }

    @Override
    public boolean confirmLinkCode(@NotNull String code, @NotNull UUID uuid, @NotNull String name) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_link_codes SET status='CONFIRMED', confirmed_uuid=?, confirmed_name=? "
                             + "WHERE code=? AND status='PENDING' AND expires_at > ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, code);
            ps.setLong(4, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("confirmLinkCode failed", ex);
        }
    }

    @Override
    public boolean markLinkConsumed(@NotNull String linkToken) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_link_codes SET status='CONSUMED' WHERE link_token=? AND status='CONFIRMED'")) {
            ps.setString(1, linkToken);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("markLinkConsumed failed", ex);
        }
    }

    @Override
    public void createSession(@NotNull Session session) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO eternal_sessions (token, user_uuid, user_name, role, created_at, expires_at) "
                             + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, session.token());
            ps.setString(2, session.userUuid().toString());
            ps.setString(3, session.userName());
            // Legacy role column kept for backward-compat (non-destructive); no
            // longer read — authorization is permission-based now.
            ps.setString(4, "");
            ps.setLong(5, session.createdAt().toEpochMilli());
            ps.setLong(6, session.expiresAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("createSession failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Session> findSession(@NotNull String token) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_sessions WHERE token = ? AND expires_at > ?")) {
            ps.setString(1, token);
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Session(
                        rs.getString("token"),
                        UUID.fromString(rs.getString("user_uuid")),
                        rs.getString("user_name"),
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        Instant.ofEpochMilli(rs.getLong("expires_at"))
                ));
            }
        } catch (SQLException ex) {
            throw new StorageException("findSession failed", ex);
        }
    }

    @Override
    public boolean deleteSession(@NotNull String token) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM eternal_sessions WHERE token = ?")) {
            ps.setString(1, token);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("deleteSession failed", ex);
        }
    }

    @Override
    public @NotNull List<Session> listActiveSessions() {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_sessions WHERE expires_at > ? ORDER BY created_at DESC")) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                List<Session> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Session(
                            rs.getString("token"),
                            UUID.fromString(rs.getString("user_uuid")),
                            rs.getString("user_name"),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            Instant.ofEpochMilli(rs.getLong("expires_at"))
                    ));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("listActiveSessions failed", ex);
        }
    }

    @Override
    public long queueAction(@NotNull String type, @NotNull UUID targetStaff, @NotNull String payload) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO eternal_actions (type, target_staff_uuid, payload, created_at) VALUES (?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setString(2, targetStaff.toString());
            ps.setString(3, payload);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException ex) {
            throw new StorageException("queueAction failed", ex);
        }
    }

    @Override
    public @NotNull List<ActionEntry> pendingActionsFor(@NotNull UUID targetStaff) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_actions WHERE target_staff_uuid = ? AND consumed_at IS NULL "
                             + "ORDER BY created_at ASC")) {
            ps.setString(1, targetStaff.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<ActionEntry> out = new ArrayList<>();
                while (rs.next()) {
                    long consumed = rs.getLong("consumed_at");
                    boolean consumedNull = rs.wasNull();
                    out.add(new ActionEntry(
                            rs.getLong("id"),
                            rs.getString("type"),
                            UUID.fromString(rs.getString("target_staff_uuid")),
                            rs.getString("payload"),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            consumedNull ? null : Instant.ofEpochMilli(consumed)
                    ));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("pendingActionsFor failed", ex);
        }
    }

    @Override
    public @NotNull List<ActionEntry> pendingActionsByType(@NotNull String type) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_actions WHERE type = ? AND consumed_at IS NULL "
                             + "ORDER BY created_at ASC")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                List<ActionEntry> out = new ArrayList<>();
                while (rs.next()) {
                    long consumed = rs.getLong("consumed_at");
                    boolean consumedNull = rs.wasNull();
                    out.add(new ActionEntry(
                            rs.getLong("id"),
                            rs.getString("type"),
                            UUID.fromString(rs.getString("target_staff_uuid")),
                            rs.getString("payload"),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            consumedNull ? null : Instant.ofEpochMilli(consumed)
                    ));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("pendingActionsByType failed", ex);
        }
    }

    @Override
    public boolean consumeAction(long id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_actions SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("consumeAction failed", ex);
        }
    }

    /* --- Unban appeals -------------------------------------------------- */

    @Override
    public long createAppeal(@NotNull UnbanAppeal appeal) {
        String sql = """
                INSERT INTO eternal_unban_appeals
                  (ban_id, applicant_uuid, applicant_name, text, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, appeal.banId());
            ps.setString(2, appeal.applicantUuid().toString());
            ps.setString(3, appeal.applicantName());
            ps.setString(4, appeal.text());
            ps.setString(5, appeal.status().name());
            ps.setLong(6, appeal.createdAt().toEpochMilli());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException ex) {
            throw new StorageException("createAppeal failed", ex);
        }
    }

    @Override
    public @NotNull Optional<UnbanAppeal> findAppeal(long id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_unban_appeals WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readAppeal(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findAppeal failed", ex);
        }
    }

    @Override
    public @NotNull List<UnbanAppeal> findAppealsByApplicant(@NotNull UUID applicant) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_unban_appeals WHERE applicant_uuid = ? ORDER BY created_at DESC")) {
            ps.setString(1, applicant.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<UnbanAppeal> out = new ArrayList<>();
                while (rs.next()) out.add(readAppeal(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findAppealsByApplicant failed", ex);
        }
    }

    @Override
    public @NotNull List<UnbanAppeal> findAppealsByStatus(@NotNull UnbanAppeal.Status status) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_unban_appeals WHERE status = ? ORDER BY created_at DESC")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<UnbanAppeal> out = new ArrayList<>();
                while (rs.next()) out.add(readAppeal(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findAppealsByStatus failed", ex);
        }
    }

    @Override
    public boolean decideAppeal(long id, @NotNull UUID reviewerUuid, @NotNull String reviewerName,
                                 @NotNull UnbanAppeal.Status decision, @NotNull String decisionReason) {
        return decideAppealFull(id, reviewerUuid, reviewerName, decision, decisionReason, null, null);
    }

    /**
     * Extended decide variant that also stamps the player-visible
     * {@code decisionMessage} and (for SHORTENED) the new remaining
     * duration in seconds. Used by the appeal-shorten flow.
     */
    public boolean decideAppealFull(long id, @NotNull UUID reviewerUuid, @NotNull String reviewerName,
                                     @NotNull UnbanAppeal.Status decision, @NotNull String decisionReason,
                                     @Nullable String decisionMessage, @Nullable Long shortenedToSeconds) {
        String sql = """
                UPDATE eternal_unban_appeals
                SET status = ?, reviewer_uuid = ?, reviewer_name = ?, reviewed_at = ?,
                    decision_reason = ?, decision_message = ?, shortened_to_seconds = ?
                WHERE id = ? AND status = 'PENDING'
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, decision.name());
            ps.setString(2, reviewerUuid.toString());
            ps.setString(3, reviewerName);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, decisionReason);
            if (decisionMessage == null) ps.setNull(6, java.sql.Types.VARCHAR);
            else ps.setString(6, decisionMessage);
            if (shortenedToSeconds == null) ps.setNull(7, java.sql.Types.BIGINT);
            else ps.setLong(7, shortenedToSeconds);
            ps.setLong(8, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("decideAppealFull failed", ex);
        }
    }

    private UnbanAppeal readAppeal(ResultSet rs) throws SQLException {
        String revUuid = rs.getString("reviewer_uuid");
        long revAt = rs.getLong("reviewed_at");
        boolean revNull = rs.wasNull();
        // New columns are added by migration; tolerate absence for in-place upgrade.
        String decisionMsg = null;
        long shortened = 0; boolean shortenedNull = true;
        try {
            decisionMsg = rs.getString("decision_message");
            shortened = rs.getLong("shortened_to_seconds");
            shortenedNull = rs.wasNull();
        } catch (SQLException ignored) { /* not yet migrated */ }
        return new UnbanAppeal(
                rs.getLong("id"),
                rs.getLong("ban_id"),
                UUID.fromString(rs.getString("applicant_uuid")),
                rs.getString("applicant_name"),
                rs.getString("text"),
                UnbanAppeal.Status.valueOf(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                revUuid == null ? null : UUID.fromString(revUuid),
                rs.getString("reviewer_name"),
                revNull ? null : Instant.ofEpochMilli(revAt),
                rs.getString("decision_reason"),
                decisionMsg,
                shortenedNull ? null : shortened
        );
    }

    /**
     * Profile prefix search by name OR UUID. Used by the dashboard
     * auto-complete; matches the start of {@code name} (case-insensitive)
     * and the start of the UUID string. Capped to {@code limit}.
     */
    /** Most-recently-seen profiles, no query filter. Powers the admin
     *  user-management list when the search box is empty so the admin
     *  sees something to act on immediately. */
    public @NotNull List<de.eternal.core.model.PlayerProfile> recentProfiles(int limit) {
        String sql = "SELECT uuid, name, first_seen, last_seen, last_address, last_tier, last_group_name, last_display_name "
                + "FROM eternal_profiles ORDER BY last_seen DESC LIMIT ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                List<de.eternal.core.model.PlayerProfile> out = new ArrayList<>();
                while (rs.next()) out.add(readProfile(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("recentProfiles failed", ex);
        }
    }

    public @NotNull List<de.eternal.core.model.PlayerProfile> searchProfiles(@NotNull String query, int limit) {
        if (query.isBlank()) return List.of();
        String like = query.toLowerCase(java.util.Locale.ROOT) + "%";
        String sql = "SELECT uuid, name, first_seen, last_seen, last_address, last_tier, last_group_name, last_display_name "
                + "FROM eternal_profiles "
                + "WHERE LOWER(name) LIKE ? OR LOWER(uuid) LIKE ? "
                + "ORDER BY last_seen DESC LIMIT ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setInt(3, Math.max(1, Math.min(50, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                List<de.eternal.core.model.PlayerProfile> out = new ArrayList<>();
                while (rs.next()) out.add(readProfile(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("searchProfiles failed", ex);
        }
    }

    /* --- Login sessions ------------------------------------------------ */

    @Override
    public long startSession(@NotNull UUID uuid, @NotNull String name, @NotNull String ip) {
        long now = System.currentTimeMillis();
        try (Connection c = conn()) {
            // A previous session may still be marked active if the server
            // crashed or the quit listener didn't fire (network plugin reload,
            // CloudNet hard-stop). Close all stale opens for this UUID with
            // the current timestamp so /lookup never shows multiple
            // "aktuell online" rows for the same player.
            try (PreparedStatement cleanup = c.prepareStatement(
                    "UPDATE eternal_login_sessions SET logout_at = ? WHERE uuid = ? AND logout_at IS NULL")) {
                cleanup.setLong(1, now);
                cleanup.setString(2, uuid.toString());
                cleanup.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eternal_login_sessions (uuid, name, ip, login_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, ip);
                ps.setLong(4, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
        } catch (SQLException ex) {
            throw new StorageException("startSession failed", ex);
        }
    }

    @Override
    public void endSession(long sessionId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_login_sessions SET logout_at = ? WHERE id = ? AND logout_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("endSession failed", ex);
        }
    }

    @Override
    public @NotNull List<LoginSession> recentSessions(@NotNull UUID uuid, int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_login_sessions WHERE uuid = ? ORDER BY login_at DESC LIMIT " + safeLimit)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<LoginSession> out = new ArrayList<>();
                while (rs.next()) {
                    long logout = rs.getLong("logout_at");
                    boolean logoutNull = rs.wasNull();
                    out.add(new LoginSession(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"),
                            rs.getString("ip"),
                            Instant.ofEpochMilli(rs.getLong("login_at")),
                            logoutNull ? null : Instant.ofEpochMilli(logout)
                    ));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("recentSessions failed", ex);
        }
    }

    /* --- Report-Ban linking ------------------------------------------- */

    @Override
    public boolean linkReportToBan(long reportId, long banId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_reports SET ban_id = ? WHERE id = ?")) {
            ps.setLong(1, banId);
            ps.setLong(2, reportId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("linkReportToBan failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Long> findBanForReport(long reportId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ban_id FROM eternal_reports WHERE id = ?")) {
            ps.setLong(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long id = rs.getLong("ban_id");
                if (rs.wasNull()) return Optional.empty();
                return Optional.of(id);
            }
        } catch (SQLException ex) {
            throw new StorageException("findBanForReport failed", ex);
        }
    }

    @Override
    public @NotNull List<ReportEntry> findReportsByBanId(long banId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_reports WHERE ban_id = ? AND hidden = 0")) {
            ps.setLong(1, banId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ReportEntry> out = new ArrayList<>();
                while (rs.next()) out.add(readReport(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findReportsByBanId failed", ex);
        }
    }

    // Suppress unused warning; kept for future binary-typed columns.
    @SuppressWarnings("unused")
    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    /* ====================================================================
     * PermissionStorage implementation — roles + role/user permission rows
     * ==================================================================== */

    @Override
    public @NotNull List<Role> listRoles() {
        String sql = "SELECT name, display_name, mc_group_name, sort_order, color, created_at "
                + "FROM eternal_roles ORDER BY sort_order DESC, name ASC";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Role> out = new ArrayList<>();
            while (rs.next()) out.add(readRole(rs));
            return out;
        } catch (SQLException ex) {
            throw new StorageException("listRoles failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Role> findRole(@NotNull String name) {
        String sql = "SELECT name, display_name, mc_group_name, sort_order, color, created_at "
                + "FROM eternal_roles WHERE name = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readRole(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findRole failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Role> findRoleByMcGroup(@NotNull String mcGroupName) {
        String sql = "SELECT name, display_name, mc_group_name, sort_order, color, created_at "
                + "FROM eternal_roles WHERE LOWER(mc_group_name) = LOWER(?)";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mcGroupName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readRole(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findRoleByMcGroup failed", ex);
        }
    }

    @Override
    public void upsertRole(@NotNull Role role) {
        // INSERT-or-UPDATE pattern: SQLite uses ON CONFLICT, MySQL uses
        // ON DUPLICATE KEY UPDATE. created_at is set on INSERT only;
        // on UPDATE we preserve the original via excluded/COALESCE.
        long now = role.createdAt().toEpochMilli();
        boolean sqlite = isSqlite();
        String sql = sqlite ? """
                INSERT INTO eternal_roles (name, display_name, mc_group_name, sort_order, color, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                  display_name=excluded.display_name,
                  mc_group_name=excluded.mc_group_name,
                  sort_order=excluded.sort_order,
                  color=excluded.color
                """ : """
                INSERT INTO eternal_roles (name, display_name, mc_group_name, sort_order, color, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  display_name=VALUES(display_name),
                  mc_group_name=VALUES(mc_group_name),
                  sort_order=VALUES(sort_order),
                  color=VALUES(color)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, role.name());
            ps.setString(2, role.displayName());
            ps.setString(3, role.mcGroupName());
            ps.setInt(4, role.sortOrder());
            ps.setString(5, role.color());
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("upsertRole failed", ex);
        }
    }

    @Override
    public boolean deleteRole(@NotNull String name) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM eternal_roles WHERE name = ?");
             PreparedStatement clearPerms = c.prepareStatement(
                     "DELETE FROM eternal_role_permissions WHERE role_name = ?")) {
            // Cascade: remove the role's permission rows too, otherwise
            // they'd dangle. Wrapped in the same logical operation; in
            // the SQLite case Hikari gives us autocommit-per-statement
            // which is fine since the delete order doesn't matter.
            clearPerms.setString(1, name);
            clearPerms.executeUpdate();
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("deleteRole failed", ex);
        }
    }

    @Override
    public @NotNull Map<String, PermissionGrant> rolePermissions(@NotNull String roleName) {
        String sql = "SELECT role_name, permission_key, granted, updated_at, updated_by "
                + "FROM eternal_role_permissions WHERE role_name = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, PermissionGrant> out = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    PermissionGrant g = readRoleGrant(rs);
                    out.put(g.permissionKey(), g);
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("rolePermissions failed", ex);
        }
    }

    @Override
    public void setRolePermission(@NotNull String roleName, @NotNull String key,
                                   boolean granted, @Nullable String updatedBy) {
        upsertPermission("eternal_role_permissions", "role_name", roleName, key, granted, updatedBy);
    }

    @Override
    public boolean clearRolePermission(@NotNull String roleName, @NotNull String key) {
        return deletePermission("eternal_role_permissions", "role_name", roleName, key);
    }

    @Override
    public @NotNull Map<String, PermissionGrant> userPermissions(@NotNull UUID userUuid) {
        String sql = "SELECT user_uuid, permission_key, granted, updated_at, updated_by, expires_at "
                + "FROM eternal_user_permissions WHERE user_uuid = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, PermissionGrant> out = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    PermissionGrant g = readUserGrant(rs);
                    out.put(g.permissionKey(), g);
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("userPermissions failed", ex);
        }
    }

    @Override
    public void setUserPermission(@NotNull UUID userUuid, @NotNull String key,
                                   boolean granted, @Nullable String updatedBy) {
        setUserPermission(userUuid, key, granted, updatedBy, null);
    }

    @Override
    public void setUserPermission(@NotNull UUID userUuid, @NotNull String key, boolean granted,
                                   @Nullable String updatedBy, @Nullable Long expiresAtMs) {
        // Dedicated upsert because user grants carry expires_at (role grants
        // don't — they go through the shared upsertPermission).
        String onConflict = isSqlite()
                ? "ON CONFLICT(user_uuid, permission_key) DO UPDATE SET granted=excluded.granted, "
                  + "updated_at=excluded.updated_at, updated_by=excluded.updated_by, expires_at=excluded.expires_at"
                : "ON DUPLICATE KEY UPDATE granted=VALUES(granted), updated_at=VALUES(updated_at), "
                  + "updated_by=VALUES(updated_by), expires_at=VALUES(expires_at)";
        String sql = "INSERT INTO eternal_user_permissions "
                + "(user_uuid, permission_key, granted, updated_at, updated_by, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) " + onConflict;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUuid.toString());
            ps.setString(2, key);
            ps.setInt(3, granted ? 1 : 0);
            ps.setLong(4, System.currentTimeMillis());
            if (updatedBy == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, updatedBy);
            if (expiresAtMs == null) ps.setNull(6, java.sql.Types.BIGINT); else ps.setLong(6, expiresAtMs);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("setUserPermission failed", ex);
        }
    }

    @Override
    public boolean clearUserPermission(@NotNull UUID userUuid, @NotNull String key) {
        return deletePermission("eternal_user_permissions", "user_uuid", userUuid.toString(), key);
    }

    /* --- access requests ----------------------------------------------- */

    @Override
    public long createPermissionRequest(@NotNull UUID requesterUuid, @NotNull String requesterName,
                                        @NotNull String permissionKey, @Nullable String justification) {
        String sql = "INSERT INTO eternal_permission_requests "
                + "(requester_uuid, requester_name, permission_key, justification, status, created_at) "
                + "VALUES (?, ?, ?, ?, 'PENDING', ?)";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, requesterUuid.toString());
            ps.setString(2, requesterName);
            ps.setString(3, permissionKey);
            if (justification == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, justification);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException ex) {
            throw new StorageException("createPermissionRequest failed", ex);
        }
    }

    @Override
    public @NotNull Optional<de.eternal.core.model.PermissionRequest> findPermissionRequest(long id) {
        String sql = "SELECT * FROM eternal_permission_requests WHERE id = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPermissionRequest(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findPermissionRequest failed", ex);
        }
    }

    @Override
    public @NotNull List<de.eternal.core.model.PermissionRequest> listPermissionRequests(
            @Nullable de.eternal.core.model.PermissionRequest.Status status) {
        String sql = "SELECT * FROM eternal_permission_requests"
                + (status != null ? " WHERE status = ?" : "")
                + " ORDER BY created_at DESC";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (status != null) ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<de.eternal.core.model.PermissionRequest> out = new ArrayList<>();
                while (rs.next()) out.add(readPermissionRequest(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("listPermissionRequests failed", ex);
        }
    }

    @Override
    public @NotNull List<de.eternal.core.model.PermissionRequest> myPermissionRequests(@NotNull UUID requesterUuid) {
        String sql = "SELECT * FROM eternal_permission_requests WHERE requester_uuid = ? ORDER BY created_at DESC";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, requesterUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<de.eternal.core.model.PermissionRequest> out = new ArrayList<>();
                while (rs.next()) out.add(readPermissionRequest(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("myPermissionRequests failed", ex);
        }
    }

    @Override
    public boolean hasPendingRequest(@NotNull UUID requesterUuid, @NotNull String permissionKey) {
        String sql = "SELECT 1 FROM eternal_permission_requests "
                + "WHERE requester_uuid = ? AND permission_key = ? AND status = 'PENDING'";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, requesterUuid.toString());
            ps.setString(2, permissionKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new StorageException("hasPendingRequest failed", ex);
        }
    }

    @Override
    public boolean decidePermissionRequest(long id, @NotNull UUID byUuid, @NotNull String byName,
                                           @NotNull de.eternal.core.model.PermissionRequest.Status status,
                                           @Nullable String note, @Nullable Long expiresAtMs) {
        String sql = "UPDATE eternal_permission_requests SET status = ?, decided_by_uuid = ?, "
                + "decided_by_name = ?, decided_at = ?, decision_note = ?, expires_at = ? "
                + "WHERE id = ? AND status = 'PENDING'";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, byUuid.toString());
            ps.setString(3, byName);
            ps.setLong(4, System.currentTimeMillis());
            if (note == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, note);
            if (expiresAtMs == null) ps.setNull(6, java.sql.Types.BIGINT); else ps.setLong(6, expiresAtMs);
            ps.setLong(7, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("decidePermissionRequest failed", ex);
        }
    }

    @Override
    public int sweepExpiredUserGrants() {
        long now = System.currentTimeMillis();
        int removed;
        List<String[]> expired = new ArrayList<>();   // (uuid, key) to clear in CloudPerms
        try (Connection c = conn()) {
            // 1. Collect which grants are about to expire (for the CloudPerms clear).
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT user_uuid, permission_key FROM eternal_user_permissions "
                    + "WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) expired.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
            // 2. Flip the APPROVED requests whose grant has now expired.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eternal_permission_requests SET status = 'EXPIRED' "
                    + "WHERE status = 'APPROVED' AND expires_at IS NOT NULL AND expires_at <= ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            // 3. Delete the expired user-override grants themselves.
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM eternal_user_permissions WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
                ps.setLong(1, now);
                removed = ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new StorageException("sweepExpiredUserGrants failed", ex);
        }
        // 4. Queue a CloudPerms clear for each lapsed grant so the in-game node
        //    is removed too (the proxy applies it). JSON built by hand — keys
        //    are permission strings, no quote/escape hazards.
        for (String[] uk : expired) {
            try {
                UUID uuid = UUID.fromString(uk[0]);
                String payload = "{\"scope\":\"user\",\"uuid\":\"" + uk[0]
                        + "\",\"key\":\"" + uk[1] + "\",\"clear\":true}";
                queueAction("CLOUDPERMS_WRITE", uuid, payload);
            } catch (IllegalArgumentException ignored) { /* malformed uuid row — skip */ }
        }
        return removed;
    }

    private @NotNull de.eternal.core.model.PermissionRequest readPermissionRequest(@NotNull ResultSet rs) throws SQLException {
        long decidedAt = rs.getLong("decided_at");
        boolean hasDecidedAt = !rs.wasNull();
        long expiresAt = rs.getLong("expires_at");
        boolean hasExpiresAt = !rs.wasNull();
        String decidedByUuid = rs.getString("decided_by_uuid");
        return new de.eternal.core.model.PermissionRequest(
                rs.getLong("id"),
                UUID.fromString(rs.getString("requester_uuid")),
                rs.getString("requester_name"),
                rs.getString("permission_key"),
                rs.getString("justification"),
                de.eternal.core.model.PermissionRequest.Status.valueOf(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                decidedByUuid == null ? null : UUID.fromString(decidedByUuid),
                rs.getString("decided_by_name"),
                hasDecidedAt ? Instant.ofEpochMilli(decidedAt) : null,
                rs.getString("decision_note"),
                hasExpiresAt ? Instant.ofEpochMilli(expiresAt) : null
        );
    }

    /* --- permission helpers ------------------------------------------ */

    /** Generic upsert against either permission table — both share the
     *  same shape (subject column, permission_key, granted, updated_at,
     *  updated_by). */
    private void upsertPermission(@NotNull String table, @NotNull String subjectCol,
                                   @NotNull String subjectValue, @NotNull String key,
                                   boolean granted, @Nullable String updatedBy) {
        boolean sqlite = isSqlite();
        String onConflict = sqlite
                ? "ON CONFLICT(" + subjectCol + ", permission_key) DO UPDATE SET "
                  + "granted=excluded.granted, updated_at=excluded.updated_at, updated_by=excluded.updated_by"
                : "ON DUPLICATE KEY UPDATE granted=VALUES(granted), "
                  + "updated_at=VALUES(updated_at), updated_by=VALUES(updated_by)";
        String sql = "INSERT INTO " + table
                + " (" + subjectCol + ", permission_key, granted, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, ?) " + onConflict;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, subjectValue);
            ps.setString(2, key);
            ps.setInt(3, granted ? 1 : 0);
            ps.setLong(4, System.currentTimeMillis());
            if (updatedBy == null) ps.setNull(5, java.sql.Types.VARCHAR);
            else ps.setString(5, updatedBy);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("upsertPermission failed on " + table, ex);
        }
    }

    private boolean deletePermission(@NotNull String table, @NotNull String subjectCol,
                                     @NotNull String subjectValue, @NotNull String key) {
        String sql = "DELETE FROM " + table + " WHERE " + subjectCol + " = ? AND permission_key = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, subjectValue);
            ps.setString(2, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("deletePermission failed on " + table, ex);
        }
    }

    private @NotNull Role readRole(@NotNull ResultSet rs) throws SQLException {
        return new Role(
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("mc_group_name"),
                rs.getInt("sort_order"),
                rs.getString("color"),
                Instant.ofEpochMilli(rs.getLong("created_at"))
        );
    }

    private @NotNull PermissionGrant readRoleGrant(@NotNull ResultSet rs) throws SQLException {
        return new PermissionGrant(
                rs.getString("role_name"),
                rs.getString("permission_key"),
                rs.getInt("granted") != 0,
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getString("updated_by"),
                null   // role grants never expire
        );
    }

    private @NotNull PermissionGrant readUserGrant(@NotNull ResultSet rs) throws SQLException {
        long exp = rs.getLong("expires_at");
        Instant expiresAt = rs.wasNull() ? null : Instant.ofEpochMilli(exp);
        return new PermissionGrant(
                rs.getString("user_uuid"),
                rs.getString("permission_key"),
                rs.getInt("granted") != 0,
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getString("updated_by"),
                expiresAt
        );
    }

    /* ====================================================================
     * Consent (GDPR) — records that a player explicitly accepted our
     * privacy policy on first join. NO row = haven't been asked yet or
     * declined; row = accepted and we may persist their data.
     * ==================================================================== */

    /** Creates the {@code eternal_consent} table on first start. Idempotent.
     *  We store the IP address alongside the consent timestamp because
     *  GDPR audit log is also explicitly consented-to data — this row IS
     *  the proof we have permission to store everything else. */
    private void createConsentTable(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eternal_consent (
                      uuid VARCHAR(36) PRIMARY KEY,
                      name VARCHAR(64) NOT NULL,
                      accepted_at BIGINT NOT NULL,
                      ip_address VARCHAR(64) NOT NULL
                    )
                    """);
        }
    }

    /** Has this UUID accepted the privacy policy? Returns false for
     *  unknown UUIDs (never asked) and for declined UUIDs (we purge
     *  declined ones, so the table reads identically). */
    public boolean hasConsent(@NotNull UUID uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM eternal_consent WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new StorageException("hasConsent failed", ex);
        }
    }

    /** Records an explicit acceptance. Called from the in-game
     *  {@code /eternal accept} handler — the rest of the data pipeline
     *  is gated on this row existing. */
    public void recordConsent(@NotNull UUID uuid, @NotNull String name, @NotNull String ip) {
        boolean sqlite = isSqlite();
        String sql = sqlite ? """
                INSERT INTO eternal_consent (uuid, name, accepted_at, ip_address)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  name=excluded.name, accepted_at=excluded.accepted_at, ip_address=excluded.ip_address
                """ : """
                INSERT INTO eternal_consent (uuid, name, accepted_at, ip_address)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  name=VALUES(name), accepted_at=VALUES(accepted_at), ip_address=VALUES(ip_address)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, ip);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("recordConsent failed", ex);
        }
    }

    /**
     * Purges every Eternal-side row that contains personal data for
     * {@code uuid}. Called from the decline path. Returns the number
     * of rows deleted across all tables (for logging).
     *
     * <p>What's purged:</p>
     * <ul>
     *     <li>{@code eternal_profiles} — IP, group, display name cache</li>
     *     <li>{@code eternal_sessions} — web dashboard sessions</li>
     *     <li>{@code eternal_login_sessions} — ingame login/logout log</li>
     *     <li>{@code eternal_consent} — the consent row itself (no leak)</li>
     *     <li>{@code eternal_link_codes} — pending account-link codes</li>
     *     <li>{@code eternal_user_permissions} — per-user permission overrides</li>
     * </ul>
     *
     * <p>What is <b>kept</b> (legitimate-interest basis, GDPR Art. 6 lit. f):</p>
     * <ul>
     *     <li>{@code eternal_punishments} — moderation history</li>
     *     <li>{@code eternal_reports} — moderation history</li>
     *     <li>{@code eternal_unban_appeals} — moderation history</li>
     *     <li>Replay files — moderation evidence</li>
     * </ul>
     *
     * <p>The moderation tables stay because allowing a banned user to
     * wipe their ban by declining the privacy notice would defeat the
     * purpose. We can defend that under Art. 6 (1) (f) — legitimate
     * interest in operating the server safely.</p>
     */
    public int purgePersonalData(@NotNull UUID uuid) {
        int total = 0;
        String uuidStr = uuid.toString();
        String[] tables = {
                "eternal_profiles",
                "eternal_sessions",
                "eternal_login_sessions",
                "eternal_consent",
                "eternal_user_permissions",
                "eternal_party_members",
                "eternal_player_prefs",
                "eternal_nick_sessions",
                "eternal_base_homes"
        };
        String[] uuidColumns = {
                "uuid", "user_uuid", "uuid", "uuid", "user_uuid",
                "uuid", "uuid", "uuid", "owner_uuid"
        };
        try (Connection c = conn()) {
            for (int i = 0; i < tables.length; i++) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + tables[i] + " WHERE " + uuidColumns[i] + " = ?")) {
                    ps.setString(1, uuidStr);
                    total += ps.executeUpdate();
                } catch (SQLException ex) {
                    // Table may not exist yet on very old DBs (user_permissions
                    // is from the permission-engine PR). Log + keep going.
                    System.err.println("[Eternal-purge] " + tables[i] + ": " + ex.getMessage());
                }
            }
            // Link-codes get a separate clean-up because column name differs.
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM eternal_link_codes WHERE confirmed_uuid = ?")) {
                ps.setString(1, uuidStr);
                total += ps.executeUpdate();
            } catch (SQLException ex) {
                System.err.println("[Eternal-purge] eternal_link_codes: " + ex.getMessage());
            }
            // Social tables with a two-sided UUID relationship — friendships,
            // friend requests, party invites and led parties are personal
            // data on BOTH ends, so purge any row mentioning the player.
            String[][] twoSided = {
                    {"eternal_friendships", "uuid_a", "uuid_b"},
                    {"eternal_friend_requests", "from_uuid", "to_uuid"},
                    {"eternal_party_invites", "from_uuid", "to_uuid"},
                    {"eternal_parties", "leader_uuid", "leader_uuid"}
            };
            for (String[] tc : twoSided) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + tc[0] + " WHERE " + tc[1] + " = ? OR " + tc[2] + " = ?")) {
                    ps.setString(1, uuidStr);
                    ps.setString(2, uuidStr);
                    total += ps.executeUpdate();
                } catch (SQLException ex) {
                    System.err.println("[Eternal-purge] " + tc[0] + ": " + ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            throw new StorageException("purgePersonalData failed", ex);
        }
        return total;
    }

    /* ====================================================================
     * SocialStorage — friends, friend requests, parties, party invites,
     * per-player prefs and nick sessions. Same conventions as everything
     * above: eternal_ prefix, UUID VARCHAR(36), millis BIGINT, INT booleans,
     * VARCHAR enum status, dialect-aware upserts, no FK constraints.
     * ==================================================================== */

    private void createSocialTables(@NotNull Connection c) throws SQLException {
        String pk = pk();
        String s = textType();
        String t = longTextType();
        try (Statement st = c.createStatement()) {
            // Friendships — one row per pair, canonically ordered (uuid_a is
            // the lexicographically smaller UUID string). Unique index powers
            // the upsert conflict target AND enforces "one friendship row".
            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_friendships (
                      id %1$s,
                      uuid_a %2$s NOT NULL,
                      name_a %2$s NOT NULL,
                      uuid_b %2$s NOT NULL,
                      name_b %2$s NOT NULL,
                      created_at BIGINT NOT NULL
                    )""").formatted(pk, s));
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_friendships_pair ON eternal_friendships(uuid_a, uuid_b)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_friendships_b ON eternal_friendships(uuid_b)");

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_friend_requests (
                      id %1$s,
                      from_uuid %2$s NOT NULL,
                      from_name %2$s NOT NULL,
                      to_uuid %2$s NOT NULL,
                      to_name %2$s NOT NULL,
                      status %2$s NOT NULL,
                      created_at BIGINT NOT NULL,
                      responded_at BIGINT
                    )""").formatted(pk, s));
            st.execute("CREATE INDEX IF NOT EXISTS idx_friend_req_to ON eternal_friend_requests(to_uuid, status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_friend_req_from ON eternal_friend_requests(from_uuid, status)");

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_parties (
                      id %1$s,
                      leader_uuid %2$s NOT NULL,
                      leader_name %2$s NOT NULL,
                      created_at BIGINT NOT NULL,
                      disbanded_at BIGINT
                    )""").formatted(pk, s));

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_party_members (
                      id %1$s,
                      party_id BIGINT NOT NULL,
                      uuid %2$s NOT NULL,
                      name %2$s NOT NULL,
                      role %2$s NOT NULL,
                      joined_at BIGINT NOT NULL
                    )""").formatted(pk, s));
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_party_members_pair ON eternal_party_members(party_id, uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_party_members_uuid ON eternal_party_members(uuid)");

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_party_invites (
                      id %1$s,
                      party_id BIGINT NOT NULL,
                      from_uuid %2$s NOT NULL,
                      from_name %2$s NOT NULL,
                      to_uuid %2$s NOT NULL,
                      to_name %2$s NOT NULL,
                      status %2$s NOT NULL,
                      created_at BIGINT NOT NULL,
                      expires_at BIGINT
                    )""").formatted(pk, s));
            st.execute("CREATE INDEX IF NOT EXISTS idx_party_invites_to ON eternal_party_invites(to_uuid, status)");

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_player_prefs (
                      uuid %1$s PRIMARY KEY NOT NULL,
                      allow_friend_requests INTEGER NOT NULL DEFAULT 1,
                      allow_party_invites INTEGER NOT NULL DEFAULT 1,
                      autonick_default_display %2$s
                    )""").formatted(s, t));

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_nick_sessions (
                      uuid %1$s PRIMARY KEY NOT NULL,
                      original_name %1$s NOT NULL,
                      original_group %1$s NOT NULL DEFAULT '',
                      nick_name %1$s NOT NULL,
                      nick_group %1$s NOT NULL DEFAULT '',
                      skin_value %2$s,
                      skin_signature %2$s,
                      started_at BIGINT NOT NULL
                    )""").formatted(s, t));
        }
    }

    private static UUID lo(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? a : b;
    }

    private static UUID hi(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? b : a;
    }

    @Override
    public void addFriendship(@NotNull UUID a, @NotNull String nameA, @NotNull UUID b, @NotNull String nameB) {
        // Canonical ordering so (a,b) and (b,a) collapse to one row.
        boolean aLow = a.toString().compareTo(b.toString()) <= 0;
        UUID lo = aLow ? a : b;
        String loName = aLow ? nameA : nameB;
        UUID hi = aLow ? b : a;
        String hiName = aLow ? nameB : nameA;
        String sql = isSqlite() ? """
                INSERT INTO eternal_friendships (uuid_a, name_a, uuid_b, name_b, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid_a, uuid_b) DO UPDATE SET name_a=excluded.name_a, name_b=excluded.name_b
                """ : """
                INSERT INTO eternal_friendships (uuid_a, name_a, uuid_b, name_b, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name_a=VALUES(name_a), name_b=VALUES(name_b)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, lo.toString());
            ps.setString(2, loName);
            ps.setString(3, hi.toString());
            ps.setString(4, hiName);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("addFriendship failed", ex);
        }
    }

    @Override
    public boolean removeFriendship(@NotNull UUID a, @NotNull UUID b) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM eternal_friendships WHERE uuid_a = ? AND uuid_b = ?")) {
            ps.setString(1, lo(a, b).toString());
            ps.setString(2, hi(a, b).toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("removeFriendship failed", ex);
        }
    }

    @Override
    public boolean areFriends(@NotNull UUID a, @NotNull UUID b) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM eternal_friendships WHERE uuid_a = ? AND uuid_b = ?")) {
            ps.setString(1, lo(a, b).toString());
            ps.setString(2, hi(a, b).toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new StorageException("areFriends failed", ex);
        }
    }

    @Override
    public @NotNull List<Friend> friendsOf(@NotNull UUID self) {
        String me = self.toString();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid_a, name_a, uuid_b, name_b FROM eternal_friendships "
                             + "WHERE uuid_a = ? OR uuid_b = ? ORDER BY created_at DESC")) {
            ps.setString(1, me);
            ps.setString(2, me);
            try (ResultSet rs = ps.executeQuery()) {
                List<Friend> out = new ArrayList<>();
                while (rs.next()) {
                    String ua = rs.getString("uuid_a");
                    if (ua.equals(me)) {
                        out.add(new Friend(UUID.fromString(rs.getString("uuid_b")), rs.getString("name_b")));
                    } else {
                        out.add(new Friend(UUID.fromString(ua), rs.getString("name_a")));
                    }
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("friendsOf failed", ex);
        }
    }

    @Override
    public int friendCount(@NotNull UUID self) {
        String me = self.toString();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM eternal_friendships WHERE uuid_a = ? OR uuid_b = ?")) {
            ps.setString(1, me);
            ps.setString(2, me);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new StorageException("friendCount failed", ex);
        }
    }

    @Override
    public long createFriendRequest(@NotNull UUID from, @NotNull String fromName,
                                    @NotNull UUID to, @NotNull String toName) {
        Optional<FriendRequest> existing = findPendingFriendRequest(from, to);
        if (existing.isPresent()) return existing.get().id();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO eternal_friend_requests (from_uuid, from_name, to_uuid, to_name, status, created_at) "
                             + "VALUES (?, ?, ?, ?, 'PENDING', ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, from.toString());
            ps.setString(2, fromName);
            ps.setString(3, to.toString());
            ps.setString(4, toName);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException ex) {
            throw new StorageException("createFriendRequest failed", ex);
        }
    }

    @Override
    public @NotNull Optional<FriendRequest> findFriendRequest(long id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_friend_requests WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readFriendRequest(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findFriendRequest failed", ex);
        }
    }

    @Override
    public @NotNull Optional<FriendRequest> findPendingFriendRequest(@NotNull UUID from, @NotNull UUID to) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_friend_requests WHERE from_uuid = ? AND to_uuid = ? AND status = 'PENDING' "
                             + "ORDER BY created_at DESC")) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readFriendRequest(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findPendingFriendRequest failed", ex);
        }
    }

    @Override
    public @NotNull List<FriendRequest> incomingFriendRequests(@NotNull UUID to) {
        return friendRequestsByColumn("to_uuid", to);
    }

    @Override
    public @NotNull List<FriendRequest> outgoingFriendRequests(@NotNull UUID from) {
        return friendRequestsByColumn("from_uuid", from);
    }

    private @NotNull List<FriendRequest> friendRequestsByColumn(@NotNull String column, @NotNull UUID id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_friend_requests WHERE " + column + " = ? AND status = 'PENDING' "
                             + "ORDER BY created_at DESC")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<FriendRequest> out = new ArrayList<>();
                while (rs.next()) out.add(readFriendRequest(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("friendRequestsByColumn failed", ex);
        }
    }

    @Override
    public boolean resolveFriendRequest(long id, @NotNull FriendRequest.Status status) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_friend_requests SET status = ?, responded_at = ? WHERE id = ? AND status = 'PENDING'")) {
            ps.setString(1, status.name());
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("resolveFriendRequest failed", ex);
        }
    }

    private FriendRequest readFriendRequest(@NotNull ResultSet rs) throws SQLException {
        long responded = rs.getLong("responded_at");
        boolean respondedNull = rs.wasNull();
        return new FriendRequest(
                rs.getLong("id"),
                UUID.fromString(rs.getString("from_uuid")),
                rs.getString("from_name"),
                UUID.fromString(rs.getString("to_uuid")),
                rs.getString("to_name"),
                FriendRequest.Status.valueOf(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                respondedNull ? null : Instant.ofEpochMilli(responded)
        );
    }

    @Override
    public long createParty(@NotNull UUID leaderUuid, @NotNull String leaderName) {
        long now = System.currentTimeMillis();
        try (Connection c = conn()) {
            long partyId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eternal_parties (leader_uuid, leader_name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, leaderUuid.toString());
                ps.setString(2, leaderName);
                ps.setLong(3, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    partyId = keys.next() ? keys.getLong(1) : -1L;
                }
            }
            if (partyId > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO eternal_party_members (party_id, uuid, name, role, joined_at) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setLong(1, partyId);
                    ps.setString(2, leaderUuid.toString());
                    ps.setString(3, leaderName);
                    ps.setString(4, PartyMember.ROLE_LEADER);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
            }
            return partyId;
        } catch (SQLException ex) {
            throw new StorageException("createParty failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Party> findParty(long id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_parties WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readParty(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findParty failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Party> findActivePartyOf(@NotNull UUID uuid) {
        String sql = """
                SELECT p.* FROM eternal_parties p
                JOIN eternal_party_members m ON m.party_id = p.id
                WHERE m.uuid = ? AND p.disbanded_at IS NULL
                ORDER BY p.created_at DESC LIMIT 1
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readParty(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findActivePartyOf failed", ex);
        }
    }

    @Override
    public @NotNull List<PartyMember> partyMembers(long partyId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_party_members WHERE party_id = ? ORDER BY joined_at ASC")) {
            ps.setLong(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PartyMember> out = new ArrayList<>();
                while (rs.next()) out.add(readPartyMember(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("partyMembers failed", ex);
        }
    }

    @Override
    public boolean addPartyMember(long partyId, @NotNull UUID uuid, @NotNull String name, @NotNull String role) {
        String sql = isSqlite() ? """
                INSERT INTO eternal_party_members (party_id, uuid, name, role, joined_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(party_id, uuid) DO UPDATE SET name=excluded.name, role=excluded.role
                """ : """
                INSERT INTO eternal_party_members (party_id, uuid, name, role, joined_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name=VALUES(name), role=VALUES(role)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, partyId);
            ps.setString(2, uuid.toString());
            ps.setString(3, name);
            ps.setString(4, role);
            ps.setLong(5, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("addPartyMember failed", ex);
        }
    }

    @Override
    public boolean removePartyMember(long partyId, @NotNull UUID uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM eternal_party_members WHERE party_id = ? AND uuid = ?")) {
            ps.setLong(1, partyId);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("removePartyMember failed", ex);
        }
    }

    @Override
    public boolean transferPartyLeader(long partyId, @NotNull UUID newLeaderUuid, @NotNull String newLeaderName) {
        try (Connection c = conn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eternal_parties SET leader_uuid = ?, leader_name = ? WHERE id = ? AND disbanded_at IS NULL")) {
                ps.setString(1, newLeaderUuid.toString());
                ps.setString(2, newLeaderName);
                ps.setLong(3, partyId);
                if (ps.executeUpdate() == 0) return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eternal_party_members SET role = ? WHERE party_id = ? AND role = ?")) {
                ps.setString(1, PartyMember.ROLE_MEMBER);
                ps.setLong(2, partyId);
                ps.setString(3, PartyMember.ROLE_LEADER);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eternal_party_members SET role = ? WHERE party_id = ? AND uuid = ?")) {
                ps.setString(1, PartyMember.ROLE_LEADER);
                ps.setLong(2, partyId);
                ps.setString(3, newLeaderUuid.toString());
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException ex) {
            throw new StorageException("transferPartyLeader failed", ex);
        }
    }

    @Override
    public void disbandParty(long partyId) {
        try (Connection c = conn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eternal_parties SET disbanded_at = ? WHERE id = ? AND disbanded_at IS NULL")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, partyId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM eternal_party_members WHERE party_id = ?")) {
                ps.setLong(1, partyId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eternal_party_invites SET status = 'CANCELLED' WHERE party_id = ? AND status = 'PENDING'")) {
                ps.setLong(1, partyId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new StorageException("disbandParty failed", ex);
        }
    }

    private Party readParty(@NotNull ResultSet rs) throws SQLException {
        long disbanded = rs.getLong("disbanded_at");
        boolean disbandedNull = rs.wasNull();
        return new Party(
                rs.getLong("id"),
                UUID.fromString(rs.getString("leader_uuid")),
                rs.getString("leader_name"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                disbandedNull ? null : Instant.ofEpochMilli(disbanded)
        );
    }

    private PartyMember readPartyMember(@NotNull ResultSet rs) throws SQLException {
        return new PartyMember(
                rs.getLong("id"),
                rs.getLong("party_id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getString("role"),
                Instant.ofEpochMilli(rs.getLong("joined_at"))
        );
    }

    @Override
    public long createPartyInvite(long partyId, @NotNull UUID from, @NotNull String fromName,
                                  @NotNull UUID to, @NotNull String toName, @NotNull Instant expiresAt) {
        Optional<PartyInvite> existing = findPendingPartyInvite(partyId, to);
        if (existing.isPresent()) return existing.get().id();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO eternal_party_invites (party_id, from_uuid, from_name, to_uuid, to_name, status, created_at, expires_at) "
                             + "VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, partyId);
            ps.setString(2, from.toString());
            ps.setString(3, fromName);
            ps.setString(4, to.toString());
            ps.setString(5, toName);
            ps.setLong(6, System.currentTimeMillis());
            ps.setLong(7, expiresAt.toEpochMilli());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException ex) {
            throw new StorageException("createPartyInvite failed", ex);
        }
    }

    @Override
    public @NotNull Optional<PartyInvite> findPartyInvite(long id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_party_invites WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPartyInvite(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findPartyInvite failed", ex);
        }
    }

    @Override
    public @NotNull Optional<PartyInvite> findPendingPartyInvite(long partyId, @NotNull UUID to) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_party_invites WHERE party_id = ? AND to_uuid = ? AND status = 'PENDING' "
                             + "ORDER BY created_at DESC")) {
            ps.setLong(1, partyId);
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPartyInvite(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findPendingPartyInvite failed", ex);
        }
    }

    @Override
    public @NotNull List<PartyInvite> incomingPartyInvites(@NotNull UUID to) {
        long now = System.currentTimeMillis();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eternal_party_invites WHERE to_uuid = ? AND status = 'PENDING' "
                             + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC")) {
            ps.setString(1, to.toString());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<PartyInvite> out = new ArrayList<>();
                while (rs.next()) out.add(readPartyInvite(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("incomingPartyInvites failed", ex);
        }
    }

    @Override
    public boolean resolvePartyInvite(long id, @NotNull PartyInvite.Status status) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_party_invites SET status = ? WHERE id = ? AND status = 'PENDING'")) {
            ps.setString(1, status.name());
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("resolvePartyInvite failed", ex);
        }
    }

    @Override
    public int expireStalePartyInvites() {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE eternal_party_invites SET status = 'EXPIRED' "
                             + "WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < ?")) {
            ps.setLong(1, System.currentTimeMillis());
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("expireStalePartyInvites failed", ex);
        }
    }

    private PartyInvite readPartyInvite(@NotNull ResultSet rs) throws SQLException {
        long expires = rs.getLong("expires_at");
        boolean expiresNull = rs.wasNull();
        return new PartyInvite(
                rs.getLong("id"),
                rs.getLong("party_id"),
                UUID.fromString(rs.getString("from_uuid")),
                rs.getString("from_name"),
                UUID.fromString(rs.getString("to_uuid")),
                rs.getString("to_name"),
                PartyInvite.Status.valueOf(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                expiresNull ? null : Instant.ofEpochMilli(expires)
        );
    }

    @Override
    public @NotNull PlayerPrefs playerPrefs(@NotNull UUID uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_player_prefs WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return PlayerPrefs.defaults(uuid);
                String display = rs.getString("autonick_default_display");
                if (rs.wasNull()) display = null;
                return new PlayerPrefs(
                        uuid,
                        rs.getInt("allow_friend_requests") != 0,
                        rs.getInt("allow_party_invites") != 0,
                        display
                );
            }
        } catch (SQLException ex) {
            throw new StorageException("playerPrefs failed", ex);
        }
    }

    @Override
    public void upsertPlayerPrefs(@NotNull PlayerPrefs prefs) {
        String sql = isSqlite() ? """
                INSERT INTO eternal_player_prefs (uuid, allow_friend_requests, allow_party_invites, autonick_default_display)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  allow_friend_requests=excluded.allow_friend_requests,
                  allow_party_invites=excluded.allow_party_invites,
                  autonick_default_display=excluded.autonick_default_display
                """ : """
                INSERT INTO eternal_player_prefs (uuid, allow_friend_requests, allow_party_invites, autonick_default_display)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  allow_friend_requests=VALUES(allow_friend_requests),
                  allow_party_invites=VALUES(allow_party_invites),
                  autonick_default_display=VALUES(autonick_default_display)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefs.uuid().toString());
            ps.setInt(2, prefs.allowFriendRequests() ? 1 : 0);
            ps.setInt(3, prefs.allowPartyInvites() ? 1 : 0);
            if (prefs.autonickDefaultDisplay() == null) ps.setNull(4, java.sql.Types.VARCHAR);
            else ps.setString(4, prefs.autonickDefaultDisplay());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("upsertPlayerPrefs failed", ex);
        }
    }

    @Override
    public void startNickSession(@NotNull NickSession session) {
        String sql = isSqlite() ? """
                INSERT INTO eternal_nick_sessions (uuid, original_name, original_group, nick_name, nick_group, skin_value, skin_signature, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  original_name=excluded.original_name, original_group=excluded.original_group,
                  nick_name=excluded.nick_name, nick_group=excluded.nick_group,
                  skin_value=excluded.skin_value, skin_signature=excluded.skin_signature,
                  started_at=excluded.started_at
                """ : """
                INSERT INTO eternal_nick_sessions (uuid, original_name, original_group, nick_name, nick_group, skin_value, skin_signature, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  original_name=VALUES(original_name), original_group=VALUES(original_group),
                  nick_name=VALUES(nick_name), nick_group=VALUES(nick_group),
                  skin_value=VALUES(skin_value), skin_signature=VALUES(skin_signature),
                  started_at=VALUES(started_at)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, session.uuid().toString());
            ps.setString(2, session.originalName());
            ps.setString(3, session.originalGroup());
            ps.setString(4, session.nickName());
            ps.setString(5, session.nickGroup());
            if (session.skinValue() == null) ps.setNull(6, java.sql.Types.VARCHAR);
            else ps.setString(6, session.skinValue());
            if (session.skinSignature() == null) ps.setNull(7, java.sql.Types.VARCHAR);
            else ps.setString(7, session.skinSignature());
            ps.setLong(8, session.startedAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("startNickSession failed", ex);
        }
    }

    @Override
    public @NotNull Optional<NickSession> findNickSession(@NotNull UUID uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_nick_sessions WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readNickSession(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("findNickSession failed", ex);
        }
    }

    @Override
    public boolean endNickSession(@NotNull UUID uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM eternal_nick_sessions WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("endNickSession failed", ex);
        }
    }

    @Override
    public @NotNull List<NickSession> activeNickSessions() {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eternal_nick_sessions");
             ResultSet rs = ps.executeQuery()) {
            List<NickSession> out = new ArrayList<>();
            while (rs.next()) out.add(readNickSession(rs));
            return out;
        } catch (SQLException ex) {
            throw new StorageException("activeNickSessions failed", ex);
        }
    }

    private NickSession readNickSession(@NotNull ResultSet rs) throws SQLException {
        String skinValue = rs.getString("skin_value");
        String skinSig = rs.getString("skin_signature");
        return new NickSession(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("original_name"),
                rs.getString("original_group"),
                rs.getString("nick_name"),
                rs.getString("nick_group"),
                skinValue,
                skinSig,
                Instant.ofEpochMilli(rs.getLong("started_at"))
        );
    }

    /* ====================================================================
     * BaseStorage — base-system homes / warps / spawn. Scoped by server so a
     * shared network DB keeps each backend separate. Same conventions as
     * above (dialect-aware DDL + upserts). Coords stored as DOUBLE.
     * ==================================================================== */

    private void createBaseTables(@NotNull Connection c) throws SQLException {
        String idStr = isSqlite() ? "TEXT" : "VARCHAR(36)";
        String shortStr = isSqlite() ? "TEXT" : "VARCHAR(64)";
        try (Statement st = c.createStatement()) {
            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_base_homes (
                      owner_uuid %1$s NOT NULL,
                      server %2$s NOT NULL,
                      name %2$s NOT NULL,
                      world %2$s NOT NULL,
                      x DOUBLE NOT NULL,
                      y DOUBLE NOT NULL,
                      z DOUBLE NOT NULL,
                      yaw DOUBLE NOT NULL,
                      pitch DOUBLE NOT NULL,
                      created_at BIGINT NOT NULL,
                      PRIMARY KEY (owner_uuid, server, name)
                    )""").formatted(idStr, shortStr));
            st.execute("CREATE INDEX IF NOT EXISTS idx_base_homes_owner ON eternal_base_homes(owner_uuid, server)");

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_base_warps (
                      server %1$s NOT NULL,
                      name %1$s NOT NULL,
                      world %1$s NOT NULL,
                      x DOUBLE NOT NULL,
                      y DOUBLE NOT NULL,
                      z DOUBLE NOT NULL,
                      yaw DOUBLE NOT NULL,
                      pitch DOUBLE NOT NULL,
                      created_at BIGINT NOT NULL,
                      PRIMARY KEY (server, name)
                    )""").formatted(shortStr));

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_base_spawn (
                      server %1$s PRIMARY KEY NOT NULL,
                      world %1$s NOT NULL,
                      x DOUBLE NOT NULL,
                      y DOUBLE NOT NULL,
                      z DOUBLE NOT NULL,
                      yaw DOUBLE NOT NULL,
                      pitch DOUBLE NOT NULL
                    )""").formatted(shortStr));
        }
    }

    @Override
    public void setHome(@NotNull UUID owner, @NotNull String server, @NotNull String name, @NotNull Loc loc) {
        String sql = isSqlite() ? """
                INSERT INTO eternal_base_homes (owner_uuid, server, name, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_uuid, server, name) DO UPDATE SET
                  world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                  yaw=excluded.yaw, pitch=excluded.pitch
                """ : """
                INSERT INTO eternal_base_homes (owner_uuid, server, name, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z),
                  yaw=VALUES(yaw), pitch=VALUES(pitch)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setString(2, server);
            ps.setString(3, name);
            bindLoc(ps, 4, loc);
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("setHome failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Loc> getHome(@NotNull UUID owner, @NotNull String server, @NotNull String name) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT world, x, y, z, yaw, pitch FROM eternal_base_homes "
                             + "WHERE owner_uuid = ? AND server = ? AND LOWER(name) = LOWER(?)")) {
            ps.setString(1, owner.toString());
            ps.setString(2, server);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readLoc(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("getHome failed", ex);
        }
    }

    @Override
    public boolean deleteHome(@NotNull UUID owner, @NotNull String server, @NotNull String name) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM eternal_base_homes WHERE owner_uuid = ? AND server = ? AND LOWER(name) = LOWER(?)")) {
            ps.setString(1, owner.toString());
            ps.setString(2, server);
            ps.setString(3, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("deleteHome failed", ex);
        }
    }

    @Override
    public @NotNull List<String> listHomes(@NotNull UUID owner, @NotNull String server) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM eternal_base_homes WHERE owner_uuid = ? AND server = ? ORDER BY name ASC")) {
            ps.setString(1, owner.toString());
            ps.setString(2, server);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString("name"));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("listHomes failed", ex);
        }
    }

    @Override
    public int homeCount(@NotNull UUID owner, @NotNull String server) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM eternal_base_homes WHERE owner_uuid = ? AND server = ?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, server);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new StorageException("homeCount failed", ex);
        }
    }

    @Override
    public void setWarp(@NotNull String server, @NotNull String name, @NotNull Loc loc) {
        String sql = isSqlite() ? """
                INSERT INTO eternal_base_warps (server, name, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(server, name) DO UPDATE SET
                  world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                  yaw=excluded.yaw, pitch=excluded.pitch
                """ : """
                INSERT INTO eternal_base_warps (server, name, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z),
                  yaw=VALUES(yaw), pitch=VALUES(pitch)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, server);
            ps.setString(2, name);
            bindLoc(ps, 3, loc);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("setWarp failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Loc> getWarp(@NotNull String server, @NotNull String name) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT world, x, y, z, yaw, pitch FROM eternal_base_warps "
                             + "WHERE server = ? AND LOWER(name) = LOWER(?)")) {
            ps.setString(1, server);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readLoc(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("getWarp failed", ex);
        }
    }

    @Override
    public boolean deleteWarp(@NotNull String server, @NotNull String name) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM eternal_base_warps WHERE server = ? AND LOWER(name) = LOWER(?)")) {
            ps.setString(1, server);
            ps.setString(2, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("deleteWarp failed", ex);
        }
    }

    @Override
    public @NotNull List<String> listWarps(@NotNull String server) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM eternal_base_warps WHERE server = ? ORDER BY name ASC")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString("name"));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("listWarps failed", ex);
        }
    }

    @Override
    public void setSpawn(@NotNull String server, @NotNull Loc loc) {
        String sql = isSqlite() ? """
                INSERT INTO eternal_base_spawn (server, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(server) DO UPDATE SET
                  world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                  yaw=excluded.yaw, pitch=excluded.pitch
                """ : """
                INSERT INTO eternal_base_spawn (server, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z),
                  yaw=VALUES(yaw), pitch=VALUES(pitch)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, server);
            bindLoc(ps, 2, loc);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("setSpawn failed", ex);
        }
    }

    @Override
    public @NotNull Optional<Loc> getSpawn(@NotNull String server) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT world, x, y, z, yaw, pitch FROM eternal_base_spawn WHERE server = ?")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readLoc(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new StorageException("getSpawn failed", ex);
        }
    }

    /** Binds world,x,y,z,yaw,pitch starting at {@code i} (6 params). */
    private static void bindLoc(@NotNull PreparedStatement ps, int i, @NotNull Loc loc) throws SQLException {
        ps.setString(i, loc.world());
        ps.setDouble(i + 1, loc.x());
        ps.setDouble(i + 2, loc.y());
        ps.setDouble(i + 3, loc.z());
        ps.setDouble(i + 4, loc.yaw());
        ps.setDouble(i + 5, loc.pitch());
    }

    private static Loc readLoc(@NotNull ResultSet rs) throws SQLException {
        return new Loc(
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                (float) rs.getDouble("yaw"),
                (float) rs.getDouble("pitch")
        );
    }

    /* ====================================================================
     * ChatLogStorage — chat / command / msg logs + social-spy registry.
     * MySQL gets FULLTEXT search, SQLite falls back to LIKE. created_at is
     * epoch millis (Instant.toEpochMilli / Instant.ofEpochMilli). Batched
     * inserts run in a local transaction for throughput.
     * ==================================================================== */

    private void createChatLogTables(@NotNull Connection c) throws SQLException {
        String idStr = isSqlite() ? "TEXT" : "VARCHAR(36)";
        String shortStr = isSqlite() ? "TEXT" : "VARCHAR(64)";
        String t = longTextType();
        try (Statement st = c.createStatement()) {
            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_chat_log (
                      id %1$s,
                      kind %2$s NOT NULL,
                      server %2$s NOT NULL,
                      sender_uuid %3$s NOT NULL,
                      sender_name %2$s NOT NULL,
                      target_uuid %3$s,
                      target_name %2$s,
                      content %4$s NOT NULL,
                      created_at BIGINT NOT NULL
                    )""").formatted(pk(), shortStr, idStr, t));
            st.execute("CREATE INDEX IF NOT EXISTS idx_chatlog_server_time ON eternal_chat_log(server, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chatlog_sender_time ON eternal_chat_log(sender_uuid, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chatlog_time ON eternal_chat_log(created_at)");

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_sensitive_log (
                      id %1$s,
                      kind %2$s NOT NULL,
                      server %2$s NOT NULL,
                      sender_uuid %3$s NOT NULL,
                      sender_name %2$s NOT NULL,
                      target_uuid %3$s,
                      target_name %2$s,
                      content %4$s NOT NULL,
                      created_at BIGINT NOT NULL
                    )""").formatted(pk(), shortStr, idStr, t));
            st.execute("CREATE INDEX IF NOT EXISTS idx_senslog_server_time ON eternal_sensitive_log(server, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_senslog_time ON eternal_sensitive_log(created_at)");

            st.execute(("""
                    CREATE TABLE IF NOT EXISTS eternal_socialspy (
                      uuid %1$s PRIMARY KEY NOT NULL
                    )""").formatted(idStr));
        }

        // FULLTEXT search is MySQL-only; add it idempotently (separate
        // try/catch via runIdempotent) so re-runs and SQLite are no-ops.
        if (!isSqlite()) {
            runIdempotent(c, "ALTER TABLE eternal_chat_log ADD FULLTEXT idx_chatlog_fts (sender_name, target_name, content)");
            runIdempotent(c, "ALTER TABLE eternal_sensitive_log ADD FULLTEXT idx_senslog_fts (sender_name, content)");
        }
    }

    @Override
    public void appendChatLogs(@NotNull List<ChatLogEntry> batch) {
        batchInsertLogs("eternal_chat_log", batch);
    }

    @Override
    public void appendSensitiveLogs(@NotNull List<ChatLogEntry> batch) {
        batchInsertLogs("eternal_sensitive_log", batch);
    }

    /** Shared batched insert for both log tables, in a local transaction. */
    private void batchInsertLogs(@NotNull String table, @NotNull List<ChatLogEntry> batch) {
        if (batch.isEmpty()) return;
        String sql = "INSERT INTO " + table
                + " (kind, server, sender_uuid, sender_name, target_uuid, target_name, content, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        Connection c = null;
        boolean prevAuto = true;
        try {
            c = conn();
            prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (ChatLogEntry e : batch) {
                    ps.setString(1, e.kind().name());
                    ps.setString(2, e.server());
                    ps.setString(3, e.senderUuid());
                    ps.setString(4, e.senderName());
                    ps.setString(5, e.targetUuid());
                    ps.setString(6, e.targetName());
                    ps.setString(7, e.content());
                    ps.setLong(8, e.createdAt().toEpochMilli());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new StorageException("appendChatLogs(" + table + ") failed", ex);
        } finally {
            if (c != null) {
                try { c.setAutoCommit(prevAuto); } catch (SQLException ignored) { /* closing anyway */ }
                try { c.close(); } catch (SQLException ignored) { /* pool return */ }
            }
        }
    }

    @Override
    public @NotNull List<ChatLogEntry> findChatLogs(@Nullable String server, @Nullable String query,
                                                    @Nullable Set<ChatLogKind> kinds, long fromMs, long toMs,
                                                    int limit, int offset) {
        return queryLogs("eternal_chat_log", server, query, kinds, fromMs, toMs, limit, offset);
    }

    @Override
    public long countChatLogs(@Nullable String server, @Nullable String query,
                              @Nullable Set<ChatLogKind> kinds, long fromMs, long toMs) {
        return countLogs("eternal_chat_log", server, query, kinds, fromMs, toMs);
    }

    @Override
    public @NotNull List<ChatLogEntry> findSensitiveLogs(@Nullable String server, @Nullable String query,
                                                         long fromMs, long toMs, int limit, int offset) {
        return queryLogs("eternal_sensitive_log", server, query, null, fromMs, toMs, limit, offset);
    }

    @Override
    public long countSensitiveLogs(@Nullable String server, @Nullable String query, long fromMs, long toMs) {
        return countLogs("eternal_sensitive_log", server, query, null, fromMs, toMs);
    }

    /** Builds the shared dynamic WHERE clause and binds its params. The
     *  sensitive table has no target_name; MATCH/LIKE adapt accordingly. */
    private void appendLogFilters(@NotNull StringBuilder sb, @NotNull List<Object> params, boolean sensitive,
                                  @Nullable String server, @Nullable String query,
                                  @Nullable Set<ChatLogKind> kinds, long fromMs, long toMs) {
        List<String> clauses = new ArrayList<>();
        if (server != null && !server.isBlank()) {
            clauses.add("server = ?");
            params.add(server);
        }
        if (kinds != null && !kinds.isEmpty()) {
            StringBuilder in = new StringBuilder("kind IN (");
            int i = 0;
            for (ChatLogKind k : kinds) {
                in.append(i++ == 0 ? "?" : ",?");
                params.add(k.name());
            }
            in.append(")");
            clauses.add(in.toString());
        }
        if (fromMs > 0) {
            clauses.add("created_at >= ?");
            params.add(fromMs);
        }
        if (toMs > 0) {
            clauses.add("created_at <= ?");
            params.add(toMs);
        }
        if (query != null && !query.isBlank()) {
            if (isSqlite()) {
                String like = "%" + query.toLowerCase() + "%";
                if (sensitive) {
                    clauses.add("(LOWER(sender_name) LIKE ? OR LOWER(content) LIKE ?)");
                    params.add(like);
                    params.add(like);
                } else {
                    clauses.add("(LOWER(sender_name) LIKE ? OR LOWER(target_name) LIKE ? OR LOWER(content) LIKE ?)");
                    params.add(like);
                    params.add(like);
                    params.add(like);
                }
            } else {
                // MySQL FULLTEXT, boolean mode with a trailing '*' for prefix matching.
                String against = query.trim() + "*";
                if (sensitive) {
                    clauses.add("MATCH(sender_name, content) AGAINST (? IN BOOLEAN MODE)");
                } else {
                    clauses.add("MATCH(sender_name, target_name, content) AGAINST (? IN BOOLEAN MODE)");
                }
                params.add(against);
            }
        }
        if (!clauses.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", clauses));
        }
    }

    private @NotNull List<ChatLogEntry> queryLogs(@NotNull String table, @Nullable String server,
                                                  @Nullable String query, @Nullable Set<ChatLogKind> kinds,
                                                  long fromMs, long toMs, int limit, int offset) {
        boolean sensitive = "eternal_sensitive_log".equals(table);
        int safeLimit = Math.max(1, Math.min(500, limit));
        int safeOffset = Math.max(0, offset);
        StringBuilder sb = new StringBuilder("SELECT * FROM ").append(table);
        List<Object> params = new ArrayList<>();
        appendLogFilters(sb, params, sensitive, server, query, kinds, fromMs, toMs);
        sb.append(" ORDER BY created_at DESC LIMIT ").append(safeLimit).append(" OFFSET ").append(safeOffset);
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChatLogEntry> out = new ArrayList<>();
                while (rs.next()) out.add(readChatLog(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("findChatLogs(" + table + ") failed", ex);
        }
    }

    private long countLogs(@NotNull String table, @Nullable String server, @Nullable String query,
                           @Nullable Set<ChatLogKind> kinds, long fromMs, long toMs) {
        boolean sensitive = "eternal_sensitive_log".equals(table);
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ").append(table);
        List<Object> params = new ArrayList<>();
        appendLogFilters(sb, params, sensitive, server, query, kinds, fromMs, toMs);
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new StorageException("countChatLogs(" + table + ") failed", ex);
        }
    }

    @Override
    public @NotNull List<ChatLogEntry> findChatAround(@Nullable String server, long anchorMs, int before, int after, long windowMs) {
        int safeBefore = Math.max(0, Math.min(500, before));
        int safeAfter = Math.max(0, Math.min(500, after));
        boolean hasServer = server != null && !server.isBlank();
        boolean bounded = windowMs > 0;
        List<ChatLogEntry> result = new ArrayList<>();
        try (Connection c = conn()) {
            // `before` rows: created_at < anchor (and >= anchor - window so an
            // anchor in a chat-less gap can't reach back to far-older messages),
            // newest first, then re-sorted ASC below.
            if (safeBefore > 0) {
                String sql = "SELECT * FROM eternal_chat_log WHERE created_at < ?"
                        + (bounded ? " AND created_at >= ?" : "")
                        + (hasServer ? " AND server = ?" : "")
                        + " ORDER BY created_at DESC LIMIT " + safeBefore;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int idx = 1;
                    ps.setLong(idx++, anchorMs);
                    if (bounded) ps.setLong(idx++, anchorMs - windowMs);
                    if (hasServer) ps.setString(idx, server);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<ChatLogEntry> beforeRows = new ArrayList<>();
                        while (rs.next()) beforeRows.add(readChatLog(rs));
                        // came back DESC; flip to chronological ASC.
                        for (int i = beforeRows.size() - 1; i >= 0; i--) result.add(beforeRows.get(i));
                    }
                }
            }
            // `after` rows: created_at >= anchor (and <= anchor + window so a
            // chat-less gap after the anchor can't pull in messages from days
            // later), ASC.
            if (safeAfter > 0) {
                String sql = "SELECT * FROM eternal_chat_log WHERE created_at >= ?"
                        + (bounded ? " AND created_at <= ?" : "")
                        + (hasServer ? " AND server = ?" : "")
                        + " ORDER BY created_at ASC LIMIT " + safeAfter;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int idx = 1;
                    ps.setLong(idx++, anchorMs);
                    if (bounded) ps.setLong(idx++, anchorMs + windowMs);
                    if (hasServer) ps.setString(idx, server);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) result.add(readChatLog(rs));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new StorageException("findChatAround failed", ex);
        }
        return result;
    }

    @Override
    public @NotNull List<ChatLogEntry> playerMessages(@NotNull String uuid, long fromMs, long toMs, int limit) {
        int safeLimit = Math.max(1, Math.min(5000, limit));
        StringBuilder sb = new StringBuilder(
                "SELECT * FROM eternal_chat_log WHERE kind IN ('CHAT','MSG') AND sender_uuid = ?");
        List<Object> params = new ArrayList<>();
        params.add(uuid);
        if (fromMs > 0) {
            sb.append(" AND created_at >= ?");
            params.add(fromMs);
        }
        if (toMs > 0) {
            sb.append(" AND created_at <= ?");
            params.add(toMs);
        }
        sb.append(" ORDER BY created_at ASC LIMIT ").append(safeLimit);
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChatLogEntry> out = new ArrayList<>();
                while (rs.next()) out.add(readChatLog(rs));
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("playerMessages failed", ex);
        }
    }

    @Override
    public @NotNull List<String> chatLogServers() {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT DISTINCT server FROM eternal_chat_log ORDER BY server ASC");
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException ex) {
            throw new StorageException("chatLogServers failed", ex);
        }
    }

    @Override
    public void setSocialSpy(@NotNull String uuid, boolean enabled) {
        if (enabled) {
            String sql = isSqlite()
                    ? "INSERT OR IGNORE INTO eternal_socialspy (uuid) VALUES (?)"
                    : "INSERT IGNORE INTO eternal_socialspy (uuid) VALUES (?)";
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new StorageException("setSocialSpy(enable) failed", ex);
            }
        } else {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM eternal_socialspy WHERE uuid = ?")) {
                ps.setString(1, uuid);
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new StorageException("setSocialSpy(disable) failed", ex);
            }
        }
    }

    @Override
    public boolean isSocialSpy(@NotNull String uuid) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM eternal_socialspy WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new StorageException("isSocialSpy failed", ex);
        }
    }

    @Override
    public @NotNull Set<String> socialSpyUuids() {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM eternal_socialspy");
             ResultSet rs = ps.executeQuery()) {
            Set<String> out = new LinkedHashSet<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException ex) {
            throw new StorageException("socialSpyUuids failed", ex);
        }
    }

    /** Binds an ordered param list: String, Long/Integer -> setLong, else setString. */
    private static void bindParams(@NotNull PreparedStatement ps, @NotNull List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) {
            if (p instanceof Long l) ps.setLong(i, l);
            else if (p instanceof Integer n) ps.setLong(i, n);
            else ps.setString(i, p == null ? null : p.toString());
            i++;
        }
    }

    private static ChatLogEntry readChatLog(@NotNull ResultSet rs) throws SQLException {
        return new ChatLogEntry(
                rs.getLong("id"),
                ChatLogKind.valueOf(rs.getString("kind")),
                rs.getString("server"),
                rs.getString("sender_uuid"),
                rs.getString("sender_name"),
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("content"),
                Instant.ofEpochMilli(rs.getLong("created_at"))
        );
    }
}

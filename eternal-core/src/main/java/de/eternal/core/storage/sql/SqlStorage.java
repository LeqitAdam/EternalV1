package de.eternal.core.storage.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.eternal.core.config.DatabaseConfig;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.model.ActionEntry;
import de.eternal.core.model.LinkCode;
import de.eternal.core.model.LoginSession;
import de.eternal.core.model.ReportEntry;
import de.eternal.core.model.ReportStatus;
import de.eternal.core.model.Session;
import de.eternal.core.model.StaffStat;
import de.eternal.core.model.UnbanAppeal;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Single SQL backend that handles both SQLite and MySQL via dialect switches.
 * Schema is identical except for AUTOINCREMENT vs AUTO_INCREMENT.
 */
public final class SqlStorage implements EternalStorage {

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
        try {
            modAt = rs.getLong("modified_at");
            modAtNull = rs.wasNull();
            modByUuid = rs.getString("modified_by_uuid");
            modByName = rs.getString("modified_by_name");
        } catch (SQLException ignored) { /* column not yet present */ }

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
                modByName
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
        String sql = """
                UPDATE eternal_punishments
                SET expires_at = ?, modified_at = ?, modified_by_uuid = ?, modified_by_name = ?
                WHERE id = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (newExpires == null) ps.setNull(1, java.sql.Types.BIGINT);
            else ps.setLong(1, newExpires.toEpochMilli());
            ps.setLong(2, System.currentTimeMillis());
            if (modifierUuid == null) ps.setNull(3, java.sql.Types.VARCHAR);
            else ps.setString(3, modifierUuid.toString());
            ps.setString(4, modifierName);
            ps.setLong(5, id);
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
                rs.getString("resolution")
        );
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
            ps.setString(4, session.role());
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
                        rs.getString("role"),
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
        String sql = """
                UPDATE eternal_unban_appeals
                SET status = ?, reviewer_uuid = ?, reviewer_name = ?, reviewed_at = ?, decision_reason = ?
                WHERE id = ? AND status = 'PENDING'
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, decision.name());
            ps.setString(2, reviewerUuid.toString());
            ps.setString(3, reviewerName);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, decisionReason);
            ps.setLong(6, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new StorageException("decideAppeal failed", ex);
        }
    }

    private UnbanAppeal readAppeal(ResultSet rs) throws SQLException {
        String revUuid = rs.getString("reviewer_uuid");
        long revAt = rs.getLong("reviewed_at");
        boolean revNull = rs.wasNull();
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
                rs.getString("decision_reason")
        );
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

    // Suppress unused warning; kept for future binary-typed columns.
    @SuppressWarnings("unused")
    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}

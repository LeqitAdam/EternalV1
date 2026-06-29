package de.eternal.core.storage;

import de.eternal.core.config.DatabaseConfig;
import de.eternal.core.model.NickSession;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.social.SocialStorage;
import de.eternal.core.storage.sql.SqlStorage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the embedded H2 (MySQL-compat mode) accepts the same SQL the network
 * MySQL path emits: full schema creation + the ON-DUPLICATE upserts + the
 * name-based punishment lookup. Replaces the old SQLite embedded driver.
 */
class H2StorageSmokeTest {

    @Test
    void schemaAndUpsertsWorkOnH2() throws Exception {
        Path dir = Files.createTempDirectory("eternal-h2-test");
        DatabaseConfig cfg = DatabaseConfig.fromMap(Map.of("type", "h2", "file", "smoke"), dir);
        assertEquals(DatabaseConfig.Type.H2, cfg.type());

        SqlStorage storage = new SqlStorage(cfg);
        storage.init(); // creates EVERY table — exercises pk()/textType()/indexes

        UUID u = UUID.randomUUID();

        // recordProfile twice → exercises ON DUPLICATE KEY UPDATE.
        storage.recordProfile(u, "Tester", "127.0.0.1", 50, "admin", "Tester");
        storage.recordProfile(u, "Tester", "127.0.0.1", 60, "owner", "Tester");
        assertTrue(storage.findProfileByName("tester").isPresent(), "case-insensitive name lookup");

        // queueAction → AUTO_INCREMENT generated key.
        long actionId = storage.queueAction("TEST", u, "{}");
        assertTrue(actionId > 0, "generated key");

        // a punishment + the new name-based fallback lookup.
        long banId = storage.insertPunishment(new PunishmentEntry(
                0, PunishmentType.BAN, u, "Tester", null, "Console", "web", "Test",
                "kick", Instant.now(), null, true, null, null, null, null, null, null, null, null, null));
        assertTrue(banId > 0);
        assertTrue(storage.findRecentPunishmentByName("TESTER").isPresent(), "name fallback resolves");
        assertTrue(storage.findActivePunishment(u, PunishmentType.BAN).isPresent());

        // nick session upsert.
        SocialStorage social = (SocialStorage) storage;
        social.startNickSession(new NickSession(u, "Tester", "admin", "FakeName", "player",
                null, null, Instant.now()));
        assertTrue(social.findNickSession(u).isPresent());

        storage.close();
    }
}

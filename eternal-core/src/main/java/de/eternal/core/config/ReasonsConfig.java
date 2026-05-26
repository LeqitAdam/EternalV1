package de.eternal.core.config;

import de.eternal.core.model.PunishmentReason;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.time.DurationParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the combined punishment reason list plus the open list of report
 * reasons.
 *
 * Both {@code /ban} and {@code /mute} look up reasons here by numeric id —
 * the reason's own {@link PunishmentType} decides whether it ends up as a ban
 * or a mute in the history, regardless of which command was used.
 */
public final class ReasonsConfig {

    private final Map<Integer, PunishmentReason> byId;
    private final List<PunishmentReason> ordered;
    private final List<ReportReason> reportReasons;
    private final List<AppealShortenTemplate> appealShortenTemplates;

    private ReasonsConfig(@NotNull List<PunishmentReason> reasons, @NotNull List<ReportReason> reports,
                           @NotNull List<AppealShortenTemplate> shortenTemplates) {
        this.ordered = List.copyOf(reasons);
        Map<Integer, PunishmentReason> map = new LinkedHashMap<>();
        for (PunishmentReason r : reasons) map.put(r.id(), r);
        this.byId = Collections.unmodifiableMap(map);
        this.reportReasons = List.copyOf(reports);
        this.appealShortenTemplates = List.copyOf(shortenTemplates);
    }

    public @NotNull List<AppealShortenTemplate> appealShortenTemplates() {
        return appealShortenTemplates;
    }

    public @NotNull List<PunishmentReason> all() {
        return ordered;
    }

    public @Nullable PunishmentReason byId(int id) {
        return byId.get(id);
    }

    public @NotNull List<ReportReason> reportReasons() {
        return reportReasons;
    }

    public @Nullable ReportReason reportByIndex(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > reportReasons.size()) return null;
        return reportReasons.get(oneBasedIndex - 1);
    }

    public static @NotNull ReasonsConfig fromMap(@NotNull Map<String, Object> raw) {
        List<PunishmentReason> reasons = new ArrayList<>();
        for (Map<String, Object> r : Configs.sectionListOr(raw, "reasons")) {
            int id = Configs.intOr(r, "id", 0);
            if (id <= 0) continue;
            String label = Configs.stringOr(r, "label", "Grund " + id);
            String typeRaw = Configs.stringOr(r, "type", "ban").toLowerCase(Locale.ROOT);
            PunishmentType type = typeRaw.startsWith("m") ? PunishmentType.MUTE : PunishmentType.BAN;
            long durationSec = DurationParser.parseToSeconds(Configs.stringOr(r, "duration", "permanent"));
            String perm = Configs.stringOrNull(r, "permission");
            int groupId = Configs.intOr(r, "groupid", 0);
            boolean adminOnly = Boolean.parseBoolean(Configs.stringOr(r, "admin", "false"));
            // Optional escalation ladder: list of durations applied in order on
            // successive offenses. When fewer entries are configured than the
            // current offense count, the last entry repeats.
            java.util.List<Long> escalation = new java.util.ArrayList<>();
            Object esc = r.get("escalation");
            if (esc instanceof java.util.List<?> list) {
                for (Object o : list) {
                    escalation.add(DurationParser.parseToSeconds(String.valueOf(o)));
                }
            }
            reasons.add(new PunishmentReason(id, label, type, durationSec, perm, groupId, adminOnly, escalation));
        }

        List<ReportReason> reports = new ArrayList<>();
        for (Map<String, Object> r : Configs.sectionListOr(raw, "report-reasons")) {
            reports.add(new ReportReason(
                    Configs.stringOr(r, "id", "unknown"),
                    Configs.stringOr(r, "label", "Unbekannt"),
                    // material: Bukkit Material name. Lazy-validated at GUI
                    // build time (Material.valueOf) — invalid names fall back
                    // to PAPER with a console warning so a typo in reasons.yml
                    // doesn't bring down the whole report flow.
                    Configs.stringOr(r, "material", "PAPER"),
                    // slot: -1 = auto-place (insertion order). When set
                    // explicitly the GUI places this reason at that slot;
                    // grid size grows to fit the largest slot.
                    Configs.intOr(r, "slot", -1)
            ));
        }

        List<AppealShortenTemplate> shortenTemplates = new ArrayList<>();
        for (Map<String, Object> r : Configs.sectionListOr(raw, "appeal-shorten-templates")) {
            shortenTemplates.add(new AppealShortenTemplate(
                    Configs.stringOr(r, "id", "tpl-" + (shortenTemplates.size() + 1)),
                    Configs.stringOr(r, "label", "Vorlage"),
                    Configs.stringOr(r, "duration", "0s"),
                    Configs.stringOr(r, "message", "")
            ));
        }

        return new ReasonsConfig(reasons, reports, shortenTemplates);
    }

    /** A reason a player can pick from the {@code /report <player>} GUI.
     *  {@code material} is the Bukkit Material name (e.g. {@code IRON_SWORD})
     *  used as the slot icon; {@code slot} is the explicit inventory index
     *  ({@code -1} = auto-place in insertion order). */
    public record ReportReason(@NotNull String id, @NotNull String label,
                                @NotNull String material, int slot) {
    }

    /**
     * Vorgefertigte Vorlage für Verkürzungen via Unban-Antrag. {@code duration}
     * ist eine DurationParser-kompatible Zeitangabe ({@code 1d}, {@code 6h},
     * {@code permanent} etc.), {@code message} wird im Dialog vorausgefüllt und
     * landet 1:1 als Decision-Message am Antrag.
     */
    public record AppealShortenTemplate(
            @NotNull String id,
            @NotNull String label,
            @NotNull String duration,
            @NotNull String message
    ) {
    }
}

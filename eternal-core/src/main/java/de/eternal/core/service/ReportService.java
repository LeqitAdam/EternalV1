package de.eternal.core.service;

import de.eternal.core.model.ReportEntry;
import de.eternal.core.model.ReportStatus;
import de.eternal.core.storage.EternalStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ReportService {

    private final EternalStorage storage;

    public ReportService(@NotNull EternalStorage storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    public @NotNull ReportEntry create(
            @NotNull UUID reporterUuid,
            @NotNull String reporterName,
            @NotNull UUID targetUuid,
            @NotNull String targetName,
            @NotNull String reasonId,
            @NotNull String reasonLabel,
            @Nullable String comment,
            @NotNull String serverName
    ) {
        ReportEntry draft = new ReportEntry(
                -1L, reporterUuid, reporterName, targetUuid, targetName,
                reasonId, reasonLabel, comment, serverName,
                Instant.now(), ReportStatus.OPEN,
                null, null, null, null, null,
                null, /* replayId — set later by ReplayBridge.endCaptureForReport */
                null  /* chatHistory — set later by linkReportChatHistory once the window closes */
        );
        long id = storage.insertReport(draft);
        return new ReportEntry(
                id, draft.reporterUuid(), draft.reporterName(), draft.targetUuid(), draft.targetName(),
                draft.reasonId(), draft.reasonLabel(), draft.comment(), draft.serverName(),
                draft.createdAt(), draft.status(), null, null, null, null, null, null, null
        );
    }

    public @NotNull List<ReportEntry> open() {
        return storage.findOpenReports();
    }

    public @NotNull Optional<ReportEntry> find(long id) {
        return storage.findReport(id);
    }

    public boolean claim(long id, @NotNull UUID handlerUuid, @NotNull String handlerName) {
        return storage.claimReport(id, handlerUuid, handlerName);
    }

    public boolean takeOverReport(long id, @NotNull UUID handlerUuid, @NotNull String handlerName) {
        return storage.takeOverReport(id, handlerUuid, handlerName);
    }

    public boolean close(long id, @NotNull String resolution) {
        return storage.closeReport(id, resolution);
    }
}

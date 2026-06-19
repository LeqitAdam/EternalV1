package de.eternal.core.chatlog;

import de.eternal.core.model.ChatLogEntry;
import de.eternal.core.model.ChatLogKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Persistence for the chat-log + social-spy feature. Implemented by
 * {@link de.eternal.core.storage.sql.SqlStorage} (cast the shared storage to
 * this interface to use it).
 *
 * <p>Filter semantics shared by the read endpoints:</p>
 * <ul>
 *     <li>{@code server} null/blank =&gt; all servers.</li>
 *     <li>{@code query} null/blank =&gt; no text filter (MySQL FULLTEXT MATCH
 *         else SQLite LIKE on sender_name/target_name/content).</li>
 *     <li>{@code kinds} null/empty =&gt; all kinds.</li>
 *     <li>{@code fromMs} &lt;= 0 =&gt; no lower bound; {@code toMs} &lt;= 0 =&gt; no upper bound.</li>
 *     <li>{@code limit} clamped to [1,500]; {@code offset} &gt;= 0.</li>
 * </ul>
 * List endpoints return newest-first EXCEPT {@link #findChatAround} and
 * {@link #playerMessages}, which return chronological (ASC).
 */
public interface ChatLogStorage {

    /** Batched insert into {@code eternal_chat_log}. */
    void appendChatLogs(@NotNull List<ChatLogEntry> batch);

    /** Batched insert into {@code eternal_sensitive_log}. */
    void appendSensitiveLogs(@NotNull List<ChatLogEntry> batch);

    @NotNull List<ChatLogEntry> findChatLogs(@Nullable String server, @Nullable String query,
                                             @Nullable Set<ChatLogKind> kinds, long fromMs, long toMs,
                                             int limit, int offset);

    long countChatLogs(@Nullable String server, @Nullable String query,
                       @Nullable Set<ChatLogKind> kinds, long fromMs, long toMs);

    @NotNull List<ChatLogEntry> findSensitiveLogs(@Nullable String server, @Nullable String query,
                                                  long fromMs, long toMs, int limit, int offset);

    long countSensitiveLogs(@Nullable String server, @Nullable String query, long fromMs, long toMs);

    /**
     * Report context: {@code before} rows with created_at &lt; anchor (DESC then
     * re-sorted ASC) plus {@code after} rows with created_at &gt;= anchor ASC,
     * combined and returned chronological (ASC).
     *
     * <p>{@code windowMs} bounds how far either side may reach from the anchor:
     * before rows are limited to {@code created_at >= anchor - windowMs} and
     * after rows to {@code created_at <= anchor + windowMs}. Without this an old
     * report whose anchor sits in a chat-less gap would pull in the next/previous
     * messages that exist — possibly days later — instead of an empty window.
     * Pass {@code windowMs <= 0} to disable the time bound (count-only).</p>
     */
    @NotNull List<ChatLogEntry> findChatAround(@Nullable String server, long anchorMs, int before, int after, long windowMs);

    /** Kind in (CHAT, MSG) for the given sender, created_at ASC. For session clustering. */
    @NotNull List<ChatLogEntry> playerMessages(@NotNull String uuid, long fromMs, long toMs, int limit);

    /** Distinct server names present in {@code eternal_chat_log}. */
    @NotNull List<String> chatLogServers();

    void setSocialSpy(@NotNull String uuid, boolean enabled);

    boolean isSocialSpy(@NotNull String uuid);

    @NotNull Set<String> socialSpyUuids();
}

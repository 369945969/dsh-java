package com.deepseek.dsh.feedback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.session.Sessions;

/**
 * 消息反馈侧车服务 —— 对应原 Harness 的 {@code MessageFeedbackService}。
 *
 * <p>为已定稿的助手消息提供持久、生命周期绑定的反馈存储。每次请求必须匹配
 * 到达项的当前版本；匹配的无操作返回已存储项而不改变其版本。
 *
 * <p>原 TS 版通过 {@code storage-domain} 的 {@code KvTable} 持久化，并校验目标消息
 * 确为会话日志中一条已定稿的助手消息。本 Java 移植采用单 JSON 文件侧车
 * （{@code message-feedback.json}，按 sessionId 索引行），并以乐观并发
 * （UUID 版本令牌）保证单会话内串行读-比较-写。目标消息存在性校验因当前
 * 会话日志投影不携带消息 ID 而简化为会话存在性校验（详见各方法文档）。
 *
 * <p>设计模式：仓储（JSON 文件持久化）+ 乐观并发（CAS 版本令牌）+ 串行队列。
 */
public final class MessageFeedbackService implements Service {

    private static final Logger log = LoggerFactory.getLogger(MessageFeedbackService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<java.util.Map<String, MessageFeedbackRow>> ROW_MAP =
            new TypeReference<>() {};

    /** 默认备注 UTF-8 字节上限。 */
    public static final int DEFAULT_MAX_NOTE_BYTES = 8192;

    private final Path configFile;
    private final int maxNoteBytes;
    private final Sessions sessions;
    private final ConcurrentMap<SessionId, MessageFeedbackRow> rows = new ConcurrentHashMap<>();
    private final ConcurrentMap<SessionId, CompletableFuture<Void>> operationTails = new ConcurrentHashMap<>();
    private volatile boolean admissionOpen = true;

    /**
     * @param dataDir      持久化目录（可为 {@code null} 表示纯内存）
     * @param maxNoteBytes 备注的 UTF-8 字节上限（正整数）
     * @param sessions     可选的会话服务，用于校验会话存在性（可为 {@code null}）
     */
    public MessageFeedbackService(Path dataDir, int maxNoteBytes, Sessions sessions) {
        if (maxNoteBytes < 1) {
            throw new IllegalArgumentException("maxNoteBytes must be a positive integer, got: " + maxNoteBytes);
        }
        this.maxNoteBytes = maxNoteBytes;
        this.sessions = sessions;
        this.configFile = dataDir != null ? dataDir.resolve("message-feedback.json") : null;
        load();
    }

    /** 纯内存构造（无持久化、无会话校验）。 */
    public MessageFeedbackService() {
        this(null, DEFAULT_MAX_NOTE_BYTES, null);
    }

    /**
     * 读取属于当前会话生命周期的反馈。被复用的 SessionId 的过期行不可见。
     *
     * @param sessionId 要检查与列出的会话标识
     * @return 当前不可变反馈项（可能为空）
     */
    public List<MessageFeedbackItem> list(SessionId sessionId) {
        ensureSession(sessionId);
        MessageFeedbackRow row = rows.get(sessionId);
        return row == null ? List.of() : List.copyOf(row.items());
    }

    /**
     * 创建或替换一条助手消息的反馈。每次请求必须匹配到目标项的当前版本；
     * 匹配的无操作返回已存储项而不改变其版本。
     *
     * @param sessionId  目标会话
     * @param messageId  目标助手消息标识
     * @param rating     期望的判定
     * @param note       可选非空解释（可为 {@code null}）
     * @param ifVersion  观察到的项版本，或 {@code null} 表示要求该项不存在
     * @return 已提交的反馈项
     */
    public MessageFeedbackItem put(SessionId sessionId, String messageId, FeedbackRating rating,
                                   String note, String ifVersion) {
        String resolvedNote = resolveNote(note);
        return joinNow(enqueue(sessionId, () -> {
            ensureSession(sessionId);
            MessageFeedbackRow row = rows.getOrDefault(sessionId, MessageFeedbackRow.empty(0L, null));
            List<MessageFeedbackItem> items = row.mutableItems();
            int index = -1;
            MessageFeedbackItem existing = null;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).messageId().equals(messageId)) {
                    index = i;
                    existing = items.get(i);
                    break;
                }
            }
            String observedVersion = ifVersion;
            String currentVersion = existing == null ? null : existing.version();
            if (!java.util.Objects.equals(observedVersion, currentVersion)) {
                throw new FeedbackException(FeedbackException.Code.VERSION_CONFLICT,
                        "supplied version does not match the current item version", existing);
            }
            if (existing != null
                    && existing.rating() == rating
                    && java.util.Objects.equals(existing.note(), resolvedNote)) {
                return existing;
            }
            long now = System.currentTimeMillis();
            MessageFeedbackItem item = new MessageFeedbackItem(
                    messageId, rating,
                    resolvedNote,
                    UUID.randomUUID().toString(),
                    existing == null ? now : existing.createdAt(),
                    existing == null ? now : Math.max(now, existing.updatedAt()));
            if (index == -1) {
                items.add(item);
            } else {
                items.set(index, item);
            }
            rows.put(sessionId, new MessageFeedbackRow(row.createdAt(), row.cwd(), items));
            persist();
            return item;
        }));
    }

    /**
     * 删除一条反馈项。无论提供的版本如何，缺失即为成功；已存在项要求精确版本匹配。
     *
     * @param sessionId 目标会话
     * @param messageId 要使其反馈缺失的消息
     * @param ifVersion 观察到的项版本；当项已缺失时被忽略
     * @return 删除后该项是否已缺失（幂等后置条件恒为真）
     */
    public boolean delete(SessionId sessionId, String messageId, String ifVersion) {
        return joinNow(enqueue(sessionId, () -> {
            ensureSession(sessionId);
            MessageFeedbackRow row = rows.get(sessionId);
            if (row == null) {
                return true;
            }
            List<MessageFeedbackItem> items = row.mutableItems();
            MessageFeedbackItem existing = null;
            for (MessageFeedbackItem item : items) {
                if (item.messageId().equals(messageId)) {
                    existing = item;
                    break;
                }
            }
            if (existing == null) {
                return true;
            }
            if (!java.util.Objects.equals(ifVersion, existing.version())) {
                throw new FeedbackException(FeedbackException.Code.VERSION_CONFLICT,
                        "supplied version does not match the current item version", existing);
            }
            items.remove(existing);
            rows.put(sessionId, new MessageFeedbackRow(row.createdAt(), row.cwd(), items));
            persist();
            return true;
        }));
    }

    /** 释放：拒绝新变更并等待所有未决串行队列完成。 */
    public void dispose() {
        admissionOpen = false;
        CompletableFuture<?>[] pending = operationTails.values().toArray(new CompletableFuture<?>[0]);
        CompletableFuture.allOf(pending).join();
        operationTails.clear();
    }

    // ---- 内部 -------------------------------------------------------------

    /** 在串行队列上 join，并把 CompletionException 解包为原始业务异常。 */
    private <T> T joinNow(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FeedbackException fe) {
                throw fe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error er) {
                throw er;
            }
            throw e;
        }
    }

    private void ensureSession(SessionId sessionId) {
        if (sessions == null) {
            return;
        }
        if (sessions.get(sessionId).isEmpty()) {
            throw new FeedbackException(FeedbackException.Code.SESSION_NOT_FOUND,
                    "no persisted session header exists for id: " + sessionId.value());
        }
    }

    private String resolveNote(String note) {
        if (note == null) {
            return null;
        }
        if (note.trim().isEmpty()) {
            throw new FeedbackException(FeedbackException.Code.NOTE_BLANK,
                    "feedback note must contain a non-whitespace character");
        }
        int actualBytes = note.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (actualBytes > maxNoteBytes) {
            throw new FeedbackException(FeedbackException.Code.NOTE_TOO_LARGE,
                    "feedback note exceeds the configured UTF-8 byte limit: "
                            + actualBytes + " > " + maxNoteBytes);
        }
        return note;
    }

    private <T> CompletableFuture<T> enqueue(SessionId sessionId, java.util.function.Supplier<T> operation) {
        if (!admissionOpen) {
            return CompletableFuture.failedFuture(
                    new FeedbackException(FeedbackException.Code.SERVICE_DISPOSING,
                            "message-feedback: service is disposing"));
        }
        CompletableFuture<Void> previous =
                operationTails.getOrDefault(sessionId, CompletableFuture.completedFuture(null));
        CompletableFuture<T> result = previous.thenApply(v -> operation.get());
        CompletableFuture<Void> tail = result.thenRun(() -> {});
        operationTails.put(sessionId, tail);
        tail.whenComplete((v, err) -> {
            operationTails.remove(sessionId, tail);
        });
        return result;
    }

    private void load() {
        if (configFile == null || !Files.isReadable(configFile)) {
            return;
        }
        try {
            java.util.Map<String, MessageFeedbackRow> loaded = MAPPER.readValue(configFile.toFile(), ROW_MAP);
            loaded.forEach((raw, row) -> rows.put(SessionId.of(raw), row));
        } catch (Exception e) {
            log.warn("Failed to load message feedback: {}", e.toString());
        }
    }

    private synchronized void persist() {
        if (configFile == null) {
            return;
        }
        try {
            Files.createDirectories(configFile.getParent());
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), rows);
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to persist message feedback: {}", e.toString());
        }
    }
}

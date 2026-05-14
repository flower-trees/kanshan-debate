package com.zhihu.kanshan.debate.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zhihu.kanshan.debate.agent.DebateOrchestrator;
import com.zhihu.kanshan.debate.model.Answer;
import com.zhihu.kanshan.debate.model.DebateSession;
import com.zhihu.kanshan.debate.model.DebateTurn;
import com.zhihu.kanshan.debate.model.StanceGroup;
import com.zhihu.kanshan.debate.repository.DebateSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class DebateSessionService {

    private final ZhihuApiService zhihuApiService;
    private final ClusteringService clusteringService;
    private final DebateOrchestrator orchestrator;
    private final DebateSessionRepository sessionRepo;
    private final Gson gson = new Gson();

    @Value("${debate.cache-enabled:true}")
    private boolean cacheEnabled;

    // Active SSE emitters keyed by session ID
    private final ConcurrentHashMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // Stop signals keyed by session ID
    private final ConcurrentHashMap<String, AtomicBoolean> stopSignals = new ConcurrentHashMap<>();

    // ── Session lifecycle ─────────────────────────────────────────────────────

    public DebateSession createSession(String topic) {
        String hash = topicHash(topic);

        if (cacheEnabled) {
            var cached = sessionRepo.findByTopicHashAndStatus(hash, DebateSession.Status.COMPLETED);
            if (cached.isPresent()) {
                log.info("Cache hit for topic: {}", topic);
                return cached.get();
            }
        }

        DebateSession session = new DebateSession();
        session.setId(UUID.randomUUID().toString());
        session.setTopicHash(hash);
        session.setTopic(topic);
        session.setStatus(DebateSession.Status.PENDING);
        return sessionRepo.save(session);
    }

    @Async
    public void runAsync(String sessionId) {
        DebateSession session = sessionRepo.findById(sessionId).orElseThrow();
        AtomicBoolean stopped = new AtomicBoolean(false);
        stopSignals.put(sessionId, stopped);
        long startMs = System.currentTimeMillis();
        log.info("[{}] Debate pipeline start: topic='{}'", sessionId, session.getTopic());

        try {
            // Phase 1: Clustering
            updateStatus(session, DebateSession.Status.CLUSTERING);
            emitEvent(sessionId, "status", Map.of("status", "CLUSTERING", "message", "正在分析立场..."));

            List<Answer> answers = zhihuApiService.searchAnswers(session.getTopic(), 15);
            log.info("[{}] Search retrieved {} answers", sessionId, answers.size());

            if (answers.isEmpty()) {
                log.warn("[{}] No answers found — likely API rate limit exceeded", sessionId);
                String errMsg1 = "知乎搜索 API 今日调用次数已达上限，暂时无法获取内容，请明天再试";
                session.setSummary(errMsg1);
                session.setStatus(DebateSession.Status.ERROR);
                sessionRepo.save(session);
                emitEvent(sessionId, "error", Map.of("message", errMsg1));
                emitEvent(sessionId, "status", Map.of("status", "ERROR"));
                return;
            }

            List<StanceGroup> stances = clusteringService.cluster(session.getTopic(), answers);
            log.info("[{}] Clustering done: {} stances — {}",
                sessionId, stances.size(),
                stances.stream().map(StanceGroup::getLabel).toList());

            if (stances.isEmpty()) {
                log.warn("[{}] Clustering produced 0 stances", sessionId);
                String errMsg2 = "未能从搜索结果中识别出有效立场，请换一个更具争议性的话题重试";
                session.setSummary(errMsg2);
                session.setStatus(DebateSession.Status.ERROR);
                sessionRepo.save(session);
                emitEvent(sessionId, "error", Map.of("message", errMsg2));
                emitEvent(sessionId, "status", Map.of("status", "ERROR"));
                return;
            }

            session.setStancesJson(gson.toJson(stances));
            sessionRepo.save(session);

            emitEvent(sessionId, "stances", stances);

            // Phase 2: Debate
            updateStatus(session, DebateSession.Status.DEBATING);
            emitEvent(sessionId, "status", Map.of("status", "DEBATING", "message", "辩论进行中..."));

            List<DebateTurn> turns = new ArrayList<>();

            orchestrator.runDebate(
                session.getTopic(),
                stances,
                turn -> {
                    turns.add(turn);
                    emitEvent(sessionId, "turn", turn);
                },
                token -> emitEvent(sessionId, "summaryToken", Map.of("token", token)),
                stopped::get
            );

            // Phase 3: Persist & complete
            session.setTurnsJson(gson.toJson(turns));
            if (!turns.isEmpty()) {
                DebateTurn lastTurn = turns.get(turns.size() - 1);
                if (lastTurn.getType() == DebateTurn.Type.SUMMARY) {
                    session.setSummary(lastTurn.getText());
                }
            }
            updateStatus(session, DebateSession.Status.COMPLETED);
            emitEvent(sessionId, "status", Map.of("status", "COMPLETED", "message", "辩论结束"));
            log.info("[{}] Debate completed: {} turns, elapsed {}ms",
                sessionId, turns.size(), System.currentTimeMillis() - startMs);

        } catch (Exception e) {
            log.error("[{}] Debate failed after {}ms: {}", sessionId,
                System.currentTimeMillis() - startMs, e.getMessage(), e);
            String errMsg = "辩论出现错误：" + e.getMessage();
            session.setSummary(errMsg);
            session.setStatus(DebateSession.Status.ERROR);
            sessionRepo.save(session);
            emitEvent(sessionId, "error", Map.of("message", errMsg));
            emitEvent(sessionId, "status", Map.of("status", "ERROR"));
        } finally {
            stopSignals.remove(sessionId);
            closeEmitters(sessionId);
        }
    }

    public void stopDebate(String sessionId) {
        AtomicBoolean signal = stopSignals.get(sessionId);
        if (signal != null) {
            signal.set(true);
            log.info("Stop signal sent for session {}", sessionId);
        }
    }

    // ── SSE emitter management ────────────────────────────────────────────────

    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 min timeout
        emitters.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(e -> removeEmitter(sessionId, emitter));

        // If session already finished, replay immediately so late subscribers don't hang
        sessionRepo.findById(sessionId).ifPresent(session -> {
            try {
                if (session.getStatus() == DebateSession.Status.COMPLETED) {
                    if (session.getStancesJson() != null) {
                        List<StanceGroup> stances = gson.fromJson(session.getStancesJson(),
                            new TypeToken<List<StanceGroup>>() {}.getType());
                        sendEvent(emitter, "stances", stances);
                    }
                    if (session.getTurnsJson() != null) {
                        List<DebateTurn> turns = gson.fromJson(session.getTurnsJson(),
                            new TypeToken<List<DebateTurn>>() {}.getType());
                        turns.forEach(t -> sendEvent(emitter, "turn", t));
                    }
                    sendEvent(emitter, "status", Map.of("status", "COMPLETED", "message", "辩论结束（来自缓存）"));
                    emitter.complete();
                } else if (session.getStatus() == DebateSession.Status.ERROR) {
                    String msg = session.getSummary();
                    if (msg != null) sendEvent(emitter, "error", Map.of("message", msg));
                    sendEvent(emitter, "status", Map.of("status", "ERROR"));
                    emitter.complete();
                }
            } catch (Exception e) {
                log.warn("Replay failed for session {}", sessionId, e);
            }
        });

        return emitter;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateStatus(DebateSession session, DebateSession.Status status) {
        session.setStatus(status);
        sessionRepo.save(session);
    }

    private void emitEvent(String sessionId, String eventType, Object data) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (list == null || list.isEmpty()) return;
        new ArrayList<>(list).forEach(emitter -> sendEvent(emitter, eventType, data));
    }

    private void sendEvent(SseEmitter emitter, String eventType, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .name(eventType)
                .data(gson.toJson(data)));
        } catch (IOException e) {
            log.debug("SSE send failed, emitter likely disconnected");
        }
    }

    private void removeEmitter(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (list != null) list.remove(emitter);
    }

    private void closeEmitters(String sessionId) {
        List<SseEmitter> list = emitters.remove(sessionId);
        if (list != null) list.forEach(e -> {
            try { e.complete(); } catch (Exception ignored) {}
        });
    }

    private static String topicHash(String topic) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(topic.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(topic.hashCode());
        }
    }
}

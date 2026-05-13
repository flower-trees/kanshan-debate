package com.zhihu.kanshan.debate.controller;

import com.zhihu.kanshan.debate.model.DebateSession;
import com.zhihu.kanshan.debate.service.DebateSessionService;
import com.zhihu.kanshan.debate.service.ZhihuApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DebateController {

    private final DebateSessionService sessionService;
    private final ZhihuApiService zhihuApiService;

    // ── Pages ─────────────────────────────────────────────────────────────────

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("hotTopics", zhihuApiService.getHotTopics());
        return "index";
    }

    // ── REST API ──────────────────────────────────────────────────────────────

    @PostMapping("/api/debate/start")
    @ResponseBody
    public ResponseEntity<Map<String, String>> startDebate(@RequestBody Map<String, String> body) {
        String topic = body.get("topic");
        if (topic == null || topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "topic is required"));
        }
        topic = topic.trim();
        log.info("Start debate request: topic='{}'", topic);

        DebateSession session = sessionService.createSession(topic);
        log.info("Session created: id={}, status={}, topic='{}'", session.getId(), session.getStatus(), topic);

        // If PENDING, kick off async execution
        if (session.getStatus() == DebateSession.Status.PENDING) {
            sessionService.runAsync(session.getId());
        }

        return ResponseEntity.ok(Map.of(
            "sessionId", session.getId(),
            "status", session.getStatus().name()
        ));
    }

    @GetMapping(value = "/api/debate/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamEvents(@PathVariable String sessionId) {
        log.debug("SSE subscribe: sessionId={}", sessionId);
        return sessionService.subscribe(sessionId);
    }

    @PostMapping("/api/debate/{sessionId}/stop")
    @ResponseBody
    public ResponseEntity<Map<String, String>> stopDebate(@PathVariable String sessionId) {
        log.info("Stop request: sessionId={}", sessionId);
        sessionService.stopDebate(sessionId);
        return ResponseEntity.ok(Map.of("status", "stop signal sent"));
    }
}

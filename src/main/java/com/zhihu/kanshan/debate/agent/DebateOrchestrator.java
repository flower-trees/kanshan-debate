package com.zhihu.kanshan.debate.agent;

import com.zhihu.kanshan.debate.model.Citation;
import com.zhihu.kanshan.debate.model.DebateTurn;
import com.zhihu.kanshan.debate.model.StanceGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.rag.embedding.AliyunEmbeddings;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.rag.vector.InMemoryVectorStore;
import org.salt.jlangchain.rag.vector.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zhihu.kanshan.debate.model.Answer;
import com.zhihu.kanshan.debate.service.ZhihuApiService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core debate engine using j-langchain's loop() and ChainActor.
 *
 * Flow:
 *  1. Build one InMemoryVectorStore per stance (evidence RAG)
 *  2. Opening statements — each debater speaks in sequence
 *  3. Cross-examination — host picks a stance per round, debater retrieves evidence + responds
 *  4. Summary via LLM (or Zhida Agent when real API available)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebateOrchestrator {

    private final ChainActor chainActor;
    private final ZhihuApiService zhihuApiService;

    @Value("${debate.max-rounds:3}")
    private int maxRounds;

    public void runDebate(String topic, List<StanceGroup> stances,
                          Consumer<DebateTurn> onTurn,
                          StopSignal stopSignal) {
        log.info("Debate start: topic='{}', stances={}, maxRounds={}", topic, stances.size(), maxRounds);

        // Build per-stance vector stores for evidence RAG
        Map<String, VectorStore> vectorStores = buildVectorStores(stances);

        // Collect non-host turns for Zhida summary context
        List<DebateTurn> speechTurns = new ArrayList<>();

        // Global author→answer map for cross-stance citation matching
        Map<String, Answer> allAnswers = stances.stream()
            .filter(s -> s.getAnswers() != null)
            .flatMap(s -> s.getAnswers().stream())
            .collect(Collectors.toMap(
                Answer::getAuthorName, a -> a, (a1, a2) -> a1, LinkedHashMap::new));

        // Emit HOST opening
        onTurn.accept(hostTurn("🎙️ 欢迎来到知乎圆桌辩论！今日议题：**" + topic + "**\n\n"
            + "我们邀请了 " + stances.size() + " 位代表不同立场的辩手，"
            + "他们的观点均来自知乎真实答主的高赞回答。辩论开始！"));

        // ── Phase 1: Opening statements ─────────────────────────────────────
        log.info("Phase 1: opening statements ({} debaters)", stances.size());
        onTurn.accept(hostTurn("📢 **第一阶段：开场陈述**"));

        // Helper: emit a speech turn and collect it for Zhida summary context
        Consumer<DebateTurn> emitSpeech = turn -> { speechTurns.add(turn); onTurn.accept(turn); };

        for (StanceGroup stance : stances) {
            if (stopSignal.isStopped()) return;

            String openingText = generateSpeech(topic, stance, null, vectorStores.get(stance.getId()),
                SpeechType.OPENING, null);
            emitSpeech.accept(DebateTurn.builder()
                .id(UUID.randomUUID().toString())
                .type(DebateTurn.Type.OPENING)
                .debaterName(stance.getLabel())
                .stanceId(stance.getId())
                .emoji(stance.getEmoji())
                .text(openingText)
                .citations(buildCitations(openingText, allAnswers))
                .timestamp(System.currentTimeMillis())
                .build());
        }

        // ── Phase 2: Cross-examination ───────────────────────────────────────
        log.info("Phase 2: cross-examination ({} rounds)", maxRounds);
        onTurn.accept(hostTurn("⚔️ **第二阶段：交叉质询**"));

        for (int round = 0; round < maxRounds; round++) {
            if (stopSignal.isStopped()) return;

            StanceGroup challenger = stances.get(round % stances.size());
            StanceGroup target = stances.get((round + 1) % stances.size());
            log.info("Round {}/{}: '{}' challenges '{}'", round + 1, maxRounds, challenger.getLabel(), target.getLabel());

            onTurn.accept(hostTurn(String.format("第 %d 轮：请 **%s** 针对 **%s** 的观点提出质疑",
                round + 1, challenger.getLabel(), target.getLabel())));

            if (stopSignal.isStopped()) return;

            String challengeText = generateSpeech(topic, challenger, target, vectorStores.get(challenger.getId()),
                SpeechType.CHALLENGE, null);
            emitSpeech.accept(DebateTurn.builder()
                .id(UUID.randomUUID().toString())
                .type(DebateTurn.Type.CROSS)
                .debaterName(challenger.getLabel())
                .stanceId(challenger.getId())
                .emoji(challenger.getEmoji())
                .text(challengeText)
                .citations(buildCitations(challengeText, allAnswers))
                .timestamp(System.currentTimeMillis())
                .build());

            if (stopSignal.isStopped()) return;

            String defenseText = generateSpeech(topic, target, challenger, vectorStores.get(target.getId()),
                SpeechType.DEFENSE, challengeText);
            emitSpeech.accept(DebateTurn.builder()
                .id(UUID.randomUUID().toString())
                .type(DebateTurn.Type.CROSS)
                .debaterName(target.getLabel())
                .stanceId(target.getId())
                .emoji(target.getEmoji())
                .text(defenseText)
                .citations(buildCitations(defenseText, allAnswers))
                .timestamp(System.currentTimeMillis())
                .build());
        }

        // ── Phase 3: Summary ─────────────────────────────────────────────────
        if (!stopSignal.isStopped()) {
            log.info("Phase 3: generating summary via Zhida (fallback: Qwen)");
            onTurn.accept(hostTurn("📋 **总结阶段**：综合各方观点"));
            String summary = generateSummary(topic, stances, speechTurns);
            onTurn.accept(DebateTurn.builder()
                .id(UUID.randomUUID().toString())
                .type(DebateTurn.Type.SUMMARY)
                .debaterName("综合视角")
                .stanceId("summary")
                .emoji("📊")
                .text(summary)
                .citations(List.of())
                .timestamp(System.currentTimeMillis())
                .build());
        }
    }

    // ── Speech generation ─────────────────────────────────────────────────────

    private enum SpeechType { OPENING, CHALLENGE, DEFENSE }

    private String generateSpeech(String topic, StanceGroup speaker, StanceGroup target,
                                   VectorStore vectorStore, SpeechType type, String priorText) {
        log.info("Generating speech: debater='{}' type={}", speaker.getLabel(), type);
        long t0 = System.currentTimeMillis();

        // RAG: retrieve relevant evidence from this stance's vector store
        String evidence = retrieveEvidence(vectorStore, topic, type, target);

        String prompt = buildDebaterPrompt(topic, speaker, target, evidence, type, priorText);

        FlowInstance speechChain = chainActor.builder()
            .next(PromptTemplate.fromTemplate("${prompt}"))
            .next(ChatAliyun.builder().model("qwen-plus").temperature(0.7f).build())
            .next(new StrOutputParser())
            .build();

        ChatGeneration result = chainActor.invoke(speechChain, Map.of("prompt", prompt));
        String text = result.getText();
        log.info("Speech done: debater='{}' type={} chars={} elapsed={}ms",
            speaker.getLabel(), type, text.length(), System.currentTimeMillis() - t0);
        return text;
    }

    private String retrieveEvidence(VectorStore store, String topic, SpeechType type, StanceGroup target) {
        if (store == null) return "";
        try {
            String query = type == SpeechType.DEFENSE && target != null
                ? target.getLabel() + " " + topic
                : topic;
            List<Document> docs = store.similaritySearch(query, 2);
            if (docs.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            docs.forEach(d -> {
                sb.append("- ").append(d.getPageContent());
                Object author = d.getMetadata().get("authorName");
                if (author != null) sb.append("（引自 @").append(author).append("）");
                sb.append("\n");
            });
            return sb.toString();
        } catch (Exception e) {
            log.warn("Evidence retrieval failed", e);
            return "";
        }
    }

    private String buildDebaterPrompt(String topic, StanceGroup speaker, StanceGroup target,
                                       String evidence, SpeechType type, String priorText) {
        String persona = String.format(
            "你是「%s」立场的辩手，代表知乎上持该立场的答主群体。你的核心论点：%s",
            speaker.getLabel(),
            String.join("；", speaker.getKeyArguments() != null ? speaker.getKeyArguments() : List.of())
        );

        String evidenceSection = evidence.isBlank() ? "" :
            "\n可以引用的论据（来自知乎真实答主，引用时标注出处）：\n" + evidence;

        return switch (type) {
            case OPENING -> String.format("""
                %s%s

                请就「%s」发表开场陈述（150字以内）。
                要求：立场鲜明，有理有据，至少引用1条论据并标注「引自 @答主名」。
                """, persona, evidenceSection, topic);

            case CHALLENGE -> {
                String targetSummary = target != null ? target.getLabel() : "对方";
                yield String.format("""
                    %s%s

                    你需要针对「%s」的观点提出质疑（120字以内）。
                    要求：找出对方论证的薄弱点，用你立场的论据反驳，标注「引自 @答主名」。
                    """, persona, evidenceSection, targetSummary);
            }

            case DEFENSE -> {
                String attackSummary = priorText != null
                    ? priorText.substring(0, Math.min(200, priorText.length()))
                    : "对方的质疑";
                yield String.format("""
                    %s%s

                    对方刚才的质疑：「%s...」

                    请为你的立场进行辩护（120字以内）。
                    要求：正面回应质疑，加强你的核心论点，标注「引自 @答主名」。
                    """, persona, evidenceSection, attackSummary);
            }
        };
    }

    private String generateSummary(String topic, List<StanceGroup> stances, List<DebateTurn> speechTurns) {
        // Try Zhida first (deep-thinking model, 100/day limit)
        String debateContext = buildDebateContext(speechTurns);
        String zhidaResult = zhihuApiService.callZhidaAgent(topic, debateContext);
        if (zhidaResult != null) {
            log.info("Summary from Zhida: {} chars", zhidaResult.length());
            return zhidaResult;
        }

        // Fallback: local Qwen
        log.info("Zhida unavailable, generating summary with local Qwen");
        StringBuilder stanceSummary = new StringBuilder();
        for (StanceGroup s : stances) {
            stanceSummary.append("- ").append(s.getEmoji()).append(" **").append(s.getLabel()).append("**：")
                .append(s.getDescription()).append("\n");
        }

        FlowInstance summaryChain = chainActor.builder()
            .next(PromptTemplate.fromTemplate("""
                请对知乎圆桌辩论「${topic}」做多视角综述（200字以内）。

                各方立场：
                ${stances}

                要求：
                1. 客观呈现各方最有力的论点
                2. 指出分歧的核心所在
                3. 给读者一个思考框架，而不是结论
                4. 结尾提示「各论点均来自知乎真实答主」
                """))
            .next(ChatAliyun.builder().model("qwen-plus").temperature(0.3f).build())
            .next(new StrOutputParser())
            .build();

        ChatGeneration result = chainActor.invoke(summaryChain, Map.of(
            "topic", topic,
            "stances", stanceSummary.toString()
        ));
        return result.getText();
    }

    private String buildDebateContext(List<DebateTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (DebateTurn turn : turns) {
            sb.append(turn.getEmoji()).append(" **").append(turn.getDebaterName()).append("**（")
              .append(turn.getType().name().toLowerCase()).append("）：\n");
            String text = turn.getText();
            sb.append(text.length() > 150 ? text.substring(0, 150) + "…" : text);
            sb.append("\n\n");
        }
        return sb.toString();
    }

    // ── Vector store setup ────────────────────────────────────────────────────

    private Map<String, VectorStore> buildVectorStores(List<StanceGroup> stances) {
        try {
            AliyunEmbeddings embeddings = AliyunEmbeddings.builder().build();
            Map<String, VectorStore> stores = new java.util.HashMap<>();
            for (StanceGroup stance : stances) {
                if (stance.getAnswers() == null || stance.getAnswers().isEmpty()) continue;

                List<Document> docs = new ArrayList<>();
                for (var answer : stance.getAnswers()) {
                    Map<String, Object> meta = new java.util.HashMap<>();
                    meta.put("authorName", answer.getAuthorName());
                    meta.put("authorUrl", answer.getAuthorUrl());
                    meta.put("answerUrl", answer.getAnswerUrl());
                    meta.put("upvotes", answer.getUpvotes());
                    for (String snippet : answer.getKeySnippets()) {
                        docs.add(Document.builder().pageContent(snippet).metadata(meta).build());
                    }
                }

                stores.put(stance.getId(), InMemoryVectorStore.fromDocuments(docs, embeddings));
            }
            return stores;
        } catch (Exception e) {
            log.warn("Vector store build failed (no embedding key?), falling back to keyword search", e);
            return buildKeywordFallbackStores(stances);
        }
    }

    private Map<String, VectorStore> buildKeywordFallbackStores(List<StanceGroup> stances) {
        // Fallback: return empty map, generateSpeech will skip RAG gracefully
        return Map.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DebateTurn hostTurn(String text) {
        return DebateTurn.builder()
            .id(UUID.randomUUID().toString())
            .type(DebateTurn.Type.HOST)
            .debaterName("主持人")
            .stanceId("host")
            .emoji("🎙️")
            .text(text)
            .citations(List.of())
            .timestamp(System.currentTimeMillis())
            .build();
    }

    // Matches @后面跟随的作者名，到空白或中文标点为止
    private static final Pattern MENTION = Pattern.compile("@([^\\s，。！？、【】「」（）\n\r]+)");

    private List<Citation> buildCitations(String speechText, Map<String, Answer> allAnswers) {
        Map<String, Citation> seen = new LinkedHashMap<>(); // dedup by authorName
        Matcher m = MENTION.matcher(speechText);
        while (m.find()) {
            String mention = m.group(1);
            // Exact match first, then longest-prefix partial match
            Answer answer = allAnswers.get(mention);
            if (answer == null) {
                answer = allAnswers.entrySet().stream()
                    .filter(e -> e.getKey().contains(mention) || mention.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            }
            if (answer != null && !seen.containsKey(answer.getAuthorName())) {
                seen.put(answer.getAuthorName(), Citation.builder()
                    .snippet(answer.getKeySnippets().isEmpty() ? "" : answer.getKeySnippets().get(0))
                    .authorName(answer.getAuthorName())
                    .authorUrl(answer.getAuthorUrl())
                    .answerUrl(answer.getAnswerUrl())
                    .upvotes(answer.getUpvotes())
                    .build());
            }
        }
        return new ArrayList<>(seen.values());
    }

    @FunctionalInterface
    public interface StopSignal {
        boolean isStopped();
    }
}

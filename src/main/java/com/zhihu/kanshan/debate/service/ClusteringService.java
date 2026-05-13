package com.zhihu.kanshan.debate.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zhihu.kanshan.debate.model.Answer;
import com.zhihu.kanshan.debate.model.StanceGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Uses LLM to cluster raw answers into 3-4 distinct stance groups.
 * Outputs structured JSON with label, description, and key arguments per stance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusteringService {

    private final ChainActor chainActor;
    private final Gson gson = new Gson();

    private static final String[] EMOJIS = {"🔵", "🔴", "🟡", "🟢"};

    public List<StanceGroup> cluster(String topic, List<Answer> answers) {
        if (answers == null || answers.isEmpty()) {
            log.warn("Clustering skipped: no answers for topic '{}'", topic);
            return List.of();
        }
        log.info("Clustering {} answers for topic '{}'", answers.size(), topic);

        String answerSummary = buildAnswerSummary(answers);

        FlowInstance clusterChain = chainActor.builder()
            .next(PromptTemplate.fromTemplate(CLUSTER_PROMPT))
            .next(ChatAliyun.builder().model("qwen-plus").temperature(0.1f).build())
            .next(new StrOutputParser())
            .build();

        ChatGeneration result = chainActor.invoke(clusterChain, Map.of(
            "topic", topic,
            "answers", answerSummary
        ));
        log.debug("LLM clustering raw output: {}", result.getText());

        return parseStances(result.getText(), answers);
    }

    private String buildAnswerSummary(List<Answer> answers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < answers.size(); i++) {
            Answer a = answers.get(i);
            sb.append(i + 1).append(". 【").append(a.getAuthorName()).append("，")
              .append(a.getUpvotes()).append("赞】\n")
              .append(a.getContent(), 0, Math.min(200, a.getContent().length()))
              .append("\n\n");
        }
        return sb.toString();
    }

    private List<StanceGroup> parseStances(String llmOutput, List<Answer> answers) {
        try {
            String json = extractJson(llmOutput);
            JsonArray arr = gson.fromJson(json, JsonArray.class);
            List<StanceGroup> groups = new ArrayList<>();

            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                String id = obj.has("id") ? obj.get("id").getAsString() : "stance_" + i;
                String label = obj.get("label").getAsString();
                String description = obj.get("description").getAsString();

                List<String> keyArgs = new ArrayList<>();
                if (obj.has("keyArguments")) {
                    obj.getAsJsonArray("keyArguments").forEach(e -> keyArgs.add(e.getAsString()));
                }

                List<Integer> answerIndices = new ArrayList<>();
                if (obj.has("answerIndices")) {
                    obj.getAsJsonArray("answerIndices").forEach(e -> answerIndices.add(e.getAsInt() - 1));
                }

                List<Answer> stanceAnswers = answerIndices.stream()
                    .filter(idx -> idx >= 0 && idx < answers.size())
                    .map(answers::get)
                    .toList();

                Answer topAnswer = stanceAnswers.isEmpty() ? null : stanceAnswers.stream()
                    .max((a, b) -> Integer.compare(a.getUpvotes(), b.getUpvotes()))
                    .orElse(stanceAnswers.get(0));

                groups.add(StanceGroup.builder()
                    .id(id)
                    .label(label)
                    .description(description)
                    .emoji(EMOJIS[i % EMOJIS.length])
                    .answers(stanceAnswers)
                    .keyArguments(keyArgs)
                    .topAuthorName(topAnswer != null ? topAnswer.getAuthorName() : "")
                    .topAuthorUrl(topAnswer != null ? topAnswer.getAuthorUrl() : "")
                    .build());
            }
            return groups;
        } catch (Exception e) {
            log.warn("Failed to parse clustering JSON, using fallback stances. Raw: {}", llmOutput, e);
            return buildFallbackStances(answers);
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private List<StanceGroup> buildFallbackStances(List<Answer> answers) {
        // Minimal fallback: 3 stances, distribute answers evenly
        int third = Math.max(1, answers.size() / 3);
        return List.of(
            StanceGroup.builder().id("pro").label("支持方").emoji("🔵")
                .description("支持/正向的立场").answers(answers.subList(0, Math.min(third, answers.size())))
                .keyArguments(List.of("支持的核心论点")).topAuthorName("").build(),
            StanceGroup.builder().id("con").label("反对方").emoji("🔴")
                .description("反对/负向的立场").answers(answers.subList(Math.min(third, answers.size()), Math.min(third * 2, answers.size())))
                .keyArguments(List.of("反对的核心论点")).topAuthorName("").build(),
            StanceGroup.builder().id("neutral").label("中立派").emoji("🟡")
                .description("折中/中立的立场").answers(answers.subList(Math.min(third * 2, answers.size()), answers.size()))
                .keyArguments(List.of("折中的核心论点")).topAuthorName("").build()
        );
    }

    private static final String CLUSTER_PROMPT = """
        你是一个立场分析专家。以下是知乎上关于「${topic}」的高赞回答摘要：

        ${answers}

        请将这些回答归类为3-4个代表性立场，输出严格的JSON数组，不要有其他文字：

        [
          {
            "id": "pro",
            "label": "支持派（简短标签，最多6字）",
            "description": "这个立场的一句话核心主张（20字以内）",
            "keyArguments": ["论点1", "论点2", "论点3"],
            "answerIndices": [1, 4, 7]
          }
        ]

        要求：
        1. 每个立场2-4个回答归属（answerIndices是上面列表的序号，从1开始）
        2. 立场要有明显区分，不要重叠
        3. 标签简短有力，便于辩论中识别
        4. keyArguments直接引用答案中的核心观点，不要泛泛而谈
        5. 只输出JSON，不要Markdown代码块
        """;
}

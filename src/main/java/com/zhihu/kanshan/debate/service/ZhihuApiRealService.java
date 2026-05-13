package com.zhihu.kanshan.debate.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.zhihu.kanshan.debate.model.Answer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Real Zhihu Search API client.
 * Activated when zhihu.secret is configured (env: ZHIHU_SECRET).
 * Falls back to ZhihuApiMockService when not configured.
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "zhihu.secret")
public class ZhihuApiRealService implements ZhihuApiService {

    private static final String SEARCH_URL = "https://developer.zhihu.com/api/v1/content/zhihu_search";
    private static final String HOT_URL    = "https://developer.zhihu.com/api/v1/content/hot_list";

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();

    private final Gson gson = new Gson();

    @Value("${zhihu.secret}")
    private String accessSecret;

    @Value("${debate.hot-cache-dir:src/main/resources/cache}")
    private String hotCacheDir;

    @Override
    public List<Answer> searchAnswers(String topic, int limit) {
        Path cacheFile = Paths.get(hotCacheDir, "search_" + topicHash(topic) + ".json");

        // Try daily cache first
        if (Files.exists(cacheFile)) {
            try {
                List<Answer> cached = gson.fromJson(
                    Files.readString(cacheFile),
                    new TypeToken<List<Answer>>() {}.getType()
                );
                if (cached != null && !cached.isEmpty()) {
                    log.info("Search cache hit: topic='{}', {} answers from {}", topic, cached.size(), cacheFile.getFileName());
                    return cached.stream().limit(limit).toList();
                }
            } catch (IOException e) {
                log.warn("Search cache read failed: {}", e.getMessage());
            }
        }

        // Fetch from API — cache all results, limit only on return
        Map<String, Answer> byId = new LinkedHashMap<>();
        fetchSearch(topic, limit).forEach(a -> byId.put(a.getId(), a));
//        fetchSearch(topic, 10).forEach(a -> byId.put(a.getId(), a));
//        if (byId.size() < limit) {
//            fetchSearch(topic + " 观点 看法", 10).forEach(a -> byId.putIfAbsent(a.getId(), a));
//        }

        List<Answer> answers = byId.values().stream()
            .sorted(Comparator.comparingInt(Answer::getUpvotes).reversed())
            .toList();

        if (!answers.isEmpty()) {
            try {
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, gson.toJson(answers));
                log.info("Search results cached: topic='{}', {} answers -> {}", topic, answers.size(), cacheFile.getFileName());
            } catch (IOException e) {
                log.warn("Search cache write failed: {}", e.getMessage());
            }
        }

        return answers.stream().limit(limit).toList();
    }

    @Override
    public List<String> getHotTopics() {
        Path cacheFile = Paths.get(hotCacheDir, "hot_" + LocalDate.now() + ".json");

        // Try daily cache first
        if (Files.exists(cacheFile)) {
            try {
                List<String> cached = gson.fromJson(
                    Files.readString(cacheFile),
                    new TypeToken<List<String>>() {}.getType()
                );
                if (cached != null && !cached.isEmpty()) {
                    log.debug("Hot topics loaded from cache: {} topics from {}", cached.size(), cacheFile.getFileName());
                    return cached;
                }
            } catch (IOException e) {
                log.warn("Hot topics cache read failed: {}", e.getMessage());
            }
        }

        // Fetch from API
        List<String> topics = fetchHotTopicsFromApi();
        if (topics.isEmpty()) return fallbackHotTopics();

        // Persist daily cache (all topics)
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, gson.toJson(topics));
            log.info("Hot topics cached to {}", cacheFile);
        } catch (IOException e) {
            log.warn("Hot topics cache write failed: {}", e.getMessage());
        }

        return topics;
    }

    private List<String> fetchHotTopicsFromApi() {
        try {
            String body = get(HOT_URL, Map.of());
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root.get("Code").getAsInt() != 0) return List.of();

            List<String> topics = new ArrayList<>();
            JsonArray items = root.getAsJsonObject("Data").getAsJsonArray("Items");
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                String title = item.has("Title") ? item.get("Title").getAsString() : "";
                if (!title.isBlank()) topics.add(title);
            }
            return topics;
        } catch (Exception e) {
            log.warn("Hot list API failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static final String ZHIDA_URL = "https://developer.zhihu.com/v1/chat/completions";

    @Override
    public String callZhidaAgent(String topic, String debateContext) {
        try {
            String prompt = String.format(
                "请对知乎圆桌辩论「%s」做多视角综述（200字以内）。\n\n辩论精华：\n%s\n\n要求：\n"
                + "1. 客观呈现各方最有力的论点\n2. 指出分歧的核心所在\n"
                + "3. 给读者一个思考框架，而不是结论\n4. 结尾提示「各论点均来自知乎真实答主」",
                topic, debateContext
            );

            JsonObject msg = new JsonObject();
            msg.addProperty("role", "user");
            msg.addProperty("content", prompt);
            JsonArray messages = new JsonArray();
            messages.add(msg);

            JsonObject reqBody = new JsonObject();
            reqBody.addProperty("model", "zhida-thinking-1p5");
            reqBody.addProperty("stream", false);
            reqBody.add("messages", messages);

            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                gson.toJson(reqBody),
                okhttp3.MediaType.get("application/json; charset=utf-8")
            );

            Request req = new Request.Builder()
                .url(ZHIDA_URL)
                .header("Authorization", "Bearer " + accessSecret)
                .header("X-Request-Timestamp", String.valueOf(System.currentTimeMillis() / 1000))
                .post(body)
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("Zhida API error: HTTP {}", resp.code());
                    return null;
                }
                JsonObject root = gson.fromJson(resp.body().string(), JsonObject.class);
                if (root.has("error")) {
                    log.warn("Zhida API error: {}", root.getAsJsonObject("error").get("message").getAsString());
                    return null;
                }
                String content = root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
                log.info("Zhida summary received: {} chars", content.length());
                return content;
            }
        } catch (Exception e) {
            log.warn("Zhida API call failed, falling back to local Qwen: {}", e.getMessage());
            return null;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private List<Answer> fetchSearch(String query, int count) {
        try {
            String body = get(SEARCH_URL, Map.of("Query", query, "Count", String.valueOf(count)));
            JsonObject root = gson.fromJson(body, JsonObject.class);

            int code = root.get("Code").getAsInt();
            if (code != 0) {
                log.warn("Zhihu search error code {}: {}", code, root.get("Message").getAsString());
                return List.of();
            }

            List<Answer> answers = new ArrayList<>();
            JsonArray items = root.getAsJsonObject("Data").getAsJsonArray("Items");

            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                Answer a = mapItem(item, query);
                if (a != null) answers.add(a);
            }
            return answers;

        } catch (Exception e) {
            log.error("Zhihu search request failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private Answer mapItem(JsonObject item, String topic) {
        String contentId = str(item, "ContentID");
        String contentText = str(item, "ContentText");
        if (contentText.isBlank()) return null;

        String authorName = str(item, "AuthorName");
        String url = str(item, "Url");
        int upvotes = item.has("VoteUpCount") ? item.get("VoteUpCount").getAsInt() : 0;
        String badgeText = str(item, "AuthorBadgeText");
        String title = str(item, "Title");

        // Build display name: name + badge if present
        String displayName = badgeText.isBlank() ? authorName : authorName + "·" + badgeText;

        // Split ContentText into 2-3 key snippets (sentences) for RAG
        List<String> snippets = splitSnippets(contentText);

        return Answer.builder()
            .id(contentId)
            .topic(topic)
            .authorName(displayName)
            .authorUrl("")          // API does not return author profile URL
            .answerUrl(url)
            .content(title.isBlank() ? contentText : title + "\n" + contentText)
            .upvotes(upvotes)
            .keySnippets(snippets)
            .build();
    }

    private List<String> splitSnippets(String text) {
        // Split on Chinese sentence-ending punctuation, keep up to 3 non-trivial snippets
        String[] parts = text.split("[。！？!?]");
        List<String> snippets = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.length() > 15) {
                snippets.add(s);
                if (snippets.size() >= 3) break;
            }
        }
        if (snippets.isEmpty()) snippets.add(text.substring(0, Math.min(100, text.length())));
        return snippets;
    }

    private String get(String url, Map<String, String> params) throws Exception {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
        params.forEach(builder::addQueryParameter);

        Request req = new Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer " + accessSecret)
            .header("X-Request-Timestamp", String.valueOf(System.currentTimeMillis() / 1000))
            .header("Content-Type", "application/json")
            .get()
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code());
            return resp.body().string();
        }
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static String topicHash(String topic) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(topic.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 12);
        } catch (Exception e) {
            return String.valueOf(Math.abs(topic.hashCode()));
        }
    }

    private static List<String> fallbackHotTopics() {
        return List.of("30岁该不该转行", "中医是不是科学", "考研还是直接工作", "房价还会继续涨吗", "躺平还是卷");
    }
}

package com.zhihu.kanshan.debate.auth;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores per-token usage counts encrypted on disk (AES-256-GCM).
 * File: ${debate.hot-cache-dir}/token_usage.enc
 *
 * When TOKEN_SECRET is not set, the store is a no-op (dev mode).
 */
@Slf4j
@Service
public class TokenUsageStore {

    @Value("${TOKEN_SECRET:}")
    private String secret;

    @Value("${debate.hot-cache-dir:src/main/resources/cache}")
    private String cacheDir;

    private TokenCipher cipher;
    private Path usageFile;
    private final Gson gson = new Gson();

    // key = short SHA-256 of the token string, value = usage count
    private final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

    public boolean isEnabled() { return cipher != null; }

    @PostConstruct
    public void init() {
        if (secret.isBlank()) {
            log.info("TOKEN_SECRET not set — token usage tracking disabled");
            throw new RuntimeException("Token usage tracking disabled");
        }
        cipher = new TokenCipher(secret);
        usageFile = Paths.get(cacheDir, "token_usage.enc");
        load();
    }

    /** Returns current usage count for the given raw token string. */
    public int getCount(String token) {
        if (!isEnabled()) return 0;
        return counts.getOrDefault(hash(token), 0);
    }

    /** Increments and persists the count for the given raw token string. */
    public synchronized void increment(String token) {
        if (!isEnabled()) return;
        counts.merge(hash(token), 1, Integer::sum);
        persist();
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(usageFile)) return;
        try {
            String enc = Files.readString(usageFile).trim();
            String json = cipher.decryptRaw(enc);
            Map<String, Integer> loaded = gson.fromJson(json, new TypeToken<Map<String, Integer>>() {}.getType());
            if (loaded != null) counts.putAll(loaded);
            log.info("Token usage loaded: {} entries", counts.size());
        } catch (Exception e) {
            log.warn("Could not load token usage file (starting fresh): {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(usageFile.getParent());
            String json = gson.toJson(counts);
            Files.writeString(usageFile, cipher.encryptRaw(json));
        } catch (Exception e) {
            log.error("Could not persist token usage: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String hash(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes());
            return Base64.getEncoder().encodeToString(h).substring(0, 12);
        } catch (Exception e) {
            return String.valueOf(token.hashCode());
        }
    }
}

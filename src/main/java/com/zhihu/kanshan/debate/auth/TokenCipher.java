package com.zhihu.kanshan.debate.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM token cipher.
 *
 * Token payload format: "label:limit"  e.g. "alice:10"
 * Encoded as: base64url( iv[12] + ciphertext + tag[16] )
 *
 * ── Local usage ──────────────────────────────────────────────────────────────
 *
 * Step 1 — generate a secret key (once, store as TOKEN_SECRET env var):
 *   mvn exec:java -Dexec.mainClass=com.zhihu.kanshan.debate.auth.TokenCipher
 *
 * Step 2 — generate a token for a tester:
 *   TOKEN_SECRET=<key> mvn exec:java \
 *     -Dexec.mainClass=com.zhihu.kanshan.debate.auth.TokenCipher \
 *     -Dexec.args="alice 10"
 *
 * Share the printed token with the tester as: http://host/?token=<token>
 */
public class TokenCipher {

    private static final String ALG    = "AES/GCM/NoPadding";
    private static final int    IV_LEN = 12;   // 96-bit IV (GCM recommendation)
    private static final int    TAG_BITS = 128;

    private final SecretKeySpec key;

    /**
     * Accepts any string as secret — SHA-256 derives a stable 256-bit AES key.
     * No need to base64-encode the secret; just set TOKEN_SECRET to any password.
     */
    public TokenCipher(String secret) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes());
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Key init failed", e);
        }
    }

    // ── Raw encrypt / decrypt (any string payload) ───────────────────────────

    public String encryptRaw(String payload) {
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALG);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(payload.getBytes());
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Encrypt failed", e);
        }
    }

    public String decryptRaw(String encoded) {
        try {
            byte[] data = Base64.getUrlDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(data, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[data.length - IV_LEN];
            System.arraycopy(data, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(ALG);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct));
        } catch (Exception e) {
            throw new RuntimeException("Decrypt failed", e);
        }
    }

    // ── Token-specific helpers ────────────────────────────────────────────────

    public String encrypt(String label, int limit) {
        return encryptRaw(label + ":" + limit);
    }

    public TokenPayload decrypt(String token) {
        try {
            String payload = decryptRaw(token);
            String[] parts = payload.split(":", 2);
            return new TokenPayload(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        } catch (Exception e) {
            throw new RuntimeException("Invalid token", e);
        }
    }

    public record TokenPayload(String label, int limit) {}

    // ── CLI ───────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String secret = System.getenv("TOKEN_SECRET");
        if (secret == null || secret.isBlank()) {
            System.err.println("错误: TOKEN_SECRET 环境变量未设置");
            System.err.println("示例: export TOKEN_SECRET=my-secret-password");
            System.exit(1);
        }

        if (args.length >= 2) {
            String label = args[0];
            int limit    = Integer.parseInt(args[1]);
            String token = new TokenCipher(secret).encrypt(label, limit);
            System.out.println("=== 生成 Token ===");
            System.out.printf("标识: %s | 次数上限: %d%n", label, limit);
            System.out.println("Token: " + token);
            System.out.println("访问链接: http://localhost:8080/?token=" + token);
        } else {
            System.err.println("用法: TOKEN_SECRET=xxx mvn exec:java " +
                "-Dexec.mainClass=...TokenCipher -Dexec.args=\"<label> <limit>\"");
            System.err.println("示例: -Dexec.args=\"alice 5\"");
        }
    }
}

package com.coin.arbitrage.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CredentialEncryptionService {
    private static final Path LOCAL_KEY = Path.of("data", "account-credentials.key");
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public CredentialEncryptionService(@Value("${account.encryption-key:}") String configuredKey) {
        this.key = new SecretKeySpec(loadKey(configuredKey), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("거래소 키 암호화에 실패했습니다.", error);
        }
    }

    public String decrypt(String encoded) {
        try {
            String[] parts = encoded.split(":", 3);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("지원하지 않는 암호문입니다.");
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("저장된 거래소 키를 복호화할 수 없습니다.", error);
        }
    }

    private static byte[] loadKey(String configuredKey) {
        try {
            byte[] bytes;
            if (configuredKey != null && !configuredKey.isBlank()) {
                bytes = Base64.getDecoder().decode(configuredKey.trim());
            } else if (Files.exists(LOCAL_KEY)) {
                bytes = Base64.getDecoder().decode(Files.readString(LOCAL_KEY).trim());
            } else {
                Files.createDirectories(LOCAL_KEY.getParent());
                bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                Files.writeString(LOCAL_KEY, Base64.getEncoder().encodeToString(bytes));
                try {
                    Files.setPosixFilePermissions(LOCAL_KEY, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) { }
            }
            if (bytes.length != 32) throw new IllegalStateException("ACCOUNT_ENCRYPTION_KEY는 Base64 인코딩된 32바이트 키여야 합니다.");
            return bytes;
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("계정 암호화 키를 준비할 수 없습니다.", error);
        }
    }
}

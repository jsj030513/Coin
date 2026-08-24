package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AccountRecoveryService {
    private static final Duration CODE_VALIDITY = Duration.ofMinutes(10);
    private static final Duration REQUEST_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;

    private final UserAccountRepository accounts;
    private final UserAccountService users;
    private final TelegramNotificationService telegram;
    private final SecureRandom random = new SecureRandom();
    private RecoveryChallenge challenge;

    public AccountRecoveryService(UserAccountRepository accounts, UserAccountService users,
                                  TelegramNotificationService telegram) {
        this.accounts = accounts;
        this.users = users;
        this.telegram = telegram;
    }

    public synchronized void requestCode() {
        Instant now = Instant.now();
        if (challenge != null && challenge.requestedAt().plus(REQUEST_COOLDOWN).isAfter(now)) {
            throw new IllegalArgumentException("복구 코드는 60초에 한 번 요청할 수 있습니다.");
        }
        List<UserAccountEntity> existing = accounts.findAll();
        if (existing.size() != 1) {
            throw new IllegalStateException("복구 가능한 개인 계정을 찾을 수 없습니다.");
        }
        UserAccountEntity user = existing.get(0);
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String salt = Long.toUnsignedString(random.nextLong(), 36);
        telegram.sendAccountRecovery(user.getUsername(), code, CODE_VALIDITY.toMinutes());
        challenge = new RecoveryChallenge(user.getUsername(), digest(salt + code), salt,
                now, now.plus(CODE_VALIDITY), MAX_ATTEMPTS);
    }

    public synchronized String resetPassword(String code, String newPassword) {
        Instant now = Instant.now();
        if (challenge == null || !challenge.expiresAt().isAfter(now)) {
            challenge = null;
            throw invalidCode();
        }
        byte[] supplied = digest(challenge.salt() + (code == null ? "" : code.trim()));
        if (!MessageDigest.isEqual(challenge.codeHash(), supplied)) {
            int remaining = challenge.remainingAttempts() - 1;
            challenge = remaining > 0 ? challenge.withRemainingAttempts(remaining) : null;
            throw invalidCode();
        }
        users.resetPassword(challenge.username(), newPassword);
        String username = challenge.username();
        challenge = null;
        return username;
    }

    private static IllegalArgumentException invalidCode() {
        return new IllegalArgumentException("복구 코드가 올바르지 않거나 만료되었습니다.");
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", error);
        }
    }

    private record RecoveryChallenge(String username, byte[] codeHash, String salt,
                                     Instant requestedAt, Instant expiresAt, int remainingAttempts) {
        RecoveryChallenge withRemainingAttempts(int attempts) {
            return new RecoveryChallenge(username, codeHash, salt, requestedAt, expiresAt, attempts);
        }
    }
}

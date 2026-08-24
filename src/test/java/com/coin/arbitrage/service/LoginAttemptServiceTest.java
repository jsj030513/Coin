package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {
    @Test
    void locksAfterFiveFailuresAndClearsAfterSuccess() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 5; i++) service.failed("127.0.0.1", "trader");

        assertThat(service.retryAfterSeconds("127.0.0.1", "trader")).isPositive();

        service.succeeded("127.0.0.1", "trader");
        assertThat(service.retryAfterSeconds("127.0.0.1", "trader")).isZero();
    }

    @Test
    void doesNotLockBeforeThreshold() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 4; i++) service.failed("127.0.0.1", "trader");

        assertThat(service.retryAfterSeconds("127.0.0.1", "trader")).isZero();
    }
}

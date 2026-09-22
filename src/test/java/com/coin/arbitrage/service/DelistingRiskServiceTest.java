package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DelistingRiskServiceTest {
    @Test
    void explicitReleaseOverridesConfiguredRiskUntilNewDesignation() {
        DelistingRiskService service = new DelistingRiskService(null, "BONK/KRW", "BB/KRW");
        assertThat(service.risky("BITHUMB", "BB/KRW")).isTrue();
        service.applyNoticeChanges("BITHUMB", Set.of(), Set.of("BB/KRW"));
        assertThat(service.risky("BITHUMB", "BB/KRW")).isFalse();
        assertThat(service.riskySymbols("BITHUMB")).doesNotContain("BB/KRW");
        service.applyNoticeChanges("BITHUMB", Set.of("BB/KRW"), Set.of());
        assertThat(service.risky("BITHUMB", "BB/KRW")).isTrue();
        assertThat(service.risky("UPBIT", "BONK/KRW")).isTrue();
    }
}

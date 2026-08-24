package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coin.arbitrage.config.ArbitrageProperties;
import com.coin.arbitrage.config.RiskProperties;
import com.coin.arbitrage.persistence.RiskSettingsEntity;
import com.coin.arbitrage.persistence.RiskSettingsRepository;
import com.coin.arbitrage.persistence.RiskProfileEntity;
import com.coin.arbitrage.persistence.RiskProfileRepository;
import com.coin.arbitrage.persistence.UserRiskSettingsRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskSettingsServiceTest {
    private RiskSettingsRepository repository;
    private RiskSettingsService service;
    private RiskProfileRepository profiles;
    private RiskSettingsEntity entity;

    @BeforeEach
    void setUp() {
        repository = mock(RiskSettingsRepository.class);
        profiles = mock(RiskProfileRepository.class);
        ArbitrageProperties defaults = new ArbitrageProperties(
                1_000_000_000, 0.1, 0.3, 20, 100_000, 1_000_000,
                500_000, 3, 60, 10, 3,
                java.util.List.of("upbit", "bithumb", "coinone", "korbit"));
        entity = new RiskSettingsEntity(1L, 1_000_000_000, 0.1, 0.3, 100, 20,
                100_000, 1_000_000, 500_000, 3, 60, "BALANCED");
        RiskProfileEntity user = new RiskProfileEntity("USER_1", 1_000_000_000, 0.1,
                0.3, 100, 20, 100_000, 1_000_000, 500_000, 3, 60);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(profiles.findById("USER_1")).thenReturn(Optional.of(user));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UserRiskSettingsRepository userSettings = mock(UserRiskSettingsRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.findAll()).thenReturn(java.util.List.of());
        service = new RiskSettingsService(repository, profiles, defaults, new RiskProperties(100), userSettings, users);
        service.initialize();
    }

    @Test
    void validSettingsAreAppliedImmediately() {
        RiskSettingsService.Settings updated = service.update(new RiskSettingsService.Settings(
                2_000_000_000, 0.12, 0.5, 250, 15, 200_000, 2_000_000,
                300_000, 2, 120, "USER_1", null));

        assertThat(updated.minProfitPercent()).isEqualTo(0.5);
        assertThat(service.get().orderAmountKrw()).isEqualTo(200_000);
        assertThat(service.get().profileName()).isEqualTo("USER_1");
        assertThat(entity.getOpportunityCooldownSeconds()).isEqualTo(120);
    }

    @Test
    void builtInPresetUsesServerDefinedValues() {
        RiskSettingsService.Settings updated = service.update(new RiskSettingsService.Settings(
                1, 4, 99, 1, 100, 5_000, 5_000,
                0, 20, 0, "CONSERVATIVE", null));

        assertThat(updated.profileName()).isEqualTo("CONSERVATIVE");
        assertThat(updated.minQuoteVolume24h()).isEqualTo(5_000_000_000L);
        assertThat(updated.maxConcurrentPositions()).isEqualTo(1);
        assertThat(updated.opportunityCooldownSeconds()).isEqualTo(300);
    }

    @Test
    void unsafeOrderLimitIsRejected() {
        assertThatThrownBy(() -> service.update(new RiskSettingsService.Settings(
                1_000_000_000, 0.1, 0.3, 100, 20, 2_000_000, 1_000_000,
                500_000, 3, 60, "USER_1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1회 최대금액");
    }
}

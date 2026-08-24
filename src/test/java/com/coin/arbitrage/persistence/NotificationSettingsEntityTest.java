package com.coin.arbitrage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationSettingsEntityTest {
    @Test
    void sendsOnceForSameRebalanceRouteUntilImbalanceIsCleared() {
        NotificationSettingsEntity settings = new NotificationSettingsEntity(
                new UserAccountEntity("user", "hash", "User"));

        assertThat(settings.claimKrwRebalanceRoute("UPBIT->BITHUMB")).isTrue();
        assertThat(settings.claimKrwRebalanceRoute("UPBIT->BITHUMB")).isFalse();

        settings.clearKrwRebalanceRoute();

        assertThat(settings.claimKrwRebalanceRoute("UPBIT->BITHUMB")).isTrue();
    }

    @Test
    void sendsAgainWhenRecommendedDirectionChanges() {
        NotificationSettingsEntity settings = new NotificationSettingsEntity(
                new UserAccountEntity("user", "hash", "User"));

        assertThat(settings.claimKrwRebalanceRoute("UPBIT->BITHUMB")).isTrue();
        assertThat(settings.claimKrwRebalanceRoute("BITHUMB->UPBIT")).isTrue();
    }
}

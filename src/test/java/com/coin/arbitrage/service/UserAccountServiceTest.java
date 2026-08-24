package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import com.coin.arbitrage.persistence.InviteCodeRepository;
import com.coin.arbitrage.persistence.InviteCodeEntity;
import com.coin.arbitrage.persistence.PortfolioOnboardingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserAccountServiceTest {
    private UserAccountRepository repository;
    private UserAccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("encoded");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        InviteCodeRepository invites = mock(InviteCodeRepository.class);
        when(invites.findById("invite")).thenReturn(java.util.Optional.of(
                new InviteCodeEntity("invite", "admin", java.time.Instant.now().plusSeconds(60))));
        PortfolioOnboardingRepository onboarding = mock(PortfolioOnboardingRepository.class);
        when(onboarding.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new UserAccountService(repository, encoder, invites, onboarding);
    }

    @Test
    void registersNormalizedSingleUser() {
        UserAccountEntity user = service.register("Trader_01", "long-password", "사용자 1", "invite");
        assertThat(user.getUsername()).isEqualTo("trader_01");
        assertThat(user.getPasswordHash()).isEqualTo("encoded");
    }

    @Test
    void rejectsDuplicateUsername() {
        when(repository.existsByUsername("trader02")).thenReturn(true);
        assertThatThrownBy(() -> service.register("trader02", "long-password", "사용자 2", "invite"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 사용");
    }
}

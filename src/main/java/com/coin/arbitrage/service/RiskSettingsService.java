package com.coin.arbitrage.service;

import com.coin.arbitrage.config.ArbitrageProperties;
import com.coin.arbitrage.config.RiskProperties;
import com.coin.arbitrage.persistence.RiskSettingsEntity;
import com.coin.arbitrage.persistence.RiskSettingsRepository;
import com.coin.arbitrage.persistence.RiskProfileEntity;
import com.coin.arbitrage.persistence.RiskProfileRepository;
import com.coin.arbitrage.persistence.UserRiskSettingsEntity;
import com.coin.arbitrage.persistence.UserRiskSettingsRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskSettingsService {
    private static final long SETTINGS_ID = 1L;
    private final RiskSettingsRepository repository;
    private final RiskProfileRepository profiles;
    private final ArbitrageProperties defaults;
    private final RiskProperties riskDefaults;
    private final UserRiskSettingsRepository userSettings;
    private final UserAccountRepository users;
    private volatile Settings current;

    public RiskSettingsService(RiskSettingsRepository repository, RiskProfileRepository profiles,
                               ArbitrageProperties defaults, RiskProperties riskDefaults,
                               UserRiskSettingsRepository userSettings, UserAccountRepository users) {
        this.repository = repository;
        this.profiles = profiles;
        this.defaults = defaults;
        this.riskDefaults = riskDefaults;
        this.userSettings = userSettings;
        this.users = users;
    }

    @PostConstruct
    @Transactional
    public void initialize() {
        RiskProfileEntity userProfile = profiles.findById("USER_1").orElseGet(() -> profiles.save(new RiskProfileEntity(
                "USER_1", defaults.minQuoteVolume24h(), defaults.feePercent(),
                defaults.minProfitPercent(), riskDefaults.minExpectedProfitKrw(), defaults.maxProfitPercent(), defaults.orderAmountKrw(),
                defaults.maxOrderAmountKrw(), defaults.dailyMaxLossKrw(),
                defaults.maxConcurrentPositions(), defaults.opportunityCooldownSeconds())));
        if (userProfile.getMinExpectedProfitKrw() <= 0) {
            userProfile.update(userProfile.getMinQuoteVolume24h(), userProfile.getFeePercent(),
                    userProfile.getMinProfitPercent(), riskDefaults.minExpectedProfitKrw(), userProfile.getMaxProfitPercent(),
                    userProfile.getOrderAmountKrw(), userProfile.getMaxOrderAmountKrw(), userProfile.getDailyMaxLossKrw(),
                    userProfile.getMaxConcurrentPositions(), userProfile.getOpportunityCooldownSeconds());
            profiles.save(userProfile);
        }
        RiskSettingsEntity entity = repository.findById(SETTINGS_ID).orElseGet(() -> repository.save(
                new RiskSettingsEntity(SETTINGS_ID, defaults.minQuoteVolume24h(), defaults.feePercent(),
                        defaults.minProfitPercent(), riskDefaults.minExpectedProfitKrw(), defaults.maxProfitPercent(), defaults.orderAmountKrw(),
                        defaults.maxOrderAmountKrw(), defaults.dailyMaxLossKrw(),
                        defaults.maxConcurrentPositions(), defaults.opportunityCooldownSeconds(), "BALANCED")));
        if (entity.getStrategyVersion() < 4) {
            Settings automated = builtin("BALANCED");
            entity.update(automated.minQuoteVolume24h(), automated.feePercent(), automated.minProfitPercent(),
                    automated.minExpectedProfitKrw(), automated.maxProfitPercent(), automated.orderAmountKrw(),
                    automated.maxOrderAmountKrw(), automated.dailyMaxLossKrw(), automated.maxConcurrentPositions(),
                    automated.opportunityCooldownSeconds(), automated.profileName());
            entity.markStrategyVersion(4);
            repository.save(entity);
        }
        if (entity.getMinExpectedProfitKrw() <= 0) {
            entity.update(entity.getMinQuoteVolume24h(), entity.getFeePercent(), entity.getMinProfitPercent(),
                    riskDefaults.minExpectedProfitKrw(), entity.getMaxProfitPercent(), entity.getOrderAmountKrw(),
                    entity.getMaxOrderAmountKrw(), entity.getDailyMaxLossKrw(), entity.getMaxConcurrentPositions(),
                    entity.getOpportunityCooldownSeconds(), entity.getProfileName());
            repository.save(entity);
        }
        current = toSettings(entity);
        users.findAll().forEach(user -> userSettings.findByUserUsername(user.getUsername()).orElseGet(() -> {
            UserRiskSettingsEntity row = new UserRiskSettingsEntity(user);
            copy(row, current);
            return userSettings.save(row);
        }));
    }

    public Settings get() {
        return current;
    }
    public Settings scanSettings() {
        List<Settings> rows = userSettings.findAll().stream().map(RiskSettingsService::toSettings).toList();
        if (rows.isEmpty()) return current;
        return new Settings(rows.stream().mapToLong(Settings::minQuoteVolume24h).min().orElse(current.minQuoteVolume24h()),
                current.feePercent(), rows.stream().mapToDouble(Settings::minProfitPercent).min().orElse(current.minProfitPercent()),
                rows.stream().mapToDouble(Settings::minExpectedProfitKrw).min().orElse(current.minExpectedProfitKrw()),
                rows.stream().mapToDouble(Settings::maxProfitPercent).max().orElse(current.maxProfitPercent()),
                rows.stream().mapToLong(Settings::orderAmountKrw).min().orElse(5000),
                rows.stream().mapToLong(Settings::maxOrderAmountKrw).max().orElse(5000),
                current.dailyMaxLossKrw(), 1, current.opportunityCooldownSeconds(), "MULTI_USER_SCAN", null);
    }

    @Transactional
    public Settings get(String username) { return toSettings(getOrCreate(username)); }

    @Transactional
    public Settings update(String username, Settings requested) {
        Settings effective = normalizeProfile(requested); validate(effective);
        UserRiskSettingsEntity entity = getOrCreate(username); copy(entity, effective);
        return toSettings(userSettings.saveAndFlush(entity));
    }

    public List<Preset> presets(String username) {
        return List.of(new Preset("CONSERVATIVE", "소극적", "높은 거래대금과 긴 쿨다운", builtin("CONSERVATIVE")),
                new Preset("BALANCED", "중간", "수익 기회와 안전성의 균형", builtin("BALANCED")),
                new Preset("AGGRESSIVE", "공격적", "더 많은 기회와 큰 변동 허용", builtin("AGGRESSIVE")),
                new Preset("USER_1", "내 설정", "이 계정만의 독립 설정", get(username)));
    }

    private UserRiskSettingsEntity getOrCreate(String username) {
        return userSettings.findByUserUsername(username).orElseGet(() -> {
            UserRiskSettingsEntity row = new UserRiskSettingsEntity(users.findByUsername(username).orElseThrow());
            copy(row, builtin("CONSERVATIVE")); return userSettings.save(row);
        });
    }

    private static void copy(UserRiskSettingsEntity row, Settings value) {
        row.update(value.minQuoteVolume24h(), value.feePercent(), value.minProfitPercent(), value.minExpectedProfitKrw(),
                value.maxProfitPercent(), value.orderAmountKrw(), value.maxOrderAmountKrw(), value.dailyMaxLossKrw(),
                value.maxConcurrentPositions(), value.opportunityCooldownSeconds(), value.profileName());
    }

    private static Settings toSettings(UserRiskSettingsEntity value) {
        return new Settings(value.getMinQuoteVolume24h(), value.getFeePercent(), value.getMinProfitPercent(),
                value.getMinExpectedProfitKrw(), value.getMaxProfitPercent(), value.getOrderAmountKrw(),
                value.getMaxOrderAmountKrw(), value.getDailyMaxLossKrw(), value.getMaxConcurrentPositions(),
                value.getOpportunityCooldownSeconds(), value.getProfileName(), value.getUpdatedAt());
    }

    @Transactional
    public synchronized Settings update(Settings requested) {
        Settings effective = normalizeProfile(requested);
        validate(effective);
        if ("USER_1".equals(effective.profileName())) {
            RiskProfileEntity userProfile = profiles.findById("USER_1").orElseThrow();
            userProfile.update(effective.minQuoteVolume24h(), effective.feePercent(),
                    effective.minProfitPercent(), effective.minExpectedProfitKrw(), effective.maxProfitPercent(), effective.orderAmountKrw(),
                    effective.maxOrderAmountKrw(), effective.dailyMaxLossKrw(),
                    effective.maxConcurrentPositions(), effective.opportunityCooldownSeconds());
            profiles.save(userProfile);
        }
        RiskSettingsEntity entity = repository.findById(SETTINGS_ID).orElseThrow();
        entity.update(effective.minQuoteVolume24h(), effective.feePercent(),
                effective.minProfitPercent(), effective.minExpectedProfitKrw(), effective.maxProfitPercent(), effective.orderAmountKrw(),
                effective.maxOrderAmountKrw(), effective.dailyMaxLossKrw(),
                effective.maxConcurrentPositions(), effective.opportunityCooldownSeconds(),
                effective.profileName());
        repository.saveAndFlush(entity);
        current = toSettings(entity);
        return current;
    }

    public List<Preset> presets() {
        RiskProfileEntity user = profiles.findById("USER_1").orElseThrow();
        return List.of(
                new Preset("CONSERVATIVE", "소극적", "높은 거래대금과 긴 쿨다운", builtin("CONSERVATIVE")),
                new Preset("BALANCED", "중간", "수익 기회와 안전성의 균형", builtin("BALANCED")),
                new Preset("AGGRESSIVE", "공격적", "더 많은 기회와 큰 변동 허용", builtin("AGGRESSIVE")),
                new Preset("USER_1", "사용자 1", "직접 조정해 저장한 나의 설정", toSettings(user))
        );
    }

    private Settings normalizeProfile(Settings requested) {
        String profile = requested.profileName() == null ? "USER_1" : requested.profileName().toUpperCase();
        if (List.of("CONSERVATIVE", "BALANCED", "AGGRESSIVE").contains(profile)) {
            return builtin(profile);
        }
        if (!"USER_1".equals(profile)) {
            throw new IllegalArgumentException("지원하지 않는 리스크 프로필입니다.");
        }
        return new Settings(requested.minQuoteVolume24h(), requested.feePercent(),
                requested.minProfitPercent(), requested.minExpectedProfitKrw(), requested.maxProfitPercent(), requested.orderAmountKrw(),
                requested.maxOrderAmountKrw(), requested.dailyMaxLossKrw(),
                requested.maxConcurrentPositions(), requested.opportunityCooldownSeconds(), "USER_1", null);
    }

    private static Settings builtin(String profile) {
        return switch (profile) {
            case "CONSERVATIVE" -> new Settings(5_000_000_000L, 0.1, 0.7, 50, 10,
                    5_000, 10_000, 2_000, 1, 300, profile, null);
            case "AGGRESSIVE" -> new Settings(500_000_000L, 0.1, 0.2, 10, 30,
                    10_000, 30_000, 10_000, 5, 15, profile, null);
            default -> new Settings(1_000_000_000L, 0.1, 0.8, 40, 3,
                    5_000, 5_000, 1_000, 1, 120, "BALANCED", null);
        };
    }

    private static void validate(Settings value) {
        if (value.minQuoteVolume24h() < 0 || value.minQuoteVolume24h() > 1_000_000_000_000_000L)
            throw new IllegalArgumentException("최소 거래대금은 0원 이상 1,000조원 이하여야 합니다.");
        if (!finite(value.feePercent()) || value.feePercent() < 0 || value.feePercent() > 5)
            throw new IllegalArgumentException("수수료율은 0% 이상 5% 이하여야 합니다.");
        if (!finite(value.minProfitPercent()) || value.minProfitPercent() < 0 || value.minProfitPercent() > 100)
            throw new IllegalArgumentException("최소 순수익률은 0% 이상 100% 이하여야 합니다.");
        if (!finite(value.minExpectedProfitKrw()) || value.minExpectedProfitKrw() < 0
                || value.minExpectedProfitKrw() > 100_000_000)
            throw new IllegalArgumentException("최소 예상수익은 0원 이상 1억원 이하여야 합니다.");
        if (!finite(value.maxProfitPercent()) || value.maxProfitPercent() <= value.minProfitPercent()
                || value.maxProfitPercent() > 1_000)
            throw new IllegalArgumentException("이상치 상한은 최소 수익률보다 크고 1,000% 이하여야 합니다.");
        if (value.orderAmountKrw() < 5_000)
            throw new IllegalArgumentException("기회 계산 금액은 최소 5,000원이어야 합니다.");
        if (value.maxOrderAmountKrw() < value.orderAmountKrw() || value.maxOrderAmountKrw() > 100_000_000)
            throw new IllegalArgumentException("1회 최대금액은 주문금액 이상 1억원 이하여야 합니다.");
        if (value.dailyMaxLossKrw() < 0 || value.dailyMaxLossKrw() > 1_000_000_000)
            throw new IllegalArgumentException("일일 최대손실은 0원 이상 10억원 이하여야 합니다.");
        if (value.maxConcurrentPositions() < 1 || value.maxConcurrentPositions() > 20)
            throw new IllegalArgumentException("최대 동시 포지션은 1개 이상 20개 이하여야 합니다.");
        if (value.opportunityCooldownSeconds() < 0 || value.opportunityCooldownSeconds() > 86_400)
            throw new IllegalArgumentException("쿨다운은 0초 이상 86,400초 이하여야 합니다.");
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static Settings toSettings(RiskSettingsEntity value) {
        return new Settings(value.getMinQuoteVolume24h(), value.getFeePercent(),
                value.getMinProfitPercent(), value.getMinExpectedProfitKrw(), value.getMaxProfitPercent(), value.getOrderAmountKrw(),
                value.getMaxOrderAmountKrw(), value.getDailyMaxLossKrw(),
                value.getMaxConcurrentPositions(), value.getOpportunityCooldownSeconds(),
                value.getProfileName(), value.getUpdatedAt());
    }

    private static Settings toSettings(RiskProfileEntity value) {
        return new Settings(value.getMinQuoteVolume24h(), value.getFeePercent(),
                value.getMinProfitPercent(), value.getMinExpectedProfitKrw(), value.getMaxProfitPercent(), value.getOrderAmountKrw(),
                value.getMaxOrderAmountKrw(), value.getDailyMaxLossKrw(),
                value.getMaxConcurrentPositions(), value.getOpportunityCooldownSeconds(),
                value.getProfileName(), value.getUpdatedAt());
    }

    public record Settings(long minQuoteVolume24h, double feePercent, double minProfitPercent,
                           double minExpectedProfitKrw, double maxProfitPercent, long orderAmountKrw, long maxOrderAmountKrw,
                           long dailyMaxLossKrw, int maxConcurrentPositions,
                           long opportunityCooldownSeconds, String profileName,
                           java.time.Instant updatedAt) {
    }

    public record Preset(String code, String name, String description, Settings settings) {
    }
}

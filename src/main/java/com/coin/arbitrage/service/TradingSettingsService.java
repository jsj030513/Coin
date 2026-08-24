package com.coin.arbitrage.service;

import com.coin.arbitrage.config.LiveTradingProperties;
import com.coin.arbitrage.persistence.TradingSettingsEntity;
import com.coin.arbitrage.persistence.TradingSettingsRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingSettingsService {
    private final TradingSettingsRepository settings;
    private final UserAccountRepository users;
    private final LiveTradingProperties liveTrading;

    public TradingSettingsService(TradingSettingsRepository settings, UserAccountRepository users,
                                  LiveTradingProperties liveTrading) {
        this.settings = settings;
        this.users = users;
        this.liveTrading = liveTrading;
    }

    @Transactional
    public Status status(String username) {
        TradingSettingsEntity entity = entity(username);
        return view(entity);
    }

    @Transactional
    public Status update(String username, boolean enabled) {
        if (enabled && (!liveTrading.enabled() || !liveTrading.autoEnabled())) {
            throw new IllegalStateException("서버의 실거래 자동주문 마스터 잠금이 켜져 있습니다.");
        }
        TradingSettingsEntity entity = entity(username);
        entity.setAutoTradingEnabled(enabled);
        return view(settings.saveAndFlush(entity));
    }

    @Transactional
    public Status emergencyStop(String username) {
        TradingSettingsEntity entity = entity(username);
        entity.setAutoTradingEnabled(false);
        return view(settings.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public boolean active(String username) {
        return liveTrading.enabled() && liveTrading.autoEnabled()
                && settings.findByUserUsername(username)
                .map(TradingSettingsEntity::isAutoTradingEnabled).orElse(false);
    }

    private TradingSettingsEntity entity(String username) {
        return settings.findByUserUsername(username).orElseGet(() -> settings.save(
                new TradingSettingsEntity(users.findByUsername(username)
                        .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다.")))));
    }

    private Status view(TradingSettingsEntity entity) {
        boolean masterEnabled = liveTrading.enabled() && liveTrading.autoEnabled();
        return new Status(masterEnabled, entity.isAutoTradingEnabled(),
                masterEnabled && entity.isAutoTradingEnabled(), entity.getUpdatedAt());
    }

    public record Status(boolean masterEnabled, boolean userEnabled, boolean active, Instant updatedAt) { }
}

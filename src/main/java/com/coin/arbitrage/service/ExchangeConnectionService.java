package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExchangeConnectionEntity;
import com.coin.arbitrage.persistence.ExchangeConnectionEntity.Exchange;
import com.coin.arbitrage.persistence.ExchangeConnectionRepository;
import com.coin.arbitrage.persistence.UserAccountEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExchangeConnectionService {
    private final ExchangeConnectionRepository connections;
    private final UserAccountService users;
    private final CredentialEncryptionService encryption;
    private final ExchangeConnectionVerifier verifier;
    private final FeeProvider feeProvider;
    private final TelegramNotificationService telegram;

    public ExchangeConnectionService(ExchangeConnectionRepository connections, UserAccountService users,
                                     CredentialEncryptionService encryption, ExchangeConnectionVerifier verifier,
                                     FeeProvider feeProvider, TelegramNotificationService telegram) {
        this.connections = connections;
        this.users = users;
        this.encryption = encryption;
        this.verifier = verifier;
        this.feeProvider = feeProvider;
        this.telegram = telegram;
    }

    public List<ConnectionView> list(String username) {
        var saved = connections.findByUserUsernameOrderByExchangeAsc(username);
        return Arrays.stream(Exchange.values()).map(exchange -> saved.stream()
                .filter(value -> value.getExchange() == exchange).findFirst()
                .map(this::view).orElse(new ConnectionView(exchange.name(), false, "NOT_CONNECTED", null,
                        null, null, 0, "UNKNOWN", "UNKNOWN", null, null, null, null))).toList();
    }

    @Transactional
    public ConnectionView saveAndVerify(String username, String exchangeName, String accessKey, String secretKey) {
        Exchange exchange = parse(exchangeName);
        if (accessKey == null || accessKey.isBlank() || accessKey.length() > 500)
            throw new IllegalArgumentException("유효한 API Key를 입력해 주세요.");
        if (secretKey == null || secretKey.isBlank() || secretKey.length() > 2000)
            throw new IllegalArgumentException("유효한 Secret Key를 입력해 주세요.");
        UserAccountEntity user = users.require(username);
        ExchangeConnectionEntity connection = connections.findByUserUsernameAndExchange(username, exchange)
                .orElseGet(() -> new ExchangeConnectionEntity(user, exchange, encryption.encrypt(accessKey.trim()),
                        encryption.encrypt(secretKey.trim()), fingerprint(accessKey)));
        if (connection.getId() != null) connection.updateCredentials(encryption.encrypt(accessKey.trim()),
                encryption.encrypt(secretKey.trim()), fingerprint(accessKey));
        connections.save(connection);
        applyVerification(connection, verifier.verify(exchange, accessKey.trim(), secretKey.trim()));
        return view(connections.save(connection));
    }

    @Transactional
    public ConnectionView verify(String username, String exchangeName) {
        Exchange exchange = parse(exchangeName);
        ExchangeConnectionEntity connection = connections.findByUserUsernameAndExchange(username, exchange)
                .orElseThrow(() -> new IllegalArgumentException("먼저 거래소 API 키를 등록해 주세요."));
        applyVerification(connection, verifier.verify(exchange, encryption.decrypt(connection.getEncryptedAccessKey()),
                encryption.decrypt(connection.getEncryptedSecretKey())));
        return view(connections.save(connection));
    }

    @Transactional
    public void delete(String username, String exchangeName) {
        Exchange exchange = parse(exchangeName);
        connections.findByUserUsernameAndExchange(username, exchange).ifPresent(connections::delete);
    }

    @Transactional
    public ConnectionView refreshFees(String username, String exchangeName) {
        Exchange exchange=parse(exchangeName);
        ExchangeConnectionEntity connection=connections.findByUserUsernameAndExchange(username,exchange)
                .filter(value -> value.getStatus()==ExchangeConnectionEntity.Status.VERIFIED)
                .orElseThrow(() -> new IllegalArgumentException("연결 확인된 거래소만 수수료를 분석할 수 있습니다."));
        ExchangeConnectionVerifier.Verification result=verifier.verify(exchange,
                encryption.decrypt(connection.getEncryptedAccessKey()), encryption.decrypt(connection.getEncryptedSecretKey()));
        if (!result.connected() || (result.buyFeePercent()==null && result.sellFeePercent()==null))
            throw new IllegalStateException("거래소가 현재 계정 수수료를 반환하지 않았습니다. 기존 값을 유지합니다.");
        observeFees(connection,result.buyFeePercent(),result.sellFeePercent());
        return view(connections.save(connection));
    }

    @Transactional
    public void observeFees(String username,String exchangeName,Double buyFee,Double sellFee) {
        connections.findByUserUsernameAndExchange(username,parse(exchangeName)).ifPresent(connection -> {
            observeFees(connection,buyFee,sellFee); connections.save(connection);
        });
    }

    private void observeFees(ExchangeConnectionEntity connection,Double buyFee,Double sellFee) {
        Double previousBuy=connection.getBuyFeePercent(), previousSell=connection.getSellFeePercent();
        boolean changed=connection.observeFees(buyFee,sellFee);
        feeProvider.override(connection.getExchange().name(),buyFee,sellFee);
        if (changed) telegram.notifyFeeChanged(connection.getUser().getUsername(),connection.getExchange().name(),
                previousBuy,previousSell,connection.getBuyFeePercent(),connection.getSellFeePercent());
    }

    private void applyVerification(ExchangeConnectionEntity connection,
                                   ExchangeConnectionVerifier.Verification result) {
        if (result.connected()) {
            Double previousBuy=connection.getBuyFeePercent(), previousSell=connection.getSellFeePercent();
            connection.verified(result.assetCount(), result.orderReadPermission().name(),
                    result.orderCreatePermission().name(), result.buyFeePercent(), result.sellFeePercent());
            feeProvider.override(connection.getExchange().name(), result.buyFeePercent(), result.sellFeePercent());
            boolean changed=previousBuy!=null && result.buyFeePercent()!=null && Math.abs(previousBuy-result.buyFeePercent())>0.0000001
                    || previousSell!=null && result.sellFeePercent()!=null && Math.abs(previousSell-result.sellFeePercent())>0.0000001;
            if (changed) telegram.notifyFeeChanged(connection.getUser().getUsername(),connection.getExchange().name(),
                    previousBuy,previousSell,connection.getBuyFeePercent(),connection.getSellFeePercent());
        }
        else connection.failed(result.message());
    }

    private ConnectionView view(ExchangeConnectionEntity value) {
        return new ConnectionView(value.getExchange().name(), true, value.getStatus().name(),
                value.getKeyFingerprint(), value.getLastVerifiedAt(), value.getLastError(), value.getAssetCount(),
                value.getOrderReadPermission(), value.getOrderCreatePermission(),
                value.getBuyFeePercent(), value.getSellFeePercent(),
                value.getFeeCheckedAt() == null ? value.getLastVerifiedAt() : value.getFeeCheckedAt(),
                value.getFeeChangedAt());
    }

    private static Exchange parse(String value) {
        try { return Exchange.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (Exception error) { throw new IllegalArgumentException("지원하지 않는 거래소입니다."); }
    }

    private static String fingerprint(String key) {
        String trimmed = key.trim();
        return "•••• " + trimmed.substring(Math.max(0, trimmed.length() - 4));
    }

    public record ConnectionView(String exchange, boolean registered, String status, String keyFingerprint,
                                 java.time.Instant lastVerifiedAt, String lastError, int assetCount,
                                 String orderReadPermission, String orderCreatePermission,
                                 Double buyFeePercent, Double sellFeePercent,
                                 java.time.Instant feeCheckedAt, java.time.Instant feeChangedAt) { }
}

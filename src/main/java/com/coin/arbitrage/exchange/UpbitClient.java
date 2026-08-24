package com.coin.arbitrage.exchange;

import com.coin.arbitrage.config.ArbitrageProperties;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.stereotype.Component;

@Component
public class UpbitClient extends UpbitStyleClient {
    public UpbitClient(HttpClient http, ObjectMapper mapper, ArbitrageProperties properties) {
        super("upbit", "https://api.upbit.com", http, mapper, properties);
    }
}

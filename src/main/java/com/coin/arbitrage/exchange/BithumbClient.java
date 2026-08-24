package com.coin.arbitrage.exchange;

import com.coin.arbitrage.config.ArbitrageProperties;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.stereotype.Component;

@Component
public class BithumbClient extends UpbitStyleClient {
    public BithumbClient(HttpClient http, ObjectMapper mapper, ArbitrageProperties properties) {
        super("bithumb", "https://api.bithumb.com", http, mapper, properties);
    }
}

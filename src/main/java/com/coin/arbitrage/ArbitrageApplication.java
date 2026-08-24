package com.coin.arbitrage;

import com.coin.arbitrage.config.ArbitrageProperties;
import com.coin.arbitrage.config.LiveTradingProperties;
import com.coin.arbitrage.config.RiskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ArbitrageProperties.class, RiskProperties.class, LiveTradingProperties.class})
public class ArbitrageApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArbitrageApplication.class, args);
    }
}

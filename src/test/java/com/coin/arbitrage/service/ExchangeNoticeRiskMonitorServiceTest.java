package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExchangeNoticeRiskMonitorServiceTest {
    @Test
    void extractsRiskSymbolsOnlyFromRiskNotices() {
        ExchangeNoticeRiskMonitorService service = new ExchangeNoticeRiskMonitorService(
                HttpClient.newHttpClient(), new ObjectMapper(), null,
                true, 1, 30, "https://example.test/%d/%d",
                false, "https://example.test/search?q=%s", "https://example.test");

        String body = """
                {"title":"[거래지원 종료] 봉크(BONK), 썬더코어(TT) 거래지원 종료 안내"}
                {"title":"[입출금 일시 중단] 솔라나(SOL) 네트워크 점검 안내"}
                {"title":"[투자유의] 미라(MIRA) 유의종목 지정 안내"}
                """;

        assertThat(service.riskySymbolsFromNoticeBody(body))
                .containsExactlyInAnyOrder("BONK/KRW", "TT/KRW", "MIRA/KRW");
    }
}

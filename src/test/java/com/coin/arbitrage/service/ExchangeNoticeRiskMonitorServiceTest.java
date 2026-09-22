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

    @Test
    void latestReleaseRemovesOnlyItsNamedSymbol() {
        ExchangeNoticeRiskMonitorService service = new ExchangeNoticeRiskMonitorService(
                HttpClient.newHttpClient(), new ObjectMapper(), null,
                true, 1, 30, "https://example.test/%d/%d",
                false, "https://example.test/search?q=%s", "https://example.test");
        String body = """
                [{"published_at":"2026-09-14 17:00:00","title":"바운스빗(BB) 거래유의종목 지정 해제"},
                 {"published_at":"2026-08-21 17:00:00","title":"바운스빗(BB), 갈라(GALA) 거래유의종목 지정"},
                 {"published_at":"2026-09-18 17:00:00","title":"달오픈네트워크(D), 훅트프로토콜(HOOK) 거래유의종목 지정"},
                 {"published_at":"2026-09-20 12:00:00","title":"페치(FET) 입출금 일시 중단 안내"}]
                """;
        ExchangeNoticeRiskMonitorService.NoticeChanges changes = service.noticeChanges(body);
        assertThat(changes.added()).containsExactlyInAnyOrder("GALA/KRW", "D/KRW", "HOOK/KRW");
        assertThat(changes.released()).containsExactly("BB/KRW");
    }
}

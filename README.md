# ARB//KOREA

업비트와 빗썸의 KRW 마켓을 실시간 비교하고, 호가 잔량·슬리피지·거래소별 수수료를 반영해 차익 기회를 찾는 개인용 Spring Boot 애플리케이션입니다.

## 현재 기능

- 업비트·빗썸 공통 KRW 마켓 자동 감시
- 호가창 기준 예상 체결가와 코인 1개당 예상 수익 계산
- 실제 거래소 잔고 조회
- 신규 코인 동일 금액 수동 매수 준비 화면
- 사용자별 자동 차익거래 ON/OFF
- 실제 주문 기록 저장
- 주문 기록 페이지네이션 및 코인·거래소·매수/매도·주문 유형 필터
- 시장 스캔·거래소 연결·자동거래·최근 주문·서버 가동시간 상태판
- 모든 주요 화면의 자동거래 비상 정지 버튼
- 로그인 5회 실패 시 15분 잠금 및 비밀번호 변경·복구 시 기존 세션 만료
- 텔레그램 일회용 코드를 이용한 아이디·비밀번호 복구
- KRW 불균형·주문 장애 텔레그램 알림

입금·출금 권한과 자동 이체 기능은 사용하지 않습니다.

## 로컬 실행

```bash
./mvnw test
./mvnw spring-boot:run
```

브라우저에서 `http://localhost:8080`에 접속합니다.

## 주문 안전장치

실제 주문은 아래 세 조건을 모두 만족해야 실행됩니다.

1. 서버 환경의 `LIVE_TRADING_ENABLED=true`
2. 서버 환경의 `AUTO_TRADING_ENABLED=true`
3. 로그인 후 메인 화면에서 `자동 차익거래 켜기`

신규 코인 수동 매수는 `LIVE_SEED_BUY_ENABLED=true`일 때만 `/live-orders` 버튼으로 실행됩니다. 자동 차익거래는 매수 거래소의 KRW와 매도 거래소의 같은 코인 수량이 모두 충분할 때만 주문합니다. 매수 성공 후 매도 요청이 실패하면 사용자 자동거래를 즉시 끄고 텔레그램으로 알립니다.

API 키에는 `자산조회`, `주문조회`, `주문하기`만 허용합니다. 입금·출금 관련 권한은 부여하지 않습니다.

## Docker 배포

서버는 고정 공인 IPv4를 사용하는 것이 좋습니다. 해당 IP를 업비트와 빗썸 API 허용 IP에 먼저 등록해야 합니다.

```bash
docker compose build
docker compose up -d
docker compose logs -f app
```

`data/`에는 로그인 계정, 암호화된 거래소 연결 정보, 주문 기록이 저장되므로 반드시 백업합니다. `.env`와 `data/account-credentials.key`는 Git에 올리지 않습니다.

처음 배포할 때는 세 주문 환경변수를 모두 `false`로 시작합니다. `/accounts`와 `/my`에서 양쪽 연결 및 실제 잔고를 확인한 뒤 필요한 스위치만 활성화하고 컨테이너를 재시작합니다.

```bash
docker compose up -d --force-recreate app
```

외부 공개 시에는 8080 포트를 직접 노출하지 말고 HTTPS 리버스 프록시와 방화벽을 앞에 둡니다. 배포 대상 서버의 주소·SSH 접속 정보·도메인이 준비되면 실제 서버 업로드와 HTTPS 연결을 이어서 진행할 수 있습니다.

## 주요 설정

| 환경변수 | 기본값 | 설명 |
|---|---:|---|
| `ENABLED_EXCHANGES` | `upbit,bithumb` | 감시 거래소 |
| `SCAN_INTERVAL_MS` | `3000` | 시장 스캔 간격 |
| `MIN_QUOTE_VOLUME_24H` | `1000000000` | 최소 24시간 거래대금 |
| `MIN_PROFIT_PERCENT` | `0.3` | 최소 순수익률 |
| `MIN_EXPECTED_PROFIT_KRW` | `100` | 주문금액 기준 최소 예상 수익 |
| `ORDER_AMOUNT_KRW` | `5000` | 호가 검증 기준 금액 |
| `LIVE_MIN_ORDER_KRW` | `5000` | 실제 주문 최소 금액 |
| `LIVE_MAX_ORDER_KRW` | `12000` | 실제 주문 및 초기매수 상한 |
| `LIVE_INVENTORY_DUST_FLOOR_KRW` | `6000` | 자동매도 후 반드시 남겨야 할 코인 평가액 |
| `LIVE_INVENTORY_MAX_IMBALANCE_PERCENT` | `45` | 거래 후 허용할 최대 재고 불균형; 초과 시 개선 방향만 허용 |
| `LIVE_INVENTORY_REBALANCE_MAX_COST_PERCENT` | `2.0` | 심한 수량 불균형 자동복원 시 허용할 최대 비용률 |
| `LIVE_CYCLE_TIMEOUT_SECONDS` | `120` | 미완료 주문 사이클 비상정지 시간 |
| `LIVE_ORDER_COOLDOWN_SECONDS` | `600` | 같은 코인·방향 재주문 대기시간 |
| `LIVE_MAX_ORDERS_PER_RUN` | `1` | 한 번의 실행당 최대 주문 쌍 |

## 데이터

- `arbitrage_opportunities`: 탐지 이력
- `live_orders`: 앱이 전송한 실제 주문
- `app_users`: 로그인 계정
- `exchange_connections`: 암호화된 거래소 키
- `trading_settings`: 사용자별 자동거래 스위치
- `notification_settings`: 사용자별 텔레그램 설정

실제 보유 금액은 DB에 복제하지 않고 `/my`를 열 때 거래소 API에서 조회합니다.

const $ = selector => document.querySelector(selector);
const won = new Intl.NumberFormat('ko-KR', {maximumFractionDigits:0});
const decimal = new Intl.NumberFormat('ko-KR', {maximumFractionDigits:10});
const percent = new Intl.NumberFormat('ko-KR', {minimumFractionDigits:2, maximumFractionDigits:4});
let balances = [];
let orderPage = 0;
let orderPageState = {first:true, last:true, totalPages:0};
let latestPortfolioSummary = null;
let latestCapitalSummary = null;

const formatWon = value => `${won.format(Number(value || 0))}원`;
const formatPercent = value => `${percent.format(Number(value || 0))}%`;
const profitClass = value => Number(value || 0) >= 0 ? 'positive' : 'negative';
const formatCount = value => `${won.format(Number(value || 0))}회`;

function renderBalances(rows, summary) {
    latestPortfolioSummary = summary;
    const query = ($('#assetSearch').value || '').trim().toUpperCase().replace('/KRW', '');
    const grouped = {UPBIT:{krw:0, assets:[]}, BITHUMB:{krw:0, assets:[]}};
    const holdings = new Map();
    rows.forEach(item => {
        const [exchange, asset] = String(item.currency).toUpperCase().split(':');
        if (!grouped[exchange] || !asset) return;
        if (asset === 'KRW') grouped[exchange].krw = Number(item.amount || 0);
        else if (Number(item.amount) > 0) {
            grouped[exchange].assets.push({asset, amount:Number(item.amount)});
            holdings.set(asset, (holdings.get(asset) || 0) + Number(item.amount));
        }
    });
    const filtered = list => query ? list.filter(item => item.asset.includes(query)) : list;
    const rowsHtml = list => filtered(list).length ? filtered(list).sort((a,b) => a.asset.localeCompare(b.asset))
        .map(item => `<div class="wallet-asset-row"><span>${item.asset}</span><strong>${decimal.format(item.amount)}</strong></div>`).join('')
        : '<p class="empty">보유 코인이 없습니다.</p>';

    for (const exchange of ['UPBIT','BITHUMB']) {
        const card = document.querySelector(`[data-wallet="${exchange}"]`);
        card.querySelector('[data-wallet-krw]').textContent = formatWon(grouped[exchange].krw);
        card.querySelector('[data-wallet-count]').textContent = `코인 ${filtered(grouped[exchange].assets).length}종`;
        card.querySelector('[data-wallet-updated]').textContent = '실제 API 조회';
        card.querySelector('[data-wallet-assets]').innerHTML = rowsHtml(grouped[exchange].assets);
    }
    const all = [...holdings].map(([asset, amount]) => ({asset, amount}));
    const allCard = document.querySelector('[data-wallet="HOLDINGS"]');
    allCard.querySelector('[data-wallet-krw]').textContent = formatWon(grouped.UPBIT.krw + grouped.BITHUMB.krw);
    allCard.querySelector('[data-wallet-count]').textContent = `코인 ${filtered(all).length}종`;
    allCard.querySelector('[data-wallet-assets]').innerHTML = rowsHtml(all);

    $('#currentBalance').textContent = formatWon(summary.currentPortfolioValueKrw);
    $('#balanceDelta').textContent = `현금 ${formatWon(summary.currentKrwBalance)} + 코인 평가 ${formatWon(summary.currentAssetValueKrw)}`;
    renderEquityGap();
    renderLookup(query, grouped);
}

function renderEquityGap() {
    if (!latestPortfolioSummary || !latestCapitalSummary) return;
    const equity = Number(latestPortfolioSummary.currentPortfolioValueKrw || 0);
    const principal = Number(latestCapitalSummary.principalKrw || 0);
    const gap = equity - principal;
    const gapText = gap >= 0 ? `원금 대비 +${won.format(gap)}원` : `원금 대비 -${won.format(Math.abs(gap))}원`;
    $('#balanceDelta').textContent = `현금 ${formatWon(latestPortfolioSummary.currentKrwBalance)} + 코인 평가 ${formatWon(latestPortfolioSummary.currentAssetValueKrw)} · ${gapText}`;
}

function renderLookup(query, grouped) {
    const panel = $('#assetLookup');
    panel.hidden = !query;
    if (!query) return;
    panel.innerHTML = `<div class="lookup-title"><span>검색 코인</span><strong>${query}</strong><small>거래소별 실제 보유수량</small></div>`
        + ['UPBIT','BITHUMB'].map(exchange => {
            const item = grouped[exchange].assets.find(asset => asset.asset === query);
            return `<div class="lookup-exchange"><span>${exchange}</span><strong>${decimal.format(item?.amount || 0)}</strong><small>${item ? '보유 중' : '보유 없음'}</small></div>`;
        }).join('');
}

async function loadPortfolio() {
    const response = await fetch('/api/portfolio', {cache:'no-store'});
    if (!response.ok) throw new Error('실제 자산을 불러오지 못했습니다.');
    const data = await response.json();
    balances = data.balances || [];
    renderBalances(balances, data.summary);
}

async function loadOrders() {
    const params = new URLSearchParams({page:orderPage, size:20});
    const filters = {
        symbol: $('#orderSymbolFilter').value,
        exchange: $('#orderExchangeFilter').value,
        side: $('#orderSideFilter').value,
        source: $('#orderSourceFilter').value
    };
    Object.entries(filters).forEach(([key, value]) => { if (value.trim()) params.set(key, value.trim()); });
    const response = await fetch(`/api/live-order-history?${params}`, {cache:'no-store'});
    if (!response.ok) throw new Error('주문 기록을 불러오지 못했습니다.');
    const result = await response.json();
    const orders = result.content || [];
    orderPageState = result;
    $('#liveOrderCount').textContent = `전체 ${won.format(result.totalElements)}건`;
    $('#orderPageLabel').textContent = result.totalPages ? `${result.page + 1} / ${result.totalPages}` : '0 / 0';
    $('#previousOrderPage').disabled = result.first;
    $('#nextOrderPage').disabled = result.last;
    $('#liveOrderRows').innerHTML = orders.length ? orders.map((order, index) => `<tr>
        <td>${result.page * result.size + index + 1}</td><td>${new Date(order.createdAt).toLocaleString('ko-KR')}</td><td>${order.exchange}</td>
        <td><span class="symbol">${order.symbol}</span></td><td>${order.side === 'BUY' ? '매수' : '매도'}</td>
        <td>${formatWon(order.requestedKrw)}</td><td>${decimal.format(Number(order.quantity || 0))}</td><td>${order.status}</td><td class="muted">${order.orderId}</td>
    </tr>`).join('') : '<tr><td colspan="9" class="empty">앱에서 실행한 실제 주문이 없습니다.</td></tr>';
}

async function loadTradeCycles() {
    const response = await fetch('/api/trade-cycles', {cache:'no-store'});
    if (!response.ok) throw new Error('실현손익을 불러오지 못했습니다.');
    const result = await response.json();
    $('#todayRealizedProfit').textContent = formatWon(result.todayProfitKrw);
    $('#todayRealizedProfit').className = profitClass(result.todayProfitKrw);
    $('#totalRealizedProfit').textContent = formatWon(result.realizedProfitKrw);
    $('#totalRealizedProfit').className = profitClass(result.realizedProfitKrw);
    renderCapitalMetrics(result);
    const rows = result.cycles || [];
    $('#tradeCycleRows').innerHTML = rows.length ? rows.map(row => `<tr>
        <td>${new Date(row.createdAt).toLocaleString('ko-KR')}</td><td><span class="symbol">${row.symbol}</span></td>
        <td>${row.buyExchange} → ${row.sellExchange}</td><td>${formatWon(row.requestedKrw)}</td>
        <td>${formatWon(row.expectedProfitKrw)}</td><td class="${profitClass(row.realizedProfitKrw)}">${row.realizedProfitKrw == null ? '체결 확인 중' : formatWon(row.realizedProfitKrw)}</td>
        <td>${row.status}</td><td class="muted">${row.detail || '—'}</td>
    </tr>`).join('') : '<tr><td colspan="8" class="empty">완료된 차익거래가 없습니다.</td></tr>';
}

function renderCapitalMetrics(result) {
    latestCapitalSummary = result;
    $('#principalKrw').textContent = formatWon(result.principalKrw);
    $('#netProfitKrw').textContent = formatWon(result.netProfitKrw);
    $('#netProfitKrw').className = profitClass(result.netProfitKrw);
    $('#netProfitDetail').textContent = `누적 거래손익 ${formatWon(result.realizedProfitKrw)} - 수수료 ${formatWon(result.externalFeeKrw)}`;
    $('#returnPercent').textContent = formatPercent(result.returnPercent);
    $('#returnPercent').className = profitClass(result.returnPercent);
    $('#todayNetProfitKrw').textContent = formatWon(result.todayNetProfitKrw);
    $('#todayNetProfitKrw').className = profitClass(result.todayNetProfitKrw);
    $('#externalFeeCount').textContent = formatCount(result.externalFeeCount);
    $('#externalFeeKrw').textContent = `누적 ${formatWon(result.externalFeeKrw)} · 1,000원 기준 ${formatCount(result.bankTransferFeeEquivalentCount)}`;
    renderEquityGap();
}

async function loadOperationStatus() {
    const response = await fetch('/api/operation-status', {cache:'no-store'});
    if (!response.ok) throw new Error('자동거래 상태를 불러오지 못했습니다.');
    renderOperationStatus(await response.json());
}

function renderOperationStatus(status) {
    const badge = $('#operationBadge');
    badge.textContent = status.label || '확인 중';
    badge.className = `operation-badge ${status.severity || 'muted'}`;
    $('#operationLabel').textContent = status.label || '—';
    $('#operationMessage').textContent = status.message || '상태 메시지가 없습니다.';
    $('#operationEquityGap').textContent = signedWon(status.equityGapKrw);
    $('#operationEquityGap').className = profitClass(status.equityGapKrw);
    $('#operationEquity').textContent = `평가액 ${formatWon(status.equityKrw)} / 원금 ${formatWon(status.principalKrw)}`;
    $('#operationExecutable').textContent = `${won.format(Number(status.executableCandidates || 0))}개`;
    $('#operationBlocked').textContent = `원화 부족 ${won.format(Number(status.krwShortageCandidates || 0))}개 · 코인 부족 ${won.format(Number(status.coinShortageCandidates || 0))}개`;
    const seven = status.sevenDay || {};
    $('#operationSevenDay').textContent = formatWon(seven.netProfitKrw);
    $('#operationSevenDay').className = profitClass(seven.netProfitKrw);
    $('#operationVerdict').textContent = `${salesLabel(status.salesVerdict?.code)} · 완료 ${won.format(Number(seven.completedCycles || 0))}건 · 실패율 ${Number(seven.failureRatePercent || 0).toFixed(2)}%`;
    $('#operationAction').textContent = actionText(status);
    $('#operationAction').className = `operation-action ${status.severity || 'muted'}`;
}

function signedWon(value) {
    const number = Number(value || 0);
    return number >= 0 ? `+${formatWon(number)}` : `-${formatWon(Math.abs(number))}`;
}

function salesLabel(code) {
    if (code === 'PASS') return '판매 검토 가능';
    if (code === 'HOLD') return '판매 보류';
    return '관찰 필요';
}

function actionText(status) {
    const code = status.code;
    if (code === 'READY') return '사용자 조치 없음: 기준을 통과하는 기회가 유지되면 자동 주문을 시도합니다.';
    if (code === 'WAITING_MARKET') return '사용자 조치 없음: 시장 가격차가 수수료와 안전 기준을 이길 때까지 기다립니다.';
    if (code === 'PRINCIPAL_PROTECTION') return '추가 입금/신규 매수 금지: 원금 회복 전까지 기존 보유분으로만 운용합니다.';
    if (code === 'KRW_SHORTAGE') {
        const rebalance = status.krwRebalance;
        if (rebalance?.alertEligible) {
            return `${rebalance.fromExchange}(${rebalance.fromBank}) → ${rebalance.toExchange}(${rebalance.toBank}) ${formatWon(rebalance.transferKrw)} 이동 추천 가능. 텔레그램 알림 기준과 같습니다.`;
        }
        return '원화 부족 상태입니다. 자동 원화 확보가 더 유리하면 프로그램이 먼저 시도하고, 직접 이동이 유리할 때만 텔레그램으로 안내합니다.';
    }
    if (code === 'COIN_SHORTAGE') return '매도 재고 부족 상태입니다. 원금 방어 중이 아니면 프로그램이 최소 5,000원 이상 매도 가능하도록 재고 확보를 검토합니다.';
    if (code === 'ORDER_STUCK') return '체결 확인 지연입니다. 거래소 주문 내역 확인 후 필요하면 비상정지/복구가 필요합니다.';
    if (code === 'USER_STOPPED') return '사용자 자동매매 스위치를 켜야 운용됩니다.';
    if (code === 'MASTER_LOCKED') return '서버 자동매매 마스터 잠금이 꺼져 있어 배포 환경변수 확인이 필요합니다.';
    if (code === 'API_DISCONNECTED' || code === 'API_ERROR') return '거래소 API 키/권한/IP 허용/잔고 조회 권한을 확인해야 합니다.';
    return status.salesVerdict?.message || '현재 금액으로 관찰하는 것이 안전합니다.';
}

async function recordCapital(kind, amount) {
    const response = await fetch(`/api/capital/${kind}`, {
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body:JSON.stringify({amountKrw:Number(amount)})
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || '기록에 실패했습니다.');
    $('#capitalStatus').textContent = kind === 'deposits'
        ? `원금 기록 완료 · 현재 총 원금 ${formatWon(result.principalKrw)}`
        : `수수료 기록 완료 · 오늘 수수료 ${formatWon(result.todayExternalFeeKrw)} · 누적 ${formatCount(result.externalFeeCount)}`;
    await loadTradeCycles();
}

$('#orderFilters').addEventListener('submit', event => {
    event.preventDefault(); orderPage = 0; loadOrders().catch(error => $('#liveOrderCount').textContent = error.message);
});
$('#resetOrderFilters').addEventListener('click', () => {
    $('#orderFilters').reset(); orderPage = 0; loadOrders().catch(error => $('#liveOrderCount').textContent = error.message);
});
$('#previousOrderPage').addEventListener('click', () => {
    if (orderPageState.first) return; orderPage -= 1; loadOrders();
});
$('#nextOrderPage').addEventListener('click', () => {
    if (orderPageState.last) return; orderPage += 1; loadOrders();
});

$('#requestLiquidation').addEventListener('click', async event => {
    if (!confirm('업비트·빗썸의 모든 보유 코인에 대해 텔레그램 전체매도 승인을 요청할까요? 아직 매도는 실행되지 않습니다.')) return;
    const button = event.currentTarget;
    button.disabled = true;
    try {
        const response = await fetch('/api/trade-approvals/liquidate-all', {method:'POST'});
        const result = await response.json();
        if (!response.ok) throw new Error(result.message || '승인 요청을 만들지 못했습니다.');
        button.textContent = `텔레그램 확인 · ${result.itemCount}개 자산`;
    } catch (error) {
        button.textContent = error.message;
    } finally {
        window.setTimeout(() => { button.disabled = false; button.textContent = '텔레그램 전체매도 승인 요청'; }, 5000);
    }
});

$('#withdrawAccount').addEventListener('click', async () => {
    if (!confirm('자동매매를 정지하고 계정을 탈퇴하시겠습니까? 이 작업 후 로그인할 수 없습니다.')) return;
    const phrase = prompt('확인을 위해 "회원탈퇴"를 입력하세요.');
    if (phrase !== '회원탈퇴') return;
    const password = prompt('현재 비밀번호를 입력하세요.');
    if (!password) return;
    const response = await fetch('/api/auth/withdraw', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({password})});
    const result = await response.json();
    if (!response.ok) return alert(result.error || '탈퇴 처리에 실패했습니다.');
    alert(result.message); location.href='/login?logout';
});

$('#depositForm').addEventListener('submit', async event => {
    event.preventDefault();
    const amount = $('#depositAmount').value;
    if (!amount || Number(amount) <= 0) return;
    const button = event.currentTarget.querySelector('button');
    button.disabled = true;
    try {
        await recordCapital('deposits', amount);
        event.currentTarget.reset();
    } catch (error) {
        $('#capitalStatus').textContent = error.message;
    } finally {
        button.disabled = false;
    }
});

$('#feeForm').addEventListener('submit', async event => {
    event.preventDefault();
    const amount = $('#feeAmount').value;
    if (!amount || Number(amount) <= 0) return;
    const button = event.currentTarget.querySelector('button');
    button.disabled = true;
    try {
        await recordCapital('fees', amount);
        $('#feeAmount').value = 1000;
    } catch (error) {
        $('#capitalStatus').textContent = error.message;
    } finally {
        button.disabled = false;
    }
});

async function refresh() {
    try { await Promise.all([loadPortfolio(), loadOrders(), loadTradeCycles(), loadOperationStatus()]); }
    catch (error) { $('#balanceDelta').textContent = error.message; }
}

$('#assetSearch').addEventListener('input', () => loadPortfolio());
refresh();
window.setInterval(refresh, 30000);

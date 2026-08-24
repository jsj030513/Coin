const $ = (selector) => document.querySelector(selector);
const won = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
const number = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 4 });
let refreshing = false;
let settingsDirty = false;
let activeProfile = 'BALANCED';
let tradingStatus = {masterEnabled:false, userEnabled:false, active:false};
let riskPresets = new Map([
    ['CONSERVATIVE', {
        minQuoteVolume24h: 5_000_000_000, minProfitPercent: 0.7, minExpectedProfitKrw: 500, maxProfitPercent: 10,
        feePercent: 0.1, orderAmountKrw: 50_000, maxOrderAmountKrw: 200_000,
        dailyMaxLossKrw: 100_000, maxConcurrentPositions: 1, opportunityCooldownSeconds: 300
    }],
    ['BALANCED', {
        minQuoteVolume24h: 1_000_000_000, minProfitPercent: 0.5, minExpectedProfitKrw: 25, maxProfitPercent: 3,
        feePercent: 0.1, orderAmountKrw: 5_000, maxOrderAmountKrw: 5_000,
        dailyMaxLossKrw: 2_000, maxConcurrentPositions: 1, opportunityCooldownSeconds: 120
    }],
    ['AGGRESSIVE', {
        minQuoteVolume24h: 500_000_000, minProfitPercent: 0.2, minExpectedProfitKrw: 50, maxProfitPercent: 30,
        feePercent: 0.1, orderAmountKrw: 300_000, maxOrderAmountKrw: 3_000_000,
        dailyMaxLossKrw: 1_000_000, maxConcurrentPositions: 5, opportunityCooldownSeconds: 15
    }]
]);

function formatWon(value) {
    const sign = value > 0 ? '+' : '';
    return `${sign}${won.format(value || 0)}원`;
}

function recommendedSymbolCount(capital) {
    if (capital < 100000) return 1;
    if (capital < 200000) return 2;
    if (capital < 350000) return 3;
    if (capital < 600000) return 4;
    if (capital < 1000000) return 5;
    if (capital < 2000000) return 7;
    return 10;
}

function timeAgo(value) {
    if (!value) return '대기 중';
    const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
    if (seconds < 5) return '방금 전';
    if (seconds < 60) return `${seconds}초 전`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}분 전`;
    return new Date(value).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}

function exchangeName(value) {
    return ({ upbit: 'UPBIT', bithumb: 'BITHUMB', coinone: 'COINONE', korbit: 'KORBIT' })[value] || value;
}

function renderOpportunities(rows) {
    $('#opportunityBadge').textContent = rows.length;
    $('#opportunityCount').textContent = rows.length;
    $('#bestProfit').textContent = rows.length ? `${rows.reduce((best, row) => Math.max(best, row.netProfitPercent), 0).toFixed(2)}%` : '—';
    $('#opportunityRows').innerHTML = rows.length ? rows.map(row => `
        <tr>
            <td><span class="symbol">${row.symbol}</span></td>
            <td><span class="route">${exchangeName(row.buyExchange)} <i>→</i> ${exchangeName(row.sellExchange)}</span></td>
            <td><span class="muted">${number.format(row.buyPrice)}</span> → ${number.format(row.sellPrice)}</td>
            <td><span class="profit">+${row.netProfitPercent.toFixed(2)}%</span></td>
            <td>${formatWon(row.expectedProfitPerCoinKrw)}</td>
            <td class="muted">${timeAgo(row.detectedAt)}</td>
        </tr>`).join('') : '<tr><td colspan="6" class="empty">현재 기준을 충족한 기회가 없습니다.</td></tr>';
}

function renderTrades(rows) {
    $('#tradeRows').innerHTML = rows.length ? rows.map(row => `
        <tr>
            <td><span class="symbol">${row.symbol}</span></td>
            <td><span class="route">${exchangeName(row.buyExchange)} <i>→</i> ${exchangeName(row.sellExchange)}</span></td>
            <td>${won.format(row.buyCostKrw)}원</td>
            <td class="${row.profitKrw >= 0 ? 'profit' : 'loss'}">${formatWon(row.profitKrw)}</td>
            <td class="muted">${timeAgo(row.executedAt)}</td>
        </tr>`).join('') : '<tr><td colspan="5" class="empty">체결 기록이 없습니다.</td></tr>';
}

function renderBalances(rows) {
    const krwTotal = rows.filter(row => row.currency.endsWith(':KRW')).reduce((sum, row) => sum + row.amount, 0);
    $('#krwBalance').textContent = rows.length ? `${won.format(krwTotal)}원` : '—';
    $('#balanceList').innerHTML = rows.length ? rows.map(row => `
        <div class="balance-item"><span>${row.currency}</span><span>${row.currency === 'KRW' ? `${won.format(row.amount)}원` : number.format(row.amount)}</span></div>`).join('')
        : '<p class="empty">잔고가 없습니다.</p>';
}

function renderSettings(settings) {
    if (!riskPresets.has('USER_1')) riskPresets.set('USER_1', {...settings, profileName: 'USER_1'});
    if (!settingsDirty) {
        ['minQuoteVolume24h', 'minProfitPercent', 'minExpectedProfitKrw', 'maxProfitPercent', 'feePercent',
            'orderAmountKrw', 'maxOrderAmountKrw', 'dailyMaxLossKrw',
            'maxConcurrentPositions', 'opportunityCooldownSeconds']
            .forEach(key => $(`#${key}`).value = settings[key]);
        activeProfile = settings.profileName || 'USER_1';
        renderActiveProfile();
    }
    $('#settingsSavedAt').textContent = settingsDirty ? '저장하지 않은 변경사항 있음'
        : settings.updatedAt ? `마지막 저장 ${timeAgo(settings.updatedAt)}` : '기본 설정 적용 중';
}

function renderActiveProfile() {
    document.querySelectorAll('.preset-card').forEach(button =>
        button.classList.toggle('active', button.dataset.profile === activeProfile));
    const names = {CONSERVATIVE: '소극적', BALANCED: '중간', AGGRESSIVE: '공격적', USER_1: '사용자 1'};
    $('#profileHint').textContent = `${names[activeProfile] || '사용자 1'} 설정 · 값을 직접 바꾸면 사용자 1로 전환됩니다.`;
}

function fillSettingsForm(settings) {
    ['minQuoteVolume24h', 'minProfitPercent', 'minExpectedProfitKrw', 'maxProfitPercent', 'feePercent',
        'orderAmountKrw', 'maxOrderAmountKrw', 'dailyMaxLossKrw',
        'maxConcurrentPositions', 'opportunityCooldownSeconds']
        .forEach(key => $(`#${key}`).value = settings[key]);
}

async function loadTradingPreferences() {
    try {
        const response = await fetch('/api/trading-preferences', {cache:'no-store'});
        if (!response.ok) return;
        const value = await response.json();
        if (value.plannedCapitalKrw > 0) $('#plannedCapitalKrw').value = value.plannedCapitalKrw;
        if (value.minExchangeKrw > 0) $('#minExchangeKrw').value = value.minExchangeKrw;
        $('#plannedSymbolCount').value = value.plannedSymbolCount || 5;
    } catch (_) { }
}

$('#applyCapitalGuide').addEventListener('click', async () => {
    const capital = Number($('#plannedCapitalKrw').value || 0);
    if (capital < 50000) return toast('전체 운용 예정금액을 5만원 이상 입력해 주세요.');
    const symbols = recommendedSymbolCount(capital);
    $('#plannedSymbolCount').value = symbols;
    const order = Math.max(5000, Math.min(20000, Math.floor(capital * 0.025 / 1000) * 1000));
    const autoMinimum = Math.max(10000, Math.ceil(capital * 0.10 / 5000) * 5000);
    const minimum = Number($('#minExchangeKrw').value || autoMinimum);
    const minRate = 0.8;
    const minExpected = Math.max(25, Math.ceil(order * minRate / 100));
    const dailyLoss = Math.max(500, Math.floor(capital * 0.005 / 100) * 100);
    const recommendedTotal = order * symbols * 2 + minimum * 2;
    $('#minExchangeKrw').value = minimum;
    $('#minProfitPercent').value = minRate;
    $('#minExpectedProfitKrw').value = minExpected;
    $('#orderAmountKrw').value = order;
    $('#maxOrderAmountKrw').value = order;
    $('#dailyMaxLossKrw').value = dailyLoss;
    $('#maxConcurrentPositions').value = 1;
    $('#opportunityCooldownSeconds').value = 120;
    $('#minQuoteVolume24h').value = 1000000000;
    $('#maxProfitPercent').value = 3;
    settingsDirty = true; activeProfile = 'USER_1'; renderActiveProfile();
    const shortage = capital < recommendedTotal ? ` · 현재 계획보다 ${won.format(recommendedTotal-capital)}원 부족` : '';
    $('#capitalGuideResult').textContent = `자동 운용 코인 ${symbols}개 · 권장 최소 총액 ${won.format(recommendedTotal)}원 · 거래소마다 현금 ${won.format(minimum)}원 유지 · 주문 ${won.format(order)}원${shortage}`;
    try {
        const response = await fetch('/api/trading-preferences/auto-symbols', {method:'POST', headers:{'Content-Type':'application/json'},
            body:JSON.stringify({plannedCapitalKrw:capital,minExchangeKrw:minimum})});
        if (!response.ok) throw new Error((await response.json()).error || '저장 실패');
        const result = await response.json();
        $('#plannedSymbolCount').value = result.recommendedSymbolCount || symbols;
        toast('운용 코인 수를 자동 계산해 적용했습니다. 아래 설정 저장 버튼을 눌러 확정하세요.');
    } catch (error) { toast(error.message); }
});

async function loadRiskPresets() {
    try {
        const response = await fetch('/api/risk-presets', {cache: 'no-store'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const presets = await response.json();
        presets.forEach(preset => riskPresets.set(preset.code, preset.settings));
    } catch (error) {
        toast('서버 프리셋 연결 전이라 기본 프리셋을 사용합니다.');
    }
}

function render(data) {
    const status = data.status;
    $('#scanState').textContent = status.scanning ? '스캔 중' : status.lastError ? '오류' : '감시 중';
    $('#scanState').classList.toggle('loss', Boolean(status.lastError));
    $('#commonCount').textContent = status.commonSymbolCount || 0;
    $('#lastScan').textContent = timeAgo(status.lastCompletedAt);
    renderOpportunities(data.opportunities || []);
    renderSettings(data.settings);
}

function renderTradingStatus(status) {
    tradingStatus = status;
    const pill = $('#tradingStatePill');
    pill.innerHTML = `<span></span> ${status.active ? 'AUTO ON' : 'AUTO OFF'}`;
    pill.classList.toggle('active', status.active);
    $('#tradingStateTitle').textContent = status.active ? '자동 차익거래 켜짐' : '자동 차익거래 꺼짐';
    $('#tradingStateDescription').textContent = status.masterEnabled
        ? (status.active ? '조건을 충족하면 실제 매수·매도 주문을 실행합니다.' : '버튼을 눌러 사용자 자동주문을 켤 수 있습니다.')
        : '서버의 실거래 마스터 잠금이 켜져 있습니다.';
    $('#tradingLockIcon').textContent = status.active ? '✓' : '×';
    const button = $('#tradingToggleButton');
    button.textContent = status.active ? '자동 차익거래 끄기' : '자동 차익거래 켜기';
    button.disabled = !status.masterEnabled && !status.userEnabled;
}

async function loadTradingStatus() {
    const response = await fetch('/api/trading', {cache:'no-store'});
    if (response.ok) renderTradingStatus(await response.json());
}

function formatUptime(seconds) {
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return days ? `${days}일 ${hours}시간` : hours ? `${hours}시간 ${minutes}분` : `${minutes}분`;
}

async function loadSystemStatus() {
    try {
        const response = await fetch('/api/system-status', {cache:'no-store'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        const healthy = data.scanHealthy && data.verifiedConnections === data.totalConnections;
        $('#systemHealthBadge').textContent = healthy ? '정상' : '확인 필요';
        $('#systemHealthBadge').classList.toggle('loss', !healthy);
        document.querySelector('.system-status-panel').classList.toggle('degraded', !healthy);
        $('#healthScan').textContent = data.scanHealthy ? '정상' : data.scanStale ? '지연' : '오류';
        $('#healthScanDetail').textContent = data.scan.lastError || `최근 완료 ${timeAgo(data.scan.lastCompletedAt)}`;
        $('#healthConnections').textContent = `${data.verifiedConnections}/${data.totalConnections}`;
        $('#healthTrading').textContent = data.trading.active ? '실행 중' : '정지';
        $('#healthTradingDetail').textContent = data.trading.masterEnabled ? '서버 주문 잠금 해제' : '서버 마스터 잠금';
        $('#healthLastOrder').textContent = data.lastOrder ? `${data.lastOrder.side} · ${data.lastOrder.status}` : '없음';
        $('#healthLastOrderDetail').textContent = data.lastOrder
            ? `${data.lastOrder.exchange} ${data.lastOrder.symbol} · ${timeAgo(data.lastOrder.createdAt)}` : '앱 주문 기록 없음';
        $('#healthUptime').textContent = formatUptime(data.uptimeSeconds);
        $('#healthServerTime').textContent = `서버 ${new Date(data.serverTime).toLocaleString('ko-KR')}`;
    } catch (error) {
        $('#systemHealthBadge').textContent = '연결 오류';
        document.querySelector('.system-status-panel').classList.add('degraded');
    }
}

async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
        const response = await fetch('/api/dashboard', { cache: 'no-store' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        render(await response.json());
    } catch (error) {
        $('#scanState').textContent = '연결 오류';
        $('#scanState').classList.add('loss');
    } finally {
        refreshing = false;
    }
}

function toast(message) {
    const element = $('#toast');
    element.textContent = message;
    element.classList.add('show');
    window.setTimeout(() => element.classList.remove('show'), 2600);
}

$('#scanButton').addEventListener('click', async () => {
    const button = $('#scanButton');
    button.disabled = true;
    button.textContent = '스캔 중…';
    try {
        const response = await fetch('/api/scan', { method: 'POST' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        toast('시장 스캔을 완료했습니다.');
        await refresh();
    } catch (error) {
        toast('스캔 요청에 실패했습니다.');
    } finally {
        button.disabled = false;
        button.textContent = '지금 스캔';
    }
});

document.querySelectorAll('#riskSettingsForm input').forEach(input => input.addEventListener('input', () => {
    settingsDirty = true;
    activeProfile = 'USER_1';
    renderActiveProfile();
    $('#settingsSavedAt').textContent = '저장하지 않은 변경사항 있음';
}));

document.querySelectorAll('.preset-card').forEach(button => button.addEventListener('click', () => {
    const preset = riskPresets.get(button.dataset.profile);
    if (!preset) {
        toast('프리셋을 아직 불러오는 중입니다.');
        return;
    }
    activeProfile = button.dataset.profile;
    fillSettingsForm(preset);
    settingsDirty = true;
    renderActiveProfile();
    $('#settingsSavedAt').textContent = '저장 버튼을 누르면 적용됩니다.';
}));

$('#riskSettingsForm').addEventListener('submit', async event => {
    event.preventDefault();
    const button = $('#saveSettingsButton');
    const keys = ['minQuoteVolume24h', 'minProfitPercent', 'minExpectedProfitKrw', 'maxProfitPercent', 'feePercent',
        'orderAmountKrw', 'maxOrderAmountKrw', 'dailyMaxLossKrw',
        'maxConcurrentPositions', 'opportunityCooldownSeconds'];
    const payload = Object.fromEntries(keys.map(key => [key, Number($(`#${key}`).value)]));
    payload.profileName = activeProfile;
    button.disabled = true;
    button.textContent = '저장 중…';
    try {
        const response = await fetch('/api/settings', {
            method: 'PUT',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || `HTTP ${response.status}`);
        settingsDirty = false;
        activeProfile = result.profileName;
        if (activeProfile === 'USER_1') riskPresets.set('USER_1', result);
        renderSettings(result);
        toast('리스크 설정을 저장했습니다. 다음 스캔부터 적용됩니다.');
    } catch (error) {
        toast(error.message || '설정을 저장하지 못했습니다.');
    } finally {
        button.disabled = false;
        button.textContent = '설정 저장';
    }
});

$('#telegramTestButton').addEventListener('click', async () => {
    const button = $('#telegramTestButton');
    button.disabled = true;
    button.textContent = '알림 전송 중…';
    try {
        const response = await fetch('/api/notifications/telegram/test', {method: 'POST'});
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '텔레그램 설정을 확인해 주세요.');
        toast('텔레그램 테스트 알림을 보냈습니다.');
    } catch (error) {
        toast(error.message || '텔레그램 테스트 알림에 실패했습니다.');
    } finally {
        button.disabled = false;
        button.textContent = '텔레그램 테스트 알림';
    }
});

$('#tradingToggleButton').addEventListener('click', async () => {
    const enabling = !tradingStatus.active;
    if (enabling && !confirm('자동 차익거래를 켜면 조건 충족 시 실제 주문이 전송됩니다. 계속할까요?')) return;
    const response = await fetch('/api/trading', {
        method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({enabled:enabling})
    });
    const result = await response.json();
    if (!response.ok) return toast(result.message || '자동 거래 설정을 변경하지 못했습니다.');
    renderTradingStatus(result);
    toast(result.active ? '자동 차익거래를 켰습니다.' : '자동 차익거래를 껐습니다.');
});

loadRiskPresets();
loadTradingPreferences();
refresh();
loadTradingStatus();
loadSystemStatus();
window.setInterval(refresh, 3000);
window.setInterval(loadTradingStatus, 10000);
window.setInterval(loadSystemStatus, 5000);
window.addEventListener('trading-emergency-stop', () => { loadTradingStatus(); loadSystemStatus(); });

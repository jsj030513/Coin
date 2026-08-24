const $ = selector => document.querySelector(selector);
const won = new Intl.NumberFormat('ko-KR', {maximumFractionDigits:0});
let settings = null;

const formatWon = value => `${won.format(Number(value || 0))}원`;

function toast(message) {
    const element = $('#orderToast');
    element.textContent = message;
    element.classList.add('show');
    setTimeout(() => element.classList.remove('show'), 4500);
}

function fillSettings(value) {
    settings = value;
    ['targetSymbolCount','targetKrwPerSymbolPerExchange','cashReserveKrwPerExchange','maxSeedBuyKrw','seedBuyCooldownSeconds']
        .forEach(key => $(`#${key}`).value = value[key]);
    $('#upbitPlanGuide').textContent = `업비트: ${value.targetSymbolCount}개 × ${formatWon(value.targetKrwPerSymbolPerExchange)} + 현금 ${formatWon(value.cashReserveKrwPerExchange)}`;
    $('#bithumbPlanGuide').textContent = `빗썸: ${value.targetSymbolCount}개 × ${formatWon(value.targetKrwPerSymbolPerExchange)} + 현금 ${formatWon(value.cashReserveKrwPerExchange)}`;
}

async function loadSettings() {
    const response = await fetch('/api/portfolio-plan/settings', {cache:'no-store'});
    if (!response.ok) throw new Error('보유 목표 설정을 불러오지 못했습니다.');
    fillSettings(await response.json());
}

async function saveSettings(event) {
    event.preventDefault();
    const keys = ['targetSymbolCount','targetKrwPerSymbolPerExchange','cashReserveKrwPerExchange','maxSeedBuyKrw','seedBuyCooldownSeconds'];
    const payload = Object.fromEntries(keys.map(key => [key, Number($(`#${key}`).value || 0)]));
    const response = await fetch('/api/portfolio-plan/settings', {
        method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || '설정을 저장하지 못했습니다.');
    fillSettings(result);
    toast('보유 목표를 저장했습니다.');
    await loadPlan();
}

function rowHtml(row, index) {
    const sameAmount = Math.min(Number(row.upbitSuggestedBuyKrw || 0), Number(row.bithumbSuggestedBuyKrw || 0));
    const action = row.ready ? '<em>준비 완료</em>' : sameAmount >= 5000
        ? `<button data-pair-buy data-symbol="${row.symbol}" data-amount="${sameAmount}" type="button">양쪽 각각 ${formatWon(sameAmount)} 매수</button>`
        : '<em>한쪽 보유량 확인 필요</em>';
    return `<article class="plan-row ${row.ready ? 'ready' : 'need'}">
        <div class="plan-rank">#${index + 1}</div>
        <div class="plan-main"><strong>${row.symbol}</strong><span>발생 ${row.occurrenceCount}회 · 평균 ${Number(row.averageProfitPercent || 0).toFixed(2)}%</span></div>
        <div class="plan-holdings"><span>UPBIT ${formatWon(row.upbit.estimatedValueKrw)} · 부족 ${formatWon(row.upbitNeedKrw)}</span><span>BITHUMB ${formatWon(row.bithumb.estimatedValueKrw)} · 부족 ${formatWon(row.bithumbNeedKrw)}</span></div>
        <div class="plan-actions">${action}</div>
    </article>`;
}

function setupResultHtml(report) {
    const kept = report.keptHoldings || [];
    const issues = report.issues || [];
    const recommendations = report.recommendations || [];
    const issueRecovery = issues.reduce((sum, row) => sum + Number(row.estimatedKrw || 0), 0);
    const issuePnl = issues.reduce((sum, row) => sum + Number(row.estimatedPnlKrw || 0), 0);
    const settings = report.settings || {};
    const list = (rows, mapper, empty) => rows.length ? rows.slice(0, 5).map(mapper).join('') : `<li>${empty}</li>`;
    return `<div class="initial-summary-grid">
        <article><span>기준 원금/평가액</span><strong>${formatWon(settings.capitalBasisKrw)}</strong><small>추천 코인 ${settings.targetSymbolCount || 0}개 · 코인당 ${formatWon(settings.targetKrwPerSymbolPerExchange)}</small></article>
        <article><span>유지 코인</span><strong>${kept.length}개</strong><small>양쪽 거래소에 이미 있는 공통 코인은 그대로 유지</small></article>
        <article><span>정리 후보</span><strong>${issues.length}개</strong><small>예상 회수 ${formatWon(issueRecovery)} · 추정손익 ${formatWon(issuePnl)}</small></article>
        <article><span>현재 KRW</span><strong>${formatWon((report.upbitCashKrw || 0) + (report.bithumbCashKrw || 0))}</strong><small>UPBIT ${formatWon(report.upbitCashKrw)} / BITHUMB ${formatWon(report.bithumbCashKrw)}</small></article>
    </div>
    <div class="initial-detail-grid">
        <div><h3>유지</h3><ul>${list(kept, row => `<li>${row.symbol} · 합산 ${formatWon(row.totalEstimatedKrw)}</li>`, '유지할 양쪽 보유 코인이 없습니다.')}</ul></div>
        <div><h3>정리 후보</h3><ul>${list(issues, row => `<li>${row.exchange} ${row.symbol} · 회수 ${formatWon(row.estimatedKrw)} · 손익 ${row.estimatedPnlKrw == null ? '계산 불가' : formatWon(row.estimatedPnlKrw)}</li>`, '정리 후보가 없습니다.')}</ul></div>
        <div><h3>추천</h3><ul>${list(recommendations, row => `<li>${row.symbol} · 평균 ${Number(row.averageProfitPercent || 0).toFixed(2)}% · 필요 UP ${formatWon(row.upbitSuggestedBuyKrw)} / BT ${formatWon(row.bithumbSuggestedBuyKrw)}</li>`, '추천 데이터가 아직 부족합니다.')}</ul></div>
    </div>`;
}

async function loadPlan() {
    const response = await fetch('/api/portfolio-plan', {cache:'no-store'});
    if (!response.ok) throw new Error('추천 코인을 불러오지 못했습니다.');
    const plan = await response.json();
    if (plan.settings) fillSettings(plan.settings);
    const rows = plan.symbols || [];
    $('#portfolioPlanSummary').textContent = `준비 완료 ${rows.filter(row => row.ready).length}/${rows.length}개 · 현금 UPBIT ${formatWon(plan.upbitCashKrw)} / BITHUMB ${formatWon(plan.bithumbCashKrw)}`;
    $('#portfolioPlanRows').innerHTML = rows.length ? rows.map(rowHtml).join('') : '<p class="empty">새 탐지 데이터를 모으는 중입니다.</p>';
    document.querySelectorAll('[data-pair-buy]').forEach(button => button.addEventListener('click', pairBuy));
}

async function pairBuy(event) {
    const button = event.currentTarget;
    const amount = Number(button.dataset.amount);
    if (!confirm(`업비트와 빗썸에서 ${button.dataset.symbol}을 각각 ${formatWon(amount)} 시장가 매수할까요?`)) return;
    button.disabled = true;
    try {
        const response = await fetch('/api/portfolio-plan/seed-buy-pair', {
            method:'POST', headers:{'Content-Type':'application/json'},
            body:JSON.stringify({symbol:button.dataset.symbol, krwAmount:amount})
        });
        const result = await response.json();
        if (!response.ok || !result.accepted) throw new Error(result.message || '동일 금액 매수에 실패했습니다.');
        toast(result.message);
        await loadPlan();
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
}

$('#refreshPlanButton').addEventListener('click', () => loadPlan().catch(error => toast(error.message)));
$('#portfolioSettingsForm').addEventListener('submit', event => saveSettings(event).catch(error => toast(error.message)));
$('#requestRecommendedBuy').addEventListener('click', async event => {
    if (!confirm('현재 추천 상위 9개의 텔레그램 매수 승인을 요청할까요? 승인 전에는 주문되지 않습니다.')) return;
    const button = event.currentTarget;
    button.disabled = true;
    try {
        const response = await fetch('/api/trade-approvals/recommended-buy', {method:'POST'});
        const result = await response.json();
        if (!response.ok) throw new Error(result.message || '승인 요청을 만들지 못했습니다.');
        toast(`텔레그램으로 ${result.itemCount}개 코인 매수 승인을 보냈습니다.`);
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
});
$('#initialSetupButton').addEventListener('click', async event => {
    if (!confirm('현재 실제 잔고를 기준으로 초기 세팅을 자동 계산할까요? 실제 매수·매도는 텔레그램 승인 전까지 실행되지 않습니다.')) return;
    const button = event.currentTarget;
    button.disabled = true;
    $('#initialSetupResult').innerHTML = '<p class="empty">초기 세팅을 계산하고 텔레그램 리포트를 전송 중입니다.</p>';
    try {
        const response = await fetch('/api/portfolio-onboarding/initial-setup', {method:'POST'});
        const result = await response.json();
        if (!response.ok) throw new Error(result.message || '초기 세팅에 실패했습니다.');
        $('#initialSetupResult').innerHTML = setupResultHtml(result);
        toast('초기 세팅 계산 완료 · 텔레그램 리포트를 확인하세요.');
        await Promise.all([loadSettings(), loadPlan()]);
    } catch (error) {
        $('#initialSetupResult').innerHTML = `<p class="empty">${window.escapeHtml(error.message)}</p>`;
        toast(error.message);
    } finally {
        button.disabled = false;
    }
});
Promise.all([loadSettings(), loadPlan()]).catch(error => toast(error.message));
window.setInterval(() => loadPlan().catch(() => {}), 60000);

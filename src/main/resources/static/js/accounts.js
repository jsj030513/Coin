const $ = selector => document.querySelector(selector);
const cards = [...document.querySelectorAll('.exchange-card')];
const nameOf = value => ({UPBIT:'Upbit',BITHUMB:'Bithumb',COINONE:'Coinone',KORBIT:'Korbit'})[value] || value;
const won = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 8 });

function toast(message) {
    const element = $('#accountToast');
    element.textContent = message; element.classList.add('show');
    setTimeout(() => element.classList.remove('show'), 3000);
}

function render(rows) {
    let verified = 0;
    rows.filter(row => ['UPBIT', 'BITHUMB'].includes(row.exchange)).forEach(row => {
        const card = document.querySelector(`[data-exchange="${row.exchange}"]`);
        card.classList.toggle('registered', row.registered);
        card.classList.toggle('verified', row.status === 'VERIFIED');
        card.classList.toggle('failed', row.status === 'FAILED');
        if (row.status === 'VERIFIED') verified++;
        const title = card.querySelector('.connection-state strong');
        const detail = card.querySelector('.connection-state span');
        if (!row.registered) { title.textContent = '연결되지 않음'; detail.textContent = '자산조회 전용 키를 등록하세요.'; }
        else if (row.status === 'VERIFIED') {
            title.textContent = '연결 확인 완료';
            const fee = row.buyFeePercent == null ? '설정 기본값' : `계정 수수료 매수 ${row.buyFeePercent.toFixed(4)}% / 매도 ${row.sellFeePercent.toFixed(4)}%`;
            const next = orderReady(row) ? 'LIVE ORDERS에서 수동 승인 후보 확인 가능' : '주문 테스트 전 주문조회·주문하기 권한 재등록 필요';
            detail.textContent = `${row.keyFingerprint} · 자산 ${row.assetCount}개 · ${fee} · ${new Date(row.lastVerifiedAt).toLocaleString('ko-KR')} · ${next}`;
        }
        else if (row.status === 'FAILED') { title.textContent = '연결 확인 실패'; detail.textContent = `${row.keyFingerprint} · ${row.lastError}`; }
        else { title.textContent = '키 저장됨'; detail.textContent = `${row.keyFingerprint} · 연결 확인 필요`; }
        renderPermissionPills(card, row);
    });
    $('#verifiedCount').textContent = `${verified}/2`;
    $('#verifiedLabel').textContent = verified === 2 ? '두 거래소 인증 완료' : `${2-verified}곳 추가 확인 필요`;
    renderFees(rows.filter(row => ['UPBIT','BITHUMB'].includes(row.exchange)));
}

function renderFees(rows) {
    $('#feeAnalysisRows').innerHTML = rows.map(row => {
        const known=row.buyFeePercent!=null||row.sellFeePercent!=null;
        const checked=row.feeCheckedAt?new Date(row.feeCheckedAt).toLocaleString('ko-KR'):'아직 확인되지 않음';
        const changed=row.feeChangedAt?`최근 변경 ${new Date(row.feeChangedAt).toLocaleString('ko-KR')}`:'변경 기록 없음';
        return `<article class="fee-analysis-card ${known?'known':'unknown'}"><span>${nameOf(row.exchange)}</span><strong>${known?`매수 ${Number(row.buyFeePercent||0).toFixed(4)}% · 매도 ${Number(row.sellFeePercent||0).toFixed(4)}%`:'거래소 응답 대기'}</strong><small>마지막 분석 ${checked}<br>${changed}</small></article>`;
    }).join('');
}

function permission(value) {
    return ({VERIFIED:'확인',NOT_GRANTED:'없음',UNKNOWN:'사전검증 불가'})[value] || '사전검증 불가';
}

function orderReady(row) {
    return row.orderReadPermission === 'VERIFIED' && row.orderCreatePermission !== 'NOT_GRANTED';
}

function pillClass(value) {
    if (value === 'VERIFIED') return 'ok';
    if (value === 'NOT_GRANTED') return 'bad';
    return 'warn';
}

function renderPermissionPills(card, row) {
    const target = card.querySelector('.permission-pills');
    if (!target) return;
    const asset = row.status === 'VERIFIED' ? 'VERIFIED' : 'UNKNOWN';
    target.innerHTML = `
        <span class="${pillClass(asset)}">자산조회 ${permission(asset)}</span>
        <span class="${pillClass(row.orderReadPermission)}">주문조회 ${permission(row.orderReadPermission)}</span>
        <span class="${pillClass(row.orderCreatePermission)}">주문하기 ${permission(row.orderCreatePermission)}</span>
    `;
}

async function loadConnections() {
    const response = await fetch('/api/exchange-connections', {cache:'no-store'});
    if (!response.ok) throw new Error('연결 상태를 불러오지 못했습니다.');
    render(await response.json());
}

function formatWon(value) {
    return `${won.format(Number(value || 0))}원`;
}

function renderLiveBalances(data) {
    $('#liveStatusGrid').innerHTML = data.statuses.map(row => `
        <div class="live-status ${row.connected ? 'ok' : 'bad'}">
            <span>${row.exchange}</span>
            <strong>${row.connected ? '조회 성공' : '조회 불가'}</strong>
            <small>${row.connected ? `자산 ${row.assetCount}개 · ${new Date(data.checkedAt).toLocaleTimeString('ko-KR')}` : row.message}</small>
        </div>
    `).join('');

    renderRebalance(data.rebalance);

    const balances = [...data.balances].sort((a, b) =>
        `${a.exchange}:${a.asset}`.localeCompare(`${b.exchange}:${b.asset}`));
    $('#liveBalanceRows').innerHTML = balances.length ? balances.map(row => `
        <div class="live-balance-row">
            <span>${row.exchange}</span>
            <strong>${row.asset}</strong>
            <em>사용 가능 ${row.asset === 'KRW' ? formatWon(row.free) : decimal.format(Number(row.free || 0))}</em>
            <small>잠김 ${row.asset === 'KRW' ? formatWon(row.locked) : decimal.format(Number(row.locked || 0))}</small>
        </div>
    `).join('') : '<p class="empty">조회된 실제 잔고가 없습니다.</p>';

    $('#liveReadinessRows').innerHTML = data.readiness.length ? data.readiness.map(row => `
        <div class="readiness-row ${row.executable ? 'executable' : 'blocked'}">
            <div>
                <strong>${row.symbol}</strong>
                <span>${row.buyExchange} 매수 → ${row.sellExchange} 매도</span>
            </div>
            <div>
                <em>${row.executable ? '가능' : '불가'}</em>
                <small>${row.reason}</small>
            </div>
            <div>
                <span>필요 KRW ${formatWon(row.requiredKrw)} / 보유 ${formatWon(row.availableKrw)}</span>
                <span>필요 코인 ${decimal.format(Number(row.requiredBase || 0))} / 보유 ${decimal.format(Number(row.availableBase || 0))}</span>
                <span>예상 ${formatWon(row.expectedProfitKrw)} · ${Number(row.netProfitPercent || 0).toFixed(2)}%</span>
            </div>
        </div>
    `).join('') : '<p class="empty">최근 차익 기회가 없습니다.</p>';
}

function renderTelegramStatus(data) {
    const settings = data.settings || {};
    $('#telegramPersonalStatus').textContent = data.configured ? '연결됨' : (data.botConfigured ? '개인 설정 필요' : '봇 설정 필요');
    $('#telegramChatId').value = settings.telegramChatId || '';
    $('#telegramEnabled').checked = Boolean(settings.telegramEnabled);
    $('#opportunityEnabled').checked = Boolean(settings.opportunityEnabled);
    $('#rebalanceEnabled').checked = settings.rebalanceEnabled !== false;
    $('#liveCandidateEnabled').checked = settings.liveCandidateEnabled !== false;
    $('#telegramCooldownSeconds').value = settings.cooldownSeconds ?? 600;
    $('#telegramSettingsHint').textContent = data.configured
        ? '내 텔레그램 알림이 연결되어 있습니다.'
        : data.botConfigured
            ? 'Chat ID를 입력하고 알림 사용을 켠 뒤 저장하세요.'
            : '서버 공용 Telegram Bot Token 설정이 필요합니다.';
}

async function loadTelegramSettings() {
    const response = await fetch('/api/notifications/telegram', {cache:'no-store'});
    if (!response.ok) throw new Error('텔레그램 설정을 불러오지 못했습니다.');
    renderTelegramStatus(await response.json());
}

async function saveTelegramSettings(event) {
    event.preventDefault();
    const button = $('#saveTelegramSettings');
    button.disabled = true;
    button.textContent = '저장 중…';
    try {
        const payload = {
            telegramChatId: $('#telegramChatId').value.trim(),
            telegramEnabled: $('#telegramEnabled').checked,
            opportunityEnabled: $('#opportunityEnabled').checked,
            rebalanceEnabled: $('#rebalanceEnabled').checked,
            liveCandidateEnabled: $('#liveCandidateEnabled').checked,
            cooldownSeconds: Number($('#telegramCooldownSeconds').value || 600)
        };
        const response = await fetch('/api/notifications/telegram', {
            method:'POST',
            headers:{'Content-Type':'application/json'},
            body:JSON.stringify(payload)
        });
        if (!response.ok) {
            const result = await response.json();
            throw new Error(result.error || '텔레그램 설정 저장 실패');
        }
        await loadTelegramSettings();
        toast('내 텔레그램 알림 설정을 저장했습니다.');
    } catch (error) {
        toast(error.message);
    } finally {
        button.disabled = false;
        button.textContent = '내 알림 설정 저장';
    }
}

async function sendTelegramTest() {
    const button = $('#sendTelegramTest');
    button.disabled = true;
    button.textContent = '전송 중…';
    try {
        const response = await fetch('/api/notifications/telegram/test', {method:'POST'});
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '텔레그램 테스트 실패');
        toast('내 텔레그램으로 테스트 알림을 보냈습니다.');
    } catch (error) {
        toast(error.message);
    } finally {
        button.disabled = false;
        button.textContent = '테스트 알림';
    }
}

function renderRebalance(row) {
    const card = $('#rebalanceCard');
    if (!row) {
        card.classList.remove('alert', 'safe');
        card.querySelector('.rebalance-head strong').textContent = '조회 대기';
        return;
    }
    card.classList.toggle('alert', row.alertEligible);
    card.classList.toggle('safe', !row.alertEligible);
    card.querySelector('.rebalance-head strong').textContent = row.alertEligible ? '이동 권장' : '정상 범위';
    card.querySelector('.rebalance-route').textContent = row.alertEligible
        ? `${row.fromExchange}(${row.fromBank}) → ${row.toExchange}(${row.toBank}) · ${formatWon(row.transferKrw)}`
        : `기준 ${row.maxSingleExchangeRatioPercent.toFixed(1)}% · 최소 알림 ${formatWon(row.minTransferKrw)}`;
    $('#upbitKrwRatio').textContent = `${Number(row.upbitRatioPercent || 0).toFixed(1)}%`;
    $('#bithumbKrwRatio').textContent = `${Number(row.bithumbRatioPercent || 0).toFixed(1)}%`;
    $('#upbitKrwBar').style.width = `${Math.min(100, Number(row.upbitRatioPercent || 0))}%`;
    $('#bithumbKrwBar').style.width = `${Math.min(100, Number(row.bithumbRatioPercent || 0))}%`;
    $('#upbitKrwAmount').textContent = formatWon(row.upbitKrw);
    $('#bithumbKrwAmount').textContent = formatWon(row.bithumbKrw);
    $('#rebalanceMessage').textContent = row.message;
}

async function loadLiveBalances() {
    const button = $('#refreshLiveBalances');
    button.disabled = true;
    try {
        const response = await fetch('/api/live-balances', {cache:'no-store'});
        if (!response.ok) throw new Error('실제 잔고 조회에 실패했습니다.');
        renderLiveBalances(await response.json());
    } catch (error) {
        $('#liveStatusGrid').innerHTML = `<p class="empty negative">${error.message}</p>`;
    } finally {
        button.disabled = false;
    }
}

cards.forEach(card => {
    const exchange = card.dataset.exchange;
    card.querySelector('.key-form').addEventListener('submit', async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const button = form.querySelector('button');
        const payload = {accessKey:form.elements.accessKey.value,secretKey:form.elements.secretKey.value};
        button.disabled = true; button.textContent = '암호화 저장 및 확인 중…';
        try {
            const response = await fetch(`/api/exchange-connections/${exchange}`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
            const result = await response.json();
            if (!response.ok) throw new Error(result.error || '등록에 실패했습니다.');
            form.reset(); await loadConnections();
            toast(result.status === 'VERIFIED' ? `${nameOf(exchange)} 연결을 확인했습니다.` : `${nameOf(exchange)} 키는 저장했지만 인증에 실패했습니다.`);
        } catch (error) { toast(error.message); }
        finally { button.disabled = false; button.textContent = '저장하고 연결 확인'; }
    });
    card.querySelector('.verify-button').addEventListener('click', async event => {
        event.currentTarget.disabled = true;
        try {
            const response = await fetch(`/api/exchange-connections/${exchange}/verify`, {method:'POST'});
            const result = await response.json();
            if (!response.ok) throw new Error(result.error || '확인에 실패했습니다.');
            await loadConnections(); toast(result.status === 'VERIFIED' ? '연결 확인 완료' : result.lastError);
        } catch (error) { toast(error.message); }
        finally { event.currentTarget.disabled = false; }
    });
    card.querySelector('.delete-button').addEventListener('click', async () => {
        if (!confirm(`${nameOf(exchange)} 연결정보를 삭제할까요?\n새 키로 바꾸기만 할 거라면 삭제하지 않고 바로 새 키를 저장해도 됩니다.`)) return;
        const response = await fetch(`/api/exchange-connections/${exchange}`, {method:'DELETE'});
        if (response.ok) { await loadConnections(); toast('암호화된 연결정보를 삭제했습니다.'); }
        else toast('연결정보 삭제에 실패했습니다.');
    });
});

$('#refreshLiveBalances').addEventListener('click', loadLiveBalances);
$('#telegramSettingsForm').addEventListener('submit', saveTelegramSettings);
$('#sendTelegramTest').addEventListener('click', sendTelegramTest);
$('#refreshFees').addEventListener('click', async event => {
    const button=event.currentTarget; button.disabled=true; button.textContent='분석 중…';
    try { const response=await fetch('/api/exchange-connections/fees/refresh',{method:'POST'});
        if(!response.ok)throw new Error('수수료 분석에 실패했습니다.'); render(await response.json()); toast('현재 계정 수수료를 다시 분석했습니다.');
    } catch(error){toast(error.message);} finally {button.disabled=false;button.textContent='지금 수수료 분석';}
});

loadConnections().then(loadLiveBalances).then(loadTelegramSettings).catch(error => toast(error.message));
window.setInterval(loadLiveBalances, 30000);

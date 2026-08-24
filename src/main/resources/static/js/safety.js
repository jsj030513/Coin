(() => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'emergency-stop-button safe';
    button.textContent = '비상 정지';
    button.setAttribute('aria-label', '자동 차익거래 즉시 정지');
    const topbar = document.querySelector('.topbar-actions');
    if (topbar) {
        const scanButton = topbar.querySelector('.scan-button');
        topbar.insertBefore(button, scanButton);
    } else {
        document.body.appendChild(button);
    }

    const refreshState = async () => {
        try {
            const response = await fetch('/api/trading', {cache:'no-store'});
            if (!response.ok) return;
            const state = await response.json();
            button.classList.toggle('active', state.active);
            button.classList.toggle('safe', !state.active);
            button.title = state.active ? '자동거래 실행 중 — 누르면 즉시 정지' : '자동거래는 현재 꺼져 있습니다';
        } catch (_) { /* 다른 화면 기능을 방해하지 않는다. */ }
    };

    button.addEventListener('click', async () => {
        if (!confirm('자동 차익거래를 즉시 정지할까요? 이미 거래소에 접수된 주문은 취소되지 않습니다.')) return;
        button.disabled = true;
        button.textContent = '정지 중…';
        try {
            const response = await fetch('/api/trading/emergency-stop', {method:'POST'});
            if (!response.ok) throw new Error('비상 정지 요청 실패');
            button.classList.remove('active');
            button.classList.add('safe');
            button.textContent = '자동거래 정지 완료';
            window.dispatchEvent(new CustomEvent('trading-emergency-stop'));
        } catch (_) {
            button.textContent = '정지 실패 · 다시 시도';
        } finally {
            button.disabled = false;
            window.setTimeout(() => { button.textContent = '비상 정지'; }, 2200);
        }
    });

    refreshState();
    window.setInterval(refreshState, 10000);
})();

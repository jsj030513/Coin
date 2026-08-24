const message = document.querySelector('#authMessage');
const showMessage = (text, success = false) => {
    if (!message) return;
    message.textContent = text;
    message.className = `auth-message show${success ? ' success' : ''}`;
};

if (document.body.dataset.page === 'login') {
    const params = new URLSearchParams(location.search);
    if (params.has('error')) showMessage('아이디 또는 비밀번호가 올바르지 않습니다.');
    if (params.has('logout')) showMessage('안전하게 로그아웃했습니다.', true);
    if (params.has('registered')) showMessage('회원가입이 완료되었습니다. 관리자 승인 후 로그인할 수 있습니다.', true);
    if (params.has('passwordChanged')) showMessage('비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요.', true);
    if (params.get('roleSwitch') === 'admin') showMessage('관리자 페이지를 이용하려면 관리자 계정으로 다시 로그인해 주세요.', true);
    if (params.get('roleSwitch') === 'user') showMessage('일반 사용자 페이지를 이용하려면 일반 사용자 계정으로 다시 로그인해 주세요.', true);
    if (params.has('locked')) {
        const seconds = Math.max(1, Number(params.get('retryAfter') || 900));
        showMessage(`로그인 시도가 잠겼습니다. 약 ${Math.ceil(seconds / 60)}분 후 다시 시도해 주세요.`);
    }
}

document.querySelector('#registerForm')?.addEventListener('submit', async event => {
    event.preventDefault();
    const password = document.querySelector('#password').value;
    if (password !== document.querySelector('#passwordConfirm').value) {
        showMessage('비밀번호 확인이 일치하지 않습니다.');
        return;
    }
    const button = document.querySelector('#registerButton');
    button.disabled = true;
    try {
        const response = await fetch('/api/auth/register', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({username: document.querySelector('#username').value,
                password, displayName: document.querySelector('#displayName').value,
                inviteCode: document.querySelector('#inviteCode').value})
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '회원가입에 실패했습니다.');
        location.href = '/login?registered';
    } catch (error) {
        showMessage(error.message);
        button.disabled = false;
    }
});

document.querySelector('#requestRecoveryButton')?.addEventListener('click', async event => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
        const response = await fetch('/api/auth/recovery/request', {method: 'POST'});
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '복구 코드를 보내지 못했습니다.');
        showMessage(result.message, true);
        let remaining = 60;
        const timer = setInterval(() => {
            remaining -= 1;
            button.textContent = `${remaining}초 후 다시 받기`;
            if (remaining <= 0) {
                clearInterval(timer);
                button.textContent = '텔레그램으로 복구 코드 다시 받기';
                button.disabled = false;
            }
        }, 1000);
    } catch (error) {
        showMessage(error.message);
        button.disabled = false;
    }
});

document.querySelector('#recoveryForm')?.addEventListener('submit', async event => {
    event.preventDefault();
    const newPassword = document.querySelector('#newPassword').value;
    if (newPassword !== document.querySelector('#newPasswordConfirm').value) {
        showMessage('새 비밀번호 확인이 일치하지 않습니다.');
        return;
    }
    const button = document.querySelector('#recoveryButton');
    button.disabled = true;
    try {
        const response = await fetch('/api/auth/recovery/reset', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({code: document.querySelector('#recoveryCode').value, newPassword})
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '계정을 복구하지 못했습니다.');
        event.currentTarget.hidden = true;
        document.querySelector('#requestRecoveryButton').hidden = true;
        const account = document.querySelector('#recoveredAccount');
        account.innerHTML = '복구된 아이디<strong></strong>';
        account.querySelector('strong').textContent = result.username;
        account.hidden = false;
        showMessage('비밀번호를 재설정했습니다. 아래 아이디로 로그인해 주세요.', true);
    } catch (error) {
        showMessage(error.message);
        button.disabled = false;
    }
});

document.querySelector('#changePasswordForm')?.addEventListener('submit', async event => {
    event.preventDefault();
    const newPassword = document.querySelector('#changedPassword').value;
    if (newPassword !== document.querySelector('#changedPasswordConfirm').value) {
        showMessage('새 비밀번호 확인이 일치하지 않습니다.');
        return;
    }
    const button = document.querySelector('#changePasswordButton');
    button.disabled = true;
    try {
        const response = await fetch('/api/auth/password', {
            method: 'PUT', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({currentPassword: document.querySelector('#currentPassword').value, newPassword})
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '비밀번호를 변경하지 못했습니다.');
        await fetch('/logout', {method: 'POST'});
        location.href = '/login?passwordChanged';
    } catch (error) {
        showMessage(error.message);
        button.disabled = false;
    }
});

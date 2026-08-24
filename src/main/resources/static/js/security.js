(() => {
    const originalFetch = window.fetch.bind(window);
    const unsafe = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
    const cookie = name => document.cookie.split('; ').find(row => row.startsWith(`${name}=`))?.slice(name.length + 1);

    window.fetch = (input, init = {}) => {
        const url = new URL(typeof input === 'string' ? input : input.url, location.href);
        const method = String(init.method || (typeof input !== 'string' && input.method) || 'GET').toUpperCase();
        if (url.origin === location.origin && unsafe.has(method)) {
            const token = cookie('XSRF-TOKEN');
            const headers = new Headers(init.headers || (typeof input !== 'string' ? input.headers : undefined));
            if (token) headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
            init = {...init, headers};
        }
        return originalFetch(input, init);
    };

    window.escapeHtml = value => String(value ?? '').replace(/[&<>'"]/g, character => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
    })[character]);
})();

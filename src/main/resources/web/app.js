const ICONS = {
    BOX: `<svg class="icon" viewBox="0 0 24 24"><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>`,
    LOCK: `<svg class="icon icon-lg" style="margin-bottom:16px;color:var(--gold)" viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`,
    CLOCK: `<svg class="icon icon-sm" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`,
    MOON: `<svg class="icon icon-sm" viewBox="0 0 24 24"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>`,
    ARROW_UP: `<svg class="icon icon-sm" style="stroke-width:3;color:var(--accent)" viewBox="0 0 24 24"><polyline points="18 15 12 9 6 15"/></svg>`,
    ARROW_DOWN: `<svg class="icon icon-sm" style="stroke-width:3;color:var(--red)" viewBox="0 0 24 24"><polyline points="6 9 12 15 18 9"/></svg>`,
    ARROW_FLAT: `<svg class="icon icon-sm" style="stroke-width:3;color:var(--text-muted)" viewBox="0 0 24 24"><line x1="5" y1="12" x2="19" y2="12"/></svg>`
};

// ── Global State ─────────────────────────────────────────────────
let SERVER_ID = '';
let TOKEN = '';
let PLAYER_UUID = '';
let API_BASE = '';
let currentPage = 'market';
let currentCategory = null;
let currentPageNum = 0;
let priceHistory = {};
let autoRefreshTimer = null;
let refreshCountdown = 20;

const AUTH_HEADERS = () => ({ 'Authorization': `Bearer ${TOKEN}` });

const IMG_BASE = 'https://assets.mcasset.cloud/26.2/assets/minecraft/textures/item/';

// Items whose texture filename differs from their item ID
const TEXTURE_OVERRIDES = {
    'enchanted_golden_apple': 'golden_apple',
    'crossbow': 'crossbow_standby',
    'debug_stick': 'stick',
    'clock': 'clock_00',
    'compass': 'compass_00',
    'recovery_compass': 'recovery_compass_00',
    'shulker_box': 'block/shulker_box',
    'piston': 'block/piston_side',
    'observer': 'block/observer_front',
};

function resolveTextureName(material) {
    const key = (material || '').toLowerCase();
    const override = TEXTURE_OVERRIDES[key];
    if (override) {
        if (override.startsWith('block/')) return override;
        return override;
    }
    return key;
}

function getItemIconUrl(material) {
    const resolved = resolveTextureName(material);
    if (resolved.startsWith('block/')) {
        return `https://assets.mcasset.cloud/26.2/assets/minecraft/textures/${resolved}.png`;
    }
    return `${IMG_BASE}${resolved}.png`;
}

function handleItemIconError(img, material, hideOnFail = false) {
    let attempt = parseInt(img.dataset.fallback || '0');
    if (attempt < 1) {
        img.src = `https://assets.mcasset.cloud/26.2/assets/minecraft/textures/block/${resolveTextureName(material)}.png`;
        img.dataset.fallback = attempt + 1;
    } else if (attempt < 2) {
        img.src = `https://assets.mcasset.cloud/26.1/assets/minecraft/textures/item/${resolveTextureName(material)}.png`;
        img.dataset.fallback = attempt + 1;
    } else if (attempt < 3) {
        img.src = `https://assets.mcasset.cloud/26.1/assets/minecraft/textures/block/${resolveTextureName(material)}.png`;
        img.dataset.fallback = attempt + 1;
    } else if (attempt < 4) {
        img.src = `https://assets.mcasset.cloud/1.21.11/assets/minecraft/textures/item/${resolveTextureName(material)}.png`;
        img.dataset.fallback = attempt + 1;
    } else if (attempt < 5) {
        img.src = `https://assets.mcasset.cloud/1.21.11/assets/minecraft/textures/block/${resolveTextureName(material)}.png`;
        img.dataset.fallback = attempt + 1;
    } else {
        if (hideOnFail) {
            img.style.display = 'none';
        } else {
            const fallback = document.createElement('div');
            fallback.innerHTML = ICONS.BOX.trim();
            img.replaceWith(fallback.firstElementChild || fallback);
        }
    }
}

// ── Error Display ────────────────────────────────────────────────

function showError(msg) {
    const overlay = document.getElementById('loading-overlay');
    overlay.innerHTML = `
        <div style="text-align:center;padding:40px;max-width:420px;">
            ${ICONS.LOCK}
            <h2 style="color:#f5c542;margin:0 0 12px;">Session Required</h2>
            <p style="color:#aaa;font-size:15px;line-height:1.6;margin:0;">
                ${esc(msg)}<br><br>
                Type <code style="background:rgba(255,255,255,.1);padding:2px 8px;border-radius:4px;color:#f5c542;">/web</code>
                or <code style="background:rgba(255,255,255,.1);padding:2px 8px;border-radius:4px;color:#f5c542;">/market web</code>
                in-game to get a dashboard link.
            </p>
        </div>
    `;
    overlay.classList.remove('hidden');
}

// ── Bootstrap ────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    const parts = window.location.pathname.split('/');
    SERVER_ID = parts[parts.length - 1] || parts[parts.length - 2];
    TOKEN = new URLSearchParams(window.location.search).get('token');
    API_BASE = `/api/${SERVER_ID}`;

    if (TOKEN && window.history.replaceState) {
        const url = new URL(window.location);
        url.searchParams.delete('token');
        window.history.replaceState({}, '', url.pathname + url.search);
    }

    waitForSession();
});

// ── Tab Sleep Mode ───────────────────────────────────────────────

document.addEventListener('visibilitychange', () => {
    const countdownText = document.getElementById('refresh-countdown');

    if (document.hidden) {
        if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null; }
        if (balanceRefreshTimer) { clearInterval(balanceRefreshTimer); balanceRefreshTimer = null; }
        if (countdownText) countdownText.innerHTML = ICONS.MOON;
    } else {
        if (!balanceRefreshTimer) {
            balanceRefreshTimer = setInterval(async () => {
                if (document.hidden || !TOKEN) return;
                try {
                    const player = await api('/player');
                    if (player) {
                        const defaultBal = player.balances?.[player.defaultCurrency] ?? 0;
                        const balEl = document.getElementById('balance-amount');
                        if (balEl) balEl.textContent = `${defaultBal.toLocaleString()} ${player.defaultCurrency}`;
                    }
                } catch (e) {}
            }, 30000);
        }
        if (countdownText) countdownText.textContent = '...';
        switchPage(currentPage);
    }
});

// ── Session Loading with Retry ───────────────────────────────────

async function waitForSession() {
    const overlay = document.getElementById('loading-overlay');
    const overlayText = overlay.querySelector('p');
    const maxRetries = 30;
    const retryInterval = 2000;

    for (let i = 0; i < maxRetries; i++) {
        const pct = Math.round((i / maxRetries) * 100);
        const eta = Math.ceil((maxRetries - i) * retryInterval / 1000);
        overlayText.textContent = `Connecting to server... ${pct}% (~${eta}s remaining)`;

        try {
            const resp = await fetch(`${API_BASE}/player`, { headers: AUTH_HEADERS() });
            if (resp.ok) {
                const data = await resp.json();
                onSessionReady(data);
                overlay.classList.add('hidden');
                return;
            }
        } catch (_) { /* retry */ }

        await new Promise(r => setTimeout(r, retryInterval));
    }

    showError('Could not connect. Your session may have expired.');
}

// ── Session Ready ────────────────────────────────────────────────

function onSessionReady(player) {
    PLAYER_UUID = player.uuid;
    document.getElementById('player-name').textContent = player.name;
    document.getElementById('player-avatar').style.backgroundImage =
        `url(https://mc-heads.net/avatar/${player.uuid}/28)`;

    const defaultBal = player.balances?.[player.defaultCurrency] ?? 0;
    document.getElementById('balance-amount').textContent =
        `${defaultBal.toLocaleString()} ${player.defaultCurrency}`;

    setupNavigation();
    loadMarketPage();
}

// ── Navigation ───────────────────────────────────────────────────

function setupNavigation() {
    document.querySelectorAll('.nav-tab').forEach(tab => {
        tab.addEventListener('click', () => switchPage(tab.dataset.page));
    });
}

function switchPage(page) {
    currentPage = page;
    document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
    document.querySelector(`.nav-tab[data-page="${page}"]`).classList.add('active');

    if (stocksObserver) { stocksObserver.disconnect(); stocksObserver = null; }

    document.querySelectorAll('.page-content').forEach(p => p.classList.add('hidden'));
    document.getElementById(`page-${page}`).classList.remove('hidden');

    switch (page) {
        case 'market': loadMarketPage(); break;
        case 'auction': loadAuctionPage(); break;
        case 'orders': loadOrdersPage(); break;
        case 'stocks': loadStocksPage(); break;
    }

    const timerUI = document.getElementById('refresh-info');
    const countdownText = document.getElementById('refresh-countdown');
    if (autoRefreshTimer) clearInterval(autoRefreshTimer);

    if (['auction', 'orders', 'stocks'].includes(page)) {
        timerUI.classList.add('visible');
        refreshCountdown = 20;
        countdownText.textContent = refreshCountdown + 's';
        autoRefreshTimer = setInterval(() => {
            refreshCountdown--;
            if (refreshCountdown <= 0) { refreshCountdown = 20; refreshCurrentPage(); }
            countdownText.textContent = refreshCountdown + 's';
        }, 1000);
    } else {
        timerUI.classList.remove('visible');
    }
}

async function refreshCurrentPage() {
    try {
        const player = await api('/player');
        if (!player) return;
        const defaultBal = player.balances?.[player.defaultCurrency] ?? 0;
        const balEl = document.getElementById('balance-amount');
        if (balEl) balEl.textContent = `${defaultBal.toLocaleString()} ${player.defaultCurrency}`;

        switch (currentPage) {
            case 'stocks': {
                const [stocks, history] = await Promise.all([api('/stocks'), api('/price-history')]);
                stocksData = stocks;
                priceHistory = history || {};
                const q = document.getElementById('stocks-search')?.value?.toLowerCase() || '';
                const sorted = getSortedStocks();
                const filtered = q ? sorted.filter(s => s.name.toLowerCase().includes(q)) : sorted;
                renderStocksBody(filtered);
                break;
            }
            case 'auction': {
                const auctions = await api('/auctions');
                if (!auctions) break;
                auctionData = auctions;
                // Re-render category counts
                const cats = await api('/categories');
                if (cats) renderAuctionCategories(cats);
                // Re-apply current filter
                if (currentAuctionCategory) {
                    let filtered;
                    if (currentAuctionCategory === '__all') filtered = auctions;
                    else if (currentAuctionCategory === '__bin') filtered = auctions.filter(a => a.isBin);
                    else if (currentAuctionCategory === '__bid') filtered = auctions.filter(a => !a.isBin);
                    else filtered = auctions.filter(a => a.categoryId === currentAuctionCategory || a.category === '');
                    renderAuctions(filtered.length > 0 ? filtered : auctions);
                } else {
                    renderAuctions(auctions);
                }
                break;
            }
            case 'orders': {
                const orders = await api('/orders');
                if (!orders) break;
                renderOrders(orders);
                break;
            }
        }
    } catch (e) {}
}

let balanceRefreshTimer = setInterval(async () => {
    if (document.hidden || !TOKEN) return;
    try {
        const player = await api('/player');
        if (!player) return;
        const defaultBal = player.balances?.[player.defaultCurrency] ?? 0;
        const balEl = document.getElementById('balance-amount');
        if (balEl) balEl.textContent = `${defaultBal.toLocaleString()} ${player.defaultCurrency}`;
    } catch (e) {}
}, 30000);

// ═══════════════════════════════════════════════════════════════════
// MARKET PAGE
// ═══════════════════════════════════════════════════════════════════

async function loadMarketPage() {
    try {
        const cats = await api('/categories');
        if (!cats) return;
        if (cats.length > 0) selectCategory(cats[0].id, cats[0].name);
    } catch (e) {
        console.error('Failed to load market:', e);
    }

    const searchInput = document.getElementById('search-input');
    searchInput.onkeyup = debounce(async () => {
        const q = searchInput.value.trim();
        if (q.length >= 2) {
            currentCategory = null;
            document.querySelectorAll('.sidebar-item').forEach(s => s.classList.remove('active'));
            const data = await api(`/search?q=${encodeURIComponent(q)}&page=0`);
            renderItems(data.items);
            renderPagination(data, (p) => searchPage(q, p));
            updateBreadcrumb(`Search: "${q}"`);
        } else if (q.length === 0 && !currentCategory) {
            const cats = await api('/categories');
            if (cats.length > 0) selectCategory(cats[0].id, cats[0].name);
        }
    }, 300);
}

function renderCategories(cats) {
    const container = document.getElementById('sidebar-categories');
    container.innerHTML = '';
    cats.forEach(cat => {
        const el = document.createElement('div');
        el.className = 'sidebar-item';
        el.dataset.catId = cat.id;
        el.innerHTML = `
            <img src="${getItemIconUrl(cat.icon || 'stone')}" width="20" height="20"
                 style="image-rendering:pixelated" onerror="this.style.display='none'">
            <span>${esc(cat.name)}</span>
            <span class="item-count">${cat.itemCount}</span>
        `;
        el.addEventListener('click', () => selectCategory(cat.id, cat.name));
        container.appendChild(el);
    });
}

async function selectCategory(catId, catName) {
    currentCategory = catId;
    currentPageNum = 0;
    document.getElementById('search-input').value = '';

    document.querySelectorAll('.sidebar-item').forEach(s => {
        s.classList.toggle('active', s.dataset.catId === catId);
    });

    updateBreadcrumb(catName);
    const data = await api(`/items?category=${catId}&page=0`);
    renderItems(data.items);
    renderPagination(data, (p) => loadCategoryPage(catId, catName, p));
}

async function loadCategoryPage(catId, catName, page) {
    currentPageNum = page;
    const data = await api(`/items?category=${catId}&page=${page}`);
    renderItems(data.items);
    renderPagination(data, (p) => loadCategoryPage(catId, catName, p));
}

async function searchPage(query, page) {
    const data = await api(`/search?q=${encodeURIComponent(query)}&page=${page}`);
    renderItems(data.items);
    renderPagination(data, (p) => searchPage(query, p));
}

function renderItems(items) {
    const grid = document.getElementById('items-grid');
    const empty = document.getElementById('empty-state');

    if (!items || items.length === 0) {
        grid.innerHTML = '';
        empty.style.display = '';
        return;
    }
    empty.style.display = 'none';

    grid.innerHTML = items.map(item => `
        <div class="item-card" onclick="openBuyModal('${escJs(item.key)}','${escJs(item.name)}',${item.price},'${escJs(item.priceFormatted)}','${escJs(item.currency)}','${escJs(item.material)}')">
            <div class="item-card-header">
                <div class="item-icon">
                    <img src="${getItemIconUrl(item.material || 'stone')}"
                         onerror="handleItemIconError(this, '${escJs(item.material || 'stone')}')" alt="">
                </div>
                <div class="item-name">${esc(item.name)}</div>
            </div>
            <div class="item-card-footer">
                <span class="item-price">${item.priceFormatted}</span>
            </div>
        </div>
    `).join('');
}

function renderPagination(data, loadFn) {
    const div = document.getElementById('pagination');
    if (data.totalPages <= 1) { div.style.display = 'none'; return; }
    div.style.display = '';
    document.getElementById('page-info').textContent = `Page ${data.page + 1} / ${data.totalPages}`;
    const prev = document.getElementById('prev-page');
    const next = document.getElementById('next-page');
    prev.disabled = data.page === 0;
    next.disabled = data.page >= data.totalPages - 1;
    prev.onclick = () => loadFn(data.page - 1);
    next.onclick = () => loadFn(data.page + 1);
}

function updateBreadcrumb(name) {
    document.getElementById('breadcrumb').innerHTML = `<span class="breadcrumb-item active">${esc(name)}</span>`;
}

// ── Buy Modal ────────────────────────────────────────────────────

let modalItem = {};
let currentBuyContext = null;

function openBuyModal(key, name, price, formatted, currency, material, maxQty = 64) {
    modalItem = { key, name, price, formatted, currency, material };
    currentBuyContext = { maxQty };
    document.getElementById('modal-item-name').textContent = name;
    document.getElementById('modal-item-price').textContent = formatted;
    document.getElementById('modal-icon').innerHTML =
        `<img src="${getItemIconUrl(material || 'stone')}" width="36" height="36" style="image-rendering:pixelated"
              onerror="handleItemIconError(this, '${escJs(material || 'stone')}')">`;
    document.getElementById('amount-input').value = 1;
    document.getElementById('amount-input').max = maxQty;
    updateModalTotal();
    updateQtyButtonStates('amount-input', 'amount-minus', 'amount-plus', maxQty);
    document.getElementById('buy-modal').style.display = '';
    document.getElementById('buy-modal').classList.remove('closing');
}

function updateModalTotal() {
    const amt = parseInt(document.getElementById('amount-input').value) || 1;
    const total = modalItem.price * amt;
    document.getElementById('modal-total').textContent =
        `${total.toLocaleString(undefined, { minimumFractionDigits: 2 })} ${modalItem.currency}`;
}

document.getElementById('modal-close')?.addEventListener('click', () => {
    closeModal('buy-modal');
});
document.getElementById('modal-cancel')?.addEventListener('click', () => {
    closeModal('buy-modal');
});
document.getElementById('amount-minus')?.addEventListener('click', () => {
    const inp = document.getElementById('amount-input');
    inp.value = Math.max(1, (parseInt(inp.value) || 1) - 1);
    updateModalTotal();
    updateQtyButtonStates('amount-input', 'amount-minus', 'amount-plus', currentBuyContext?.maxQty || 64);
});
document.getElementById('amount-plus')?.addEventListener('click', () => {
    const inp = document.getElementById('amount-input');
    inp.value = Math.min(currentBuyContext?.maxQty || 64, (parseInt(inp.value) || 1) + 1);
    updateModalTotal();
    updateQtyButtonStates('amount-input', 'amount-minus', 'amount-plus', currentBuyContext?.maxQty || 64);
});
document.getElementById('amount-input')?.addEventListener('input', () => {
    updateModalTotal();
    updateQtyButtonStates('amount-input', 'amount-minus', 'amount-plus', currentBuyContext?.maxQty || 64);
});

document.getElementById('modal-buy')?.addEventListener('click', async () => {
    const btn = document.getElementById('modal-buy');
    btn.disabled = true;
    btn.querySelector('.btn-buy-text').textContent = 'Processing...';

    try {
        const amount = parseInt(document.getElementById('amount-input').value) || 1;
        const resp = await fetch(`${API_BASE}/buy`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...AUTH_HEADERS() },
            body: JSON.stringify({ item: modalItem.key, amount })
        });
        const result = await resp.json();
        if (result.success) {
            showToast('success', `Purchased ${amount}x ${modalItem.name}!`);
            document.getElementById('buy-modal').style.display = 'none';
            pollPurchase(result.purchaseId);
        } else {
            showToast('error', result.error || 'Purchase failed');
        }
    } catch (e) {
        showToast('error', 'Network error');
    }

    btn.disabled = false;
    btn.querySelector('.btn-buy-text').textContent = 'Purchase';
});

async function pollPurchase(purchaseId) {
    for (let i = 0; i < 15; i++) {
        await new Promise(r => setTimeout(r, 2000));
        try {
            const resp = await fetch(`${API_BASE}/purchase-status?id=${purchaseId}`, { headers: AUTH_HEADERS() });
            const data = await resp.json();
            if (data.status === 'completed') {
                if (data.result?.newBalance !== undefined) {
                    document.getElementById('balance-amount').textContent =
                        `${data.result.newBalance.toLocaleString()} ${modalItem.currency}`;
                }
                showToast('success', `Delivered! Spent ${data.result?.spent || '?'}`);
                return;
            }
            if (data.status === 'failed') {
                showToast('error', 'Purchase was rejected by the server.');
                return;
            }
        } catch (_) { /* retry */ }
    }
    showToast('warn', 'Purchase taking longer than expected. Check in-game.');
}

// ═══════════════════════════════════════════════════════════════════
// AUCTION PAGE
// ═══════════════════════════════════════════════════════════════════

let auctionData = [];
let currentAuctionCategory = null;

async function loadAuctionPage() {
    try {
        const [auctions, cats] = await Promise.all([
            api('/auctions'),
            api('/categories')
        ]);
        if (!auctions) return;
        auctionData = auctions;
        if (cats && cats.length > 0) {
            renderAuctionCategories(cats);
            selectAuctionCategory('__all', 'All Listings');
        } else {
            renderAuctions(auctionData);
        }
    } catch (e) {
        console.error('Failed to load auctions:', e);
    }

    document.getElementById('auction-search').onkeyup = debounce(() => {
        const q = document.getElementById('auction-search').value.toLowerCase();
        currentAuctionCategory = null;
        document.querySelectorAll('#auction-sidebar-categories .sidebar-item').forEach(s => s.classList.remove('active'));
        const filtered = auctionData.filter(a =>
            a.itemName.toLowerCase().includes(q) || a.seller.toLowerCase().includes(q)
        );
        renderAuctions(filtered);
    }, 200);
}

function renderAuctionCategories(cats) {
    const container = document.getElementById('auction-sidebar-categories');
    if (!container) return;
    container.innerHTML = '';

    // Special auction-type filters
    const specialFilters = [
        { id: '__all', name: 'All Listings', icon: null, count: auctionData.length },
        { id: '__bin', name: 'BIN Listings', icon: null, count: auctionData.filter(a => a.isBin).length },
        { id: '__bid', name: 'BID Listings', icon: null, count: auctionData.filter(a => !a.isBin).length },
    ];

    specialFilters.forEach(f => {
        const el = document.createElement('div');
        el.className = 'sidebar-item';
        el.dataset.catId = f.id;
        el.dataset.special = 'true';
        el.innerHTML = `
            <span style="font-weight:600;color:var(--text-primary)">${esc(f.name)}</span>
            <span class="item-count">${f.count}</span>
        `;
        el.addEventListener('click', () => selectAuctionCategory(f.id, f.name));
        container.appendChild(el);
    });

    // Separator
    const sep = document.createElement('div');
    sep.className = 'sidebar-separator';
    container.appendChild(sep);

    // Item categories
    cats.forEach(cat => {
        const el = document.createElement('div');
        el.className = 'sidebar-item';
        el.dataset.catId = cat.id;
        el.innerHTML = `
            <img src="${getItemIconUrl(cat.icon || 'stone')}" width="20" height="20"
                 style="image-rendering:pixelated" onerror="this.style.display='none'">
            <span>${esc(cat.name)}</span>
            <span class="item-count">${cat.itemCount}</span>
        `;
        el.addEventListener('click', () => selectAuctionCategory(cat.id, cat.name));
        container.appendChild(el);
    });
}

function selectAuctionCategory(catId, catName) {
    currentAuctionCategory = catId;
    document.getElementById('auction-search').value = '';

    document.querySelectorAll('#auction-sidebar-categories .sidebar-item').forEach(s => {
        s.classList.toggle('active', s.dataset.catId === catId);
    });

    let filtered;
    if (catId === '__all') {
        filtered = auctionData;
    } else if (catId === '__bin') {
        filtered = auctionData.filter(a => a.isBin);
    } else if (catId === '__bid') {
        filtered = auctionData.filter(a => !a.isBin);
    } else {
        filtered = auctionData.filter(a => a.categoryId === catId || a.category === catName);
    }
    renderAuctions(filtered, catName);
}

function renderAuctions(auctions, categoryName) {
    const grid = document.getElementById('auction-grid');
    const empty = document.getElementById('auction-empty');
    const emptyTitle = document.getElementById('auction-empty-title');
    const emptySub = document.getElementById('auction-empty-sub');

    if (!auctions || auctions.length === 0) {
        grid.innerHTML = '';
        if (categoryName) {
            emptyTitle.textContent = `No auctions in ${categoryName}`;
            emptySub.textContent = 'No items are currently listed in this category. Check back later!';
        } else {
            emptyTitle.textContent = 'No active auctions';
            emptySub.textContent = 'Check back later or list items in-game.';
        }
        empty.style.display = '';
        empty.classList.remove('fade-in');
        void empty.offsetWidth;
        empty.classList.add('fade-in');
        return;
    }
    empty.style.display = 'none';
    empty.classList.remove('fade-in');

    grid.innerHTML = auctions.map(a => {
        const now = Date.now();
        const remaining = a.expiration - now;
        const timeStr = remaining > 0 ? formatDuration(remaining) : 'Expired';
        const isExpiring = remaining > 0 && remaining < 300_000;
        const isOwner = a.sellerUuid === PLAYER_UUID;

        return `
        <div class="auction-card">
            <div class="auction-tag ${a.isBin ? 'bin' : 'bid'}">${a.isBin ? 'BIN' : 'BID'}</div>
            <div class="auction-card-header">
                <div class="auction-item-icon">
                    <img src="${getItemIconUrl(a.material || 'stone')}"
                         onerror="handleItemIconError(this, '${escJs(a.material || 'stone')}')" alt="">
                </div>
                <div class="auction-item-info">
                    <div class="auction-item-name">${esc(a.itemName)}</div>
                    <div class="auction-item-amount">${a.amount > 1 ? `x${a.amount}` : ''} by ${a.seller}</div>
                </div>
            </div>
            <div class="auction-details">
                <div class="auction-detail-row">
                    <span class="auction-detail-label">${a.isBin ? 'Price' : 'Current Bid'}</span>
                    <span class="auction-price-value">${a.currencySymbol}${a.price.toLocaleString()}</span>
                </div>
                ${a.highestBidder ? `
                <div class="auction-detail-row">
                    <span class="auction-detail-label">Top Bidder</span>
                    <span class="auction-detail-value">${a.highestBidder}</span>
                </div>` : ''}
            </div>
            <div class="auction-timer ${isExpiring ? 'expiring' : ''}">
                ${ICONS.CLOCK} ${timeStr}
            </div>
            <button class="btn-buy" 
                style="margin: 10px 15px 15px; width: calc(100% - 30px); font-size: 13px; padding: 10px; cursor: ${isOwner ? 'not-allowed' : 'pointer'}; opacity: ${isOwner ? 0.6 : 1};" 
                ${isOwner ? 'disabled' : ''}
                onclick="openAuctionModal(${a.id}, ${a.isBin}, '${escJs(a.itemName)}', '${escJs(a.material || 'stone')}', ${a.price}, '${escJs(a.currencySymbol)}')">
                ${isOwner ? 'Your Auction' : (a.isBin ? 'Buy It Now' : 'Place Bid')}
            </button>
        </div>`;
    }).join('');
}

function formatDuration(ms) {
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const h = Math.floor(m / 60);
    const d = Math.floor(h / 24);

    if (d > 0) return `${d}d ${h % 24}h`;
    if (h > 0) return `${h}h ${m % 60}m`;
    if (m > 0) return `${m}m ${s % 60}s`;
    return `${s}s`;
}

let currentAuctionContext = null;

function openAuctionModal(id, isBin, name, material, price, currencyStr) {
    currentAuctionContext = { id, isBin, price, currency: currencyStr };

    document.getElementById('auction-modal-title').textContent = isBin ? 'Buy It Now' : 'Place Bid';
    document.getElementById('auction-modal-item-name').textContent = name;
    document.getElementById('auction-modal-item-price').textContent = isBin ? `Price: ${currencyStr}${price.toLocaleString()}` : `Current: ${currencyStr}${price.toLocaleString()}`;

    // Update label: BIN shows "Price (per item)", BID shows "Your Bid (per item)"
    document.getElementById('auction-amount-label').textContent = isBin ? 'Price (per item)' : 'Your Bid (per item)';

    const iconEl = document.getElementById('auction-modal-icon');
    iconEl.innerHTML = `<img src="${getItemIconUrl(material)}" onerror="handleItemIconError(this, '${escJs(material)}', true)">`;

    const input = document.getElementById('auction-amount-input');
    if (isBin) {
        input.value = price;
        input.disabled = true;
        document.getElementById('auction-modal-btn-text').textContent = 'Confirm Purchase';
    } else {
        input.value = price + 1;
        input.min = price + 0.1;
        input.disabled = false;
        document.getElementById('auction-modal-btn-text').textContent = 'Confirm Bid';
    }

    document.getElementById('auction-modal').style.display = 'flex';
    document.getElementById('auction-modal').classList.remove('closing');
}

document.getElementById('auction-modal-close')?.addEventListener('click', () => {
    closeModal('auction-modal');
});
document.getElementById('auction-modal-cancel')?.addEventListener('click', () => {
    closeModal('auction-modal');
});

document.getElementById('auction-modal-submit')?.addEventListener('click', async () => {
    if (!currentAuctionContext) return;

    const btn = document.getElementById('auction-modal-submit');
    btn.disabled = true;
    btn.querySelector('.btn-buy-text').textContent = 'Processing...';

    try {
        const input = document.getElementById('auction-amount-input');
        const amount = parseFloat(input.value);

        if (isNaN(amount) || amount <= 0 || (!currentAuctionContext.isBin && amount <= currentAuctionContext.price)) {
            showToast('error', 'Invalid bid amount');
            btn.disabled = false;
            btn.querySelector('.btn-buy-text').textContent = currentAuctionContext.isBin ? 'Confirm Purchase' : 'Confirm Bid';
            return;
        }

        const resp = await fetch(`${API_BASE}/bid`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...AUTH_HEADERS() },
            body: JSON.stringify({ auctionId: currentAuctionContext.id, amount })
        });
        const result = await resp.json();

        if (result.success) {
            showToast('success', currentAuctionContext.isBin ? 'Purchase queued...' : 'Bid placed... waiting for server confirmation.');
            closeModal('auction-modal');
            pollPurchase(result.purchaseId);
        } else {
            showToast('error', result.error || 'Request failed');
        }
    } catch (e) {
        showToast('error', 'Network error');
    }

    btn.disabled = false;
    btn.querySelector('.btn-buy-text').textContent = currentAuctionContext.isBin ? 'Confirm Purchase' : 'Confirm Bid';
});

// ═══════════════════════════════════════════════════════════════════
// ORDERS PAGE
// ═══════════════════════════════════════════════════════════════════

async function loadOrdersPage() {
    try {
        const orders = await api('/orders');
        renderOrders(orders);
    } catch (e) {
        console.error('Failed to load orders:', e);
    }
}

function renderOrders(orders) {
    const body = document.getElementById('orders-body');
    const empty = document.getElementById('orders-empty');

    if (!orders || orders.length === 0) {
        body.innerHTML = '';
        empty.style.display = '';
        document.getElementById('orders-table-wrap').style.display = 'none';
        return;
    }
    empty.style.display = 'none';
    document.getElementById('orders-table-wrap').style.display = '';

    body.innerHTML = orders.map(o => {
        const pct = o.amountRequested > 0
            ? Math.round((o.amountFilled / o.amountRequested) * 100) : 0;
        const remaining = o.amountRequested - o.amountFilled;
        const isOwner = o.buyerUuid === PLAYER_UUID;

        return `
        <tr>
            <td>
                <div class="order-item-cell">
                    <img class="order-item-icon" src="${getItemIconUrl(o.material || 'stone')}"
                         loading="lazy" onerror="handleItemIconError(this, '${escJs(o.material || 'stone')}', true)" alt="">
                    <span class="order-item-name">${esc(o.itemName)}</span>
                </div>
            </td>
            <td>${o.buyer}</td>
            <td style="color:var(--accent);font-weight:600">${o.currencySymbol}${o.pricePerPiece.toLocaleString()}</td>
            <td>
                <div class="order-progress-wrap">
                    <div class="order-progress-text">${o.amountFilled} / ${o.amountRequested}</div>
                    <div class="order-progress-bar">
                        <div class="order-progress-fill" style="width:${pct}%"></div>
                    </div>
                </div>
            </td>
            <td>
                <button class="btn-buy" 
                    style="padding: 6px 12px; font-size: 12px; cursor: ${isOwner ? 'not-allowed' : 'pointer'}; opacity: ${isOwner ? 0.6 : 1};" 
                    ${isOwner ? 'disabled' : ''}
                    onclick="openOrderFillModal(${o.id}, '${escJs(o.itemName)}', '${escJs(o.material || 'stone')}', ${o.pricePerPiece}, '${escJs(o.currencySymbol)}', ${remaining})">
                    ${isOwner ? 'Your Order' : 'Fill Order'}
                </button>
            </td>
        </tr>`;
    }).join('');
}

let currentOrderContext = null;

function openOrderFillModal(id, name, material, price, currencyStr, maxAmount) {
    currentOrderContext = { id, price, currency: currencyStr, maxAmount };

    document.getElementById('order-fill-modal-item-name').textContent = name;
    document.getElementById('order-fill-modal-item-price').textContent = `Payout: ${currencyStr}${price.toLocaleString()} each`;

    const iconEl = document.getElementById('order-fill-modal-icon');
    iconEl.innerHTML = `<img src="${getItemIconUrl(material)}" onerror="handleItemIconError(this, '${escJs(material)}', true)">`;

    const input = document.getElementById('order-fill-amount-input');
    input.value = 1;
    input.max = Math.min(64, maxAmount);

    updateOrderFillTotal();
    document.getElementById('order-fill-modal').style.display = 'flex';
}

function updateOrderFillTotal() {
    if (!currentOrderContext) return;
    let amount = parseInt(document.getElementById('order-fill-amount-input').value) || 1;
    amount = Math.min(amount, currentOrderContext.maxAmount, 64);
    amount = Math.max(1, amount);
    const total = currentOrderContext.price * amount;
    document.getElementById('order-fill-modal-total').textContent = `${currentOrderContext.currency}${total.toLocaleString()}`;
}

document.getElementById('order-fill-amount-minus')?.addEventListener('click', () => {
    const inp = document.getElementById('order-fill-amount-input');
    inp.value = Math.max(1, (parseInt(inp.value) || 1) - 1);
    updateOrderFillTotal();
    updateQtyButtonStates('order-fill-amount-input', 'order-fill-amount-minus', 'order-fill-amount-plus', currentOrderContext?.maxAmount || 64);
});
document.getElementById('order-fill-amount-plus')?.addEventListener('click', () => {
    if (!currentOrderContext) return;
    const inp = document.getElementById('order-fill-amount-input');
    inp.value = Math.min(currentOrderContext.maxAmount, 64, (parseInt(inp.value) || 1) + 1);
    updateOrderFillTotal();
    updateQtyButtonStates('order-fill-amount-input', 'order-fill-amount-minus', 'order-fill-amount-plus', currentOrderContext?.maxAmount || 64);
});
document.getElementById('order-fill-amount-input')?.addEventListener('input', () => {
    updateOrderFillTotal();
    updateQtyButtonStates('order-fill-amount-input', 'order-fill-amount-minus', 'order-fill-amount-plus', currentOrderContext?.maxAmount || 64);
});

document.getElementById('order-fill-modal-close')?.addEventListener('click', () => {
    closeModal('order-fill-modal');
});
document.getElementById('order-fill-modal-cancel')?.addEventListener('click', () => {
    closeModal('order-fill-modal');
});

document.getElementById('order-fill-modal-submit')?.addEventListener('click', async () => {
    if (!currentOrderContext) return;

    const btn = document.getElementById('order-fill-modal-submit');
    btn.disabled = true;
    btn.querySelector('.btn-buy-text').textContent = 'Processing...';

    try {
        const input = document.getElementById('order-fill-amount-input');
        const amount = parseInt(input.value);

        if (isNaN(amount) || amount <= 0 || amount > currentOrderContext.maxAmount) {
            showToast('error', 'Invalid fill amount');
            btn.disabled = false;
            btn.querySelector('.btn-buy-text').textContent = 'Confirm Fill';
            return;
        }

        const resp = await fetch(`${API_BASE}/fill-order`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...AUTH_HEADERS() },
            body: JSON.stringify({ orderId: currentOrderContext.id, amount })
        });
        const result = await resp.json();

        if (result.success) {
            showToast('success', 'Fulfillment queued... checking your inventory in-game.');
            closeModal('order-fill-modal');
            pollPurchase(result.purchaseId);
        } else {
            showToast('error', result.error || 'Request failed');
        }
    } catch (e) {
        showToast('error', 'Network error');
    }

    btn.disabled = false;
    btn.querySelector('.btn-buy-text').textContent = 'Confirm Fill';
});

// ═══════════════════════════════════════════════════════════════════
// STOCKS PAGE (with charts)
// ═══════════════════════════════════════════════════════════════════

let stocksData = [];
let stocksRenderCount = 50;
let stocksObserver = null;

async function loadStocksPage() {
    try {
        const [stocks, history] = await Promise.all([api('/stocks'), api('/price-history')]);
        if (!stocks) return;
        stocksData = stocks;
        priceHistory = history || {};
        renderStocks(stocksData);
    } catch (e) {
        console.error('Failed to load stocks:', e);
    }

    document.getElementById('stocks-search').onkeyup = debounce(() => {
        stocksRenderCount = 50;
        const q = document.getElementById('stocks-search').value.toLowerCase();
        const sorted = getSortedStocks();
        const filtered = sorted.filter(s => s.name.toLowerCase().includes(q));
        renderStocksBody(filtered);
    }, 200);

    document.getElementById('stocks-sort').onchange = () => {
        stocksRenderCount = 50;
        const q = document.getElementById('stocks-search').value.toLowerCase();
        const sorted = getSortedStocks();
        const filtered = q ? sorted.filter(s => s.name.toLowerCase().includes(q)) : sorted;
        renderStocksBody(filtered);
    };
}

function getSortedStocks() {
    const sortBy = currentStocksSort;
    const copy = [...stocksData];
    switch (sortBy) {
        case 'name': copy.sort((a, b) => a.name.localeCompare(b.name)); break;
        case 'buyPrice': copy.sort((a, b) => b.buyPrice - a.buyPrice); break;
        case 'sellPrice': copy.sort((a, b) => b.sellPrice - a.sellPrice); break;
        case 'change': copy.sort((a, b) => Math.abs(b.change) - Math.abs(a.change)); break;
    }
    return copy;
}

function renderStocks(stocks) {
    stocksRenderCount = 50;
    renderStocksBody(getSortedStocks());
}

function renderStocksBody(stocks, isAppend = false) {
    const body = document.getElementById('stocks-body');
    if (!stocks || stocks.length === 0) {
        body.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:var(--text-muted)">No price data available</td></tr>';
        return;
    }

    if (stocksObserver) stocksObserver.disconnect();

    const start = isAppend ? stocksRenderCount - 50 : 0;
    const end = stocksRenderCount;
    const toRender = stocks.slice(start, end);

    const html = toRender.map(s => {
        const changeClass = s.change > 0.5 ? 'up' : s.change < -0.5 ? 'down' : 'neutral';
        const changeStr = s.change > 0 ? `+${s.change.toFixed(1)}%` : `${s.change.toFixed(1)}%`;
        const arrow = s.change > 0.5 ? ICONS.ARROW_UP : s.change < -0.5 ? ICONS.ARROW_DOWN : ICONS.ARROW_FLAT;

        return `
        <tr onclick="openStockChart('${escJs(s.key)}','${escJs(s.name)}','${escJs(s.material)}',${s.buyPrice},${s.sellPrice},${s.change},'${escJs(s.currencySymbol)}')">
            <td>
                <div class="stock-item-cell">
                    <img class="stock-item-icon" src="${getItemIconUrl(s.material || 'stone')}"
                         loading="lazy" onerror="handleItemIconError(this, '${escJs(s.material || 'stone')}', true)" alt="">
                    <span>${esc(s.name)}</span>
                </div>
            </td>
            <td style="color:var(--accent);font-weight:600">${s.buyPrice > 0 ? s.currencySymbol + s.buyPrice.toLocaleString(undefined, { maximumFractionDigits: 2 }) : '—'}</td>
            <td style="font-weight:500">${s.sellPrice > 0 ? s.currencySymbol + s.sellPrice.toLocaleString(undefined, { maximumFractionDigits: 2 }) : '—'}</td>
            <td><span class="stock-change ${changeClass}">${arrow} ${changeStr}</span></td>
        </tr>`;
    }).join('');

    if (isAppend) {
        body.querySelector('.stocks-loader-row')?.remove();
        body.insertAdjacentHTML('beforeend', html);
    } else {
        body.innerHTML = html;
    }

    if (stocks.length > stocksRenderCount) {
        const loader = document.createElement('tr');
        loader.className = 'stocks-loader-row';
        loader.innerHTML = '<td colspan="4" style="text-align:center;padding:15px;color:var(--text-muted)">Loading more...</td>';
        body.appendChild(loader);

        stocksObserver = new IntersectionObserver((entries) => {
            if (entries[0].isIntersecting) {
                stocksRenderCount += 50;
                renderStocksBody(stocks, true);
            }
        }, { rootMargin: '200px' });
        stocksObserver.observe(loader);
    }
}

// ── Stock Chart Modal ────────────────────────────────────────────

function openStockChart(key, name, material, buyPrice, sellPrice, change, currency) {
    document.querySelector('.chart-modal-overlay')?.remove();

    const changeClass = change > 0 ? 'change-up' : change < 0 ? 'change-down' : '';
    const changeStr = change > 0 ? `+${change.toFixed(1)}%` : `${change.toFixed(1)}%`;

    const overlay = document.createElement('div');
    overlay.className = 'chart-modal-overlay';
    overlay.innerHTML = `
        <div class="chart-modal">
            <div class="chart-modal-header">
                <h3>
                    <img src="${getItemIconUrl(material || 'stone')}" onerror="handleItemIconError(this, '${escJs(material || 'stone')}', true)" alt="">
                    ${esc(name)}
                </h3>
                <button class="chart-modal-close" onclick="this.closest('.chart-modal-overlay').remove()">
                    <svg class="icon" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </button>
            </div>
            <div class="chart-modal-body">
                <div class="chart-stats">
                    <div class="chart-stat">
                        <span class="chart-stat-label">Buy Price</span>
                        <span class="chart-stat-value price">${buyPrice.toLocaleString(undefined, { maximumFractionDigits: 2 })} ${currency}</span>
                    </div>
                    <div class="chart-stat">
                        <span class="chart-stat-label">Sell Price</span>
                        <span class="chart-stat-value">${sellPrice.toLocaleString(undefined, { maximumFractionDigits: 2 })} ${currency}</span>
                    </div>
                    <div class="chart-stat">
                        <span class="chart-stat-label">Change</span>
                        <span class="chart-stat-value ${changeClass}">${changeStr}</span>
                    </div>
                </div>
                <div class="chart-container">
                    <canvas id="stock-chart-canvas"></canvas>
                    <div class="chart-tooltip" id="chart-tooltip">
                        <div class="chart-tooltip-date"></div>
                        <div class="chart-tooltip-value"></div>
                    </div>
                </div>
            </div>
        </div>
    `;
    overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });
    document.body.appendChild(overlay);

    const history = priceHistory[key] || [];
    setTimeout(() => drawChart(history, change >= 0), 50);
}

// ── Canvas Chart Drawing ─────────────────────────────────────────

function drawChart(data, isPositive) {
    const canvas = document.getElementById('stock-chart-canvas');
    if (!canvas) return;

    const container = canvas.parentElement;
    const dpr = window.devicePixelRatio || 1;
    const w = container.clientWidth;
    const h = container.clientHeight;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';

    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    const padLeft = 60, padRight = 20, padTop = 20, padBottom = 40;
    const chartW = w - padLeft - padRight;
    const chartH = h - padTop - padBottom;

    const lineColor = isPositive ? '#1bd96a' : '#ef4444';
    const gradientTop = isPositive ? 'rgba(27, 217, 106, 0.25)' : 'rgba(239, 68, 68, 0.25)';
    const gradientBot = isPositive ? 'rgba(27, 217, 106, 0.0)' : 'rgba(239, 68, 68, 0.0)';

    if (!data || data.length < 2) {
        ctx.fillStyle = '#6c6c80';
        ctx.font = '14px Inter, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('No price history yet — data records every 10 minutes', w / 2, h / 2);
        return;
    }

    const prices = data.map(d => d.b);
    const times = data.map(d => d.t);
    const minPrice = Math.min(...prices) * 0.95;
    const maxPrice = Math.max(...prices) * 1.05;
    const priceRange = maxPrice - minPrice || 1;
    const minTime = times[0];
    const maxTime = times[times.length - 1];
    const timeRange = maxTime - minTime || 1;

    const xScale = (t) => padLeft + ((t - minTime) / timeRange) * chartW;
    const yScale = (p) => padTop + chartH - ((p - minPrice) / priceRange) * chartH;

    ctx.strokeStyle = '#2d2e36';
    ctx.lineWidth = 1;

    const yTicks = 5;
    ctx.fillStyle = '#6c6c80';
    ctx.font = '11px Inter, sans-serif';
    ctx.textAlign = 'right';
    for (let i = 0; i <= yTicks; i++) {
        const val = minPrice + (priceRange * i / yTicks);
        const y = yScale(val);
        ctx.beginPath(); ctx.moveTo(padLeft, y); ctx.lineTo(w - padRight, y); ctx.stroke();
        ctx.fillText(val.toFixed(val >= 100 ? 0 : 1), padLeft - 8, y + 4);
    }

    ctx.textAlign = 'center';
    let lastLabelX = -100;
    for (let i = 0; i < data.length; i++) {
        const t = times[i];
        const x = xScale(t);
        if (x - lastLabelX < 70 && i !== data.length - 1) continue;
        const date = new Date(t);
        const label = `${date.getDate()} ${date.toLocaleString('en', { month: 'short' })}`;
        ctx.fillText(label, x, h - 10);
        lastLabelX = x;
    }

    const gradient = ctx.createLinearGradient(0, padTop, 0, padTop + chartH);
    gradient.addColorStop(0, gradientTop);
    gradient.addColorStop(1, gradientBot);

    ctx.beginPath();
    ctx.moveTo(xScale(times[0]), yScale(prices[0]));
    for (let i = 1; i < data.length; i++) {
        const x = xScale(times[i]), y = yScale(prices[i]);
        const prevX = xScale(times[i - 1]), prevY = yScale(prices[i - 1]);
        const cpx = (prevX + x) / 2;
        ctx.bezierCurveTo(cpx, prevY, cpx, y, x, y);
    }
    ctx.lineTo(xScale(times[times.length - 1]), padTop + chartH);
    ctx.lineTo(xScale(times[0]), padTop + chartH);
    ctx.closePath();
    ctx.fillStyle = gradient;
    ctx.fill();

    ctx.beginPath();
    ctx.moveTo(xScale(times[0]), yScale(prices[0]));
    for (let i = 1; i < data.length; i++) {
        const x = xScale(times[i]), y = yScale(prices[i]);
        const prevX = xScale(times[i - 1]), prevY = yScale(prices[i - 1]);
        const cpx = (prevX + x) / 2;
        ctx.bezierCurveTo(cpx, prevY, cpx, y, x, y);
    }
    ctx.strokeStyle = lineColor;
    ctx.lineWidth = 2.5;
    ctx.stroke();

    const lastX = xScale(times[times.length - 1]);
    const lastY = yScale(prices[prices.length - 1]);
    ctx.beginPath(); ctx.arc(lastX, lastY, 5, 0, Math.PI * 2); ctx.fillStyle = lineColor; ctx.fill();
    ctx.beginPath(); ctx.arc(lastX, lastY, 3, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill();

    const tooltip = document.getElementById('chart-tooltip');
    canvas.onmousemove = (e) => {
        const rect = canvas.getBoundingClientRect();
        const mx = e.clientX - rect.left;
        const my = e.clientY - rect.top;
        if (mx < padLeft || mx > w - padRight) { tooltip.classList.remove('visible'); return; }

        let closest = 0, closestDist = Infinity;
        for (let i = 0; i < data.length; i++) {
            const dx = Math.abs(xScale(times[i]) - mx);
            if (dx < closestDist) { closestDist = dx; closest = i; }
        }

        const d = data[closest];
        const px = xScale(d.t), py = yScale(d.b);
        const date = new Date(d.t);
        const dateStr = date.toLocaleDateString('en', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

        tooltip.querySelector('.chart-tooltip-date').textContent = dateStr;
        tooltip.querySelector('.chart-tooltip-value').textContent = `Buy: ${d.b.toLocaleString(undefined, { maximumFractionDigits: 2 })}  Sell: ${d.s.toLocaleString(undefined, { maximumFractionDigits: 2 })}`;

        let tx = px + 12, ty = py - 40;
        if (tx + 180 > w) tx = px - 190;
        if (ty < 0) ty = py + 10;
        tooltip.style.left = tx + 'px';
        tooltip.style.top = ty + 'px';
        tooltip.classList.add('visible');
    };
    canvas.onmouseleave = () => { tooltip.classList.remove('visible'); };
}

// ═══════════════════════════════════════════════════════════════════
// UTILITIES
// ═══════════════════════════════════════════════════════════════════

async function api(endpoint) {
    const resp = await fetch(`${API_BASE}${endpoint}`, { headers: AUTH_HEADERS() });
    if (resp.status === 401) {
        showError('Session expired. Use /web in-game to get a new link.');
        return null;
    }
    if (!resp.ok) throw new Error(`API ${resp.status}`);
    return resp.json();
}

function showToast(type, msg) {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = msg;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 4000);
}

function esc(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
        .replace(/`/g, '&#96;');
}

function escJs(str) {
    return String(str)
        .replace(/\\/g, '\\\\')
        .replace(/'/g, "\\'")
        .replace(/"/g, '\\"')
        .replace(/`/g, '\\`')
        .replace(/\n/g, '\\n')
        .replace(/\r/g, '\\r');
}

function debounce(fn, ms) {
    let timer;
    return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
}

// ── Modal Close Animation ────────────────────────────────────────
function closeModal(overlayId) {
    const overlay = document.getElementById(overlayId);
    if (!overlay) return;
    overlay.classList.add('closing');
    overlay.addEventListener('animationend', () => {
        overlay.style.display = 'none';
        overlay.classList.remove('closing');
    }, { once: true });
}

// ── Update +/- button disabled states ──────────────────────────
function updateQtyButtonStates(inputId, minusId, plusId, maxQty) {
    const val = parseInt(document.getElementById(inputId)?.value) || 1;
    const minusBtn = document.getElementById(minusId);
    const plusBtn = document.getElementById(plusId);
    if (minusBtn) {
        if (val <= 1) minusBtn.classList.add('at-limit');
        else minusBtn.classList.remove('at-limit');
    }
    if (plusBtn) {
        if (val >= maxQty) plusBtn.classList.add('at-limit');
        else plusBtn.classList.remove('at-limit');
    }
}

// ── Custom Select Dropdown ──────────────────────────────────────
let currentStocksSort = 'name';

function initCustomSelects() {
    document.querySelectorAll('.custom-select').forEach(sel => {
        const trigger = sel.querySelector('.custom-select-trigger');
        const options = sel.querySelector('.custom-select-options');
        const valueSpan = sel.querySelector('.custom-select-value');
        if (!trigger || !options) return;

        trigger.addEventListener('click', (e) => {
            e.stopPropagation();
            const isOpen = trigger.getAttribute('aria-expanded') === 'true';
            closeAllCustomSelects();
            if (!isOpen) {
                trigger.setAttribute('aria-expanded', 'true');
                options.classList.add('open');
            }
        });

        options.querySelectorAll('li').forEach(li => {
            li.addEventListener('click', () => {
                const val = li.getAttribute('data-value');
                const text = li.textContent;
                valueSpan.textContent = text;
                options.querySelectorAll('li').forEach(l => l.classList.remove('selected'));
                li.classList.add('selected');
                trigger.setAttribute('aria-expanded', 'false');
                options.classList.remove('open');

                currentStocksSort = val;
                stocksRenderCount = 50;
                const q = document.getElementById('stocks-search')?.value?.toLowerCase() || '';
                const sorted = getSortedStocks();
                const filtered = q ? sorted.filter(s => s.name.toLowerCase().includes(q)) : sorted;
                renderStocksBody(filtered);
            });
        });
    });

    document.addEventListener('click', closeAllCustomSelects);
}

function closeAllCustomSelects() {
    document.querySelectorAll('.custom-select-trigger[aria-expanded="true"]').forEach(t => {
        t.setAttribute('aria-expanded', 'false');
        t.nextElementSibling?.classList.remove('open');
    });
}

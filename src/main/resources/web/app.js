/* ═══════════════════════════════════════════════════════════════════
   Aurelium Market — Web Dashboard Client
   ═══════════════════════════════════════════════════════════════════ */

(() => {
    'use strict';

    // ── State ────────────────────────────────────────────────────────
    const state = {
        token: null,
        player: null,
        categories: [],
        currentCategory: null,
        currentPage: 0,
        totalPages: 1,
        searchQuery: '',
        searchTimeout: null,
        selectedItem: null,
    };

    // ── DOM Refs ─────────────────────────────────────────────────────
    const $ = id => document.getElementById(id);

    const dom = {
        loading: $('loading-overlay'),
        balanceAmt: $('balance-amount'),
        playerName: $('player-name'),
        playerAvatar: $('player-avatar'),
        sidebar: $('sidebar-categories'),
        grid: $('items-grid'),
        pagination: $('pagination'),
        prevPage: $('prev-page'),
        nextPage: $('next-page'),
        pageInfo: $('page-info'),
        emptyState: $('empty-state'),
        breadcrumb: $('breadcrumb'),
        searchInput: $('search-input'),

        // Modal
        buyModal: $('buy-modal'),
        modalTitle: $('modal-title'),
        modalIcon: $('modal-icon'),
        modalName: $('modal-item-name'),
        modalPrice: $('modal-item-price'),
        modalTotal: $('modal-total'),
        modalClose: $('modal-close'),
        modalCancel: $('modal-cancel'),
        modalBuy: $('modal-buy'),
        amountInput: $('amount-input'),
        amountMinus: $('amount-minus'),
        amountPlus: $('amount-plus'),
    };

    // ── Init ─────────────────────────────────────────────────────────

    function init() {
        // Parse token from URL
        const params = new URLSearchParams(window.location.search);
        state.token = params.get('token');

        if (!state.token) {
            showError('Missing session token. Use /web in-game to get a link.');
            return;
        }

        // Load initial data
        Promise.all([fetchPlayer(), fetchCategories()])
            .then(() => {
                dom.loading.classList.add('hidden');
                setTimeout(() => dom.loading.style.display = 'none', 400);
            })
            .catch(err => {
                showError('Failed to connect: ' + err.message);
            });

        // Bind events
        bindEvents();
    }

    // ── API ──────────────────────────────────────────────────────────

    function api(endpoint, method = 'GET') {
        const sep = endpoint.includes('?') ? '&' : '?';
        const url = endpoint + sep + 'token=' + encodeURIComponent(state.token);

        return fetch(url, { method })
            .then(res => res.json().then(data => {
                if (!res.ok) throw new Error(data.error || 'Request failed');
                return data;
            }));
    }

    async function fetchPlayer() {
        const data = await api('/api/player');
        state.player = data;
        dom.playerName.textContent = data.name;
        dom.playerAvatar.style.backgroundImage =
            `url(https://mc-heads.net/avatar/${data.name}/28)`;

        // Show default currency balance
        dom.balanceAmt.textContent = formatBal(data);
    }

    function formatBal(player) {
        const cur = player.defaultCurrency;
        const bal = player.balances[cur] ?? 0;
        return bal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    async function fetchCategories() {
        const data = await api('/api/categories');
        state.categories = data;
        renderSidebar();

        // Load first category by default
        if (data.length > 0) {
            selectCategory(data[0]);
        }
    }

    async function fetchItems(category, page = 0) {
        const endpoint = `/api/items?category=${encodeURIComponent(category.id)}&page=${page}`;
        const data = await api(endpoint);
        renderItems(data);
        state.currentPage = data.page;
        state.totalPages = data.totalPages;
        updatePagination();
    }

    async function searchItems(query, page = 0) {
        const endpoint = `/api/search?q=${encodeURIComponent(query)}&page=${page}`;
        const data = await api(endpoint);
        renderItems(data);
        state.currentPage = data.page;
        state.totalPages = data.totalPages;
        updatePagination();
    }

    async function buyItem(itemKey, amount) {
        const endpoint = `/api/buy?item=${encodeURIComponent(itemKey)}&amount=${amount}`;
        const data = await api(endpoint, 'POST');
        return data;
    }

    // ── Render ───────────────────────────────────────────────────────

    function renderSidebar() {
        dom.sidebar.innerHTML = '';
        state.categories.forEach(cat => {
            const el = document.createElement('div');
            el.className = 'sidebar-item';
            el.innerHTML = `
                <span>${getIcon(cat.icon)}</span>
                <span>${cat.name}</span>
                <span class="item-count">${cat.itemCount}</span>
            `;
            el.addEventListener('click', () => selectCategory(cat));
            dom.sidebar.appendChild(el);
        });
    }

    function renderItems(data) {
        dom.grid.innerHTML = '';

        if (!data.items || data.items.length === 0) {
            dom.grid.style.display = 'none';
            dom.emptyState.style.display = 'block';
            return;
        }

        dom.grid.style.display = '';
        dom.emptyState.style.display = 'none';

        data.items.forEach(item => {
            const card = document.createElement('div');
            card.className = 'item-card';
            card.innerHTML = `
                <div class="item-card-header">
                    <div class="item-icon">
                        <img src="https://mc.nerothe.com/img/1.21.11/${item.material}" 
                             onerror="this.parentElement.textContent='📦'" alt="">
                    </div>
                    <div class="item-name">${escHtml(item.name)}</div>
                </div>
                <div class="item-card-footer">
                    <span class="item-price">${escHtml(item.priceFormatted)}</span>
                    <span class="item-currency">${escHtml(item.currency)}</span>
                </div>
            `;
            card.addEventListener('click', () => openBuyModal(item));
            dom.grid.appendChild(card);
        });
    }

    function updatePagination() {
        if (state.totalPages <= 1) {
            dom.pagination.style.display = 'none';
            return;
        }
        dom.pagination.style.display = '';
        dom.prevPage.disabled = state.currentPage <= 0;
        dom.nextPage.disabled = state.currentPage >= state.totalPages - 1;
        dom.pageInfo.textContent = `Page ${state.currentPage + 1} / ${state.totalPages}`;
    }

    function updateBreadcrumb() {
        let html = '';
        if (state.searchQuery) {
            html = `<span class="breadcrumb-item" onclick="window._clearSearch()">All Categories</span>
                    <span class="breadcrumb-sep"></span>
                    <span class="breadcrumb-item active">Search: "${escHtml(state.searchQuery)}"</span>`;
        } else if (state.currentCategory) {
            html = `<span class="breadcrumb-item" onclick="window._clearSearch()">All Categories</span>
                    <span class="breadcrumb-sep"></span>
                    <span class="breadcrumb-item active">${escHtml(state.currentCategory.name)}</span>`;
        } else {
            html = `<span class="breadcrumb-item active">All Categories</span>`;
        }
        dom.breadcrumb.innerHTML = html;
    }

    // ── Category Selection ───────────────────────────────────────────

    function selectCategory(cat) {
        state.currentCategory = cat;
        state.currentPage = 0;
        state.searchQuery = '';
        dom.searchInput.value = '';

        // Update sidebar active state
        document.querySelectorAll('.sidebar-item').forEach((el, i) => {
            el.classList.toggle('active', state.categories[i] === cat);
        });

        updateBreadcrumb();
        fetchItems(cat, 0);
    }

    // ── Search ───────────────────────────────────────────────────────

    function handleSearch(query) {
        state.searchQuery = query;
        state.currentPage = 0;

        // Clear sidebar active
        document.querySelectorAll('.sidebar-item').forEach(el => el.classList.remove('active'));

        updateBreadcrumb();

        if (query.length === 0) {
            if (state.currentCategory) {
                selectCategory(state.currentCategory);
            }
            return;
        }

        searchItems(query, 0);
    }

    // ── Buy Modal ────────────────────────────────────────────────────

    function openBuyModal(item) {
        state.selectedItem = item;

        dom.modalName.textContent = item.name;
        dom.modalPrice.textContent = item.priceFormatted + ' each';
        dom.modalIcon.innerHTML = `<img src="https://mc.nerothe.com/img/1.21.11/${item.material}" 
                                        onerror="this.parentElement.textContent='📦'" alt="" 
                                        style="width:32px;height:32px;image-rendering:pixelated">`;
        dom.amountInput.value = 1;
        updateModalTotal();

        dom.buyModal.style.display = '';
    }

    function closeModal() {
        dom.buyModal.style.display = 'none';
        state.selectedItem = null;
    }

    function updateModalTotal() {
        if (!state.selectedItem) return;
        const amount = parseInt(dom.amountInput.value) || 1;
        const total = state.selectedItem.price * amount;
        dom.modalTotal.textContent = total.toLocaleString('en-US', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2
        });
    }

    async function handleBuy() {
        if (!state.selectedItem) return;
        const amount = parseInt(dom.amountInput.value) || 1;

        dom.modalBuy.disabled = true;
        dom.modalBuy.querySelector('.btn-buy-text').textContent = 'Processing...';

        try {
            const result = await buyItem(state.selectedItem.key, amount);
            toast('success', `Purchased ${amount}x ${state.selectedItem.name} for ${result.spent}`);

            // Update balance display
            dom.balanceAmt.textContent = parseFloat(result.newBalance)
                .toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

            closeModal();
        } catch (err) {
            toast('error', err.message);
        } finally {
            dom.modalBuy.disabled = false;
            dom.modalBuy.querySelector('.btn-buy-text').textContent = 'Purchase';
        }
    }

    // ── Events ───────────────────────────────────────────────────────

    function bindEvents() {
        // Search with debounce
        dom.searchInput.addEventListener('input', () => {
            clearTimeout(state.searchTimeout);
            state.searchTimeout = setTimeout(() => {
                handleSearch(dom.searchInput.value.trim());
            }, 300);
        });

        // ESC to clear search
        dom.searchInput.addEventListener('keydown', e => {
            if (e.key === 'Escape') {
                dom.searchInput.value = '';
                handleSearch('');
                dom.searchInput.blur();
            }
        });

        // Pagination
        dom.prevPage.addEventListener('click', () => {
            if (state.currentPage > 0) {
                state.currentPage--;
                if (state.searchQuery) {
                    searchItems(state.searchQuery, state.currentPage);
                } else if (state.currentCategory) {
                    fetchItems(state.currentCategory, state.currentPage);
                }
            }
        });

        dom.nextPage.addEventListener('click', () => {
            if (state.currentPage < state.totalPages - 1) {
                state.currentPage++;
                if (state.searchQuery) {
                    searchItems(state.searchQuery, state.currentPage);
                } else if (state.currentCategory) {
                    fetchItems(state.currentCategory, state.currentPage);
                }
            }
        });

        // Modal controls
        dom.modalClose.addEventListener('click', closeModal);
        dom.modalCancel.addEventListener('click', closeModal);
        dom.modalBuy.addEventListener('click', handleBuy);
        dom.buyModal.addEventListener('click', e => {
            if (e.target === dom.buyModal) closeModal();
        });

        // Amount controls
        dom.amountMinus.addEventListener('click', () => {
            const v = parseInt(dom.amountInput.value) || 1;
            dom.amountInput.value = Math.max(1, v - 1);
            updateModalTotal();
        });
        dom.amountPlus.addEventListener('click', () => {
            const v = parseInt(dom.amountInput.value) || 1;
            dom.amountInput.value = Math.min(64, v + 1);
            updateModalTotal();
        });
        dom.amountInput.addEventListener('input', updateModalTotal);

        // Keyboard shortcuts
        document.addEventListener('keydown', e => {
            if (e.key === '/' && document.activeElement !== dom.searchInput) {
                e.preventDefault();
                dom.searchInput.focus();
            }
            if (e.key === 'Escape' && dom.buyModal.style.display !== 'none') {
                closeModal();
            }
        });
    }

    // Expose for inline onclick in breadcrumb
    window._clearSearch = () => {
        dom.searchInput.value = '';
        state.searchQuery = '';
        if (state.currentCategory) {
            selectCategory(state.currentCategory);
        }
    };

    // ── Helpers ──────────────────────────────────────────────────────

    function toast(type, message) {
        const container = document.getElementById('toast-container');
        const el = document.createElement('div');
        el.className = `toast ${type}`;
        el.textContent = message;
        container.appendChild(el);
        setTimeout(() => {
            el.style.transition = 'opacity 0.3s ease';
            el.style.opacity = '0';
            setTimeout(() => el.remove(), 300);
        }, 4000);
    }

    function showError(message) {
        const overlay = dom.loading;
        overlay.innerHTML = `<div style="color:#ef4444;font-size:16px;text-align:center;padding:20px;">
            <p style="font-size:24px;margin-bottom:12px">⚠</p>
            <p>${escHtml(message)}</p>
        </div>`;
    }

    function escHtml(s) {
        const div = document.createElement('div');
        div.textContent = s;
        return div.innerHTML;
    }

    /** Map a material name to an emoji icon for the sidebar. */
    function getIcon(material) {
        const icons = {
            diamond_sword: '⚔️', golden_carrot: '🥕', diamond: '💎',
            blaze_rod: '🔥', oak_sapling: '🌳', redstone: '⚡',
            oak_log: '🪵', copper_block: '🟫', spawner: '🧟',
            white_wool: '🎨', bricks: '🧱', painting: '🖼️',
            enchanted_book: '📖', compass: '🧭'
        };
        return icons[material] || '📦';
    }

    // ── Start ────────────────────────────────────────────────────────
    init();

})();

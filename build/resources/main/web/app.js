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

    /**
     * Initialize the dashboard by reading the session token, starting initial data loads, and attaching UI event handlers.
     *
     * If the session token is missing, an error overlay is shown and initialization stops. When the token is present,
     * player and category data are fetched concurrently; on successful load the loading overlay is hidden. Event handlers
     * for the UI are bound during initialization.
     */

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

    /**
     * Send an HTTP request to the specified API endpoint and return the parsed JSON response.
     * @param {string} endpoint - The API endpoint path; existing query parameters are preserved and the session token will be appended.
     * @param {string} [method='GET'] - HTTP method to use for the request.
     * @returns {any} The parsed JSON response body.
     * @throws {Error} When the response status is not OK; message is taken from `data.error` if provided, otherwise "Request failed".
     */

    function api(endpoint, method = 'GET') {
        const sep = endpoint.includes('?') ? '&' : '?';
        const url = endpoint + sep + 'token=' + encodeURIComponent(state.token);

        return fetch(url, { method })
            .then(res => res.json().then(data => {
                if (!res.ok) throw new Error(data.error || 'Request failed');
                return data;
            }));
    }

    /**
     * Load current player data and update application state and player UI.
     *
     * Stores the fetched player object in `state.player`, sets the player name text,
     * updates the avatar background image, and updates the displayed default currency balance.
     */
    async function fetchPlayer() {
        const data = await api('/api/player');
        state.player = data;
        dom.playerName.textContent = data.name;
        dom.playerAvatar.style.backgroundImage =
            `url(https://mc-heads.net/avatar/${data.name}/28)`;

        // Show default currency balance
        dom.balanceAmt.textContent = formatBal(data);
    }

    /**
     * Format a player's balance for their default currency.
     * @param {Object} player - Player object with `defaultCurrency` (string) and `balances` (mapping of currency codes to numeric balances).
     * @returns {string} The player's balance for the default currency formatted with two decimal places using the en-US locale.
     */
    function formatBal(player) {
        const cur = player.defaultCurrency;
        const bal = player.balances[cur] ?? 0;
        return bal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    /**
     * Fetches the list of categories from the API, stores them in application state, renders the sidebar, and selects the first category if any exist.
     */
    async function fetchCategories() {
        const data = await api('/api/categories');
        state.categories = data;
        renderSidebar();

        // Load first category by default
        if (data.length > 0) {
            selectCategory(data[0]);
        }
    }

    /**
     * Load items for the given category and update the rendered item grid and pagination state.
     * @param {{id: string|number}} category - Category object whose `id` will be used to request items.
     * @param {number} [page=0] - Zero-based page index to fetch.
     */
    async function fetchItems(category, page = 0) {
        const endpoint = `/api/items?category=${encodeURIComponent(category.id)}&page=${page}`;
        const data = await api(endpoint);
        renderItems(data);
        state.currentPage = data.page;
        state.totalPages = data.totalPages;
        updatePagination();
    }

    /**
     * Fetches items matching a search query, renders the results, and updates pagination state.
     * @param {string} query - The search string to query the catalog.
     * @param {number} [page=0] - Zero-based page index to request.
     */
    async function searchItems(query, page = 0) {
        const endpoint = `/api/search?q=${encodeURIComponent(query)}&page=${page}`;
        const data = await api(endpoint);
        renderItems(data);
        state.currentPage = data.page;
        state.totalPages = data.totalPages;
        updatePagination();
    }

    /**
     * Send a purchase request for a specific item and return the server response.
     * @param {string} itemKey - The item's unique key or identifier.
     * @param {number} amount - The quantity to purchase.
     * @returns {Object} The parsed JSON response payload from the server for the purchase request.
     */
    async function buyItem(itemKey, amount) {
        const endpoint = `/api/buy?item=${encodeURIComponent(itemKey)}&amount=${amount}`;
        const data = await api(endpoint, 'POST');
        return data;
    }

    /**
     * Render the sidebar category list from the current application state.
     *
     * Clears the sidebar container and creates a `.sidebar-item` element for each
     * entry in `state.categories`, showing the category icon, name, and item count.
     * Each item receives a click handler that selects the corresponding category.
     */

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

    /**
     * Render a list of items into the items grid and update empty-state visibility.
     *
     * Populates the grid with item cards from the provided response, hides the empty-state when items exist, and shows the empty-state when the list is empty. Each rendered card is clickable and opens the purchase modal for that item.
     *
     * @param {Object} data - Response object from the items/search API.
     * @param {Array<Object>} data.items - Array of item objects to render. Each item is expected to include at least `name`, `material`, `priceFormatted`, `currency`, and `key`.
     */
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

    /**
     * Update pagination control visibility, enabled state, and displayed page info based on current pagination state.
     *
     * Hides the pagination when there is only one or zero pages. When visible, disables the previous button on the first page,
     * disables the next button on the last page, and sets the page info text to "Page X / Y".
     */
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

    /**
     * Update the breadcrumb trail to reflect the current search, selected category, or the default view.
     *
     * Updates the DOM breadcrumb element's HTML to show:
     * - a clickable "All Categories" segment that clears the search when applicable,
     * - and an active segment containing either the current search query or the current category name.
     * User-provided text shown in the breadcrumb is HTML-escaped.
     */
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

    /**
     * Selects a category for browsing and loads its first page of items.
     *
     * Clears any active search, resets pagination, updates the sidebar and breadcrumb,
     * and fetches items for the chosen category.
     * @param {Object} cat - Category object to select (should match an entry in state.categories).
     */

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

    /**
     * Apply a search query: update search state, reset pagination, clear sidebar selection, refresh the breadcrumb, and perform a search when the query is non-empty.
     * @param {string} query - The search string to apply; an empty string clears the search and restores the current category view if one is selected.
     */

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

    /**
     * Open the purchase modal for the given item and initialize its fields.
     * @param {{ key?: string, name: string, price: number, priceFormatted: string, material?: string }} item - Item data used to populate the modal. Required fields: `name` (display name) and `priceFormatted` (formatted per-item price). Optional fields: `key`, `price`, and `material` (used for the icon).
     */

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

    /**
     * Closes the purchase modal and clears the currently selected item.
     *
     * Hides the buy modal UI and sets `state.selectedItem` to `null`.
     */
    function closeModal() {
        dom.buyModal.style.display = 'none';
        state.selectedItem = null;
    }

    /**
     * Update the buy modal's displayed total price from the currently selected item's price and the amount input.
     *
     * If no item is selected the function does nothing. It reads the amount input (defaults to 1 on invalid values),
     * multiplies by the selected item's price, and writes the result to `dom.modalTotal.textContent` formatted to two decimal places.
     */
    function updateModalTotal() {
        if (!state.selectedItem) return;
        const amount = parseInt(dom.amountInput.value) || 1;
        const total = state.selectedItem.price * amount;
        dom.modalTotal.textContent = total.toLocaleString('en-US', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2
        });
    }

    /**
     * Process the purchase from the buy modal: submit the order, update UI, and show feedback.
     *
     * Disables the buy button and shows a processing state, reads the requested amount (defaults to 1),
     * attempts the purchase, displays a success toast and updates the visible balance and closes the modal on success,
     * displays an error toast on failure, and always re-enables the buy button and restores its label.
     */
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

    /**
     * Attach UI event listeners for search, pagination, modal interactions, quantity controls, and keyboard shortcuts.
     *
     * Sets up a debounced (300ms) search input and Escape key behavior to clear the search; prev/next pagination handlers that fetch either search results or category items depending on current state; modal controls for close, cancel, buy, and backdrop click; quantity stepper and input handling that clamp values between 1 and 64 and update the modal total; and global keyboard shortcuts that focus the search input on `/` and close the modal on `Escape`.
     */

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

    /**
     * Show a temporary toast notification in the page's toast container.
     *
     * Appends a div with class `toast` and the provided `type` to `#toast-container`,
     * sets its text to `message`, fades it out after 4 seconds with a 0.3s transition,
     * and then removes it from the DOM.
     * @param {string} type - Toast category used as an additional CSS class (e.g. "success", "error", "info") to control styling.
     * @param {string} message - The plaintext message to display inside the toast.
     */

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

    /**
     * Replace the loading overlay with a styled error panel showing the provided message.
     * @param {string} message - The error text to display (will be HTML-escaped).
     */
    function showError(message) {
        const overlay = dom.loading;
        overlay.innerHTML = `<div style="color:#ef4444;font-size:16px;text-align:center;padding:20px;">
            <p style="font-size:24px;margin-bottom:12px">⚠</p>
            <p>${escHtml(message)}</p>
        </div>`;
    }

    /**
     * Escape HTML special characters in a string so it can be safely inserted into HTML.
     * @param {string} s - The input string to escape.
     * @returns {string} The escaped string with HTML entities substituted for reserved characters.
     */
    function escHtml(s) {
        const div = document.createElement('div');
        div.textContent = s;
        return div.innerHTML;
    }

    /**
     * Return an emoji representing a material identifier for use in the UI.
     * @param {string} material - Material identifier (e.g., "diamond_sword", "oak_log").
     * @returns {string} Emoji for the given material, or the package emoji `📦` when no match is found.
     */
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

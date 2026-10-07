/**
 * Aurelium web dashboard.
 *
 * Drives the tabbed market / auction / orders / stocks layout in index.html.
 * The server id comes from the URL path (/shop/<server-id>) and the session
 * token from ?token=, matching the plugin's /web command.
 *
 * The backend is a queue, not an executor: a mutating POST returns a purchaseId
 * and we poll purchase-status until the plugin has actually executed it in-game.
 * Nothing here assumes success on the POST alone.
 */

let SERVER_ID = '';
let TOKEN = '';
let API_BASE = '';

let currentPage = 'market';
let categories = [];
let currentCategory = null;
let marketPage = 0;
let marketSearch = '';
let auctionFilter = 'all';
let auctionSearch = '';
let stocks = [];
let stocksSearch = '';
let stocksSort = 'name';
let stocksDir = 'asc';
let stocksStats = {};
let ordersSearch = '';
let ordersSort = 'pricePerPiece';
let ordersDir = 'desc';
let ordersFilter = 'all';
let defaultCurrency = '';
let playerUuid = '';
let balanceTimer = null;
let refreshTimer = null;
let countdownTimer = null;
let secondsUntilRefresh = 20;

const REFRESH_SECONDS = 20;

// Inline SVG rather than an emoji: glyph rendering varies per platform and the
// moon was the one remaining emoji in the chrome.
const ICON_MOON =
    '<svg class="inline-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"' +
    ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>';

const IMG_BASE = 'https://assets.mcasset.cloud/26.2/assets/minecraft/textures/item/';
const BLOCK_IMG_BASE = 'https://assets.mcasset.cloud/26.2/assets/minecraft/textures/block/';

// Shown when a material has no vanilla texture under any name we can derive
// (entity-rendered blocks such as chests/heads, and enchantment glyphs).
const FALLBACK_ICON =
    'data:image/svg+xml;charset=utf-8,' +
    encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' +
            '<rect width="32" height="32" rx="4" fill="#2a2f3a"/>' +
            '<path d="M16 6l3 6 6 .9-4.5 4.3 1 6.3L16 20.5 10.5 23.5l1-6.3L7 12.9 13 12z" ' +
            'fill="#8b94a8" opacity=".55"/></svg>'
    );

// Textures whose filename differs from their item id.
const TEXTURE_OVERRIDES = {
    enchanted_golden_apple: 'golden_apple',
    tipped_arrow: 'arrow',
    spawn_egg: 'pig_spawn_egg',
    red_bed: 'bed',
    white_bed: 'bed',
    powered_rail: 'block/powered_rail_on',
    detector_rail: 'block/detector_rail',
    activator_rail: 'block/activator_rail',
    minecart: 'item/minecart',
    oak_boat: 'item/oak_boat',
    water_bucket: 'item/bucket',
    lava_bucket: 'item/bucket',
    oak_door: 'item/oak_door',
    iron_door: 'item/iron_door',
    cake: 'block/cake_inner',
    melon: 'block/melon_side',
    melon_slice: 'block/melon_side',
    experience_bottle: 'item/experience_bottle',
    filled_map: 'item/map',
    knowledge_book: 'item/book',
    written_book: 'item/book',
    writable_book: 'item/book',
    enchanted_book: 'item/book',

    // Blocks whose texture is not derivable from the item name. Every value
    // below was verified against the CDN; the value pins "block/" or "item/".
    magma_block: 'block/magma',
    smooth_sandstone: 'block/sandstone',
    smooth_red_sandstone: 'block/red_sandstone',
    smooth_quartz: 'block/quartz_block_top',
    dispenser: 'block/dispenser_front',
    dropper: 'block/dropper_front',
    purpur_block: 'block/purpur_block',
    purpur_pillar: 'block/purpur_block',
    mossy_stone_bricks: 'block/mossy_stone_bricks',
    moss_block: 'block/moss_block',
    moss_carpet: 'block/green_wool',
    snow_block: 'block/snow',
    // Pressure plates have no texture of their own; the weighted block they are
    // cut from is the closest match.
    heavy_weighted_pressure_plate: 'block/heavy_core',
    light_weighted_pressure_plate: 'block/iron_block',
    chest: 'block/oak_planks',
    trapped_chest: 'block/oak_planks',
    ender_chest: 'block/obsidian',
    copper_chest: 'block/copper_block',
    exposed_copper_chest: 'block/exposed_copper',
    oxidized_copper_chest: 'block/oxidized_copper',
    weathered_copper_chest: 'block/weathered_copper',
    waxed_copper_chest: 'block/copper_block',
    waxed_exposed_copper_chest: 'block/exposed_copper',
    waxed_oxidized_copper_chest: 'block/oxidized_copper',
    waxed_weathered_copper_chest: 'block/weathered_copper',
    // Heads and skulls are entity-rendered, so the CDN has no block texture for
    // them; borrow the closest look-alike rather than 404ing.
    creeper_head: 'block/green_concrete',
    zombie_head: 'block/green_concrete',
    zombie_villager_head: 'block/emerald_block',
    piglin_head: 'block/pink_concrete',
    dragon_head: 'block/purple_concrete',
    skeleton_skull: 'block/bone_block_top',
    wither_skeleton_skull: 'block/soul_sand',
    decorated_pot: 'block/terracotta',
    carved_pumpkin: 'block/pumpkin_side',
    jack_o_lantern: 'block/jack_o_lantern',
    carved_pumpkin_face: 'block/pumpkin_side',
    // Enchantment glyphs are not inventory items.
    channeling: 'item/book',
    mending: 'item/book',
    infinity: 'item/book',
    multishot: 'item/book',
    flame: 'item/blaze_powder',

    // Copper doors and trapdoors are the only shapes the family rules cannot
    // reach, because the texture is <stage>_copper_door_top / _bottom. Point
    // them at the block face they are cut from.
    copper_door: 'block/copper_door_top',
    copper_trapdoor: 'block/copper_trapdoor',
    exposed_copper_door: 'block/exposed_copper_door_top',
    exposed_copper_trapdoor: 'block/exposed_copper_trapdoor',
    oxidized_copper_door: 'block/oxidized_copper_door_top',
    oxidized_copper_trapdoor: 'block/oxidized_copper_trapdoor',
    weathered_copper_door: 'block/weathered_copper_door_top',
    weathered_copper_trapdoor: 'block/weathered_copper_trapdoor',
    waxed_copper_door: 'block/copper_door_top',
    waxed_copper_trapdoor: 'block/copper_trapdoor',
    waxed_exposed_copper_door: 'block/exposed_copper_door_top',
    waxed_exposed_copper_trapdoor: 'block/exposed_copper_trapdoor',
    waxed_oxidized_copper_door: 'block/oxidized_copper_door_top',
    waxed_oxidized_copper_trapdoor: 'block/oxidized_copper_trapdoor',
    waxed_weathered_copper_door: 'block/weathered_copper_door_top',
    waxed_weathered_copper_trapdoor: 'block/weathered_copper_trapdoor',

    // Slabs/pieces of blocks whose own name is irregular. The base derivation
    // cannot reach these, so pin each one to its full block.
    purpur_slab: 'block/purpur_block',
    purpur_stairs: 'block/purpur_block',
    smooth_sandstone_slab: 'block/sandstone',
    smooth_sandstone_stairs: 'block/sandstone',
    smooth_red_sandstone_slab: 'block/red_sandstone',
    smooth_red_sandstone_stairs: 'block/red_sandstone',
    smooth_quartz_slab: 'block/quartz_block_top',
    smooth_quartz_stairs: 'block/quartz_block_top',
    deepslate_tile_slab: 'block/deepslate_tiles',
    deepslate_tile_stairs: 'block/deepslate_tiles',
    deepslate_tile_wall: 'block/deepslate_tiles',
    waxed_cut_copper_slab: 'block/cut_copper',
    waxed_cut_copper_stairs: 'block/cut_copper',
    waxed_exposed_cut_copper_slab: 'block/exposed_copper',
    waxed_exposed_cut_copper_stairs: 'block/exposed_copper',
    waxed_oxidized_cut_copper_slab: 'block/oxidized_copper',
    waxed_oxidized_cut_copper_stairs: 'block/oxidized_copper',
    waxed_weathered_cut_copper_slab: 'block/weathered_copper',
    waxed_weathered_cut_copper_stairs: 'block/weathered_copper',
    stripped_crimson_hyphae: 'block/stripped_crimson_stem',
    stripped_warped_hyphae: 'block/stripped_warped_stem',
    crimson_hyphae: 'block/crimson_stem',
    warped_hyphae: 'block/warped_stem',
    sticky_piston: 'block/piston_top',
};

// Suffixes whose items are rendered from the block they are cut from.
// Order matters: the first match wins when deriving the base block, so the
// longer/more specific suffixes come first (oak_hanging_sign, not oak_sign).
const DERIVED_SUFFIXES = [
    '_hanging_sign', '_wall', '_slab', '_stairs', '_pressure_plate', '_button',
    '_fence_gate', '_fence', '_trapdoor', '_sign', '_carpet', '_candle',
    '_sapling', '_banner', '_door', '_bed',
];

// Qualifies the base block (polished_granite -> granite).
const DERIVED_PREFIXES = [
    'polished_', 'smooth_', 'cut_', 'chiseled_', 'mossy_', 'red_', 'cobbled_',
    'deepslate_', 'stripped_', 'exposed_', 'weathered_', 'oxidized_', 'waxed_',
];

// Copper's oxidation stages. Waxed copper has no texture of its own, so it
// borrows the unwaxed block, which is visually near-identical.
const COPPER_STAGES = ['copper', 'exposed_copper', 'weathered_copper', 'oxidized_copper'];

// The complete set of wood/stem families in 26.2, i.e. the bases that actually
// have a _planks or _log texture. Offering those candidates for stone or metal
// bases only produces guaranteed 404s.
const WOOD_FAMILIES = new Set([
    'acacia', 'bamboo', 'birch', 'cherry', 'crimson', 'dark_oak', 'jungle',
    'mangrove', 'oak', 'pale_oak', 'spruce', 'warped',
]);

const isWoodFamily = (base) => {
    if (WOOD_FAMILIES.has(base)) {
        return true;
    }
    // stripped_oak, and the copper oxidation/waxing prefixes, keep the family
    // name underneath.
    return WOOD_FAMILIES.has(base.replace(/^stripped_/, ''));
};

/** Ordered texture file names to try for a material, most likely first. */
function iconCandidates(material) {
    const m = String(material || 'stone').toLowerCase();
    const out = [];
    const add = (name, dir) => {
        if (name && !out.some((c) => c.name === name && c.dir === dir)) {
            out.push({ dir, name });
        }
    };

    const direct = TEXTURE_OVERRIDES[m] || m;
    // An override may pin its directory with a "block/" or "item/" prefix.
    let directDir = null;
    let directName = direct;
    const pin = /^(item|block)\/(.+)$/.exec(direct);
    if (pin) {
        directDir = pin[1];
        directName = pin[2];
    }

    // A material whose name ends in a derived suffix (stone_slab, oak_fence, ...)
    // never has a texture of its own, so try the base block first. Doing it the
    // other way round costs a guaranteed 404 on every such item.
    let derivedBase = null;
    let matchedSuffix = null;
    for (const suf of DERIVED_SUFFIXES) {
        if (m.endsWith(suf) && m.length > suf.length) {
            derivedBase = m.slice(0, -suf.length);
            matchedSuffix = suf;
            break;
        }
    }
    if (derivedBase === null) {
        for (const suf of ['_wood', '_hyphae', '_stem', '_log']) {
            if (m.endsWith(suf) && m.length > suf.length) {
                derivedBase = m.slice(0, -suf.length);
                matchedSuffix = suf;
                break;
            }
        }
    }

    // A pinned override is authoritative: it was hand-checked against the CDN,
    // so try it first and skip the guesswork entirely.
    if (directDir) {
        add(directName, directDir);
    }

    if (derivedBase !== null) {
        // A carpet is the dyed wool of the same colour, so that is the right
        // texture and it goes first; nothing else maps onto wool.
        if (matchedSuffix === '_carpet') {
            add(derivedBase + '_wool', 'block');
        }
        // Brick blocks are plural in the texture name (nether_brick -> nether_bricks).
        if (/_brick$/.test(derivedBase)) {
            add(derivedBase + 's', 'block');
        }
        // Wood families lead with their planks, which is the texture a derived
        // piece actually looks like.
        if (isWoodFamily(derivedBase)) {
            // A stripped log keeps the "stripped_" infix in its texture name.
            const fam = derivedBase.replace(/^stripped_/, '');
            add(fam + '_planks', 'block');
            add(fam + '_planks', 'item');
            add(fam + '_log', 'block');
        }
        add(derivedBase, 'item');
        add(derivedBase, 'block');
        add(derivedBase + '_side', 'block');
    }

    if (!directDir) {
        add(directName, 'item');
    }
    add(m, 'item');
    if (!directDir) {
        add(directName, 'block');
    }
    add(m, 'block');

    // Multi-face blocks (logs, barrels, furnaces, melon, ...) keep their faces
    // as separate files.
    for (const face of ['_side', '_top', '_front']) {
        add(direct + face, 'block');
        add(m + face, 'block');
    }

    if (derivedBase !== null) {
        add(derivedBase + '_hanging_sign', 'block');
        add(derivedBase + '_sign', 'block');
    }

    // Plurals: nether_brick_slab -> nether_bricks. The base is singular
    // ("nether_brick") but the block texture is plural ("nether_bricks").
    // Handled above, right after the base is derived.

    // Iron-family blocks are named without the "iron_" prefix.
    const IRON = {
        weighted_pressure_plate: 'heavy_core',
        pressure_plate: 'light_weighted_pressure_plate',
        bars: 'iron_bars',
        block: 'iron_block',
        door: 'iron_door',
        trapdoor: 'iron_trapdoor',
        nugget: 'iron_nugget',
        ingot: 'iron_ingot',
    };
    for (const suf of Object.keys(IRON)) {
        if (m.endsWith('_' + suf) && m.length > suf.length + 1) {
            add(IRON[suf], 'block');
            add(IRON[suf], 'item');
        }
    }

    // Remaining derived pieces, in case the first suffix guess was wrong.
    for (const suf of DERIVED_SUFFIXES) {
        if (m.endsWith(suf) && m.length > suf.length) {
            const base = m.slice(0, -suf.length);
            add(base, 'block');
            add(base, 'item');
            add(base + '_side', 'block');
            add(base + '_top', 'block');
        }
    }

    // Wood families: oak_wood / oak_slab / stripped_oak_wood -> oak_planks.
    for (const suf of ['_wood', '_hyphae', '_log', '_stem', '_planks', '_leaves']) {
        if (m.endsWith(suf) && m.length > suf.length) {
            const base = m.slice(0, -suf.length);
            add(base + '_planks', 'block');
            add(base + '_log', 'block');
            add(base + '_wood', 'block');
        }
    }

    // Qualified variants: polished_granite -> granite, smooth_quartz -> quartz.
    for (const pre of DERIVED_PREFIXES) {
        if (m.startsWith(pre) && m.length > pre.length) {
            const base = m.slice(pre.length);
            add(base, 'block');
            add(base, 'item');
        }
    }

    // waxed_copper_* -> the matching unwaxed copper texture.
    if (m.startsWith('waxed_') && m.length > 6) {
        const base = m.slice(6);
        add(base, 'block');
        add(base, 'item');
        for (const stage of COPPER_STAGES) {
            if (stage.endsWith(base) || base.endsWith(stage)) {
                add(stage, 'block');
            }
        }
    }

    return out;
}

function iconUrlFor(entry) {
    return (entry.dir === 'item' ? IMG_BASE : BLOCK_IMG_BASE) + entry.name + '.png';
}

function itemIconUrl(material) {
    const cands = iconCandidates(material);
    return cands.length ? iconUrlFor(cands[0]) : FALLBACK_ICON;
}

/**
 * Walk the candidate chain on load failure. Bounded by the chain length so a
 * fully missing material ends on the inline placeholder instead of looping.
 */
function iconFallback(img, material) {
    const cands = iconCandidates(material);
    const step = Number(img.dataset.fallback || '0');
    if (step < cands.length) {
        img.dataset.fallback = String(step + 1);
        img.src = iconUrlFor(cands[step]);
        return;
    }
    img.removeAttribute('onerror');
    img.src = FALLBACK_ICON;
    img.style.visibility = 'visible';
}

/** Point a reused <img> at an item icon, resetting the fallback state. */
function setItemIcon(img, material) {
    if (!img) {
        return;
    }
    img.dataset.fallback = '0';
    img.style.visibility = 'visible';
    img.onerror = () => iconFallback(img, material);
    img.src = itemIconUrl(material);
}

function itemIconTag(material) {
    const key = String(material || 'stone').toLowerCase();
    return `<img src="${itemIconUrl(key)}" alt="" loading="lazy"
                 onerror="iconFallback(this, '${escJs(key)}')">`;
}

// ── bootstrap ───────────────────────────────────────────��───────────

document.addEventListener('DOMContentLoaded', () => {
    const parts = window.location.pathname.split('/');
    SERVER_ID = parts[parts.length - 1] || parts[parts.length - 2] || 'local';
    TOKEN = new URLSearchParams(window.location.search).get('token');
    API_BASE = `/api/${SERVER_ID}`;

    // Do not leave the token sitting in the address bar.
    if (TOKEN && window.history.replaceState) {
        const url = new URL(window.location);
        url.searchParams.delete('token');
        window.history.replaceState({}, '', url.pathname + url.search);
    }

    bindEvents();
    waitForSession();
});

async function waitForSession() {
    const overlay = document.getElementById('loading-overlay');
    const label = overlay ? overlay.querySelector('p') : null;
    const maxAttempts = 30;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
        try {
            const resp = await fetch(`${API_BASE}/player`, {
                headers: { Authorization: `Bearer ${TOKEN}` },
            });
            if (resp.ok) {
                onSessionReady(await resp.json());
                if (overlay) {
                    overlay.classList.add('hidden');
                }
                return;
            }
            if (resp.status === 401 && attempt > 4) {
                showError('Session expired. Use /web in-game to get a new link.');
                if (overlay) {
                    overlay.classList.add('hidden');
                }
                return;
            }
        } catch (_) {
            // backend not up yet
        }
        if (label) {
            label.textContent = 'Waiting for session...';
        }
        await sleep(1000);
    }

    showError('Could not reach the server. Is the Minecraft server still running?');
    if (overlay) {
        overlay.classList.add('hidden');
    }
}

function onSessionReady(player) {
    if (!player) {
        return;
    }
    const nameEl = document.getElementById('player-name');
    if (nameEl) {
        nameEl.textContent = player.name || 'Unknown';
    }
    renderAvatar(player);
    updateBalanceDisplay(player);

    // 30s balance poll; the whole page is untouched by it.
    if (balanceTimer) {
        clearInterval(balanceTimer);
    }
    balanceTimer = setInterval(async () => {
        if (document.hidden || !TOKEN) {
            return;
        }
        try {
            const p = await api('/player');
            if (p) {
                updateBalanceDisplay(p);
            }
        } catch (_) {
            /* transient */
        }
    }, 30000);

    // Return to the tab the reader was last on, not always the market.
    switchPage(lastPage());
    startAutoRefresh();
}

/**
 * Show the player's own head, falling back to their initial.
 *
 * The url comes from the server because the provider is configurable there. The
 * image is only swapped in once it has decoded, so a failure leaves the initial
 * in place rather than an empty box, and a stale /web session cannot leave a
 * previous player's face on screen.
 */
function renderAvatar(player) {
    const el = document.getElementById('player-avatar');
    if (!el || !player) {
        return;
    }
    const url = player.avatarUrl || '';
    // The 30s balance poll calls this again with the same player; skip the work
    // unless the identity or the image actually changed.
    if (el.dataset.avatarFor === `${player.name || ''}|${url}`) {
        return;
    }
    el.dataset.avatarFor = `${player.name || ''}|${url}`;

    const initial = (player.name || '?').charAt(0).toUpperCase();
    el.textContent = initial;
    el.classList.remove('has-skin');

    if (!url) {
        // Avatars disabled in the config: the initial is the whole answer.
        return;
    }
    const img = new Image();
    img.alt = '';
    img.referrerPolicy = 'no-referrer';
    img.onload = () => {
        if (document.getElementById('player-avatar') !== el) {
            return;
        }
        el.classList.add('has-skin');
        el.replaceChildren(img);
    };
    img.onerror = () => {
        /* No image for this player; the initial is already showing. */
    };
    img.src = url;
}

function updateBalanceDisplay(player) {
    if (!player) {
        return;
    }
    const el = document.getElementById('balance-amount');
    if (!el) {
        return;
    }
    defaultCurrency = player.defaultCurrency || defaultCurrency;
    // Needed to tell your own buy orders apart from other people's.
    playerUuid = player.uuid || playerUuid;
    const cur = player.defaultCurrency;
    const bal = player.balances?.[cur] ?? 0;
    el.textContent = `${Number(bal).toLocaleString(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    })} ${cur}`;
}

/**
 * A currency badge for a price, or nothing when the price is already in the
 * default currency. Repeating "Aurels" on every card told the reader nothing
 * they could not see from the header, but a non-default currency still has to be
 * spelled out or the numbers are ambiguous.
 */
function currencyBadge(currency) {
    if (!currency || currency === defaultCurrency) {
        return '';
    }
    return `<span class="item-currency">${esc(currency)}</span>`;
}

// ── auto refresh ───────────────────────────────────────────────────

function startAutoRefresh() {
    if (refreshTimer) {
        clearInterval(refreshTimer);
    }
    if (countdownTimer) {
        clearInterval(countdownTimer);
    }
    const el = document.getElementById('refresh-countdown');

    refreshTimer = setInterval(() => {
        if (document.hidden) {
            return;
        }
        secondsUntilRefresh = 0;
        refreshCurrentPage();
    }, REFRESH_SECONDS * 1000);

    countdownTimer = setInterval(() => {
        if (document.hidden) {
            if (el) {
                el.innerHTML = ICON_MOON + '<span>paused</span>';
            }
            return;
        }
        secondsUntilRefresh = Math.max(0, REFRESH_SECONDS - (REFRESH_SECONDS - (secondsUntilRefresh % REFRESH_SECONDS)));
        if (el) {
            el.textContent = `${((secondsUntilRefresh - 1) % REFRESH_SECONDS) + 1}s`;
        }
    }, 1000);

    // Refresh the moment the tab comes back rather than waiting out the timer.
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            refreshCurrentPage();
        }
    });
}

function refreshCurrentPage() {
    switch (currentPage) {
        case 'market':
            if (marketSearch) {
                searchPage(marketSearch, 0);
            } else if (currentCategory) {
                loadCategoryPage(currentCategory.id, currentCategory.name, marketPage);
            } else {
                loadAllItems();
            }
            break;
        case 'auction':
            loadAuctions();
            break;
        case 'orders':
            loadOrders();
            break;
        case 'stocks':
            loadStocks();
            break;
    }
}

// ── api ────────────────────────────────────────────────────────────

function authHeaders() {
    return { Authorization: `Bearer ${TOKEN}` };
}

async function api(endpoint) {
    const resp = await fetch(`${API_BASE}${endpoint}`, { headers: authHeaders() });
    if (resp.status === 401) {
        showError('Session expired. Use /web in-game to get a new link.');
        return null;
    }
    if (!resp.ok) {
        throw new Error(`API ${resp.status}`);
    }
    return resp.json();
}

async function post(endpoint, body) {
    const resp = await fetch(`${API_BASE}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders() },
        body: JSON.stringify(body),
    });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) {
        throw new Error(data.error || `API ${resp.status}`);
    }
    return data;
}

// ── market page ────────────────────────────────────────────────────

async function loadCategories() {
    const list = await api('/categories');
    if (!list) {
        return;
    }
    categories = list;
    renderSidebar();
}

function renderSidebar() {
    const box = document.getElementById('sidebar-categories');
    if (!box) {
        return;
    }
    // Rebuild from scratch: this runs on every category change and on the
    // auto-refresh, so appending would stack a fresh copy each time.
    box.replaceChildren();

    const all = document.createElement('div');
    all.className = 'sidebar-item' + (currentCategory === null ? ' active' : '');
    all.innerHTML =
        '<svg class="inline-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"' +
        ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
        '<rect x="8" y="2" width="8" height="4" rx="1"/>' +
        '<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/>' +
        '<line x1="8" y1="10" x2="16" y2="10"/>' +
        '<line x1="8" y1="14" x2="13" y2="14"/></svg>' +
        '<span>All Items</span>';
    all.onclick = () => selectCategory(null);
    box.appendChild(all);

    // De-duplicate by id: a category can be reported by more than one source.
    const seen = new Set();
    for (const cat of categories) {
        if (!cat || seen.has(cat.id)) {
            continue;
        }
        seen.add(cat.id);
        const el = document.createElement('div');
        el.className =
            'sidebar-item' + (currentCategory && currentCategory.id === cat.id ? ' active' : '');
        el.innerHTML =
            `<span>${esc(cat.name)}</span>` +
            `<span class="item-count">${cat.itemCount ?? 0}</span>`;
        el.onclick = () => selectCategory(cat);
        box.appendChild(el);
    }
}

function setBreadcrumb(catName) {
    const bc = document.getElementById('breadcrumb');
    if (!bc) {
        return;
    }
    bc.innerHTML = `<span class="breadcrumb-item active">${esc(catName || 'All Categories')}</span>`;
}

async function loadAllItems(page = 0) {
    marketPage = page;
    setBreadcrumb('All Categories');
    // ALL_ITEMS flattens every category server-side, so the "All" view is
    // genuinely all items rather than just the first category.
    const data = await api(`/items?category=ALL_ITEMS&page=${page}`);
    if (data) {
        renderItems(data.items);
        renderPagination(data, (p) => loadAllItems(p));
    }
}

async function selectCategory(cat) {
    currentCategory = cat;
    renderSidebar();
    setBreadcrumb(cat ? cat.name : 'All Categories');
    marketPage = 0;
    const data = await api(
        `/items?category=${encodeURIComponent(cat ? cat.id : 'ALL_ITEMS')}&page=0`
    );
    if (data) {
        renderItems(data.items);
        renderPagination(data, (p) => loadCategoryPage(cat, p));
    }
}

async function loadCategoryPage(cat, page = 0) {
    marketPage = page;
    const data = await api(
        `/items?category=${encodeURIComponent(cat ? cat.id : 'ALL_ITEMS')}&page=${page}`
    );
    if (data) {
        renderItems(data.items);
        renderPagination(data, (p) => loadCategoryPage(cat, p));
    }
}

async function searchPage(query, page) {
    marketPage = page;
    setBreadcrumb(`Search: ${query}`);
    const data = await api(`/search?q=${encodeURIComponent(query)}&page=${page}`);
    if (data) {
        renderItems(data.items);
        renderPagination(data, (p) => searchPage(query, p));
    }
}

function renderItems(items) {
    const grid = document.getElementById('items-grid');
    if (!grid) {
        return;
    }
    if (!items || items.length === 0) {
        grid.innerHTML = emptyStateHtml('No items found', 'Try a different search or category.');
        return;
    }
    grid.innerHTML = items
        .map(
            (item) => `
        <div class="item-card" data-action="buy" data-obj="${esc(JSON.stringify(item))}">
            <div class="item-card-header">
                <div class="item-icon">
                    ${itemIconTag(item.material)}
                </div>
                <div class="item-name">${esc(item.name)}</div>
            </div>
            <div class="item-card-footer">
                <div class="item-card-price">
                    <span class="item-price">${esc(item.priceFormatted)}</span>
                    ${currencyBadge(item.currency)}
                </div>
                <button class="btn-trade trade-buy card-action" type="button">Buy</button>
            </div>
        </div>`
        )
        .join('');
}

/**
 * The one paginator, shared by market, auctions, orders and stocks. Every one of
 * those endpoints answers with the same envelope, so this only has to work once.
 * Shows numbered pages with an ellipsis, and a "showing X-Y of Z" readout so the
 * size of the collection is visible rather than implied.
 */
function renderPagination(data, loadFn, mountId = 'pagination', label = '') {
    const box = document.getElementById(mountId);
    if (!box) {
        return;
    }
    const totalPages = data?.totalPages ?? 1;
    const totalItems = data?.totalItems ?? 0;
    const pageSize = data?.pageSize ?? 0;
    const page = data?.page ?? 0;

    if (totalItems === 0) {
        box.innerHTML = '';
        return;
    }

    const from = page * pageSize + 1;
    const to = Math.min(totalItems, (page + 1) * pageSize);
    const readout = `<span class="page-info">${esc(label ? `${label} ` : '')}${from.toLocaleString()}–${to.toLocaleString()} of ${totalItems.toLocaleString()}</span>`;

    if (totalPages <= 1) {
        box.innerHTML = `<div class="pager">${readout}</div>`;
        return;
    }

    box.innerHTML = `
        <div class="pager">
            <button class="page-btn" data-page="${page - 1}" ${page <= 0 ? 'disabled' : ''}>&larr;</button>
            ${pageNumbers(page, totalPages)
                .map((n) =>
                    n === '…'
                        ? '<span class="page-gap">…</span>'
                        : `<button class="page-btn${n === page ? ' active' : ''}" data-page="${n}">${n + 1}</button>`
                )
                .join('')}
            <button class="page-btn" data-page="${page + 1}" ${page >= totalPages - 1 ? 'disabled' : ''}>&rarr;</button>
            ${readout}
        </div>`;

    box.querySelectorAll('.page-btn[data-page]').forEach((btn) => {
        if (!btn.disabled) {
            btn.onclick = () => loadFn(Number(btn.getAttribute('data-page')));
        }
    });
}

/** Page numbers to show, with '…' marking the gaps around the current page. */
function pageNumbers(page, totalPages) {
    if (totalPages <= 7) {
        return Array.from({ length: totalPages }, (_, i) => i);
    }
    const out = [0];
    const start = Math.max(1, page - 1);
    const end = Math.min(totalPages - 2, page + 1);
    if (start > 1) {
        out.push('…');
    }
    for (let i = start; i <= end; i++) {
        out.push(i);
    }
    if (end < totalPages - 2) {
        out.push('…');
    }
    out.push(totalPages - 1);
    return out;
}

// ── auction page ───────────────────────────────────────────────────

const AUCTION_FILTERS = [
    { id: 'all', label: 'All' },
    { id: 'bin', label: 'Buy It Now' },
    { id: 'auction', label: 'Bids' },
    { id: 'stack', label: 'Stack' },
    { id: 'unit', label: 'Per Unit' },
];

function renderAuctionFilters() {
    const box = document.getElementById('auction-categories');
    if (!box) {
        return;
    }
    box.innerHTML = AUCTION_FILTERS.map(
        (f) =>
            `<div class="sidebar-item${f.id === auctionFilter ? ' active' : ''}" data-filter="${f.id}">` +
            `<span>${esc(f.label)}</span></div>`
    ).join('');
    box.querySelectorAll('.sidebar-item').forEach((el) => {
        el.onclick = () => {
            auctionFilter = el.getAttribute('data-filter');
            renderAuctionFilters();
            loadAuctions();
        };
    });
}

async function loadAuctions(page = 0) {
    const grid = document.getElementById('auctions-grid');
    if (!grid) {
        return;
    }
    renderAuctionFilters();
    // Search and paging happen server side; the sidebar filter is applied on top
    // because it is a client-side concern over a small result set.
    const data = await api(
        `/auctions?page=${page}&sort=expiration&dir=asc${auctionSearch ? `&q=${encodeURIComponent(auctionSearch)}` : ''}`
    );
    if (!data) {
        return;
    }
    let shown = data.items || [];

    if (auctionFilter === 'bin') {
        shown = shown.filter((a) => a.isBin);
    } else if (auctionFilter === 'auction') {
        shown = shown.filter((a) => !a.isBin);
    } else if (auctionFilter === 'stack') {
        shown = shown.filter((a) => a.purchaseMode === 'STACK');
    } else if (auctionFilter === 'unit') {
        shown = shown.filter((a) => a.purchaseMode === 'UNIT');
    }

    if (shown.length === 0) {
        grid.innerHTML = emptyStateHtml(
            'No auctions',
            auctionSearch || auctionFilter !== 'all'
                ? 'Nothing matches those filters.'
                : 'Nothing is listed right now.'
        );
        renderPagination(data, loadAuctions, 'auctions-pagination', 'Showing');
        return;
    }

    grid.innerHTML = shown
        .map(
            (a) => `
        <div class="auction-card" data-action="bid" data-obj="${esc(JSON.stringify(a))}">
            <div class="auction-card-header">
                <div class="auction-item-icon">
                    ${itemIconTag(a.material)}
                </div>
                <div class="auction-item-info">
                    <div class="auction-item-name">${esc(a.itemName)}</div>
                    <div class="auction-item-amount">x${a.remaining ?? a.amount}</div>
                </div>
                <span class="auction-tag ${a.isBin ? 'bin' : 'bid'}">${a.isBin ? 'BIN' : 'BID'}</span>
            </div>
            <div class="auction-details">
                <div class="auction-detail-row">
                    <span class="auction-detail-label">${a.isBin ? 'Price' : 'Current bid'}</span>
                    <span class="auction-price-value">${esc(money(a.price, a.currency, a.currencySymbol))}</span>
                </div>
                <div class="auction-detail-row">
                    <span class="auction-detail-label">Seller</span>
                    <span class="auction-detail-value">${esc(a.seller || 'Unknown')}</span>
                </div>
                <div class="auction-detail-row">
                    <span class="auction-detail-label">Ends in</span>
                    <span class="auction-timer" data-expires="${a.expiration}">--</span>
                </div>
            </div>
            <div class="auction-card-footer">
                <button class="btn-trade trade-buy card-action" type="button">${a.isBin ? 'Buy now' : 'Place bid'}</button>
            </div>
        </div>`
        )
        .join('');
    tickTimers();
    renderPagination(data, loadAuctions, 'auctions-pagination', 'Showing');
}

function tickTimers() {
    document.querySelectorAll('.auction-timer').forEach((el) => {
        const expires = Number(el.getAttribute('data-expires'));
        const left = expires - Date.now();
        el.textContent = formatDuration(left);
        el.classList.toggle('expiring', left > 0 && left < 5 * 60 * 1000);
    });
}

// ── orders page ────────────────────────────────────────────────────

async function loadOrders(page = 0) {
    const wrap = document.getElementById('orders-table');
    if (!wrap) {
        return;
    }
    // Sort, paging, search and the fill-state filter are all server side, so the
    // page count and the row set always agree.
    const data = await api(
        `/orders?page=${page}&sort=${encodeURIComponent(ordersSort)}` +
            `&dir=${ordersDir === 'asc' ? 'asc' : 'desc'}` +
            `&filter=${encodeURIComponent(ordersFilter)}` +
            `${ordersSearch ? `&q=${encodeURIComponent(ordersSearch)}` : ''}`
    );
    if (!data) {
        return;
    }
    renderOrdersStats(data.stats);
    updateOrderSortIndicators();
    const list = data.items || [];
    if (list.length === 0) {
        wrap.innerHTML = emptyStateHtml(
            'No matching orders',
            ordersSearch || ordersFilter !== 'all'
                ? 'Nothing matches those filters.'
                : 'Nobody is looking to buy right now.'
        );
        renderPagination(data, loadOrders, 'orders-pagination', 'Showing');
        return;
    }
    wrap.innerHTML = `
        <table class="orders-table">
            <thead>
                <tr>
                    <th class="col-sort" data-orders-sort="itemName">Item</th>
                    <th class="col-sort col-num" data-orders-sort="amountRequested">Wanted</th>
                    <th class="col-num">Filled</th>
                    <th class="col-sort col-num" data-orders-sort="pricePerPiece">Price Each</th>
                    <th class="col-sort" data-orders-sort="buyer">Buyer</th>
                    <th class="col-actions">Trade</th>
                </tr>
            </thead>
            <tbody>
                ${list
                    .map(
                        (o) => `
                    <tr>
                        <td>
                            <div class="order-item-cell">
                                <div class="order-item-icon">
                                    ${itemIconTag(o.material)}
                                </div>
                                <span>${esc(o.itemName)}</span>
                            </div>
                        </td>
                        <td class="col-num">${o.amountRequested}</td>
                        <td class="col-num">
                            <div class="order-progress">
                                <div class="order-progress-bar">
                                    <div class="order-progress-fill" style="width:${pct(o.amountFilled, o.amountRequested)}%"></div>
                                </div>
                                <span class="order-progress-text">${o.amountFilled}/${o.amountRequested}</span>
                            </div>
                        </td>
                        <td class="col-num">${esc(money(o.pricePerPiece, o.currency, o.currencySymbol))}</td>
                        <td>${esc(o.buyer || 'Unknown')}</td>
                        <td class="col-actions">${fillCell(o)}</td>
                    </tr>`
                    )
                    .join('')}
            </tbody>
        </table>`;
    renderPagination(data, loadOrders, 'orders-pagination', 'Showing');
}

/**
 * The action cell for one order. Your own order is labelled rather than left
 * looking fillable, because the server refuses it and the button used to
 * suggest otherwise.
 */
function fillCell(order) {
    if (isOwnOrder(order)) {
        return '<span class="order-own" title="You placed this order, so you cannot fill it">Yours</span>';
    }
    if (orderRemaining(order) <= 0) {
        return '<span class="muted">Filled</span>';
    }
    return `<button class="btn-trade trade-sell" data-action="fill" data-obj="${esc(JSON.stringify(order))}">Fill</button>`;
}

function handleOrdersSearch(input) {
    ordersSearch = input.value.trim();
    debouncedOrdersSearch();
}

// Orders summary strip. Figures come from the server and cover every open
// order, not just the filtered page.
function renderOrdersStats(stats) {
    const set = (id, text) => {
        const el = document.getElementById(id);
        if (el) {
            el.textContent = text;
        }
    };
    const s = stats || {};
    set('ostat-orders', Number(s.orders || 0).toLocaleString());
    set('ostat-best', s.best || '-');
    set('ostat-biggest', s.biggest || '-');
    set('ostat-escrow', Number(s.escrow || 0).toLocaleString(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    }));
}

function updateOrderSortIndicators() {
    document.querySelectorAll('[data-orders-sort]').forEach((th) => {
        const on = th.getAttribute('data-orders-sort') === ordersSort;
        th.classList.toggle('sorted', on);
        th.setAttribute('aria-sort', on ? (ordersDir === 'asc' ? 'ascending' : 'descending') : 'none');
        th.dataset.dir = on ? ordersDir : '';
    });
}

function toggleOrderSort(field) {
    if (ordersSort === field) {
        ordersDir = ordersDir === 'asc' ? 'desc' : 'asc';
    } else {
        ordersSort = field;
        ordersDir = field === 'itemName' || field === 'buyer' ? 'asc' : 'desc';
    }
    loadOrders(0);
}

function setOrderFilter(filter) {
    ordersFilter = filter;
    document.querySelectorAll('#orders-filters .chip').forEach((chip) => {
        chip.classList.toggle('active', chip.getAttribute('data-filter') === filter);
    });
    loadOrders(0);
}

// ── stocks page ────────────────────────────────────────────────────

async function loadStocks(page = 0) {
    const body = document.getElementById('stocks-body');
    if (!body) {
        return;
    }
    // Sorting and paging happen server side, otherwise the browser would only
    // ever be able to sort the rows it happened to fetch.
    const data = await api(
        `/stocks?page=${page}&sort=${encodeURIComponent(stocksSort)}` +
            `&dir=${stocksDir === 'asc' ? 'asc' : 'desc'}` +
            `${stocksSearch ? `&q=${encodeURIComponent(stocksSearch)}` : ''}`
    );
    if (!data) {
        return;
    }
    stocks = data.items || [];
    stocksStats = data.stats || {};
    // The sparklines come from the same history endpoint the chart modal uses.
    priceHistory = await loadPriceHistory().catch(() => ({}));
    renderStocks();
    renderPagination(data, loadStocks, 'stocks-pagination', 'Showing');
}

let priceHistory = {};

async function loadPriceHistory() {
    const data = await api('/price-history');
    return data && typeof data === 'object' ? data : {};
}

// Rows arrive already filtered, sorted and paged, so this only re-applies the
// bits that are purely presentational.
function filteredStocks() {
    return stocks;
}

// The figures come from the server, computed over every row rather than the
// visible page, so "top gainer" is the top gainer in the market and not just on
// this page of 28.
function renderStats(stats) {
    const set = (id, text) => {
        const el = document.getElementById(id);
        if (el) {
            el.textContent = text;
        }
    };
    const s = stats || {};
    const markets = Number(s.markets || 0);
    set('stat-markets', markets.toLocaleString());
    // With markets listed but none of them moving, say so rather than showing a
    // bare dash; the dash is reserved for "no markets at all".
    set('stat-gainer', s.gainer || (markets ? 'No movers' : '-'));
    set('stat-loser', s.loser || (markets ? 'No losers' : '-'));
    set('stat-volume', Number(s.volume || 0).toLocaleString());
}

/** Compact inline trend from the cached price history. */
function sparkline(key) {
    const series = priceHistory[key];
    if (!Array.isArray(series) || series.length < 2) {
        return '<span class="trend-flat">no data</span>';
    }
    const pts = series.slice(-40);
    const vals = pts.map((p) => Number(p.b ?? p.s ?? 0));
    const min = Math.min(...vals);
    const max = Math.max(...vals);
    const span = max - min || 1;
    const w = 96;
    const h = 24;
    const coords = vals
        .map((v, i) => {
            const x = (i / (vals.length - 1)) * w;
            const y = h - ((v - min) / span) * h;
            return `${x.toFixed(1)},${y.toFixed(1)}`;
        })
        .join(' ');
    const rising = vals[vals.length - 1] >= vals[0];
    const cls = rising ? 'up' : 'down';
    return `<svg class="sparkline ${cls}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"
                 role="img" aria-label="7 day trend, ${rising ? 'up' : 'down'}">
                <polyline points="${coords}" fill="none" stroke="currentColor" stroke-width="1.5"
                          vector-effect="non-scaling-stroke"/>
            </svg>`;
}

function renderStocks() {
    const body = document.getElementById('stocks-body');
    if (!body) {
        return;
    }
    const list = filteredStocks();
    renderStats(stocksStats);
    updateSortIndicators();

    if (list.length === 0) {
        body.innerHTML = `<tr><td colspan="7">${emptyStateHtml(
            'No markets',
            stocksSearch ? 'Nothing matches that search.' : 'No items are trading yet.'
        )}</td></tr>`;
        return;
    }
    body.innerHTML = list
        .map((s) => {
            const change = Number(s.change || 0);
            const cls = change > 0 ? 'up' : change < 0 ? 'down' : 'neutral';
            const sign = change > 0 ? '+' : '';
            const vol = Number(s.volume || 0);
            const trades = Number(s.trades || 0);
            return `
            <tr>
                <td>
                    <div class="stock-item-cell">
                        <div class="stock-item-icon">
                            ${itemIconTag(s.material)}
                        </div>
                        <div class="stock-item-text">
                            <span class="stock-item-name">${esc(s.name)}</span>
                            ${trades ? `<span class="stock-item-sub">${trades} trade${trades === 1 ? '' : 's'}</span>` : ''}
                        </div>
                    </div>
                </td>
                <td class="col-num">${esc(money(s.buyPrice, s.currency, s.currencySymbol))}</td>
                <td class="col-num stock-sell-price">${esc(money(s.sellPrice, s.currency, s.currencySymbol))}</td>
                <td class="col-num">${vol ? vol.toLocaleString() : '<span class="muted">&mdash;</span>'}</td>
                <td class="col-num">
                    <span class="stock-change ${cls}"${
                        s.changeBasis === '24h'
                            ? ''
                            : ' title="No price from 24h ago yet, so this is the change since the item was listed"'
                    }>${sign}${change.toFixed(2)}%${s.changeBasis === '24h' ? '' : '<span class="basis-tag">listing</span>'}</span>
                </td>
                <td class="col-trend">${sparkline(s.key)}</td>
                <td class="col-actions">
                    <button class="btn-trade trade-buy" data-action="buy" data-obj="${esc(JSON.stringify(s))}"
                            ${Number(s.buyPrice) > 0 ? '' : 'disabled'}>Buy</button>
                    <button class="btn-trade trade-sell" data-action="sell" data-obj="${esc(JSON.stringify(s))}"
                            ${Number(s.sellPrice) > 0 ? '' : 'disabled'}>Sell</button>
                </td>
            </tr>`;
        })
        .join('');
}

function updateSortIndicators() {
    document.querySelectorAll('.col-sort').forEach((th) => {
        const on = th.getAttribute('data-sort') === stocksSort;
        th.classList.toggle('sorted', on);
        th.setAttribute('aria-sort', on ? (stocksDir === 'asc' ? 'ascending' : 'descending') : 'none');
        th.dataset.dir = on ? stocksDir : '';
    });
}

/** Click a column header to sort by it; clicking again flips the direction. */
function toggleStockSort(field) {
    if (stocksSort === field) {
        stocksDir = stocksDir === 'asc' ? 'desc' : 'asc';
    } else {
        stocksSort = field;
        stocksDir = field === 'name' ? 'asc' : 'desc';
    }
    loadStocks(0);
}

let stocksSearchTimer = null;

function handleStocksSearch(input) {
    stocksSearch = input.value.trim();
    clearTimeout(stocksSearchTimer);
    stocksSearchTimer = setTimeout(() => loadStocks(0), 300);
}

let ordersSearchTimer = null;

function debouncedOrdersSearch() {
    clearTimeout(ordersSearchTimer);
    ordersSearchTimer = setTimeout(() => loadOrders(0), 300);
}

function openChartModal(stock) {
    openModal('chart-modal');
    const title = document.getElementById('chart-modal-title');
    if (title) {
        title.textContent = `${stock.name} - Price History`;
    }
    drawChart(document.getElementById('chart-modal-canvas'), stock.key);
}

// ── chart ──────────────────────────────────────────────────────────

async function drawChart(canvas, key) {
    if (!canvas) {
        return;
    }
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const history = (await api('/price-history')) || {};
    const points = history[key] || [];

    if (points.length < 2) {
        ctx.fillStyle = '#888';
        ctx.font = '14px Inter, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('Not enough history yet', canvas.width / 2, canvas.height / 2);
        return;
    }

    const prices = points.map((p) => Number(p.b ?? p.s ?? 0));
    const min = Math.min(...prices);
    const max = Math.max(...prices);
    const span = max - min || 1;
    const pad = 24;

    const x = (i) => pad + (i / (points.length - 1)) * (canvas.width - pad * 2);
    const y = (v) => canvas.height - pad - ((v - min) / span) * (canvas.height - pad * 2);

    // grid
    ctx.strokeStyle = 'rgba(255,255,255,0.08)';
    ctx.lineWidth = 1;
    for (let g = 0; g <= 4; g++) {
        const gy = pad + (g / 4) * (canvas.height - pad * 2);
        ctx.beginPath();
        ctx.moveTo(pad, gy);
        ctx.lineTo(canvas.width - pad, gy);
        ctx.stroke();
    }

    // area
    const grad = ctx.createLinearGradient(0, pad, 0, canvas.height - pad);
    grad.addColorStop(0, 'rgba(245,158,11,0.35)');
    grad.addColorStop(1, 'rgba(245,158,11,0.02)');
    ctx.beginPath();
    ctx.moveTo(x(0), canvas.height - pad);
    points.forEach((p, i) => ctx.lineTo(x(i), y(prices[i])));
    ctx.lineTo(x(points.length - 1), canvas.height - pad);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();

    // line
    ctx.beginPath();
    points.forEach((p, i) => (i ? ctx.lineTo(x(i), y(prices[i])) : ctx.moveTo(x(i), y(prices[i]))));
    ctx.strokeStyle = '#f59e0b';
    ctx.lineWidth = 2;
    ctx.stroke();

    // labels
    ctx.fillStyle = '#888';
    ctx.font = '11px Inter, sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(max.toFixed(2), pad, pad - 8);
    ctx.fillText(min.toFixed(2), pad, canvas.height - pad + 14);
}

// ── modals ─────────────────────────────────────────────────────────

function openModal(id) {
    const el = document.getElementById(id);
    if (el) {
        el.style.display = 'flex';
    }
}

function closeModal(id) {
    const el = document.getElementById(id);
    if (el) {
        el.style.display = 'none';
    }
}

let modalItem = null;
let modalAuction = null;
let modalOrder = null;

function openBuyModal(item) {
    // Market rows expose `price`; stock rows expose `buyPrice`. Normalise so
    // the same modal works from either page.
    modalItem = { ...item, price: Number(item.price ?? item.buyPrice ?? 0) };
    setItemIcon(document.getElementById('buy-item-icon'), item.material);
    const name = document.getElementById('buy-item-name');
    if (name) {
        name.textContent = item.name;
    }
    const qty = document.getElementById('amount-input');
    if (qty) {
        qty.value = 1;
    }
    updateStepperLimits('amount-input');
    updateBuyTotal();
    openModal('buy-modal');
}

function updateBuyTotal() {
    const el = document.getElementById('buy-total');
    const qty = document.getElementById('amount-input');
    if (!el || !modalItem) {
        return;
    }
    const n = Math.max(1, Number(qty?.value || 1));
    el.textContent = money(Number(modalItem.price || 0) * n, modalItem.currency, modalItem.currencySymbol);
}

async function confirmBuy() {
    if (!modalItem) {
        return;
    }
    const qty = document.getElementById('amount-input');
    const amount = Math.max(1, Number(qty?.value || 1));
    try {
        const res = await post('/buy', { item: modalItem.key, amount });
        closeModal('buy-modal');
        toast('success', `Queued ${amount}x ${modalItem.name}`);
        if (res.purchaseId) {
            await awaitPurchase(res.purchaseId, async (result) => {
                if (result?.error) {
                    toast('error', result.error);
                } else {
                    toast('success', `Delivered! Spent ${result?.spent || '?'}`);
                    const p = await api('/player');
                    updateBalanceDisplay(p);
                }
            });
        }
    } catch (e) {
        toast('error', e.message);
    }
}

// ── sell ───────────────────────────────────────────────────────────

let modalStock = null;

function openSellModal(stock) {
    // Same normalisation as openBuyModal: market rows carry `price`, stock rows
    // carry `sellPrice`.
    modalStock = { ...stock, price: Number(stock.price ?? stock.sellPrice ?? 0) };
    setItemIcon(document.getElementById('sell-item-icon'), stock.material);
    const name = document.getElementById('sell-item-name');
    if (name) {
        name.textContent = stock.name;
    }
    const qty = document.getElementById('sell-amount-input');
    if (qty) {
        qty.value = 1;
    }
    const hint = document.getElementById('sell-hint');
    if (hint) {
        hint.textContent = `- ${money(modalStock.price, stock.currency, stock.currencySymbol)} each`;
    }
    updateStepperLimits('sell-amount-input');
    updateSellTotal();
    openModal('sell-modal');
}

function updateSellTotal() {
    const el = document.getElementById('sell-total');
    const qty = document.getElementById('sell-amount-input');
    if (!el || !modalStock) {
        return;
    }
    const n = Math.max(1, Number(qty?.value || 1));
    el.textContent = money(
        Number(modalStock.price || 0) * n,
        modalStock.currency,
        modalStock.currencySymbol
    );
}

async function confirmSell() {
    if (!modalStock) {
        return;
    }
    const qty = document.getElementById('sell-amount-input');
    const amount = Math.max(1, Number(qty?.value || 1));
    try {
        const res = await post('/sell', { item: modalStock.key, amount });
        closeModal('sell-modal');
        toast('success', `Queued ${amount}x ${modalStock.name}`);
        if (res.purchaseId) {
            await awaitPurchase(res.purchaseId, async (result) => {
                if (result?.error) {
                    toast('error', result.error);
                } else {
                    toast('success', `Sold! Earned ${result?.earned || '?'}`);
                    const p = await api('/player');
                    updateBalanceDisplay(p);
                    loadStocks();
                }
            });
        }
    } catch (e) {
        toast('error', e.message);
    }
}

function openBidModal(auction) {
    modalAuction = auction;
    setItemIcon(document.getElementById('bid-item-icon'), auction.material);
    const name = document.getElementById('bid-item-name');
    if (name) {
        name.textContent = auction.itemName;
    }
    const input = document.getElementById('bid-amount-input');
    if (input) {
        input.value = auction.isBin ? Number(auction.price || 0).toFixed(2) : (Number(auction.price || 0) + 1).toFixed(2);
    }
    const title = document.getElementById('bid-modal-title');
    if (title) {
        title.textContent = auction.isBin ? 'Buy It Now' : 'Place Bid';
    }
    openModal('bid-modal');
}

async function confirmBid() {
    if (!modalAuction) {
        return;
    }
    const input = document.getElementById('bid-amount-input');
    const amount = Number(input?.value || 0);
    try {
        const res = await post('/bid', { auctionId: modalAuction.id, amount });
        closeModal('bid-modal');
        toast('success', modalAuction.isBin ? 'Purchase queued...' : 'Bid queued...');
        if (res.purchaseId) {
            await awaitPurchase(res.purchaseId, (result) => {
                if (result?.error) {
                    toast('error', result.error);
                } else {
                    toast('success', result?.spent ? `Done! Spent ${result.spent}` : 'Confirmed in-game.');
                }
                loadAuctions();
            });
        }
    } catch (e) {
        toast('error', e.message);
    }
}

function openFillModal(order) {
    modalOrder = order;
    setItemIcon(document.getElementById('fill-item-icon'), order.material);
    const name = document.getElementById('fill-item-name');
    if (name) {
        name.textContent = order.itemName;
    }
    // The stepper used to be free to go to 64 on an order that only wanted 10.
    // The server caps the fill at what is left, but asking for more than that
    // just silently fills less, so the control has to say so up front.
    const remaining = orderRemaining(order);
    const hint = document.getElementById('fill-hint');
    if (hint) {
        hint.textContent = remaining > 0 ? `- ${remaining} still needed` : '- order already filled';
    }
    const qty = document.getElementById('order-fill-amount-input');
    if (qty) {
        qty.max = String(Math.max(1, remaining));
        qty.value = String(Math.max(1, Math.min(1, remaining)));
    }
    updateStepperLimits('order-fill-amount-input');
    updateFillTotal();
    openModal('order-fill-modal');
}

/** How many items the order still wants. */
function orderRemaining(order) {
    if (!order) {
        return 0;
    }
    return Math.max(0, (Number(order.amountRequested) || 0) - (Number(order.amountFilled) || 0));
}

/** An order cannot be filled by the player who placed it. */
function isOwnOrder(order) {
    return Boolean(order && playerUuid) && order.buyerUuid === playerUuid;
}

function updateFillTotal() {
    const el = document.getElementById('fill-total');
    const qty = document.getElementById('order-fill-amount-input');
    if (!el || !modalOrder) {
        return;
    }
    const n = Math.max(1, Number(qty?.value || 1));
    el.textContent = money(Number(modalOrder.pricePerPiece || 0) * n, modalOrder.currency, modalOrder.currencySymbol);
}

async function confirmOrderFill() {
    if (!modalOrder) {
        return;
    }
    if (isOwnOrder(modalOrder)) {
        closeModal('order-fill-modal');
        toast('error', 'You cannot fill your own order');
        return;
    }
    const remaining = orderRemaining(modalOrder);
    if (remaining <= 0) {
        closeModal('order-fill-modal');
        toast('error', 'That order is already filled');
        return;
    }
    const qty = document.getElementById('order-fill-amount-input');
    // Clamp rather than trust: the field is typeable, not just steppable.
    const amount = Math.min(remaining, Math.max(1, Number(qty?.value || 1)));
    try {
        const res = await post('/fill-order', { orderId: modalOrder.id, amount });
        closeModal('order-fill-modal');
        toast('success', 'Fulfillment queued...');
        if (res.purchaseId) {
            await awaitPurchase(res.purchaseId, (result) => {
                if (result?.error) {
                    toast('error', result.error);
                } else {
                    toast('success', `Filled ${result?.filled ?? amount}x ${modalOrder.itemName}`);
                }
                loadOrders();
            });
        }
    } catch (e) {
        toast('error', e.message);
    }
}

/**
 * The POST only queues the action; the plugin executes it on the main thread and
 * marks it done. Poll until then so the toast reflects what actually happened.
 */
async function awaitPurchase(purchaseId, onDone) {
    for (let i = 0; i < 20; i++) {
        await sleep(1000);
        const data = await api(`/purchase-status?id=${encodeURIComponent(purchaseId)}`).catch(() => null);
        if (!data) {
            continue;
        }
        if (data.status === 'completed' || data.status === 'failed') {
            onDone(data.result);
            return;
        }
    }
    toast('error', 'Timed out waiting for the server to confirm.');
}

// ── page switching ─────────────────────────────────────────────────

const PAGE_KEY = 'aurelium.page';
const PAGES = ['market', 'auction', 'orders', 'stocks'];

function switchPage(page) {
    currentPage = page;
    rememberPage(page);
    PAGES.forEach((p) => {
        const el = document.getElementById(`page-${p}`);
        if (el) {
            el.classList.toggle('hidden', p !== page);
        }
    });
    document.querySelectorAll('.nav-tab').forEach((tab) => {
        tab.classList.toggle('active', tab.getAttribute('data-page') === page);
    });

    if (page === 'market') {
        loadCategories().then(() => (marketSearch ? searchPage(marketSearch, 0) : loadAllItems(0)));
    } else if (page === 'auction') {
        loadAuctions();
    } else if (page === 'orders') {
        loadOrders();
    } else if (page === 'stocks') {
        loadStocks();
    }
}

// Reloading used to drop the reader back on the market tab. Remember the tab so
// a refresh, or a crash, lands where they left off.
function rememberPage(page) {
    try {
        localStorage.setItem(PAGE_KEY, page);
    } catch (_) {
        /* private mode or storage disabled; the tab still works */
    }
}

function lastPage() {
    try {
        const stored = localStorage.getItem(PAGE_KEY);
        return PAGES.includes(stored) ? stored : 'market';
    } catch (_) {
        return 'market';
    }
}

// ── helpers ────────────────────────────────────────────────────────

/**
 * Wire up the declarative attributes in index.html.
 *
 * These used to be inline on* handlers. A Content-Security-Policy blocks inline
 * event handlers outright unless every value is hashed, and a hash list breaks
 * silently whenever a handler changes, so the attributes are bound here instead.
 * data-* is invisible to CSS and to the CSP, so the markup renders identically.
 *
 * Three attributes are handled:
 *   data-close-modal="buy-modal" -> closeModal('buy-modal')
 *   data-action="confirmBuy"    -> confirmBuy()
 *   data-search="handleOrdersSearch" -> handleOrdersSearch(this)
 */
function bindDeclarativeHandlers() {
    document.querySelectorAll('[data-close-modal]').forEach((el) => {
        const modal = el.getAttribute('data-close-modal');
        el.addEventListener('click', () => closeModal(modal));
    });

    document.querySelectorAll('[data-action]').forEach((el) => {
        const name = el.getAttribute('data-action');
        el.addEventListener('click', () => {
            if (typeof window[name] === 'function') {
                window[name]();
            }
        });
    });

    // "input" rather than "change" to match the previous oninput behaviour.
    document.querySelectorAll('[data-search]').forEach((el) => {
        const name = el.getAttribute('data-search');
        el.addEventListener('input', () => {
            if (typeof window[name] === 'function') {
                window[name](el);
            }
        });
    });
}

function bindEvents() {
    bindDeclarativeHandlers();
    // Card/row/canvas clicks are delegated: the payload rides in data-obj (HTML
    // escaped) instead of an inline onclick, which breaks as soon as a value
    // contains a quote.
    document.addEventListener('click', (ev) => {
        const el = ev.target.closest('[data-action][data-obj]');
        if (!el) {
            return;
        }
        let payload;
        try {
            payload = JSON.parse(el.getAttribute('data-obj'));
        } catch (_) {
            return;
        }
        const action = el.getAttribute('data-action');
        if (action === 'buy') {
            openBuyModal(payload);
        } else if (action === 'sell') {
            openSellModal(payload);
        } else if (action === 'bid') {
            openBidModal(payload);
        } else if (action === 'fill') {
            openFillModal(payload);
        } else if (action === 'chart') {
            openChartModal(payload);
        }
    });

    document.querySelectorAll('.nav-tab').forEach((tab) => {
        tab.onclick = () => switchPage(tab.getAttribute('data-page'));
    });

    const search = document.getElementById('search-input');
    if (search) {
        let t = null;
        search.oninput = () => {
            clearTimeout(t);
            t = setTimeout(() => {
                marketSearch = search.value.trim();
                if (marketSearch) {
                    searchPage(marketSearch, 0);
                } else {
                    loadAllItems(0);
                }
            }, 300);
        };
    }

    const aSearch = document.getElementById('auction-search');
    if (aSearch) {
        let t = null;
        aSearch.oninput = () => {
            clearTimeout(t);
            t = setTimeout(() => {
                auctionSearch = aSearch.value.trim();
                loadAuctions();
            }, 300);
        };
    }

    // Quantity steppers: click steps once, hold accelerates.
    bindStepper('amount-minus', 'amount-input', -1);
    bindStepper('amount-plus', 'amount-input', 1);
    bindStepper('sell-amount-minus', 'sell-amount-input', -1);
    bindStepper('sell-amount-plus', 'sell-amount-input', 1);
    bindStepper('order-fill-amount-minus', 'order-fill-amount-input', -1);
    bindStepper('order-fill-amount-plus', 'order-fill-amount-input', 1);
    updateStepperLimits('amount-input');
    updateStepperLimits('sell-amount-input');
    updateStepperLimits('order-fill-amount-input');
    // Releasing a hold outside the button (alt-tab, scroll) must still stop it.
    window.addEventListener('blur', stopHold);

    const amount = document.getElementById('amount-input');
    if (amount) {
        amount.oninput = () => {
            updateStepperLimits('amount-input');
            updateBuyTotal();
        };
    }
    const fAmount = document.getElementById('order-fill-amount-input');
    if (fAmount) {
        fAmount.oninput = () => {
            updateStepperLimits('order-fill-amount-input');
            updateFillTotal();
        };
    }
    const sAmount = document.getElementById('sell-amount-input');
    if (sAmount) {
        sAmount.oninput = () => {
            updateStepperLimits('sell-amount-input');
            updateSellTotal();
        };
    }

    // market table: sortable headers, and the stat tiles double as sort shortcuts
    document.querySelectorAll('.col-sort').forEach((th) => {
        th.onclick = () => toggleStockSort(th.getAttribute('data-sort'));
    });
    document.querySelectorAll('.stat-tile').forEach((tile) => {
        tile.onclick = () => {
            const field = tile.getAttribute('data-sort');
            if (field && field !== 'all') {
                toggleStockSort(field);
            }
        };
    });

    // orders: its own sortable headers (the stocks ones use a different attribute)
    document.querySelectorAll('[data-orders-sort]').forEach((th) => {
        th.onclick = () => toggleOrderSort(th.getAttribute('data-orders-sort'));
    });
    document.querySelectorAll('#orders-filters .chip').forEach((chip) => {
        chip.onclick = () => setOrderFilter(chip.getAttribute('data-filter'));
    });
    document.querySelectorAll('#orders-stats .stat-tile').forEach((tile) => {
        const field = tile.getAttribute('data-sort');
        if (field && field !== 'all') {
            tile.onclick = () => toggleOrderSort(field);
        }
    });

    // modal backdrops
    document.querySelectorAll('.modal-overlay').forEach((overlay) => {
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) {
                overlay.style.display = 'none';
            }
        });
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            document.querySelectorAll('.modal-overlay').forEach((m) => (m.style.display = 'none'));
        }
    });

    setInterval(tickTimers, 1000);
}

// ── quantity stepper ──────────────────────────────────────────────
// A plain click steps once. Holding ramps up: after HOLD_START_MS the value
// starts repeating, and both the step size and the repeat rate increase until
// the input hits its min/max.
const HOLD_START_MS = 300;
const HOLD_RAMP_MS = 1400;
const HOLD_MAX_STEP = 8;

let holdTimer = null;
let holdStoppers = [];

function stopHold() {
    if (holdTimer !== null) {
        clearTimeout(holdTimer);
        holdTimer = null;
    }
    for (const fn of holdStoppers) {
        document.removeEventListener('pointerup', fn);
        document.removeEventListener('pointercancel', fn);
    }
    holdStoppers = [];
}

/** Step an input, honouring its step/min/max, then refresh the page total. */
function stepQty(id, delta) {
    const input = document.getElementById(id);
    if (!input) {
        return;
    }
    const min = input.min === '' ? -Infinity : Number(input.min);
    const max = input.max === '' ? Infinity : Number(input.max);
    const current = Number(input.value);
    const base = Number.isFinite(current) ? current : (Number.isFinite(min) ? min : 0);

    let next = base + delta;
    next = Math.min(max, Math.max(min, next));
    // Keep integers integral; avoid 3.0000000000000004 style drift.
    input.value = Number.isInteger(base) ? Math.round(next) : Number(next.toFixed(2));

    updateStepperLimits(id);
    if (id === 'amount-input') {
        updateBuyTotal();
    } else if (id === 'sell-amount-input') {
        updateSellTotal();
    } else if (id === 'order-fill-amount-input') {
        updateFillTotal();
    }
}

/** Grey out a stepper button once its direction can go no further. */
function updateStepperLimits(id) {
    const input = document.getElementById(id);
    if (!input) {
        return;
    }
    const v = Number(input.value) || 0;
    const min = input.min === '' ? -Infinity : Number(input.min);
    const max = input.max === '' ? Infinity : Number(input.max);
    for (const [suffix, atLimit] of [
        ['minus', v <= min],
        ['plus', v >= max],
    ]) {
        const btn = document.getElementById(`${id.replace('-input', '')}-${suffix}`);
        if (btn) {
            btn.classList.toggle('at-limit', atLimit);
        }
    }
}

/**
 * Wire a +/- pair for hold-to-repeat. pointerdown applies the first step
 * immediately, then schedules accelerating repeats; releasing anywhere stops.
 */
function bindStepper(btnId, inputId, delta) {
    const btn = document.getElementById(btnId);
    if (!btn) {
        return;
    }
    btn.addEventListener('pointerdown', (ev) => {
        ev.preventDefault();
        stopHold();
        stepQty(inputId, delta);

        const started = Date.now();
        const tick = () => {
            const held = Date.now() - started;
            const t = Math.min(1, held / HOLD_RAMP_MS);
            // Ramp the step from 1x to HOLD_MAX_STEP and shorten the interval,
            // so holding goes from a gentle tick to a blur.
            const mult = 1 + Math.floor(t * (HOLD_MAX_STEP - 1));
            const interval = 220 - 160 * t;
            stepQty(inputId, delta * mult);
            holdTimer = setTimeout(tick, interval);
        };
        holdTimer = setTimeout(tick, HOLD_START_MS);

        // Release anywhere ends the hold, so dragging off the button still stops.
        const onUp = () => stopHold();
        holdStoppers = [onUp, onUp];
        document.addEventListener('pointerup', onUp);
        document.addEventListener('pointercancel', onUp);
    });
    // A held drag must not select the button's label as text.
    btn.addEventListener('dragstart', (ev) => ev.preventDefault());
}

function pct(a, b) {
    if (!b) {
        return 0;
    }
    return Math.min(100, Math.round((Number(a || 0) / Number(b)) * 100));
}

function money(amount, currency, symbol) {
    const n = Number(amount || 0);
    const text = n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return symbol ? `${symbol}${text}` : `${text} ${currency || ''}`.trim();
}

function formatDuration(ms) {
    if (ms <= 0) {
        return 'Ended';
    }
    const s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400);
    const h = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (d > 0) {
        return `${d}d ${h}h`;
    }
    if (h > 0) {
        return `${h}h ${m}m`;
    }
    if (m > 0) {
        return `${m}m ${String(sec).padStart(2, '0')}s`;
    }
    return `${sec}s`;
}

function emptyStateHtml(title, body) {
    // An inline icon rather than an emoji: emoji render at different sizes and
    // colours per platform, and the magnifying glass was reading as a glyph
    // rather than part of the UI.
    return `<div class="empty-state">
        <svg class="empty-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor"
             stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="21" cy="21" r="13"></circle>
            <line x1="30.5" y1="30.5" x2="41" y2="41"></line>
            <line x1="16" y1="21" x2="26" y2="21"></line>
        </svg>
        <h3>${esc(title)}</h3>
        <p>${esc(body)}</p>
    </div>`;
}

function esc(s) {
    if (s === null || s === undefined) {
        return '';
    }
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/**
 * Escape a value for embedding inside a single-quoted JS string inside an
 * onclick attribute. Also escapes U+2028/U+2029, which are literal newlines to a
 * JS parser and would otherwise break the attribute.
 */
function escJs(s) {
    return String(s)
        .replace(/\\/g, '\\\\')
        .replace(/'/g, "\\'")
        .replace(/</g, '\\u003c')
        .replace(/>/g, '\\u003e')
        .replace(/&/g, '\\u0026')
        .replace(/[\u2028\u2029]/g, (ch) => (ch === '\u2028' ? '\\u2028' : '\\u2029'));

}

function toast(type, message) {
    const box = document.getElementById('toast-container');
    if (!box) {
        return;
    }
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = message;
    box.appendChild(el);
    setTimeout(() => el.remove(), 4500);
}

function showError(message) {
    const el = document.getElementById('error-message');
    if (el) {
        el.textContent = message;
    }
    openModal('error-overlay');
}

function sleep(ms) {
    return new Promise((r) => setTimeout(r, ms));
}

// Globals referenced by inline onclick handlers in index.html.
window.closeModal = closeModal;
window.confirmBuy = confirmBuy;
window.confirmSell = confirmSell;
window.confirmBid = confirmBid;
window.confirmOrderFill = confirmOrderFill;
window.openBuyModal = openBuyModal;
window.openSellModal = openSellModal;
window.openBidModal = openBidModal;
window.openFillModal = openFillModal;
window.openChartModal = openChartModal;
window.handleStocksSearch = handleStocksSearch;
window.handleOrdersSearch = handleOrdersSearch;
window.iconFallback = iconFallback;

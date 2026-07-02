#!/usr/bin/env python3
"""Config migration test for Aurelium.

Simulates upgrading from an old config format (v1.5.1 flat structure)
to the current version. Verifies that:
1. Nested sections (economy.currencies, web.cloud, etc.) survive migration
2. User-modified values are preserved (not overwritten by defaults)
3. New keys from the default config are added
4. configessions (only non-expired)
    const sessionRows = await loadAllRows('sessions', 500);
    const now = Date.now();
    let loadedSessions = 0;
    for (const row of sessionRows) {
        if (row.expires > now) {
            const session = deserializeSession(row);
            // Use tokenHash (the DB PK) as the cache key for consistent lookups
            sessionCache.set(row.session_token, session);
            loadedSessions++;
        }
    }
    console.log(`[Cache] Loaded ${loadedSessions} active sessions`);

    // Hydrate server cache from DB
    const serverRows = await loadAllRows('servers');
    for (const row of serverRows) {
        serverCache.set(row.server_id, deserializeServer(row));
    }
    console.log(`[Cache] Loaded ${serverCache.size} servers`);

    // Load purchases (only pending, not stale)
    const purchaseRows = await loadAllRows('purchases', 500);
    let loadedPurchases = 0;
    for (const row of purchaseRows) {
        const age = now - row.created_at;
        if (row.status === 'pending' && age < 600_000) {
            purchaseCache.set(row.purchase_id, deserializePurchase(row));
            loadedPurchases++;
        } else if (row.status !== 'pending' && age < 300_000) {
            purchaseCache.set(row.purchase_id, deserializePurchase(row));
            loadedPurchases++;
        }
    }
    console.log(`[Cache] Loaded ${loadedPurchases} recent purchases`);

    cacheReady = true;
    console.log('[Cache] Ready');
}

// ── Serialization Helpers (with encryption) ─────────────────────
// Serialization ENCRYPTS sensitive fields before writing to Astra DB.
// Deserialization DECRYPTS fields when reading from Astra DB.
// The in-memory cache always stores plaintext (decrypted) values.
// Session PK uses tokenHash() for stable DB primary key.

function serializeServer(s) {
    return {
        server_id: s.serverId,
        api_key: encrypt(s.apiKey),
        server_name: s.serverName || 'Minecraft Server',
        last_sync: s.lastSync || Date.now(),
        categories_json: JSON.stringify(s.categories || []),
        items_json: JSON.stringify(s.items || {}),
        auctions_json: s.auctionsJson || '[]',
        orders_json: s.ordersJson || '[]',
        stocks_json: s.stocksJson || '[]',
        price_history_json: s.priceHistoryJson || '{}',
    };
}

function deserializeServer(row) {
    let categories = [], items = {};
    try { categories = JSON.parse(row.categories_json || '[]'); } catch {}
    try { items = JSON.parse(row.items_json || '{}'); } catch {}
    return {
        serverId: row.server_id,
        apiKey: decrypt(row.api_key),
        serverName: row.server_name,
        lastSync: row.last_sync,
        categories,
        items,
        auctionsJson: row.auctions_json || '[]',
        ordersJson: row.orders_json || '[]',
        stocksJson: row.stocks_json || '[]',
        priceHistoryJson: row.price_history_json || '{}',
    };
}

function serializeSession(token, s) {
    return {
        session_token: tokenHash(token),  // Stable PK (HMAC, not random IV encrypt)
        server_id: s.serverId,
        player_uuid: encrypt(s.playerUuid),
        player_name: s.playerName || 'Player',
        balances_json: encryptJson(s.balances || {}),
        default_currency: s.defaultCurrency || 'Aurels',
        expires: s.expires,
    };
}

function deserializeSession(row) {
    return {
        serverId: row.server_id,
        playerUuid: decrypt(row.player_uuid),
        playerName: row.player_name || 'Player',
        balances: decryptJson(row.balances_json),
        defaultCurrency: row.default_currency || 'Aurels',
        expires: row.expires,
    };
}

function serializePurchase(id, p) {
    return {
        purchase_id: id,
        server_id: p.serverId,
        player_uuid: encrypt(p.playerUuid),
        type: p.type,
        item_key: p.item || p.itemKey || '',
        auction_id: p.auctionId || 0,
        order_id: p.orderId || 0,
        amount: String(p.amount || 0),
        status: p.status,
        created_at: p.createdAt,
        result_json: p.result ? encrypt(JSON.stringify(p.result)) : '',
    };
}

function deserializePurchase(row) {
    let result = null;
    const resultRaw = decrypt(row.result_json || '');
    try { result = JSON.parse(resultRaw || 'null'); } catch {}
    return {
        serverId: row.server_id,
        playerUuid: decrypt(row.player_uuid),
        type: row.type,
        item: row.item_key,
        itemKey: row.item_key,
        auctionId: row.auction_id,
        orderId: row.order_id,
        amount: row.type === 'bid' || row.type === 'fill_order' ? parseFloat(row.amount) : parseInt(row.amount),
        status: row.status,
        createdAt: row.created_at,
        result,
    };
}

// ── Security Middleware ──────────────────────────────────────────
app.use(helmet({ contentSecurityPolicy: false }));
app.use(cors({ origin: 'https://webaureliummc.onrender.com' }));
app.use(express.json({ limit: '7mb' }));

// Rate limit: 330 requests per minute per IP
app.use('/api/', rateLimit({
    windowMs: 60_000,
    max: 330,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many requests, please slow down.' },
    skip: (req) => {
        const sid = req.headers['x-server-id'];
        const s = serverCache.get(sid);
        return s && s.apiKey === req.headers['x-api-key'];
    }
}));

// ── Middleware: API Key Auth ─────────────────────────────────────
function requireApiKey(req, res, next) {
    const serverId = req.params.serverId || req.body.serverId || req.query.serverId;
    const apiKey = req.headers['x-api-key'];

    if (!serverId || !apiKey) {
        return res.status(401).json({ error: 'Missing server ID or API key' });
    }

    const server = serverCache.get(serverId);
    if (!server || server.apiKey !== apiKey) {
        return res.status(403).json({ error: 'Invalid server ID or API key' });
    }

    req.serverId = serverId;
    req.server = server;
    next();
}

// ══════════════════════════════════════════════════════════════════
// PLUGIN → RENDER  (MC server pushes data here)
// ══════════════════════════════════════════════════════════════════

/** POST /api/register — MC plugin registers on startup */
app.post('/api/register', async (req, res) => {
    const regSecret = process.env.REGISTRATION_SECRET || '';
    if (regSecret && req.headers['x-registration-secret'] !== regSecret) {
        return res.status(403).json({ error: 'Invalid registration secret' });
    }
    const { serverId, apiKey, serverName } = req.body;

    if (!serverId || !apiKey) {
        return res.status(400).json({ error: 'Missing serverId or apiKey' });
    }

    // If server already exists in cache, validate the key
    if (serverCache.has(serverId)) {
        const existing = serverCache.get(serverId);
        if (existing.apiKey !== apiKey) {
            return res.status(403).json({ error: 'API key mismatch for this server ID' });
        }
        // Re-register: update lastSync
        existing.lastSync = Date.now();
        if (ASTRA_TOKEN) {
            astraUpdate('servers', serverId, { last_sync: existing.lastSync }).catch(e =>
                console.error('[Astra] Failed to update lastSync:', e.message)
            );
        }
        return res.json({ success: true });
    }

    // Check Astra DB for existing server (may have survived a restart)
    if (ASTRA_TOKEN starToken) {
        const dbResult = await astraGet('servers', serverId);
        if (dbResult.ok && dbResult.data?.data) {
            const existing = deserializeServer(dbResult.data.data);
            if (existing.apiKey !== apiKey) {
                return res.status(403).json({ error: 'API key mismatch for this server ID' });
            }
            // Restore to cache
            existing.lastSync = Date.now();
            serverCache.set(serverId, existing);
            astraUpdate('servers', serverId, { last_sync: existing.lastSync }).catch(() => {});
            console.log(`[Register] Restored server "${existing.serverName}" from DB (${serverId})`);
            return res.json({ success: true });
        }
    }

    // New server — create it
    const server = {
        serverId,
        apiKey,
        serverName: serverName || 'Minecraft Server',
        lastSync: Date.now(),
        categories: [],
        items: {},
        auctionsJson: '[]',
        ordersJson: '[]',
        stocksJson: '[]',
        priceHistoryJson: '{}',
    };

    serverCache.set(serverId, server);

    if (ASTRA_TOKEN) {
        const result = await astraInsert('servers', serializeServer(server));
        if (!result.ok) {
            serverCache.delete(serverId);
            return res.status(503).json({ error: 'Failed to persist server registration' });
        }
    }

    console.log(`[Register] Server "${serverName}" registered as ${serverId}`);
    res.json({ success: true });
});

/** POST /api/sync — MC plugin pushes market data + auctions + orders + stocks */
app.post('/api/sync', requireApiKey, async (req, res) => {
    const { categories, items, auctions, orders, stocks, priceHistory, customItems } = req.body;
    const server = req.server;

    if (categories) server.categories = categories;
    if (items) server.items = items;

    // ── Custom Items from Aurelium Scanner ─────────────────
    const safeItems = Array customItems ? customItems : [];
    const mapped = safeItems
        .filter(item => typeof item === 'object' && item !== null && typeof item.name === 'string')
        .map(item => {
            const price = item.buyPrice ?? item.price ?? 0;
            return {
                key: item.id ?? item.key ?? '',
                name: item.name,
                material: item.material ?? 'stone',
                price,
                priceFormatted: (item.currencySymbol ?? '$') + Number(price).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
                currency: item.currency ?? '',
                currencySymbol: item.currencySymbol ?? '$',
            };
        });
    server.items['CUSTOM_ITEMS'] = mapped;
    const catIdx = server.categories.findIndex(c => c.id === 'CUSTOM_ITEMS');
    if (mapped.length > 0) {
        if (catIdx === -1) {
            server.categories.push({
                id: 'CUSTOM_ITEMS',
                name: 'Custom Items',
                icon: 'knowledge_book',
                itemCount: mapped.length,
            });
        } else {
            server.categories[catIdx].itemCount = mapped.length;
        }
    } else {
        delete server.items['CUSTOM_ITEMS'];
        if (catIdx !== -1) server.categories.splice(catIdx, 1);
    }

    // Store bulk data as raw JSON strings
    if (auctions) server.auctionsJson = JSON.stringify(auctions);
    if (orders) server.ordersJson = JSON.stringify(orders);
    if (stocks) server.stocksJson = JSON.stringify(stocks);
    if (priceHistory) server.priceHistoryJson = JSON.stringify(priceHistory);
    server.lastSync = Date.now();

    // Persist to Astra (fire-and-forget for sync — data is in cache either way)
    if (ASTRA_TOKEN) {
        astraUpdate('servers', req.serverId, {
            categories_json: JSON.stringify(server.categories),
            items_json: JSON.stringify(server.items),
            auctions_json: server.auctionsJson,
            orders_json: server.ordersJson,
            stocks_json: server.stocksJson,
            price_history_json: server.priceHistoryJson,
            last_sync: server.lastSync,
        }).catch(e => console.error('[Astra] Sync write failed:', e.message));
    }

    res.json({
        success: true,
        pendingPurchases: getPendingPurchases(req.serverId).map(p => ({
            id: p.id, itemKey: p.itemKey, amount: p.amount,
            currency: p.currency, status: p.status,
        })),
    });
});

/** POST /api/session — MC plugin creates a player session */
app.post('/api/session', requireApiKey, async (req, res) => {
    const { token, playerUuid, playerName, balances, defaultCurrency } = req.body;

    if (!token || !playerUuid) {
        return res.status(400).json({ error: 'Missing token or playerUuid' });
    }

    const session = {
        serverId: req.serverId,
        playerUuid,
        playerName: playerName || 'Player',
        balances: balances || {},
        defaultCurrency: defaultCurrency || 'Aurels',
        expires: Date.now() + 3_600_000,
    };

    // Cache key is tokenHash(token) — consistent with DB PK and requireSession lookup
    const sessionKey = tokenHash(token);
    sessionCache.set(sessionKey, session);

    if (ASTRA_TOKEN) {
        const result = await astraInsert('sessions', serializeSession(token, session));
        if (!result.ok) {
            sessionCache.delete(sessionKey);
            return res.status(503).json({ error: 'Failed to persist session' });
        }
    }

    res.json({ success: true });
});

/** POST /api/session-update — MC plugin updates a player's balance after purchase */
app.post('/api/session-update', requireApiKey, async (req, res) => {
    const { playerUuid, balances } = req.body;

    // Update all sessions for this player on this server
    const updates = [];
    for (const [sessionKey, session] of sessionCache) {
        if (session.serverId === req.serverId && session.playerUuid === playerUuid) {
            session.balances = balances;
            if (ASTRA_TOKEN) {
                // sessionKey is already tokenHash(token) — same as DB PK
                updates.push(astraUpdate('sessions', sessionKey, {
                    balances_json: encryptJson(balances),
                }).catch(e => console.error('[Astra] Session update failed:', e.message)));
            }
        }
    }

    await Promise.allSettled(updates);
    res.json({ success: true });
});

/** POST /api/confirm-purchase — MC plugin confirms a purchase was executed */
app.post('/api/confirm-purchase', requireApiKey, async (req, res) => {
    const { purchaseId, success, newBalance, spent } = req.body;

    const purchase = purchaseCache.get(purchaseId);
    if (!purchase || purchase.serverId !== req.serverId) {
        return res.status(404).json({ error: 'Purchase not found' });
    }

    purchase.status = success ? 'completed' : 'failed';
    purchase.result = { newBalance, spent, success };

    if (ASTRA_TOKEN) {
        const result = await astraUpdate('purchases', purchaseId, {
            status: purchase.status,
            result_json: encrypt(JSON.stringify(purchase.result)),
        });
        if (!result.ok) {
            return res.status(503).json({ error: 'Failed to persist purchase confirmation' });
        }
    }

    res.json({ success: true });
});

// ══════════════════════════════════════════════════════════════════
// BROWSER → RENDER  (player dashboard requests)
// ══════════════════════════════════════════════════════════════════

/** Middleware: validate session token for browser requests */
function requireSession(req, res, next) {
    const token = (req.headers.authorization || '').replace('Bearer ', '');
    if (!token) return res.status(401).json({ error: 'Missing token' });

    const sessionKey = tokenHash(token);
    const session = sessionCache.get(sessionKey);
    if (!session) return res.status(401).json({ error: 'Invalid or expired session. Use /web in Cobalt.' });
    if (session.expires < Date.now()) {
        sessionCache.delete(sessionKey);
        if (ASTRA_TOKEN) astraDelete('sessions', sessionKey).catch(() => {});
        return res.status(401).json({ error: 'Session expired. Use /web in-game.' });
    }

    // Rolling 1-hour timeout
    session.expires = Date.now() + 3_600_000;
    if (ASTRA_TOKEN) {
        astraUpdate('sessions', sessionKey, { expires: session.expires }).catch(() => {});
    }

    req.session = session;
    req.serverId = session.serverId;
    req.server = serverCache.get(session.serverId);

    if (!req.server) {
        return res.status(503).json({ error: 'Server is offline' });
    }

    next();
}

/** GET /api/:serverId/player — Browser gets player info */
app.get('/api/:serverId/player', requireSession, (req, res) => {
    const s = req.session;
    res.json({
        name: s.playerName,
        uuid: s.playerUuid,
        defaultCurrency: s.defaultCurrency,
        balances: s.balances,
    });
});

/** GET /api/:serverId/categories — Browser gets categories */
app.get('/api/:serverId/categories', requireSession, (req, res) => {
    res.json(req.server.categories || []);
});

/** GET /api/:serverId/items?category=X&page=0 — Browser gets items */
app.get('/api/:serverId/items', requireSession, (req, res) => {
    const catId = req.query.category || '';
    const page = parseInt(req.query.page) || 0;
    const perPage = 28;

    const allItems = req.server.items?.[catId] || [];
    const totalPages = Math.max(1, Math.ceil(allItems.length / perPage));
    const start = page * perPage;
    const pageItems = allItems.slice(start, start + perPage);

    res.json({ page, totalPages, totalItems: allItems.length, items: pageItems });
});

/** GET /api/:serverId/search?q=X&page=0 — Browser searches items */
app.get('/api/:serverId/search', requireSession, (req, res) => {
    const query = (req.query.q || '').toLowerCase();
    const page = parseInt(req.query.page) || 0;
    const perPage = 28;

    if (!query) return res.status(400).json({ error: 'Missing search query' });

    const results = [];
    const seen = new Set();
    const items = req.server.items || {};
    for (const catItems of Object.values(items)) {
        for (const item of catItems) {
            const key = item.key || item.name;
            if (!seen.has(key) && item.name.toLowerCase().includes(query)) {
                seen.add(key);
                results.push(item);
            }
        }
    }

    const totalPages = Math.max(1, Math.ceil(results.length / perPage));
    const start = page * perPage;
    const pageItems = results.slice(start, start + perPage);

    res.json({ page, totalPages, totalItems: results.length, items: pageItems });
});

/** GET /*/
app.get('/api/:serverId/auctions', requireSession, (req, res) => {
    res.type('json').send(req.server.auctionsJson || '[]');
});

/** GET /api/:serverId/orders — Browser gets active buy orders */
app.get('/api/:serverId/orders', requireSession, (req, res) => {
    res.type('json').send(req.server.ordersJson || '[]');
});

/** GET /api/:serverId/stocks — Browser gets stock/price data */
app.get('/api/:serverId/stocks', requireSession, (req, res) => {
    res.type('json').send(req.server.stocksJson || '[]');
});

/** GET /api/:serverId/price-history — Browser gets price history for charts */
app.get('/api/:serverId/price-history', requireSession, (req, res) => {
    res.type('json').send(req.server.priceHistoryJson || '{}');
});

/** POST /api/:serverId/buy — Browser submits a purchase */
app.post('/api/:serverId/buy', requireSession, async (req, res) => {
    const { item, amount } = req.body;

    const parsedAmount = Number(amount);
    if (!item || !Number.isInteger(parsedAmount) || parsedAmount < 1 || parsedAmount > 64) {
        return res.status(400).json({ error: 'Invalid item or amount' });
    }

    const purchaseId = crypto.randomUUID();
    const purchase = {
        serverId: req.serverId,
        playerUuid: req.session.playerUuid,
        type: 'buy',
        item,
        itemKey: item,
        amount: parsedAmount,
        status: 'pending',
        createdAt: Date.now(),
    };

    purchaseCache.set(purchaseId, purchase);

    if (ASTRA_TOKEN) {
        const result = await astraInsert('purchases', serializePurchase(purchaseId, purchase));
        if (!result.ok) {
            purchaseCache.delete(purchaseId);
            return res.status(503).json({ error: 'Failed to persist purchase' });
        }
    }

    res.json({ success: true, purchaseId, message: 'Purchase queued — delivering in-game...' });
});

/** POST /api/:serverId/bid — Browser submits an auction bid */
app.post('/api/:serverId/bid', requireSession, async (req, res) => {
    const { auctionId, amount } = req.body;

    const parsedAuctionId = Number(auctionId);
    if (!Number.isFinite(parsedAuctionId) || !Number.isInteger(parsedAuctionId)) {
        return res.status(400).json({ error: 'Invalid auction ID' });
    }

    const parsedAmount = parseFloat(amount);
    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
        return res.status(400).json({ error: 'Invalid bid amount' });
    }

    const purchaseId = crypto.randomUUID();
    const purchase = {
        serverId: req.serverId,
        playerUuid: req.session.playerUuid,
        type: 'bid',
        auctionId: parsedAuctionId,
        amount: parsedAmount,
        status: 'pending',
        createdAt: Date.now(),
    };

    purchaseCache.set(purchaseId, purchase);

    if (ASTRA_TOKEN) {
        const result = await astraInsert('purchases', serializePurchase(purchaseId, purchase));
        if (!result.ok) {
            purchaseCache.delete(purchaseId);
            return res.status(503).json({ error: 'Failed to persist bid' });
        }
    }

    res.json({ success: true, purchaseId, message: 'Bid queued — confirming in-game...' });
});

/** POST /api/:serverId/fill-order — Browser submits items to fulfill a buy order */
app.post('/api/:serverId/fill-order', requireSession, async (req, res) => {
    const { orderId, amount } = req.body;

    const parsedOrderId = Number(orderId);
    if (!Number.isFinite(parsedOrderId) || !Number.isInteger(parsedOrderId)) {
        return res.status(400).json({ error: 'Invalid order ID' });
    }

    const parsedAmount = Number(amount);
    if (!Number.isInteger(parsedAmount) || parsedAmount <= 0) {
        return res.status(400).json({ error: 'Invalid fill amount' });
    }

    const purchaseId = crypto.randomUUID();
    const purchase = {
        serverId: req.serverId,
        playerUuid: req.session.playerUuid,
        type: 'fill_order',
        orderId: parsedOrderId,
        amount: parsedAmount,
        status: 'pending',
        createdAt: Date.now(),
    };

    purchaseCache.set(purchaseId, purchase);

    if (ASTRA_TOKEN) {
        const result = await astraInsert('purchases', serializePurchase(purchaseId, purchase));
        if (!result.ok) {
            purchaseCache.delete(purchaseId);
            return res.status(503).json({ error: 'Failed to persist order fill' });
        }
    }

    res.json({ success: true, purchaseId, message: 'Fulfillment queued — verifying in-game inventory...' });
});

/** GET /api/:serverId/purchase-status?id=X — Browser polls purchase result */
app.get('/api/:serverId/purchase-status', requireSession, (req, res) => {
    const id = req.query.id;
    const purchase = purchaseCache.get(id);

    if (!purchase) return res.status(404).json({ error: 'Purchase not found' });

    // IDOR protection
    if (purchase.playerUuid !== req.session.playerUuid) {
        return res.status(403).json({ error: 'Not your purchase' });
    }

    res.json({
        status: purchase.status,
        result: purchase.result || null,
    });
});

// ══════════════════════════════════════════════════════════════════
// STATIC FILES + DASHBOARD PAGE
// ══════════════════════════════════════════════════════════════════

app.use('/static', express.static(path.join(__dirname, 'public')));

// Landing page
app.get('/', (req, res) => {
    const gaId = process.env.GA_MEASUREMENT_ID || 'G-RCQKF2LJVQ';
    res.send(`<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>Aurelium Web Market</title>
<!-- Google tag (gtag.js) -->
<script async src="https://www.googletagmanager.com/gtag/js?id=${gaId}"></script>
<script>
  window.dataLayer = window.dataLayer || [];
  function gtag(){dataLayer.push(arguments);}
  gtag('js', new Date());
  gtag('config', '${gaId}');
</script>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center; background:#111218; color:#e8e8ec; font-family:'Inter', sans-serif; }
.box { text-align:center; padding:48px 40px; border-radius:12px; background:#1e1f25; border:1px solid #2d2e36; box-shadow:0 8px 32px rgba(0,0,0,0.5); max-width:400px; width:90%; }
.logo { display:flex; align-items:center; justify-content:center; gap:12px; margin-bottom:24px; }
.logo svg { width:32px; height:32px; color:#f59e0b; fill:none; stroke:currentColor; stroke-width:2.2; stroke-linecap:round; stroke-linejoin:round; }
.logo-title { font-size:24px; font-weight:800; background:linear-gradient(135deg, #f59e, #eab308); -webkit-background-clip:text; -webkit-text-fill-color:transparent; margin:0; }
p { color:#9a9aad; font-size:15px; line-height:1.5; margin:0 0 32px 0; }
code { background:#111218; padding:4px 8px; border-radius:6px; color:#1bd96a; font-weight:600; font-family:monospace; border:1px solid #2d2e36; }
.link-btn { display:inline-flex; align-items:center; justify-content:center; gap:8px; padding:12px 24px; background:#3b82f6; color:#fff; text-decoration:none; border-radius:8px; font-weight:600; font-size:14px; transition:all 0.2s ease; width:100%; box-sizing:border-box; }
.link-btn:hover { background:#2563eb; transform:translateY(-1px); box-shadow:0 4px 12px rgba(59,130,246,0.3); }
</style></head><body>
<div class="box">
    <div class="logo">
        <svg viewBox="0 0 24 24"><path d="M14.5 2l7.5 7.5-7 7-7.5-7.5z"/><path d="M2 22l7-7"/><path d="M11 13l3.5-3.5"/></svg>
        <h1 class="logo-title">Server Market</h1>
    </div>
    <p>Run <code>/web</code> in-game to get your personal dashboard link and start trading!</p>
    <a href="https://modrinth.com/plugin/aurelium" target="_blank" class="link-btn">
        <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" style="width:18px;height:18px;stroke-width:2.5;stroke-linecap:round;stroke-linejoin:round;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
        Download Plugin
    </a>
</div>
</body></html>`);
});

// Dashboard entry point
let indexHtmlCache = null;
setInterval(() => { indexHtmlCache = null; }, 3600_000);
app.get('/shop/:serverId', (req, res) => {
    try {
        if (!indexHtmlCache) {
            indexHtmlCache = fs.readFileSync(path.join(__dirname, 'public', 'index.html'), 'utf8');
        }
        const gaId = process.env.GA_MEASUREMENT_ID || 'G-RCQKF2LJVQ';
        const finalHtml = indexHtmlCache.replace(/G-XXXXXXXXXX/g, gaId);
        res.send(finalHtml);
    } catch (e) {
        console.error('Failed to serve index.html:', e);
        res.status(500).send('Server Error');
    }
});

// ══════════════════════════════════════════════════════════════════
// HELPERS
// ══════════════════════════════════════════════════════════════════

function getPendingPurchases(serverId, playerUuid) {
    const pending = [];
    for (const [id, p] of purchaseCache) {
        if (p.serverId === serverId && p.status === 'pending' && (!playerUuid || p.playerUuid === playerUuid)) {
            pending.push({ id, ...p });
        }
    }
    return pending;
}

// Cleanup expired sessions and old purchases every 60 seconds.
// Cache eviction only — does NOT delete from Astra DB.
// DB records persist for crash recovery; stale DB rows are
// filtered out on startup load (expired sessions, old purchases).
setInterval(() => {
    const now = Date.now();

    for (const [sessionKey, session] of sessionCache) {
        if (session.expires < now) {
            sessionCache.delete(sessionKey);
            // Delete expired session from DB
            if (ASTRA_TOKEN) astraDelete('sessions', sessionKey).catch(() => {});
        }
    }

    for (const [id, p] of purchaseCache) {
        if (p.status !== 'pending' && now - p.createdAt > 300_000) {
            purchaseCache.delete(id);
            // Completed/failed purchases: evict from cache, keep in DB for audit trail
        } else if (p.status === 'pending' && now - p.createdAt > 600_000) {
            purchaseCache.delete(id);
            // Stale pending purchases: evict from cache, mark expired in DB
            if (ASTRA_TOKEN) astraUpdate('purchases', id, { status: 'expired' }).catch(() => {});
        }
    }

    for (const [id, server] of serverCache) {
        if (now - server.lastSync > 300_000) {
            const hasActive = [...sessionCache.values()].some(s => s.serverId === id);
            if (!hasActive) {
                console.log(`[Cleanup] Evicting stale server "${server.serverName}" (${id}) from cache`);
                serverCache.delete(id);
                // Server stays in DB — will be restored on next register/sync
            }
        }
    }
}, 60_000);

// ══════════════════════════════════════════════════════════════════
// START
// ══════════════════════════════════════════════════════════════════

const PORT = process.env.PORT || 3000;

async function start() {
    // Load cache from Astra DB before serving requests
    await loadCacheFromDB();

    app.listen(PORT, () => {
        console.log(`Aurelium Web Dashboard running on port ${PORT}`);
        console.log(`Persistence: ${ASTRA_TOKEN ? 'Astra DB + in-memory cache' : 'in-memory only (no ASTRA_TOKEN)'}`);
        console.log(`Encryption: ${encryptionEnabled ? 'AES-256-GCM (field-level)' : 'disabled (no ENCRYPTION_KEY)'}`);
    });
}

start().catch(e => {
    console.error('Failed to start:', e);
    process.exit(1);
});

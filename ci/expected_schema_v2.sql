-- Expected schema for Aurelium v1.4.5 with custom item scanner
-- This file is used for schema snapshot comparison in CI

CREATE TABLE players (
 uuid TEXT PRIMARY KEY,
 username TEXT,
 last_seen INTEGER
);

CREATE TABLE player_balances (
 uuid TEXT PRIMARY KEY,
 balance REAL DEFAULT 0,
 aurels REAL DEFAULT 0,
 FOREIGN KEY (uuid) REFERENCES players(uuid)
);

CREATE TABLE auctions (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 seller_uuid TEXT NOT NULL,
 item_data TEXT NOT NULL,
 price REAL NOT NULL,
 created_at INTEGER NOT NULL,
 expires_at INTEGER NOT NULL,
 cancelled INTEGER DEFAULT 0,
 FOREIGN KEY (seller_uuid) REFERENCES players(uuid)
);

CREATE TABLE offline_earnings (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 player_uuid TEXT NOT NULL,
 currency TEXT NOT NULL,
 amount REAL NOT NULL,
 timestamp INTEGER NOT NULL
);

CREATE TABLE buy_orders (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 player_uuid TEXT NOT NULL,
 material TEXT NOT NULL,
 amount INTEGER NOT NULL,
 price_per_unit REAL NOT NULL,
 created_at INTEGER NOT NULL,
 filled INTEGER DEFAULT 0
);

CREATE TABLE price_history (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 material TEXT NOT NULL,
 currency TEXT NOT NULL,
 price REAL NOT NULL,
 timestamp INTEGER NOT NULL
);

CREATE TABLE auction_offers (
 auction_id INTEGER NOT NULL,
 buyer_uuid TEXT NOT NULL,
 offer_price REAL NOT NULL,
 offered_at INTEGER NOT NULL,
 FOREIGN KEY (auction_id) REFERENCES auctions(id)
);

CREATE TABLE custom_items (
 canonical_id TEXT PRIMARY KEY,
 source_plugin TEXT NOT NULL,
 display_name TEXT,
 item_data TEXT NOT NULL,
 pdc_key TEXT,
 model_data_key TEXT,
 lore_hash TEXT,
 plugin_native_id TEXT,
 category TEXT,
 buy_price REAL DEFAULT -1,
 sell_price REAL DEFAULT -1,
 enabled INTEGER DEFAULT 1,
 discovery_methods TEXT,
 first_discovered INTEGER NOT NULL,
 last_seen INTEGER NOT NULL
);

CREATE TABLE database_info (
 key TEXT PRIMARY KEY,
 value TEXT
);
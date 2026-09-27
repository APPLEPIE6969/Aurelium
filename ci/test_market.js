// Tests the market (stocks) table: the query it sends to the server, how it
// renders a page of rows, and the summary strip.
//
// Sorting and paging moved server side, so this asserts the request the client
// builds rather than re-testing the sort. The sort itself is covered by
// CollectionPagerTest on the Java side.
const fs = require('fs');

const src = fs.readFileSync('src/main/resources/web/app.js', 'utf8');
const varsEnd = src.indexOf('\n', src.indexOf('let ordersSearch')) + 1;
// The stocks page code runs up to the chart modal, but the sell modal sits in
// between, so take two runs: the state vars, and the stocks functions. The
// second run already carries `let priceHistory` and `loadPriceHistory`.
const region = src.slice(src.indexOf('let stocks = [];'), varsEnd) +
    src.slice(src.indexOf('async function loadStocks('), src.indexOf('function openChartModal('));

const nodes = {};
function el(id) {
    return {
        id, innerHTML: '', textContent: '',
        _cls: new Set(),
        classList: {
            add(c) { nodes[id]._cls.add(c); },
            toggle(c, on) { on ? nodes[id]._cls.add(c) : nodes[id]._cls.delete(c); },
            contains(c) { return nodes[id]._cls.has(c); },
        },
        setAttribute() {}, getAttribute() { return null; }, querySelectorAll: () => [],
    };
}
for (const id of ['stocks-body', 'stocks-pagination', 'stat-markets', 'stat-gainer', 'stat-loser', 'stat-volume']) {
    nodes[id] = el(id);
}

global.document = {
    getElementById: (id) => nodes[id] || null,
    querySelectorAll: () => [],
};
// W is captured by the closure created inside the Function, so it must be the
// same object throughout -- never reassigned, only mutated.
const W = { data: null, url: null };
S = new Function(
    'nodes', 'esc', 'money', 'emptyStateHtml', 'itemIconTag', 'pct', 'WINDOW',
    // The stocks code calls these; stub the ones outside the slice. Only record
    // the /stocks call: loadPriceHistory shares the api() stub and would
    // otherwise overwrite the URL under test.
    'const renderPagination = () => {};\n' +
    'const api = async (u) => { if (u.startsWith("/stocks")) { WINDOW.url = u; }' +
    ' return WINDOW.data; };\n' +
    region +
    '\nreturn { loadStocks, renderStocks, renderStats, sparkline, toggleStockSort, filteredStocks,' +
    ' get stocks() { return stocks; }, set stocks(v) { stocks = v; },' +
    ' get stats() { return stocksStats; }, set stats(v) { stocksStats = v; },' +
    ' get s() { return stocksSort; }, set s(v) { stocksSort = v; },' +
    ' get d() { return stocksDir; }, set d(v) { stocksDir = v; },' +
    ' get search() { return stocksSearch; }, set search(v) { stocksSearch = v; } };'
)(
    nodes,
    (s) => String(s ?? ''),
    (a, c) => `${Number(a || 0).toFixed(2)} ${c || ''}`.trim(),
    (t, b) => `<div class="empty-state">${t}${b}</div>`,
    () => '<img>',
    (a, b) => (b ? Math.round((a / b) * 100) : 0),
    W
);

const ROWS = [
    { key: 'DIAMOND', name: 'diamond', material: 'diamond', buyPrice: 100, sellPrice: 90, volume: 5, trades: 1, change: 2, currency: 'A', currencySymbol: '$' },
    { key: 'IRON_INGOT', name: 'iron ingot', material: 'iron_ingot', buyPrice: 300, sellPrice: 280, volume: 900, trades: 40, change: -3, currency: 'A', currencySymbol: '$' },
];
const PAGE = {
    page: 0, totalPages: 3, totalItems: 1160, pageSize: 28, items: ROWS,
    stats: { markets: 1160, volume: 905, gainer: 'diamond +2.0%', loser: 'iron ingot -3.0%' },
};

let fails = 0;
const eq = (label, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) fails++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`}`);
};
const has = (label, hay, needle) => {
    const ok = String(hay).includes(needle);
    if (!ok) fails++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `: missing ${JSON.stringify(needle)}`}`);
};

async function main() {
    console.log('the request carries sort, direction and page');
    S.s = 'name'; S.d = 'asc'; S.search = '';
    W.data = PAGE;
    await S.loadStocks(0);
    has('page 0', W.url, 'page=0');
    has('sort field', W.url, 'sort=name');
    has('direction', W.url, 'dir=asc');
    // Guards against an unset state variable leaking into the query string.
    eq('no undefined in the url', W.url.includes('undefined'), false);
    eq('no null in the url', W.url.includes('null'), false);

    S.s = 'volume'; S.d = 'desc';
    await S.loadStocks(2);
    has('page 2', W.url, 'page=2');
    has('sort volume', W.url, 'sort=volume');
    has('dir desc', W.url, 'dir=desc');

    console.log('the search term is sent as q');
    S.search = 'iron ingot';
    await S.loadStocks(0);
    has('q param', W.url, 'q=iron%20ingot');
    S.search = '';
    await S.loadStocks(0);
    eq('no q when the box is empty', W.url.includes('&q='), false);

    console.log('clicking a column re-sorts from page 1');
    S.s = 'name'; S.d = 'asc';
    S.toggleStockSort('buyPrice');
    eq('numeric column defaults to desc', S.d, 'desc');
    await S.loadStocks(0);
    has('new sort reaches the server', W.url, 'sort=buyPrice');
    S.toggleStockSort('buyPrice');
    eq('clicking again flips it', S.d, 'asc');

    console.log('rows render from the page the server sent');
    S.stocks = ROWS;
    S.renderStocks();
    const html = nodes['stocks-body'].innerHTML;
    eq('a row per item', (html.match(/<tr>/g) || []).length, 2);
    has('buy price', html, '100.00');
    has('sell price (the old dead dash)', html, '90.00');
    has('volume', html, '900');
    has('trade count', html, '40 trades');
    has('buy action', html, 'data-action="buy"');
    has('sell action', html, 'data-action="sell"');
    eq('no dead dash cell', html.includes('<td>-</td>'), false);

    console.log('the summary strip uses the server figures, not the visible page');
    S.renderStats(PAGE.stats);
    // toLocaleString is locale dependent, so compare against the same call
    // rather than a hard-coded separator.
    eq('markets', nodes['stat-markets'].textContent, (1160).toLocaleString());
    eq('gainer', nodes['stat-gainer'].textContent, 'diamond +2.0%');
    eq('loser', nodes['stat-loser'].textContent, 'iron ingot -3.0%');
    eq('volume', nodes['stat-volume'].textContent, '905');

    S.renderStats({ markets: 0, volume: 0, gainer: null, loser: null });
    eq('empty market shows a dash', nodes['stat-gainer'].textContent, '-');
    S.renderStats({ markets: 5, volume: 0, gainer: null, loser: null });
    eq('markets but no movers', nodes['stat-gainer'].textContent, 'No movers');
    eq('markets but no losers', nodes['stat-loser'].textContent, 'No losers');

    console.log('sparkline');
    has('no history yet', S.sparkline('MISSING'), 'no data');

    console.log('empty page');
    S.stocks = [];
    S.renderStocks();
    has('empty state', nodes['stocks-body'].innerHTML, 'empty-state');

    console.log(fails === 0 ? '\nRESULT: PASS' : `\nRESULT: FAIL (${fails})`);
    process.exit(fails === 0 ? 0 : 1);
}
main();

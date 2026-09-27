// Tests currencyBadge(): a price in the default currency should not repeat the
// currency on every row, but a non-default currency must still be spelled out.
const fs = require('fs');

const src = fs.readFileSync('src/main/resources/web/app.js', 'utf8');
const from = src.indexOf('function updateBalanceDisplay(player) {');
const to = src.indexOf('// ── auto refresh');
const region = src.slice(from, to);

const nodes = { 'balance-amount': { textContent: '' } };
global.document = { getElementById: (id) => nodes[id] || null };
// The real escaper, so the escaping assertion below actually tests something.
const esc = (s) =>
    String(s ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
// updateBalanceDisplay also records the session uuid, so declare it here.
const S = new Function(
    'nodes', 'esc', 'document', 'defaultCurrency',
    'let playerUuid = "";\n' + region +
    '\nreturn { currencyBadge, updateBalanceDisplay, get dc() { return defaultCurrency; } };'
)(
    nodes,
    esc,
    global.document,
    ''
);

let fails = 0;
const eq = (label, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) fails++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`}`);
};

console.log('before the player payload arrives, nothing is suppressed');
// defaultCurrency is '' at this point, so any real currency must still render;
// otherwise prices would be ambiguous while the session is still resolving.
eq('unknown default -> badge shown', S.currencyBadge('Aurels'), '<span class="item-currency">Aurels</span>');

console.log('the default currency is learned from the player payload');
S.updateBalanceDisplay({ defaultCurrency: 'Aurels', balances: { Aurels: 100 } });
eq('defaultCurrency captured', S.dc, 'Aurels');
// toLocaleString is locale dependent, so match the same call rather than a
// hard-coded decimal separator.
eq('header balance still shows the currency',
   nodes['balance-amount'].textContent, `${(100).toLocaleString(undefined, {
       minimumFractionDigits: 2, maximumFractionDigits: 2,
   })} Aurels`);

console.log('a price in the default currency carries no badge');
eq('Aurels price', S.currencyBadge('Aurels'), '');
eq('repeat call is stable', S.currencyBadge('Aurels'), '');

console.log('a non-default currency is still spelled out');
eq('other currency', S.currencyBadge('Tokens'), '<span class="item-currency">Tokens</span>');

console.log('degenerate input errs towards showing the currency, not hiding it');
eq('empty', S.currencyBadge(''), '');
eq('undefined', S.currencyBadge(undefined), '');
eq('null', S.currencyBadge(null), '');

console.log('the currency name is escaped');
eq('escapes html', S.currencyBadge('<img src=x>'),
   '<span class="item-currency">&lt;img src=x&gt;</span>');

console.log(fails === 0 ? '\nRESULT: PASS' : `\nRESULT: FAIL (${fails})`);
process.exit(fails === 0 ? 0 : 1);

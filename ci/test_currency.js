// Tests money(): a price carries its symbol when the server sent one, and spells
// out the currency name when it did not. Also checks updateBalanceDisplay, which
// shows the player's balance in their default currency.
//
// The badge that used to sit on every market card was dropped on purpose in
// commit af05452, so this no longer asserts on it. What it asserts instead is the
// replacement path, which is where a wrong currency or a missing symbol would now
// show up instead: a row for a non-default currency has to name it, or the player
// cannot tell what they are about to be paid in.
const fs = require('fs');

const src = fs.readFileSync('src/main/resources/web/app.js', 'utf8');

/** Pull a named function's full source out of the file by brace matching. */
function fn(name) {
    const start = src.indexOf(`function ${name}(`);
    if (start < 0) throw new Error(`no function ${name}`);
    let i = src.indexOf('{', start);
    let depth = 0;
    for (let j = i; j < src.length; j++) {
        if (src[j] === '{') depth++;
        else if (src[j] === '}') {
            depth--;
            if (depth === 0) return src.slice(start, j + 1);
        }
    }
    throw new Error(`unbalanced braces in ${name}`);
}

const nodes = { 'balance-amount': { textContent: '' } };
global.document = { getElementById: (id) => nodes[id] || null };

const region = [fn('money'), fn('updateBalanceDisplay')].join('\n');

const S = new Function(
    'nodes', 'document', 'defaultCurrency',
    'let playerUuid = "";\n' + region + '\nreturn { money, updateBalanceDisplay };'
)(nodes, global.document, 'coins');

let fails = 0;
function eq(label, actual, expected) {
    if (actual === expected) {
        console.log(`ok   ${label}`);
    } else {
        fails++;
        console.log(`FAIL ${label}\n       expected: ${JSON.stringify(expected)}`);
        console.log(`       actual:   ${JSON.stringify(actual)}`);
    }
}
function ok(label, condition, detail) {
    eq(label + (detail ? ` (${detail})` : ''), !!condition, true);
}

// toLocaleString groups and separates digits per the viewer's locale, so these
// checks pin the number of decimals rather than the separators themselves.
const DECIMALS = /[.,](\d\d)($|\s)/;
function hasTwoDecimals(s) {
    const m = s.match(DECIMALS);
    return m ? m[1] : null;
}

console.log('money() uses the symbol the server sent, and does not repeat the currency');
const priced = S.money(1234.5, 'coins', '$');
ok('keeps the symbol', priced.startsWith('$'), priced);
ok('drops the currency name', !priced.includes('coins'), priced);
eq('keeps both decimals', hasTwoDecimals(priced), '50');

console.log('\nmoney() spells the currency out when there is no symbol');
const spelled = S.money(1234.5, 'gems', '');
ok('names a non-default currency', spelled.includes('gems'), spelled);
eq('still has both decimals', hasTwoDecimals(spelled), '50');

console.log('\nmoney() falls back to the currency name when the symbol is missing');
const noSymbol = S.money(1234.5, 'coins', undefined);
ok('names the currency', noSymbol.includes('coins'), noSymbol);
const neither = S.money(1234.5);
eq('renders a bare amount when given nothing', hasTwoDecimals(neither), '50');

console.log('\nmoney() survives a zero or absent amount');
ok('zero is not blank', S.money(0, 'coins', '$').includes('0'), S.money(0, 'coins', '$'));
ok('undefined is zero', S.money(undefined, 'coins', '$').includes('0'), S.money(undefined, 'coins', '$'));

console.log('\nupdateBalanceDisplay() shows the default currency balance');
nodes['balance-amount'].textContent = '';
S.updateBalanceDisplay({ uuid: 'u1', defaultCurrency: 'gems', balances: { gems: 5 } });
const shown = nodes['balance-amount'].textContent;
ok('names the currency', shown.includes('gems'), shown);
ok('shows the amount', shown.includes('5'), shown);

console.log('\nupdateBalanceDisplay() is a no-op without a player');
nodes['balance-amount'].textContent = '';
S.updateBalanceDisplay(null);
eq('nothing written', nodes['balance-amount'].textContent, '');

console.log('\nupdateBalanceDisplay() treats a missing balance as zero');
nodes['balance-amount'].textContent = '';
S.updateBalanceDisplay({ uuid: 'u1', defaultCurrency: 'gems', balances: {} });
const zeroed = nodes['balance-amount'].textContent;
ok('shows zero', zeroed.includes('0'), zeroed);
ok('still names the currency', zeroed.includes('gems'), zeroed);

console.log(fails === 0 ? '\nRESULT: PASS' : `\nRESULT: FAIL (${fails})`);
process.exit(fails === 0 ? 0 : 1);
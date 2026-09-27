// Tests the orders page rules: you cannot fill your own order, and the quantity
// stepper is capped at what the order still needs.
//
// Both were broken: the Fill button was offered on your own order (the server
// refused it), and the stepper was free to ask for 64 items on an order that
// wanted 10.
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

const region = ['orderRemaining', 'isOwnOrder', 'fillCell', 'openFillModal', 'updateStepperLimits']
    .map(fn)
    .join('\n');

const nodes = {};
global.document = { getElementById: (id) => nodes[id] || null };

const S = new Function(
    'document', 'esc', 'setItemIcon', 'openModal', 'updateFillTotal', 'me',
    'let modalOrder = null;\nlet playerUuid = me;' + region +
    '\nreturn { orderRemaining, isOwnOrder, fillCell, openFillModal,' +
    ' set me(v) { playerUuid = v; },' +
    ' set modalOrder(v) { modalOrder = v; } };'
)(
    global.document,
    (s) => String(s ?? ''),
    () => {},
    () => {},
    () => {},
    ''
);

let fails = 0;
const eq = (label, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) fails++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`}`);
};

const ME = 'bdee55d2-a7f8-3414-976a-dde4f1392e7f';
const THEM = '069a79f4-44e9-4726-a5be-fca90e38aaf5';

console.log('how much an order still wants');
eq('nothing filled', S.orderRemaining({ amountRequested: 10, amountFilled: 0 }), 10);
eq('partly filled', S.orderRemaining({ amountRequested: 10, amountFilled: 4 }), 6);
eq('fully filled', S.orderRemaining({ amountRequested: 10, amountFilled: 10 }), 0);
eq('over-filled cannot go negative', S.orderRemaining({ amountRequested: 10, amountFilled: 12 }), 0);
eq('missing fields', S.orderRemaining({}), 0);
eq('null order', S.orderRemaining(null), 0);

console.log('recognising your own order');
S.me = ME;
eq('same uuid', S.isOwnOrder({ buyerUuid: ME }), true);
eq('different uuid', S.isOwnOrder({ buyerUuid: THEM }), false);
S.me = '';
eq('no session yet, do not guess', S.isOwnOrder({ buyerUuid: ME }), false);
S.me = ME;

console.log('the action cell');
const own = S.fillCell({ buyerUuid: ME, amountRequested: 10, amountFilled: 0 });
eq('own order is labelled', own.includes('Yours'), true);
eq('own order has no fill button', own.includes('data-action="fill"'), false);
eq('own order explains why', own.includes('cannot fill it'), true);

const done = S.fillCell({ buyerUuid: THEM, amountRequested: 10, amountFilled: 10 });
eq('filled order is not fillable', done.includes('data-action="fill"'), false);
eq('filled order says so', done.includes('Filled'), true);

const open = S.fillCell({ buyerUuid: THEM, amountRequested: 10, amountFilled: 0, id: 1 });
eq('someone else\'s order is fillable', open.includes('data-action="fill"'), true);

console.log('the stepper is capped at the remaining amount');
function openFor(order) {
    nodes['order-fill-amount-input'] = { value: '1', min: '1', max: '' };
    nodes['fill-hint'] = { textContent: '' };
    nodes['fill-item-name'] = { textContent: '' };
    S.openFillModal(order);
    return nodes['order-fill-amount-input'];
}
let input = openFor({ itemName: 'concrete', material: 'concrete', amountRequested: 10, amountFilled: 0 });
eq('max is what is left, not 64', input.max, '10');
eq('hint states the remainder', nodes['fill-hint'].textContent, '- 10 still needed');

input = openFor({ itemName: 'concrete', material: 'concrete', amountRequested: 10, amountFilled: 7 });
eq('max shrinks as it fills', input.max, '3');

input = openFor({ itemName: 'concrete', material: 'concrete', amountRequested: 10, amountFilled: 10 });
eq('a finished order cannot be driven below 1', input.max, '1');
eq('and says it is already filled', nodes['fill-hint'].textContent, '- order already filled');

console.log('the markup no longer hardcodes a max of 64');
const html = fs.readFileSync('src/main/resources/web/index.html', 'utf8');
const field = html.match(/<input[^>]*id="order-fill-amount-input"[^>]*>/)[0];
eq('no max attribute', /max=/.test(field), false);

console.log(fails === 0 ? '\nRESULT: PASS' : `\nRESULT: FAIL (${fails})`);
process.exit(fails === 0 ? 0 : 1);

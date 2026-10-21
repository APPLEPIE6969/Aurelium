// Headless test for the BIN purchase modal: the browser sends the quantity it
// is buying, never the price, and a UNIT listing caps that quantity at the
// stock it has left (H4). Slices the real functions out of app.js and runs them
// against a stubbed DOM, the same way test_stepper.js does.
const fs = require('fs');

const src = fs.readFileSync('src/main/resources/web/app.js', 'utf8');
const start = src.indexOf('function openBidModal');
const end = src.indexOf('function openFillModal');
if (start < 0 || end < 0 || end <= start) {
    console.error('FAIL: could not find the bid modal functions in app.js');
    process.exit(1);
}
const region = src.slice(start, end);

// ---- minimal DOM ----
const nodes = {};
function field(id) {
    return {
        id,
        hidden: false,
        disabled: false,
        value: '',
        max: '',
        textContent: '',
    };
}
for (const id of [
    'bid-item-icon', 'bid-item-name', 'bid-modal-title', 'bid-confirm',
    'bid-amount-block', 'bid-amount-input',
    'bid-quantity-block', 'bid-quantity-input', 'bid-hint',
]) {
    nodes[id] = field(id);
}
global.document = { getElementById: (id) => nodes[id] || null };

const seen = { post: null, modals: [] };
const wrapped = new Function(
    'post', 'openModal', 'closeModal', 'toast', 'awaitPurchase', 'loadAuctions', 'setItemIcon', 'money',
    'let modalAuction = null;\n' + region +
    '\nreturn { openBidModal, confirmBid };'
)(
    async (endpoint, body) => {
        seen.post = { endpoint, body };
        return { purchaseId: 'p1' };
    },
    (id) => seen.modals.push(id),
    () => {},
    () => {},
    async () => {},
    () => {},
    () => {},
    (amount) => String(amount)
);

let fails = 0;
function check(label, got, want) {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) fails++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}: got ${JSON.stringify(got)}${ok ? '' : `, want ${JSON.stringify(want)}`}`);
}

function bin(overrides) {
    return {
        id: 7,
        isBin: true,
        purchaseMode: 'UNIT',
        price: 100.5,
        remaining: 5,
        currency: 'coins',
        currencySymbol: 'C',
        itemName: 'Diamond',
        material: 'diamond',
        ...overrides,
    };
}

async function main() {
    console.log('a UNIT BIN asks for a quantity, not a price');
    wrapped.openBidModal(bin());
    check('quantity block is shown', nodes['bid-quantity-block'].hidden, false);
    check('amount block is hidden', nodes['bid-amount-block'].hidden, true);
    check('quantity defaults to one', nodes['bid-quantity-input'].value, '1');
    check('quantity is capped at the stock', nodes['bid-quantity-input'].max, '5');
    check('quantity is editable for a UNIT listing', nodes['bid-quantity-input'].disabled, false);
    await wrapped.confirmBid();
    check('posts to /bid', seen.post.endpoint, '/bid');
    check('sends quantity 1', seen.post.body, { auctionId: 7, quantity: 1 });

    console.log('the buyer can raise the quantity');
    nodes['bid-quantity-input'].value = '3';
    await wrapped.confirmBid();
    check('sends quantity 3', seen.post.body, { auctionId: 7, quantity: 3 });

    console.log('a STACK BIN buys the whole stack');
    wrapped.openBidModal(bin({ purchaseMode: 'STACK', remaining: 12, price: 1200 }));
    check('quantity is fixed at one', nodes['bid-quantity-input'].value, '1');
    check('quantity is not editable', nodes['bid-quantity-input'].disabled, true);
    check('quantity is capped at one', nodes['bid-quantity-input'].max, '1');
    await wrapped.confirmBid();
    check('still sends a quantity', seen.post.body, { auctionId: 7, quantity: 1 });

    console.log('a real auction still sends its bid price');
    wrapped.openBidModal(bin({ isBin: false, purchaseMode: 'STACK', price: 100, remaining: 1 }));
    check('amount block is shown', nodes['bid-amount-block'].hidden, false);
    check('quantity block is hidden', nodes['bid-quantity-block'].hidden, true);
    nodes['bid-amount-input'].value = '150.25';
    await wrapped.confirmBid();
    check('sends the bid amount', seen.post.body, { auctionId: 7, amount: 150.25 });

    console.log(fails === 0 ? '\nRESULT: PASS' : `\nRESULT: FAIL (${fails})`);
    process.exit(fails === 0 ? 0 : 1);
}

main();

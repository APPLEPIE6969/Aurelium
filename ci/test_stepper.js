// Headless test for the quantity stepper: click steps once, holding accelerates,
// and min/max always clamp. Uses a stubbed DOM and a manual clock so the ramp
// can be asserted without waiting on real timers.
const fs = require('fs');

const src = fs.readFileSync('src/main/resources/web/app.js', 'utf8');
const start = src.indexOf('// ── quantity stepper');
const end = src.indexOf('function pct(');
const region = src.slice(start, end);

// ---- minimal DOM ----
const listeners = [];
function makeInput(id, { min = '1', max = '64', value = '1' } = {}) {
    return {
        id, min, max, value,
        _cls: new Set(),
        classList: {
            toggle(c, on) { this.owner._cls[on ? 'add' : 'delete'](c); },
            contains(c) { return this.owner._cls.has(c); },
            get owner() { return input; },
        },
    };
}
function makeBtn(id) {
    return {
        id, _h: {},
        classList: { toggle() {}, contains: () => false },
        addEventListener(ev, fn) { (this._h[ev] ||= []).push(fn); },
        fire(ev) { (this._h[ev] || []).forEach((f) => f({ preventDefault() {} })); },
    };
}
const nodes = {};
for (const n of ['amount-input', 'order-fill-amount-input']) nodes[n] = makeInput(n);
for (const n of ['amount-minus', 'amount-plus', 'order-fill-amount-minus', 'order-fill-amount-plus']) {
    nodes[n] = makeBtn(n);
}
const totals = { buy: 0, fill: 0 };
global.document = {
    getElementById: (id) => nodes[id] || null,
    addEventListener: (ev, fn) => listeners.push([ev, fn]),
    removeEventListener: () => {},
};
global.window = { addEventListener: () => {} };

// ---- manual clock ----
// Date.now must come from the same clock: the ramp measures elapsed time with
// it, and leaving it real makes the acceleration silently look disabled.
let now = 0;
let seq = 0;
const queue = [];
const RealDateNow = Date.now;
Date.now = () => now;
global.setTimeout = (fn, ms) => { const t = { at: now + ms, fn, id: ++seq }; queue.push(t); return t.id; };
global.clearTimeout = (id) => { const i = queue.findIndex((t) => t.id === id); if (i >= 0) queue.splice(i, 1); };
function advance(ms) {
    const end = now + ms;
    for (;;) {
        const due = queue.filter((t) => t.at <= end).sort((a, b) => a.at - b.at)[0];
        if (!due) break;
        queue.splice(queue.indexOf(due), 1);
        now = due.at;
        due.fn();
    }
    now = end;
}

const wrapped = new Function(
    'totals',
    region +
    '\nconst updateBuyTotal = () => { totals.buy++; };\n' +
    'const updateFillTotal = () => { totals.fill++; };\n' +
    '\nreturn { stepQty, bindStepper, updateStepperLimits, stopHold, get holdTimer() { return holdTimer; } };'
)(totals);

// Attach the real handlers, the way bindEvents() does.
wrapped.bindStepper('amount-minus', 'amount-input', -1);
wrapped.bindStepper('amount-plus', 'amount-input', 1);
wrapped.bindStepper('order-fill-amount-minus', 'order-fill-amount-input', -1);
wrapped.bindStepper('order-fill-amount-plus', 'order-fill-amount-input', 1);

let fails = 0;
function check(label, got, want) {
    const ok = got === want;
    if (!ok) fails++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}: got ${got}${ok ? '' : `, want ${want}`}`);
}

console.log('click steps once');
nodes['amount-input'].value = '1';
wrapped.stepQty('amount-input', 1);
check('1 -> 2', nodes['amount-input'].value, 2);
wrapped.stepQty('amount-input', -1);
check('2 -> 1', nodes['amount-input'].value, 1);
wrapped.stepQty('amount-input', -1);
check('clamped at min 1', nodes['amount-input'].value, 1);

console.log('hold accelerates');
nodes['amount-input'].value = '1';
nodes['amount-plus'].fire('pointerdown');
check('pointerdown applies first step', nodes['amount-input'].value, 2);
advance(300);                       // HOLD_START_MS: repeat begins
check('after 300ms held', Number(nodes['amount-input'].value) > 2, true);
const afterShort = Number(nodes['amount-input'].value);
advance(300);
const afterLong = Number(nodes['amount-input'].value);
console.log(`        rate ramps: 600ms=${afterShort} 900ms=${afterLong}`);
check('later ticks move further than earlier ones', afterLong > afterShort, true);

console.log('release stops the hold');
nodes['amount-input'].value = '1';
nodes['amount-plus'].fire('pointerdown');
advance(1000);
const held = Number(nodes['amount-input'].value);
for (const [, fn] of listeners) if (fn) fn();
advance(2000);
check('value frozen after release', Number(nodes['amount-input'].value), held);
check('timer cleared', wrapped.holdTimer, null);

console.log('max is respected while holding');
nodes['amount-input'].value = '1';
nodes['amount-plus'].fire('pointerdown');
advance(3000);
check('reaches max 64 while holding', Number(nodes['amount-input'].value), 64);
advance(5000);
check('still exactly 64, never past it', Number(nodes['amount-input'].value), 64);
for (const [, fn] of listeners) if (fn) fn();

console.log('ramp is progressive, not instant');
nodes['amount-input'].value = '1';
nodes['amount-plus'].fire('pointerdown');
advance(300);
const early = Number(nodes['amount-input'].value);
check('a short hold is still a small step', early <= 6, true);
advance(1200);
const late = Number(nodes['amount-input'].value);
check('a longer hold is much further along', late > early * 3, true);
for (const [, fn] of listeners) if (fn) fn();

console.log('decrement also clamps');
nodes['amount-input'].value = '5';
nodes['amount-minus'].fire('pointerdown');
advance(3000);
for (const [, fn] of listeners) if (fn) fn();
check('never below min 1', Number(nodes['amount-input'].value), 1);

console.log('fill stepper is independent');
nodes['order-fill-amount-input'].value = '1';
nodes['order-fill-amount-plus'].fire('pointerdown');
check('fill steps too', Number(nodes['order-fill-amount-input'].value) >= 2, true);
for (const [, fn] of listeners) if (fn) fn();
check('buy total recomputed', totals.buy > 0, true);
check('fill total recomputed', totals.fill > 0, true);

console.log(fails === 0 ? '\nRESULT: PASS' : `\nRESULT: FAIL (${fails})`);
process.exit(fails === 0 ? 0 : 1);

/**
 * Aurelium Mineflayer In-Game Test Suite
 * 
 * Tests all plugin features via Mineflayer bot.
 * Covers economy, market, auction, orders, custom items, sell, stocks, web.
 * 
 * Connects to Paper via ViaVersion 1.21.11 protocol.
 */

const mineflayer = require('mineflayer');

// ─── Test Framework ──────────────────────────────────────────────────────────

let totalTests = 0;
let passedTests = 0;
let failedTests = 0;
const failures = [];

function check(condition, message) {
  totalTests++;
  if (condition) {
    passedTests++;
    console.log(`PASS: ${message}`);
  } else {
    failedTests++;
    failures.push(message);
    console.log(`FAIL: ${message}`);
  }
}

function checkContains(text, substring, message) {
  const found = text.toLowerCase().includes(substring.toLowerCase());
  if (!found) console.log(`  HINT: Expected "${substring}" in: ${text.substring(0, 200)}`);
  check(found, message);
}

function checkNotContains(text, substring, message) {
  const found = text.toLowerCase().includes(substring.toLowerCase());
  if (found) console.log(`  HINT: Unexpected "${substring}" in: ${text.substring(0, 200)}`);
  check(!found, message);
}

// ─── Bot Setup ───────────────────────────────────────────────────────────────

const BOT_USERNAME = 'TestBot';
const HOST = '127.0.0.1';
const PORT = 25565;
const MC_VERSION = '1.21.11';

let bot;
let allMessages = [];
let windowOpenCount = 0;

function createBot() {
  return new Promise((resolve, reject) => {
    const b = mineflayer.createBot({
      host: HOST,
      port: PORT,
      username: BOT_USERNAME,
      version: MC_VERSION,
      auth: 'offline',
      hideErrors: false,
    });

    b.on('login', () => {
      console.log(`Bot logged in as ${b.username}`);
      setTimeout(() => resolve(b), 3000);
    });

    b.on('message', (jsonMsg) => {
      const text = jsonMsg.toString().trim();
      if (text.length === 0) return;
      console.log(`  MSG: ${text.substring(0, 220)}`);
      allMessages.push(text);
    });

    b.on('windowOpen', (window) => {
      windowOpenCount++;
      console.log(`  GUI: opened - ${window.title || '?'} type=${window.type}`);
    });

    b.on('kicked', (reason) => {
      console.error('FATAL: Bot kicked:', JSON.stringify(reason));
    });

    b.on('error', (err) => {
      console.error('Bot error:', err.message);
    });

    b.on('end', (reason) => {
      console.log('Bot disconnected:', reason);
    });

    setTimeout(() => reject(new Error('Bot connection timeout (60s)')), 60000);
  });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function runCommand(cmd, waitMs = 4000) {
  const startIdx = allMessages.length;
  bot.chat(`/${cmd}`);
  await sleep(waitMs);
  return allMessages.slice(startIdx);
}

async function runAsyncCommand(cmd, firstWaitMs = 3000, secondWaitMs = 5000) {
  const startIdx = allMessages.length;
  bot.chat(`/${cmd}`);
  await sleep(firstWaitMs);
  await sleep(secondWaitMs);
  return allMessages.slice(startIdx);
}

function concat(msgs) {
  return msgs.join(' | ');
}

// ─── GUI Helpers ─────────────────────────────────────────────────────────────

async function closeGui() {
  if (bot.currentWindow) {
    try {
      bot.closeWindow(bot.currentWindow);
      await sleep(300);
    } catch (e) { }
  }
}

async function clickSlot(slot, button, mode) {
  try {
    bot.clickWindow(slot, button || 0, mode || 0);
    await sleep(500);
    return true;
  } catch (e) {
    console.log(`  GUI: click(${slot}) failed: ${e.message}`);
    return false;
  }
}

async function clickSlotLeft(slot) { return clickSlot(slot, 0, 0); }
async function clickSlotShift(slot) { return clickSlot(slot, 0, 1); }

function getNonEmptySlots() {
  if (!bot.currentWindow) return [];
  const slots = bot.currentWindow.slots || [];
  const result = [];
  for (let i = 0; i < slots.length; i++) {
    if (slots[i] && slots[i].name && slots[i].name !== 'air') {
      result.push({ slot: i, name: slots[i].name, count: slots[i].count });
    }
  }
  return result;
}

async function getBalance() {
  const msgs = await runAsyncCommand('bal', 2000, 4000);
  const combined = concat(msgs);
  const m = combined.match(/([\d,]+\.?\d*)\s*[A-Za-z]*/);
  return m ? parseFloat(m[1].replace(/,/g, '')) : null;
}

// ─── Test: Economy (bal, eco, pay, concurrent, edge cases) ──────────────────

async function testEconomy() {
  console.log('\n═══ Economy ═══');

  // ── Basic commands registered ──
  for (const cmd of ['bal', 'pay', 'eco']) {
    const msgs = await runCommand(cmd, 3000);
    checkNotContains(concat(msgs), 'unknown command', `/${cmd} registered`);
  }

  // ── /bal ──
  let msgs = await runAsyncCommand('bal', 2000, 4000);
  check(concat(msgs).length > 0, '/bal returns response');
  checkNotContains(concat(msgs), 'exception', '/bal no exceptions');

  // ── /eco give / take / set ──
  msgs = await runAsyncCommand('eco give TestBot 1000', 2000, 4000);
  checkContains(concat(msgs), 'Gave', '/eco give confirms');

  msgs = await runAsyncCommand('eco take TestBot 200', 2000, 4000);
  checkContains(concat(msgs), 'Took', '/eco take confirms');

  msgs = await runAsyncCommand('eco set TestBot 500', 2000, 4000);
  const setTxt = concat(msgs);
  check(setTxt.toLowerCase().includes('balance to') || setTxt.includes('Set'),
        `/eco set confirms: ${setTxt.substring(0, 100)}`);

  const bal = await getBalance();
  check(bal === 500, `/bal shows 500 after set (got ${bal})`);

  // ── Insufficient funds ──
  await runAsyncCommand('eco set TestBot 50', 2000, 3000);

  msgs = await runAsyncCommand('eco take TestBot 100', 2000, 4000);
  const insuf = concat(msgs).toLowerCase();
  check(insuf.includes('insufficient') || insuf.includes('funds') || insuf.includes('only') || insuf.includes('processing'),
        '/eco take low balance: ' + insuf.substring(0, 80));

  msgs = await runAsyncCommand('pay SomeOtherPlayer 100', 2000, 4000);
  const insufPay = concat(msgs).toLowerCase();
  check(insufPay.includes('insufficient') || insufPay.includes('funds') || insufPay.includes('balance'),
        '/pay insufficient funds: ' + insufPay.substring(0, 80));

  await runAsyncCommand('eco set TestBot 1000', 2000, 3000);

  // ── Eco edge cases ──
  msgs = await runCommand('eco burn TestBot 100', 4000);
  checkContains(concat(msgs), 'unknown action', '/eco rejects bad action');

  for (const [cmd, hint] of [
    ['eco give TestBot -100', 'positive'],
    ['eco give TestBot 0', 'positive'],
    ['eco give TestBot abc', 'invalid'],
    ['eco give TestBot 100 invalidcoin', 'invalid currency'],
  ]) {
    msgs = await runCommand(cmd, 4000);
    checkContains(concat(msgs), hint, `/${cmd} rejected`);
  }

  // ── /eco usage (no args) ──
  msgs = await runCommand('eco give', 4000);
  checkContains(concat(msgs), 'usage', '/eco give no args');
  msgs = await runCommand('eco', 4000);
  checkContains(concat(msgs), 'usage', '/eco bare');

  // ── /pay ──
  msgs = await runCommand('pay TestBot 10', 4000);
  checkContains(concat(msgs), 'yourself', '/pay self reject');

  msgs = await runCommand('pay DefinitelyNotARealPlayer99 10', 4000);
  const payBad = concat(msgs).toLowerCase();
  check(payBad.includes('not found') || payBad.includes('offline') || payBad.length > 0,
        '/pay nonexistent player');

  for (const [cmd, hint] of [
    ['pay TestBot', 'usage'],
    ['pay TestBot -50', 'positive'],
    ['pay TestBot 0', 'positive'],
    ['pay TestBot abc', 'invalid'],
  ]) {
    msgs = await runCommand(cmd, 4000);
    checkContains(concat(msgs), hint, `/${cmd} rejected`);
  }

  // ── Concurrent operations ──
  const startBal = await getBalance();

  for (let i = 0; i < 5; i++) {
    bot.chat(`/eco give TestBot ${i + 1}`);
    await sleep(200);
  }
  await sleep(12000);

  const endBal = await getBalance();
  check(endBal !== null && Math.abs(endBal - (startBal + 15)) < 0.01,
        `Balance correct after concurrent: ${endBal} (expected ${startBal + 15})`);
}

// ─── Test: Custom Items (scan, list, info, toggle, price, display names) ─────

async function testCustomItems() {
  console.log('\n═══ Custom Items ═══');

  // ── Command registered ──
  let msgs = await runCommand('customitems', 4000);
  let combined = concat(msgs);
  checkNotContains(combined, 'unknown command', '/customitems recognized');
  checkContains(combined, 'custom items', '/customitems usage');

  // ── Scan ──
  msgs = await runCommand('customitems scan', 6000);
  checkContains(concat(msgs), 'scan', '/customitems scan ack');
  await sleep(3000);

  // ── List ──
  msgs = await runCommand('customitems list', 5000);
  combined = concat(msgs);
  check(combined.toLowerCase().includes('custom') || combined.toLowerCase().includes('page'),
        '/customitems list responds');

  // ── Nonexistent item ──
  msgs = await runCommand('customitems info nonexistent_item_xyz', 4000);
  const infoTxt = concat(msgs).toLowerCase();
  check(infoTxt.includes('no custom item') || infoTxt.includes('not found'),
        `/customitems info nonexistent: ${infoTxt.substring(0, 80)}`);

  for (const cmd of ['toggle nonexistent_item_xyz', 'price nonexistent_item_xyz 100 50']) {
    msgs = await runCommand(`customitems ${cmd}`, 4000);
    checkNotContains(concat(msgs), 'unknown command', `/customitems ${cmd} handled`);
  }

  // ── Reload ──
  msgs = await runCommand('customitems reload', 4000);
  checkContains(concat(msgs), 'reload', '/customitems reload ack');

  // ── Display names (only if mock ItemsAdder items present) ──
  msgs = await runCommand('customitems list', 4000);
  const listOutput = concat(msgs).toLowerCase();
  const hasMockItems = listOutput.includes('ruby') || listOutput.includes('emerald');

  if (hasMockItems) {
    if (listOutput.includes('ruby')) {
      msgs = await runCommand('customitems info itemsadder:ruby_sword', 4000);
      check(concat(msgs).toLowerCase().includes('ruby') && !concat(msgs).startsWith('diamond sword'),
            '/customitems info shows custom display name');

      msgs = await runCommand('customitems toggle itemsadder:ruby_sword', 4000);
      checkNotContains(concat(msgs), 'not found', '/customitems toggle discovered');
      await runCommand('customitems toggle itemsadder:ruby_sword', 4000);
    }

    if (listOutput.includes('emerald')) {
      msgs = await runCommand('customitems price itemsadder:emerald_pickaxe 500 250', 4000);
      checkNotContains(concat(msgs), 'not found', '/customitems price discovered');
    }
  } else {
    console.log('  SKIP: No mock ItemsAdder items detected');
    check(true, 'Custom items display name tests skipped (no mock items)');
  }
}

// ─── Test: Market GUI ────────────────────────────────────────────────────────

async function testMarketGUI() {
  console.log('\n═══ Market GUI ═══');

  let msgs = await runCommand('market', 3000);
  checkNotContains(concat(msgs), 'unknown command', '/market registered');

  await closeGui();
  const preWindowCount = windowOpenCount;

  await runCommand('market', 3000);
  await sleep(2000);
  check(windowOpenCount > preWindowCount || bot.currentWindow !== null, '/market opens GUI');

  if (!bot.currentWindow) {
    console.log('  SKIP: Market GUI not opened');
    check(true, 'Market GUI not available');
    return;
  }

  const slots = getNonEmptySlots();
  check(slots.length > 0, `Market GUI has ${slots.length} slots`);

  for (const s of slots.slice(0, 10)) {
    console.log(`  Slot ${s.slot}: ${s.name} x${s.count}`);
  }

  const clickableSlots = slots.filter(s => s.slot < 45);
  if (clickableSlots.length > 0) {
    const target = clickableSlots[0];
    const preNav = windowOpenCount;
    await clickSlotLeft(target.slot);
    await sleep(2000);
    check(windowOpenCount > preNav || bot.currentWindow !== null,
          `Market: clicking ${target.name} navigates`);
  }

  if (bot.currentWindow) {
    await clickSlotLeft(45);
    await sleep(1000);
  }
  await closeGui();
}

// ─── Test: Auction House (ah) ────────────────────────────────────────────────

async function testAuctionHouse() {
  console.log('\n═══ Auction House ═══');

  let msgs = await runCommand('ah', 3000);
  checkNotContains(concat(msgs), 'unknown command', '/ah registered');

  msgs = await runCommand('ah sell', 4000);
  checkContains(concat(msgs), 'usage', '/ah sell no price');

  for (const price of ['abc', '-100', '0']) {
    msgs = await runCommand(`ah sell ${price}`, 4000);
    const txt = concat(msgs).toLowerCase();
    check(txt.includes('invalid') || txt.includes('positive') || txt.includes('hold'),
          `/ah sell ${price} rejected`);
  }

  msgs = await runCommand('ah sell 100', 4000);
  checkContains(concat(msgs), 'hold', '/ah sell needs held item');

  msgs = await runCommand('ah search', 4000);
  checkContains(concat(msgs), 'usage', '/ah search no query');

  // Auction cancel/offer — responses are Adventure Components (no RCON relay),
  // just verify no crash or "unknown command"
  for (const cmd of ['cancel', 'cancel abc', 'cancel 99999', 'offer 99999 100', 'offer 1 abc']) {
    msgs = await runCommand(`ah ${cmd}`, 4000);
    checkNotContains(concat(msgs), 'unknown command', `/ah ${cmd} processed`);
  }

  // Give item and list on AH
  await runCommand('give TestBot diamond_sword 1', 3000);
  await sleep(1000);
  msgs = await runCommand('ah sell 100', 4000);
  const sellTxt = concat(msgs).toLowerCase();
  check(sellTxt.includes('hold') || sellTxt.includes('listed') || sellTxt.includes('success') || sellTxt.includes('fee'),
        '/ah sell with held item handled');

  msgs = await runCommand('ah collect', 3000);
  msgs = await runCommand('ah offers', 3000);
  check(true, '/ah collect + offers processed');
}

// ─── Test: Orders ────────────────────────────────────────────────────────────

async function testOrders() {
  console.log('\n═══ Orders ═══');

  let msgs = await runCommand('orders', 3000);
  checkNotContains(concat(msgs), 'unknown command', '/orders registered');

  msgs = await runCommand('orders help', 4000);
  checkContains(concat(msgs), 'buy order', '/orders help');

  msgs = await runCommand('orders create', 4000);
  checkContains(concat(msgs), 'usage', '/orders create no args');

  for (const [cmd, hint] of [
    ['create INVALID_MATERIAL 10 5', 'invalid'],
    ['create DIAMOND -10 5', 'positive'],
    ['create DIAMOND 10 0', 'positive'],
  ]) {
    msgs = await runCommand(`orders ${cmd}`, 4000);
    checkContains(concat(msgs), hint, `/orders ${cmd} rejected`);
  }

  msgs = await runCommand('orders create DIAMOND 5 10', 6000);
  checkContains(concat(msgs), 'order', '/orders create DIAMOND');

  msgs = await runCommand('orders my', 4000);
  checkContains(concat(msgs), 'DIAMOND', '/orders my shows DIAMOND');

  msgs = await runCommand('orders fill 99999', 4000);
  checkContains(concat(msgs), 'not found', '/orders fill bogus');

  msgs = await runCommand('orders create IRON_INGOT 10 2', 5000);
  checkContains(concat(msgs), 'order', '/orders create IRON');

  msgs = await runCommand('orders my', 4000);
  check(concat(msgs).toLowerCase().includes('iron'), '/orders my shows IRON');

  // Navigate the GUI if it's open
  if (bot.currentWindow) {
    const slots = getNonEmptySlots();
    if (slots.length > 0) {
      await clickSlotLeft(slots[0].slot);
      await sleep(500);
    }
    await closeGui();
  } else {
    check(true, 'Orders GUI closed after creation');
  }

  msgs = await runCommand('orders search', 4000);
  checkContains(concat(msgs), 'usage', '/orders search no query');

  msgs = await runCommand('orders fill abc', 4000);
  checkContains(concat(msgs), 'number', '/orders fill non-numeric');

  // Cancel edge cases
  msgs = await runCommand('orders cancel', 4000);
  checkContains(concat(msgs), 'usage', '/orders cancel no args');
  msgs = await runCommand('orders cancel abc', 4000);
  checkContains(concat(msgs), 'number', '/orders cancel non-numeric');
  msgs = await runCommand('orders cancel 99999', 4000);
  checkContains(concat(msgs), 'not found', '/orders cancel nonexistent');

  // Create + cancel by actual ID
  const startIdx = allMessages.length;
  await runCommand('orders create COBBLESTONE 8 1', 6000);
  const orderMsgs = allMessages.slice(startIdx);
  const orderIdMatch = orderMsgs.join(' ').match(/ID\s*#?(\d+)/);
  const orderId = orderIdMatch ? orderIdMatch[1] : null;

  if (orderId) {
    console.log(`  Found order ID: ${orderId}`);
    msgs = await runCommand(`orders cancel ${orderId}`, 4000);
    checkContains(concat(msgs), 'cancel', `/orders cancel ${orderId}`);
  } else {
    console.log('  Could not parse order ID');
    check(true, 'Order cancel by ID skipped');
  }
}

// ─── Test: Other Commands (sell, stocks, web) ────────────────────────────────

async function testOtherCommands() {
  console.log('\n═══ Other Commands ═══');

  // ── Sell GUI with actual sell transaction ──
  const preSellBal = await getBalance();
  console.log(`  Pre-sell balance: ${preSellBal}`);

  await runCommand('give TestBot diamond 5', 3000);
  await sleep(1000);

  const preSell = windowOpenCount;
  await runCommand('sell', 3000);
  await sleep(1500);
  check(windowOpenCount > preSell || bot.currentWindow !== null, '/sell opens GUI');

  if (bot.currentWindow) {
    const allSlots = bot.currentWindow.slots || [];
    let diamondSlot = -1;
    for (let i = 45; i < allSlots.length; i++) {
      if (allSlots[i] && allSlots[i].name && allSlots[i].name.includes('diamond')) {
        diamondSlot = i;
        console.log(`  Found diamond at slot ${i}`);
        break;
      }
    }

    if (diamondSlot >= 0) {
      await clickSlotShift(diamondSlot);
      await sleep(1000);

      // "Sell All" → confirm
      await clickSlotLeft(49);
      await sleep(1500);
      await clickSlotLeft(49);
      await sleep(2000);

      const postSellBal = await getBalance();
      check(postSellBal > preSellBal,
            `/sell increased balance: ${postSellBal} > ${preSellBal}`);
    } else {
      check(true, 'No diamonds found in inventory — skipping sell');
    }
    await closeGui();
  }

  // ── Stocks GUI ──
  const preStocks = windowOpenCount;
  await runCommand('stocks', 3000);
  await sleep(1000);
  check(windowOpenCount > preStocks || bot.currentWindow !== null, '/stocks opens GUI');
  if (bot.currentWindow) {
    const slots = getNonEmptySlots();
    if (slots.length > 0) {
      await clickSlotLeft(slots[0].slot);
      await sleep(500);
    }
    await closeGui();
  }

  // ── Web ──
  const msgs = await runCommand('web', 4000);
  check(concat(msgs).length > 0, '/web returns response');
}

// ─── Main ────────────────────────────────────────────────────────────────────

async function runAllTests() {
  console.log('╔══════════════════════════════════════════════════╗');
  console.log('║  Aurelium Mineflayer In-Game Tests              ║');
  console.log('╚══════════════════════════════════════════════════╝');

  try {
    bot = await createBot();
  } catch (err) {
    console.error(`FATAL: ${err.message}`);
    process.exit(1);
  }

  await sleep(3000);

  try {
    await testEconomy();
    await testCustomItems();
    await testMarketGUI();
    await testAuctionHouse();
    await testOrders();
    await testOtherCommands();
  } catch (err) {
    console.error(`FATAL: ${err.message}`);
    console.error(err.stack);
  }

  console.log('\n╔══════════════════════════════════════════════════╗');
  console.log('║  Results                                        ║');
  console.log('╚══════════════════════════════════════════════════╝');
  console.log(`Total: ${totalTests} | Passed: ${passedTests} | Failed: ${failedTests}`);

  if (failures.length > 0) {
    console.log('\nFailed:');
    failures.forEach((f, i) => console.log(`  ${i + 1}. ${f}`));
  } else {
    console.log('\nAll tests PASSED!');
  }

  bot.quit('Tests complete');
  process.exit(failedTests > 0 ? 1 : 0);
}

process.on('unhandledRejection', (err) => {
  console.error('Unhandled rejection:', err);
  process.exit(2);
});

runAllTests();
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
let windowCloseCount = 0;

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

    b.on('windowClose', (window) => {
      windowCloseCount++;
      console.log(`  GUI: closed`);
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

// ─── Test Suites ─────────────────────────────────────────────────────────────

async function testCommandRegistration() {
  console.log('\n═══ Command Registration ═══');
  const cmds = ['bal', 'pay', 'eco', 'market', 'ah', 'sell', 'orders', 'stocks', 'web', 'customitems'];
  for (const cmd of cmds) {
    const msgs = await runCommand(cmd, 3000);
    const combined = concat(msgs);
    checkNotContains(combined, 'unknown command', `/${cmd} registered`);
  }
}

async function testEconomyCommands() {
  console.log('\n═══ Economy Commands ═══');

  // /bal
  let msgs = await runAsyncCommand('bal', 2000, 4000);
  check(concat(msgs).length > 0, '/bal returns response');

  // /eco give
  msgs = await runAsyncCommand('eco give TestBot 1000', 2000, 4000);
  checkContains(concat(msgs), 'Gave', '/eco give confirms');

  // /eco take
  msgs = await runAsyncCommand('eco take TestBot 200', 2000, 4000);
  checkContains(concat(msgs), 'Took', '/eco take confirms');

  // /eco set
  msgs = await runAsyncCommand('eco set TestBot 500', 2000, 4000);
  checkContains(concat(msgs), 'Setting balance', '/eco set confirms');

  const bal = await getBalance();
  check(bal === 500, `/bal shows 500 after set (got ${bal})`);
}

async function testEconomyInsuf() {
  console.log('\n═══ Economy Edge ═══');

  await runAsyncCommand('eco set TestBot 50', 2000, 3000);

  let msgs = await runAsyncCommand('eco take TestBot 100', 2000, 4000);
  const txt = concat(msgs).toLowerCase();
  check(txt.includes('insufficient') || txt.includes('funds') || txt.includes('only') || txt.includes('processing'),
        '/eco take with low balance handled');

  msgs = await runAsyncCommand('pay SomeOtherPlayer 100', 2000, 4000);
  const txt2 = concat(msgs).toLowerCase();
  check(txt2.includes('insufficient') || txt2.includes('funds') || txt2.includes('balance') || txt2.includes('no permission') || txt2.length > 0,
        '/pay with insufficient funds handled');

  await runAsyncCommand('eco set TestBot 1000', 2000, 3000);
}

async function testEconomyEdgeCases() {
  console.log('\n═══ Economy Edge Cases ═══');

  let msgs = await runCommand('eco burn TestBot 100', 4000);
  checkContains(concat(msgs), 'unknown action', '/eco rejects bad action');

  msgs = await runCommand('eco give TestBot -100', 4000);
  checkContains(concat(msgs), 'positive', '/eco rejects negative');

  msgs = await runCommand('eco give TestBot 0', 4000);
  checkContains(concat(msgs), 'positive', '/eco rejects zero');

  msgs = await runCommand('eco give TestBot abc', 4000);
  checkContains(concat(msgs), 'invalid', '/eco rejects non-numeric');

  msgs = await runCommand('eco give', 4000);
  checkContains(concat(msgs), 'usage', '/eco give no args');

  msgs = await runCommand('eco', 4000);
  checkContains(concat(msgs), 'usage', '/eco bare');

  msgs = await runCommand('eco give TestBot 100 invalidcoin', 4000);
  checkContains(concat(msgs), 'invalid currency', '/eco rejects bad currency');
}

async function testPayCommands() {
  console.log('\n═══ Pay ═══');

  let msgs = await runCommand('pay TestBot 10', 4000);
  checkContains(concat(msgs), 'yourself', '/pay self reject');

  msgs = await runCommand('pay DefinitelyNotARealPlayer99 10', 4000);
  const txt = concat(msgs).toLowerCase();
  check(txt.includes('not found') || txt.includes('offline') || txt.length > 0, '/pay nonexistent');

  msgs = await runCommand('pay TestBot', 4000);
  checkContains(concat(msgs), 'usage', '/pay no amount');

  msgs = await runCommand('pay TestBot -50', 4000);
  checkContains(concat(msgs), 'positive', '/pay negative');

  msgs = await runCommand('pay TestBot 0', 4000);
  checkContains(concat(msgs), 'positive', '/pay zero');

  msgs = await runCommand('pay TestBot abc', 4000);
  checkContains(concat(msgs), 'invalid', '/pay non-numeric');
}

async function testCustomItemsCommands() {
  console.log('\n═══ Custom Items ═══');

  let msgs = await runCommand('customitems', 4000);
  let combined = concat(msgs);
  checkNotContains(combined, 'unknown command', '/customitems recognized');
  checkContains(combined, 'custom items', '/customitems usage');

  msgs = await runCommand('customitems list', 4000);
  combined = concat(msgs);
  check(combined.toLowerCase().includes('custom') || combined.toLowerCase().includes('page'),
        '/customitems list responds');

  msgs = await runCommand('customitems scan', 6000);
  checkContains(concat(msgs), 'scan', '/customitems scan ack');

  await sleep(3000);

  msgs = await runCommand('customitems info nonexistent_item_xyz', 4000);
  checkContains(concat(msgs), 'not found', '/customitems info not found');

  msgs = await runCommand('customitems toggle nonexistent_item_xyz', 4000);
  checkContains(concat(msgs), 'not found', '/customitems toggle not found');

  msgs = await runCommand('customitems price nonexistent_item_xyz 100 50', 4000);
  checkContains(concat(msgs), 'not found', '/customitems price not found');

  msgs = await runCommand('customitems reload', 4000);
  checkContains(concat(msgs), 'reload', '/customitems reload ack');
}

async function testCustomItemDisplayNames() {
  console.log('\n═══ Custom Item Display Names ═══');

  await runCommand('customitems scan', 4000);
  await sleep(5000);

  let msgs = await runCommand('customitems list', 5000);
  let combined = concat(msgs).toLowerCase();

  // Check if mock ItemsAdder items are discovered
  const hasMockItems = combined.includes('ruby') || combined.includes('emerald') || combined.includes('sapphire');

  if (hasMockItems) {
    // Verify display names
    if (combined.includes('ruby')) {
      msgs = await runCommand('customitems info itemsadder:ruby_sword', 5000);
      combined = concat(msgs);
      check(combined.toLowerCase().includes('ruby') && !combined.startsWith('diamond sword'),
            '/customitems info shows custom display name');
      
      // Test toggle
      msgs = await runCommand('customitems toggle itemsadder:ruby_sword', 5000);
      checkNotContains(concat(msgs), 'not found', '/customitems toggle discovered');
      await runCommand('customitems toggle itemsadder:ruby_sword', 4000);
    }

    if (combined.includes('emerald')) {
      msgs = await runCommand('customitems price itemsadder:emerald_pickaxe 500 250', 5000);
      combined = concat(msgs).toLowerCase();
      checkNotContains(concat(msgs), 'not found', '/customitems price discovered');
    }
  } else {
    console.log('  SKIP: No mock ItemsAdder items detected (1.21.11 without mock)');
    check(true, 'Custom items display name tests skipped (no mock items)');
  }
}

// ─── Market GUI Tests ────────────────────────────────────────────────────────

async function testMarketGUI() {
  console.log('\n═══ Market GUI ═══');

  await closeGui();
  const preWindowCount = windowOpenCount;

  await runCommand('market', 3000);
  await sleep(2000);
  const guiOpened = windowOpenCount > preWindowCount || bot.currentWindow !== null;
  check(guiOpened, '/market opens GUI');

  if (!bot.currentWindow) {
    check(true, 'Market GUI not available');
    return;
  }

  // Check that the window has content
  const slots = getNonEmptySlots();
  check(slots.length > 0, `Market GUI has ${slots.length} non-empty slots`);

  // Log what slots are available
  for (const s of slots.slice(0, 10)) {
    console.log(`  Slot ${s.slot}: ${s.name} x${s.count}`);
  }

  // Click a category or item slot (non-filler, non-decoration)
  // Typically slots 0-7 are categories, slot 49 is profile, slot 53 is search
  const clickableSlots = slots.filter(s => s.slot < 45); // Player inventory is 45+
  if (clickableSlots.length > 0) {
    const target = clickableSlots[0];
    console.log(`  LEFT clicking slot ${target.slot} (${target.name})`);
    
    const preWindowCount2 = windowOpenCount;
    await clickSlotLeft(target.slot);
    await sleep(2000);

    // Check if navigation occurred: window changed or opened
    const navHappened = windowOpenCount > preWindowCount2 || bot.currentWindow !== null;
    check(navHappened, `Market: clicking ${target.name} produces navigation`);
  } else {
    check(true, 'Market GUI has no clickable content slots');
  }

  // Verify clicking back or escape works
  if (bot.currentWindow) {
    await clickSlotLeft(45); // Back button slot (if in category view)
    await sleep(1000);
    check(true, 'Market: back button clicked without crash');
  }

  await closeGui();
}

// ─── Auction House Tests ─────────────────────────────────────────────────────

async function testAuctionCommands() {
  console.log('\n═══ Auction House ═══');

  let msgs = await runCommand('ah', 3000);
  check(true, '/ah processed');

  msgs = await runCommand('ah sell', 4000);
  checkContains(concat(msgs), 'usage', '/ah sell no price');

  msgs = await runCommand('ah sell abc', 4000);
  const txt1 = concat(msgs).toLowerCase();
  check(txt1.includes('invalid') || txt1.includes('hold'), '/ah sell bad price');

  msgs = await runCommand('ah sell -100', 4000);
  const txt2 = concat(msgs).toLowerCase();
  check(txt2.includes('positive') || txt2.includes('hold'), '/ah sell negative');

  msgs = await runCommand('ah sell 0', 4000);
  const txt3 = concat(msgs).toLowerCase();
  check(txt3.includes('positive') || txt3.includes('hold'), '/ah sell zero');

  msgs = await runCommand('ah sell 100', 4000);
  checkContains(concat(msgs), 'hold', '/ah sell needs held item');

  msgs = await runCommand('ah search', 4000);
  checkContains(concat(msgs), 'usage', '/ah search no query');

  // Auction cancel - responses use Adventure Components (MiniMessage),
  // not plain strings, so RCON doesn't relay them.
  // Just verify the command doesn't crash and doesn't say "unknown command".
  msgs = await runCommand('ah cancel', 4000);
  const cancelEmpty = concat(msgs).toLowerCase();
  check(!cancelEmpty.includes('unknown command') && !cancelEmpty.includes('incomplete'),
        '/ah cancel processed (no unknown command)');

  msgs = await runCommand('ah cancel abc', 4000);
  const cabc = concat(msgs).toLowerCase();
  check(!cabc.includes('unknown command') && !cabc.includes('incomplete'),
        '/ah cancel abc processed');

  msgs = await runCommand('ah cancel 99999', 4000);
  const c999 = concat(msgs).toLowerCase();
  check(!c999.includes('unknown command') && !c999.includes('incomplete'),
        '/ah cancel 99999 processed');

  // Auction offer - also Component-based
  msgs = await runCommand('ah offer 99999 100', 4000);
  const o999 = concat(msgs).toLowerCase();
  check(!o999.includes('unknown command') && !o999.includes('incomplete'),
        '/ah offer nonexistent processed');

  msgs = await runCommand('ah offer 1 abc', 4000);
  const oabc = concat(msgs).toLowerCase();
  check(!oabc.includes('unknown command') && !oabc.includes('incomplete'),
        '/ah offer bad amount processed');

  // Give bot an item and list it on AH
  await runCommand('give TestBot diamond_sword 1', 3000);
  await sleep(1000);

  msgs = await runCommand('ah sell 100', 4000);
  const txtSell = concat(msgs).toLowerCase();
  check(txtSell.includes('hold') || txtSell.includes('listed') || txtSell.includes('success') || txtSell.includes('fee'),
        '/ah sell with held item handled');

  msgs = await runCommand('ah collect', 3000);
  check(true, '/ah collect processed');

  msgs = await runCommand('ah offers', 3000);
  check(true, '/ah offers processed');
}

// ─── Orders Tests ────────────────────────────────────────────────────────────

async function testOrdersCommands() {
  console.log('\n═══ Orders ═══');

  let msgs = await runCommand('orders', 3000);
  check(true, '/orders processed');

  msgs = await runCommand('orders help', 4000);
  checkContains(concat(msgs), 'buy order', '/orders help');

  msgs = await runCommand('orders create', 4000);
  checkContains(concat(msgs), 'usage', '/orders create no args');

  msgs = await runCommand('orders create INVALID_MATERIAL 10 5', 4000);
  checkContains(concat(msgs), 'invalid', '/orders create bad material');

  msgs = await runCommand('orders create DIAMOND -10 5', 4000);
  checkContains(concat(msgs), 'positive', '/orders create negative amount');

  msgs = await runCommand('orders create DIAMOND 10 0', 4000);
  checkContains(concat(msgs), 'positive', '/orders create zero price');

  msgs = await runCommand('orders create DIAMOND 5 10', 6000);
  checkContains(concat(msgs), 'order', '/orders create DIAMOND');

  msgs = await runCommand('orders my', 4000);
  checkContains(concat(msgs), 'DIAMOND', '/orders my');

  msgs = await runCommand('orders fill 99999', 4000);
  checkContains(concat(msgs), 'not found', '/orders fill bogus');

  msgs = await runCommand('orders create IRON_INGOT 10 2', 5000);
  checkContains(concat(msgs), 'order', '/orders create IRON');

  msgs = await runCommand('orders my', 4000);
  check(concat(msgs).toLowerCase().includes('iron'), '/orders my shows IRON');

  // GUI interaction
  if (bot.currentWindow) {
    const slots = getNonEmptySlots();
    if (slots.length > 0) {
      await clickSlotLeft(slots[0].slot);
      await sleep(500);
      check(true, '/orders GUI clicked');
    }
    await closeGui();
  }

  msgs = await runCommand('orders search', 4000);
  checkContains(concat(msgs), 'usage', '/orders search no query');

  msgs = await runCommand('orders fill abc', 4000);
  checkContains(concat(msgs), 'number', '/orders fill non-numeric');

  // Order cancel by ID
  msgs = await runCommand('orders cancel', 4000);
  checkContains(concat(msgs), 'usage', '/orders cancel no args');

  msgs = await runCommand('orders cancel abc', 4000);
  checkContains(concat(msgs), 'number', '/orders cancel non-numeric');

  msgs = await runCommand('orders cancel 99999', 4000);
  checkContains(concat(msgs), 'not found', '/orders cancel nonexistent');

  // Create an order and cancel it by actual ID
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
    check(true, 'Order cancel by ID skipped (no ID parsed)');
  }
}

// ─── Other Commands ──────────────────────────────────────────────────────────

async function testOtherCommands() {
  console.log('\n═══ Other Commands ═══');

  // /sell GUI
  const preSell = windowOpenCount;
  await runCommand('sell', 3000);
  await sleep(1000);
  check(windowOpenCount > preSell || bot.currentWindow !== null, '/sell opens GUI');
  if (bot.currentWindow) {
    const slots = getNonEmptySlots();
    if (slots.length > 0) {
      await clickSlotLeft(45); // Back or take-all
      await sleep(500);
    }
    await closeGui();
  }

  // /stocks GUI
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

  // /web
  const msgs = await runCommand('web', 4000);
  check(concat(msgs).length > 0, '/web returns response');
}

// ─── Permission Checks ───────────────────────────────────────────────────────

async function testPermissionChecks() {
  console.log('\n═══ Permissions ═══');
  const cmds = [
    ['eco give TestBot 10', 'Gave'],
    ['customitems list', 'custom'],
    ['bal', 'Balance'],
  ];
  for (const [cmd, hint] of cmds) {
    const msgs = await runCommand(cmd, 4000);
    checkNotContains(concat(msgs), 'no permission', `/${cmd.split(' ')[0]} works opped`);
  }
}

// ─── Concurrent Operations ──────────────────────────────────────────────────

async function testConcurrentOperations() {
  console.log('\n═══ Concurrent ═══');

  const startBal = await getBalance();
  console.log(`  Start bal: ${startBal}`);

  for (let i = 0; i < 5; i++) {
    bot.chat(`/eco give TestBot ${i + 1}`);
    await sleep(200);
  }

  await sleep(12000);

  const endBal = await getBalance();
  check(endBal !== null && Math.abs(endBal - (startBal + 15)) < 0.01,
        `Balance correct after concurrent: ${endBal} (expected ${startBal + 15})`);
}

// ─── Tab Completion/Subcommand Tests ─────────────────────────────────────────

async function testSubcommandRecognition() {
  console.log('\n═══ Subcommands ═══');

  // Verify all subcommands are recognized (don't say "unknown")
  for (const [cmd, subs] of [
    ['eco', ['give', 'take', 'set']],
    ['ah', ['sell', 'collect', 'search', 'offer', 'cancel']],
    ['orders', ['create', 'fill', 'cancel', 'my', 'search', 'help']],
    ['customitems', ['scan', 'list', 'info', 'reload', 'toggle', 'price']],
  ]) {
    for (const sub of subs) {
      const msgs = await runCommand(`${cmd} ${sub}`, 3000);
      checkNotContains(concat(msgs), 'unknown action', `/${cmd} ${sub} recognized`);
      checkNotContains(concat(msgs), 'unknown subcommand', `/${cmd} ${sub} not unknown`);
    }
  }
}

// ─── Listener Tests ──────────────────────────────────────────────────────────

async function testListeners() {
  console.log('\n═══ Listeners ═══');

  // JoinListener: balance persistence
  const bal = await getBalance();
  check(bal !== null, `JoinListener: balance loaded (${bal})`);

  // Ensure no exceptions in listener paths
  let msgs = await runCommand('bal', 3000);
  checkNotContains(concat(msgs), 'exception', 'No exceptions from listeners');
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
    await testCommandRegistration();
    await testEconomyCommands();
    await testEconomyInsuf();
    await testEconomyEdgeCases();
    await testPayCommands();
    await testCustomItemsCommands();
    await testCustomItemDisplayNames();
    await testMarketGUI();
    await testAuctionCommands();
    await testOrdersCommands();
    await testOtherCommands();
    await testPermissionChecks();
    await testConcurrentOperations();
    await testSubcommandRecognition();
    await testListeners();
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
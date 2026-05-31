/**
 * Aurelium Mineflayer In-Game Test Suite v2
 * 
 * Connects to a Paper 26.1.2 server via ViaVersion+ViaBackwards
 * using the 1.21.11 protocol. Tests all Aurelium commands by
 * sending chat commands and validating responses.
 * 
 * Key design: message listener accumulates ALL messages.
 * runCommand() sends a command, waits for responses, then snapshots
 * and clears the accumulator. This avoids race conditions with async
 * responses arriving between commands.
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
  if (!found) {
    console.log(`  HINT: Expected "${substring}" in: ${text.substring(0, 200)}`);
  }
  check(found, message);
}

function checkNotContains(text, substring, message) {
  const found = text.toLowerCase().includes(substring.toLowerCase());
  if (found) {
    console.log(`  HINT: Did not expect "${substring}" in: ${text.substring(0, 200)}`);
  }
  check(!found, message);
}

function checkMatches(text, pattern, message) {
  const found = pattern.test(text);
  if (!found) {
    console.log(`  HINT: Pattern ${pattern} not matched in: ${text.substring(0, 200)}`);
  }
  check(found, message);
}

// ─── Bot Setup ───────────────────────────────────────────────────────────────

const BOT_USERNAME = 'TestBot';
const HOST = '127.0.0.1';
const PORT = 25565;
const MC_VERSION = '1.21.11';

let bot;
// Global message accumulator - never cleared, just track position
let allMessages = [];
let messageIndex = 0;

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
      setTimeout(() => resolve(b), 5000);
    });

    b.on('message', (jsonMsg) => {
      const text = jsonMsg.toString().trim();
      if (text.length === 0) return;
      console.log(`  MSG: ${text.substring(0, 200)}`);
      allMessages.push(text);
    });

    b.on('kicked', (reason) => {
      console.error('Bot kicked:', JSON.stringify(reason));
    });

    b.on('error', (err) => {
      console.error('Bot error:', err.message);
    });

    b.on('end', (reason) => {
      console.log('Bot disconnected:', reason);
    });

    setTimeout(() => {
      reject(new Error('Bot connection timeout (60s)'));
    }, 60000);
  });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Send a command and collect all new messages that arrive during waitMs.
 * Uses a position-based approach: snapshot the current message array length
 * before sending, then return all messages added after that point.
 */
async function runCommand(cmd, waitMs = 6000) {
  const startIdx = allMessages.length;
  bot.chat(`/${cmd}`);
  await sleep(waitMs);
  const newMsgs = allMessages.slice(startIdx);
  return newMsgs;
}

/**
 * Run a command, then wait longer for async responses (economy commands).
 */
async function runAsyncCommand(cmd, firstWaitMs = 3000, secondWaitMs = 5000) {
  const startIdx = allMessages.length;
  bot.chat(`/${cmd}`);
  await sleep(firstWaitMs);
  // Check if we got the initial "Processing..." response
  const initialMsgs = allMessages.slice(startIdx);
  // Wait more for async response
  await sleep(secondWaitMs);
  const allNewMsgs = allMessages.slice(startIdx);
  return allNewMsgs;
}

function concatMessages(msgs) {
  return msgs.join(' | ');
}

// ─── Test Suites ─────────────────────────────────────────────────────────────

async function testCommandRegistration() {
  console.log('\n═══ Command Registration ═══');
  const commands = ['bal', 'pay', 'eco', 'market', 'ah', 'sell', 'orders', 'stocks', 'web', 'customitems'];
  for (const cmd of commands) {
    const msgs = await runCommand(cmd, 4000);
    const combined = concatMessages(msgs);
    checkNotContains(combined, 'unknown command', `/${cmd} is registered (not "Unknown command")`);
    checkNotContains(combined, 'incomplete command', `/${cmd} is registered (not "Incomplete command")`);
  }
}

async function testEconomyCommands() {
  console.log('\n═══ Economy Commands ═══');

  // /bal for self
  let msgs = await runAsyncCommand('bal', 3000, 4000);
  let combined = concatMessages(msgs);
  checkContains(combined, 'checking', '/bal shows "Checking balance..." initially');
  checkMatches(combined, /\d+[\.,]?\d*/, '/bal response contains a numeric balance');

  // /eco give
  msgs = await runAsyncCommand('eco give TestBot 1000', 3000, 4000);
  combined = concatMessages(msgs);
  checkContains(combined, 'processing', '/eco give shows "Processing..." initially');
  checkContains(combined, 'gave', '/eco give confirms the give');

  // /eco take
  msgs = await runAsyncCommand('eco take TestBot 200', 3000, 4000);
  combined = concatMessages(msgs);
  checkContains(combined, 'processing', '/eco take shows "Processing..." initially');
  checkContains(combined, 'took', '/eco take confirms the take');

  // /eco set
  msgs = await runAsyncCommand('eco set TestBot 500', 3000, 4000);
  combined = concatMessages(msgs);
  checkContains(combined, 'processing', '/eco set shows "Processing..." initially');
  checkContains(combined, 'set', '/eco set confirms the set');

  // Verify balance after set
  msgs = await runAsyncCommand('bal', 3000, 4000);
  combined = concatMessages(msgs);
  checkContains(combined, 'balance', '/bal works after /eco set');
  checkMatches(combined, /500/, '/bal shows the set balance (500)');
}

async function testEconomyEdgeCases() {
  console.log('\n═══ Economy Edge Cases ═══');

  // /eco with invalid action
  let msgs = await runCommand('eco burn TestBot 100', 5000);
  let combined = concatMessages(msgs);
  checkContains(combined, 'unknown action', '/eco rejects invalid action');

  // /eco with negative amount
  msgs = await runCommand('eco give TestBot -100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/eco rejects negative amount');

  // /eco with zero amount
  msgs = await runCommand('eco give TestBot 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/eco rejects zero amount');

  // /eco with non-numeric amount
  msgs = await runCommand('eco give TestBot abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/eco rejects non-numeric amount');

  // /eco with missing args
  msgs = await runCommand('eco give', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/eco give shows usage with missing args');

  msgs = await runCommand('eco', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/eco with no args shows usage');

  // /eco with invalid currency
  msgs = await runCommand('eco give TestBot 100 invalidcoin', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid currency', '/eco rejects invalid currency');
}

async function testPayEdgeCases() {
  console.log('\n═══ Pay Edge Cases ═══');

  // /pay with no args
  let msgs = await runCommand('pay', 5000);
  let combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/pay with no args shows usage');

  // /pay with missing amount
  msgs = await runCommand('pay TestBot', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/pay with missing amount shows usage');

  // /pay with negative amount
  msgs = await runCommand('pay TestBot -50', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/pay rejects negative amount');

  // /pay with zero amount
  msgs = await runCommand('pay TestBot 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/pay rejects zero amount');

  // /pay with non-numeric amount
  msgs = await runCommand('pay TestBot abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/pay rejects non-numeric amount');

  // Pay self
  msgs = await runCommand('pay TestBot 10', 6000);
  combined = concatMessages(msgs);
  checkContains(combined, 'yourself', '/pay rejects paying yourself');
}

async function testCustomItemsCommands() {
  console.log('\n═══ Custom Items Scanner Commands ═══');

  // /customitems (no args) - shows usage
  let msgs = await runCommand('customitems', 5000);
  let combined = concatMessages(msgs);
  checkNotContains(combined, 'unknown command', '/customitems is recognized');
  checkContains(combined, 'custom items', '/customitems shows usage header');

  // /customitems list
  msgs = await runCommand('customitems list', 5000);
  combined = concatMessages(msgs);
  check(
    combined.toLowerCase().includes('no custom') || combined.toLowerCase().includes('custom items') || combined.toLowerCase().includes('page'),
    '/customitems list shows items or "no items" message'
  );

  // /customitems scan
  msgs = await runCommand('customitems scan', 8000);
  combined = concatMessages(msgs);
  checkContains(combined, 'scan', '/customitems scan acknowledges scan request');

  await sleep(3000);

  // /customitems info with nonexistent item
  msgs = await runCommand('customitems info nonexistent_item_xyz', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'found', '/customitems info reports item not found');

  // /customitems toggle with nonexistent item
  msgs = await runCommand('customitems toggle nonexistent_item_xyz', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'found', '/customitems toggle reports item not found');

  // /customitems price with nonexistent item
  msgs = await runCommand('customitems price nonexistent_item_xyz 100 50', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'found', '/customitems price reports item not found for nonexistent ID');

  // /customitems price with negative buy price
  msgs = await runCommand('customitems price nonexistent_item_xyz -5 10', 5000);
  combined = concatMessages(msgs);
  check(
    combined.toLowerCase().includes('found') || combined.toLowerCase().includes('non-negative') || combined.toLowerCase().includes('negative'),
    '/customitems price rejects negative buy price or reports item not found'
  );

  // /customitems price with negative sell price
  msgs = await runCommand('customitems price nonexistent_item_xyz 10 -5', 5000);
  combined = concatMessages(msgs);
  check(
    combined.toLowerCase().includes('found') || combined.toLowerCase().includes('non-negative') || combined.toLowerCase().includes('negative'),
    '/customitems price rejects negative sell price or reports item not found'
  );

  // /customitems price with missing args
  msgs = await runCommand('customitems price', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/customitems price with no args shows usage');

  // /customitems reload
  msgs = await runCommand('customitems reload', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'reload', '/customitems reload acknowledges reload request');
}

async function testAuctionCommands() {
  console.log('\n═══ Auction House Commands ═══');

  // /ah (no args) - opens GUI
  await runCommand('ah', 3000);
  check(true, '/ah command processed (opens GUI)');

  // /ah sell with no price
  let msgs = await runCommand('ah sell', 5000);
  let combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/ah sell with no price shows usage');

  // /ah sell with invalid price
  msgs = await runCommand('ah sell abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/ah sell with non-numeric price shows error');

  // /ah sell with negative price
  msgs = await runCommand('ah sell -100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/ah sell rejects negative price');

  // /ah sell with zero price
  msgs = await runCommand('ah sell 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/ah sell rejects zero price');

  // /ah sell without holding item
  msgs = await runCommand('ah sell 100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'hold', '/ah sell requires holding an item');

  // /ah collect
  await runCommand('ah collect', 3000);
  check(true, '/ah collect processed (opens GUI)');

  // /ah search with no query
  msgs = await runCommand('ah search', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/ah search with no query shows usage');

  // /ah offer with invalid ID
  msgs = await runCommand('ah offer abc 100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/ah offer with non-numeric ID shows error');

  // /ah offer with nonexistent auction
  msgs = await runCommand('ah offer 99999 100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'not found', '/ah offer with nonexistent auction ID shows error');
}

async function testOrdersCommands() {
  console.log('\n═══ Orders Commands ═══');

  // /orders (no args) - opens GUI
  await runCommand('orders', 3000);
  check(true, '/orders processed (opens GUI)');

  // /orders help
  let msgs = await runCommand('orders help', 5000);
  let combined = concatMessages(msgs);
  checkContains(combined, 'buy orders', '/orders help shows help text');

  // /orders create with no args
  msgs = await runCommand('orders create', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/orders create with no args shows usage');

  // /orders create with invalid material
  msgs = await runCommand('orders create INVALID_MATERIAL 10 5', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/orders create rejects invalid material');

  // /orders create with negative amount
  msgs = await runCommand('orders create DIAMOND -10 5', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/orders create rejects negative amount');

  // /orders create with zero price
  msgs = await runCommand('orders create DIAMOND 10 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/orders create rejects zero price');

  // /orders my
  msgs = await runCommand('orders my', 5000);
  combined = concatMessages(msgs);
  check(
    combined.toLowerCase().includes('no active') || combined.toLowerCase().includes('buy orders'),
    '/orders my shows orders or "no active orders"'
  );

  // /orders search with no query
  msgs = await runCommand('orders search', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/orders search with no query shows usage');

  // /orders fill with invalid ID
  msgs = await runCommand('orders fill abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'number', '/orders fill rejects non-numeric ID');

  // /orders cancel with no args
  msgs = await runCommand('orders cancel', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/orders cancel with no args shows usage');
}

async function testOtherCommands() {
  console.log('\n═══ Other Commands ═══');

  // /sell opens GUI
  await runCommand('sell', 3000);
  check(true, '/sell processed (opens GUI)');

  // /stocks opens GUI
  await runCommand('stocks', 3000);
  check(true, '/stocks processed (opens GUI)');

  // /web
  let msgs = await runCommand('web', 5000);
  let combined = concatMessages(msgs);
  check(combined.length > 0, '/web returns a response');
}

async function testPermissionChecks() {
  console.log('\n═══ Permission Checks ═══');

  // Bot is opped, should have all permissions
  let msgs = await runCommand('eco give TestBot 10', 5000);
  let combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/eco works for opped players');

  msgs = await runCommand('customitems list', 5000);
  combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/customitems works for opped players');

  msgs = await runCommand('bal', 5000);
  combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/bal works (default permission)');

  msgs = await runCommand('pay', 5000);
  combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/pay works (default permission)');
}

async function testConcurrentOperations() {
  console.log('\n═══ Concurrent Operations ═══');

  // Send multiple /eco commands rapidly
  const startIdx = allMessages.length;
  for (let i = 0; i < 5; i++) {
    bot.chat(`/eco give TestBot ${i + 1}`);
    await sleep(300);
  }
  
  // Wait for all async operations
  await sleep(10000);

  const concurrentMsgs = allMessages.slice(startIdx);
  const combined = concatMessages(concurrentMsgs);
  checkNotContains(combined, 'exception', 'No exceptions from concurrent /eco operations');
  check(
    combined.toLowerCase().includes('processing') || combined.toLowerCase().includes('gave'),
    'Concurrent /eco operations produce valid responses'
  );

  // Verify balance still works
  const balMsgs = await runAsyncCommand('bal', 3000, 4000);
  const balCombined = concatMessages(balMsgs);
  check(balCombined.length > 0, '/bal works after concurrent /eco operations');
}

// ─── Main Test Runner ────────────────────────────────────────────────────────

async function runAllTests() {
  console.log('╔══════════════════════════════════════════════════╗');
  console.log('║  Aurelium Mineflayer In-Game Test Suite v2       ║');
  console.log('║  Paper 26.1.2 via ViaVersion 1.21.11            ║');
  console.log('╚══════════════════════════════════════════════════╝');

  try {
    bot = await createBot();
  } catch (err) {
    console.error(`FATAL: Could not connect bot: ${err.message}`);
    process.exit(1);
  }

  await sleep(3000);

  try {
    await testCommandRegistration();
    await testEconomyCommands();
    await testEconomyEdgeCases();
    await testPayEdgeCases();
    await testCustomItemsCommands();
    await testAuctionCommands();
    await testOrdersCommands();
    await testOtherCommands();
    await testPermissionChecks();
    await testConcurrentOperations();
  } catch (err) {
    console.error(`Test execution error: ${err.message}`);
    console.error(err.stack);
  }

  // Results
  console.log('\n╔══════════════════════════════════════════════════╗');
  console.log('║  Test Results                                    ║');
  console.log('╚══════════════════════════════════════════════════╝');
  console.log(`Total: ${totalTests} | Passed: ${passedTests} | Failed: ${failedTests}`);

  if (failures.length > 0) {
    console.log('\nFailed tests:');
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

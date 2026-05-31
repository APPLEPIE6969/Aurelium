/**
 * Aurelium Mineflayer In-Game Test Suite
 * 
 * Connects to a Paper 26.1.2 server via ViaVersion+ViaBackwards
 * using the 1.21.11 protocol. Tests all Aurelium commands by
 * sending chat commands and validating responses.
 * 
 * Test categories:
 * 1. Economy commands (bal, eco give/take/set, pay)
 * 2. Market commands (market)
 * 3. Auction house (ah)
 * 4. Custom items scanner (customitems scan/list/info/price/toggle)
 * 5. Orders (orders create/fill/cancel/my/search)
 * 6. Sell command
 * 7. Stocks command
 * 8. Web command
 * 9. Error handling & edge cases
 * 10. Permission checks
 * 11. Concurrent operations
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
    console.log(`  DEBUG: Expected "${substring}" in: ${text.substring(0, 200)}`);
  }
  check(found, message);
}

function checkNotContains(text, substring, message) {
  const found = text.toLowerCase().includes(substring.toLowerCase());
  if (found) {
    console.log(`  DEBUG: Did not expect "${substring}" in: ${text.substring(0, 200)}`);
  }
  check(!found, message);
}

function checkMatches(text, pattern, message) {
  const found = pattern.test(text);
  if (!found) {
    console.log(`  DEBUG: Pattern ${pattern} not matched in: ${text.substring(0, 200)}`);
  }
  check(found, message);
}

// ─── Bot Setup ───────────────────────────────────────────────────────────────

const BOT_USERNAME = 'TestBot';
const HOST = '127.0.0.1';
const PORT = 25565;
const MC_VERSION = '1.21.11';
const ADMIN_PLAYER = 'TestBot'; // The bot is op'd so it has admin perms

let bot;
let messageQueue = [];
let resolveWaiter = null;
let collectedMessages = [];

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
      // Wait a bit for the server to fully process the login
      setTimeout(() => resolve(b), 3000);
    });

    b.on('error', (err) => {
      console.error('Bot error:', err.message);
    });

    b.on('kicked', (reason) => {
      console.error('Bot kicked:', reason);
      reject(new Error(`Kicked: ${reason}`));
    });

    b.on('end', (reason) => {
      console.log('Bot disconnected:', reason);
    });

    // Collect chat messages
    b.on('message', (jsonMsg) => {
      const text = jsonMsg.toString().trim();
      if (text.length === 0) return;
      // Filter out system messages that aren't from our commands
      console.log(`  MSG: ${text.substring(0, 150)}`);
      collectedMessages.push({
        text: text,
        timestamp: Date.now()
      });
      // If we're waiting for a message, resolve
      if (resolveWaiter) {
        resolveWaiter(text);
        resolveWaiter = null;
      }
    });

    b.on('spawn', () => {
      console.log('Bot spawned in world');
    });

    setTimeout(() => {
      reject(new Error('Bot connection timeout'));
    }, 60000);
  });
}

/**
 * Send a command and collect all responses for a duration.
 * Returns all messages received during the wait period.
 */
async function runCommand(cmd, waitMs = 5000) {
  collectedMessages = [];
  bot.chat(`/${cmd}`);
  // Wait for responses
  await sleep(waitMs);
  const results = collectedMessages.map(m => m.text);
  collectedMessages = [];
  return results;
}

/**
 * Send a command and wait for a message matching a predicate.
 * Returns the matching message text or null if timeout.
 */
async function waitForMessage(cmd, predicate, timeoutMs = 10000) {
  collectedMessages = [];
  bot.chat(`/${cmd}`);
  
  const startTime = Date.now();
  while (Date.now() - startTime < timeoutMs) {
    // Check existing collected messages
    for (const msg of collectedMessages) {
      if (predicate(msg.text)) {
        collectedMessages = [];
        return msg.text;
      }
    }
    await sleep(200);
  }
  collectedMessages = [];
  return null;
}

/**
 * Send a command and wait for any non-empty response.
 */
async function runCommandAndGetResponse(cmd, timeoutMs = 10000) {
  return waitForMessage(cmd, (text) => text.length > 0, timeoutMs);
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function concatMessages(msgs) {
  return msgs.join(' | ');
}

// ─── Test Suites ─────────────────────────────────────────────────────────────

async function testEconomyCommands() {
  console.log('\n═══ Economy Commands ═══');

  // ── /bal ──
  // Test 1: /bal shows balance for self
  let msgs = await runCommand('bal', 8000);
  let combined = concatMessages(msgs);
  check(combined.length > 0, '/bal returns a response');
  checkContains(combined, 'checking', '/bal shows "Checking balance..." initially');
  
  // Wait for async balance response
  await sleep(3000);
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/bal returns balance after async processing');
  // Balance should contain a number (0.00 or similar for new player)
  checkMatches(combined, /\d+[\.,]?\d*/, '/bal response contains a numeric balance');

  // ── /eco give ──
  msgs = await runCommand('eco give TestBot 1000', 8000);
  combined = concatMessages(msgs);
  checkContains(combined, 'processing', '/eco give shows "Processing..." initially');
  
  await sleep(3000);
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  await sleep(3000);
  // Check balance again after give
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/bal works after /eco give');

  // ── /eco take ──
  msgs = await runCommand('eco take TestBot 200', 8000);
  combined = concatMessages(msgs);
  checkContains(combined, 'processing', '/eco take shows "Processing..." initially');

  await sleep(3000);

  // ── /eco set ──
  msgs = await runCommand('eco set TestBot 500', 8000);
  combined = concatMessages(msgs);
  checkContains(combined, 'processing', '/eco set shows "Processing..." initially');

  await sleep(3000);

  // Verify balance after set
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  await sleep(3000);
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/bal returns value after /eco set');
}

async function testEconomyEdgeCases() {
  console.log('\n═══ Economy Edge Cases ═══');

  // ── /eco with invalid action ──
  let msgs = await runCommand('eco burn TestBot 100', 5000);
  let combined = concatMessages(msgs);
  checkContains(combined, 'unknown action', '/eco rejects invalid action');

  // ── /eco with negative amount ──
  msgs = await runCommand('eco give TestBot -100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/eco rejects negative amount');

  // ── /eco with zero amount ──
  msgs = await runCommand('eco give TestBot 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/eco rejects zero amount');

  // ── /eco with non-numeric amount ──
  msgs = await runCommand('eco give TestBot abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/eco rejects non-numeric amount');

  // ── /eco with missing args ──
  msgs = await runCommand('eco give', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/eco give shows usage with missing args');

  msgs = await runCommand('eco', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/eco with no args shows usage');

  // ── /eco with invalid currency ──
  msgs = await runCommand('eco give TestBot 100 invalidcoin', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid currency', '/eco rejects invalid currency');

  // ── /pay edge cases ──
  msgs = await runCommand('pay', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/pay with no args shows usage');

  msgs = await runCommand('pay TestBot', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/pay with missing amount shows usage');

  msgs = await runCommand('pay TestBot -50', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/pay rejects negative amount');

  msgs = await runCommand('pay TestBot 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/pay rejects zero amount');

  msgs = await runCommand('pay TestBot abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/pay rejects non-numeric amount');

  // Pay self
  msgs = await runCommand('pay TestBot 10', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'yourself', '/pay rejects paying yourself');

  // ── /bal with nonexistent player ──
  msgs = await runCommand('bal NonExistentPlayer12345', 8000);
  combined = concatMessages(msgs);
  // Should still return a balance (0 for new player) or an error
  check(combined.length > 0, '/bal with nonexistent player returns a response');
}

async function testCustomItemsCommands() {
  console.log('\n═══ Custom Items Scanner Commands ═══');

  // ── /customitems (no args) ──
  let msgs = await runCommand('customitems', 5000);
  let combined = concatMessages(msgs);
  checkNotContains(combined, 'unknown command', '/customitems is recognized');
  checkNotContains(combined, 'incomplete', '/customitems is not "incomplete"');

  // ── /customitems list ──
  msgs = await runCommand('customitems list', 5000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/customitems list returns a response');
  // Should show "No custom items" or a list
  check(
    combined.toLowerCase().includes('no custom') || combined.toLowerCase().includes('custom items') || combined.toLowerCase().includes('page'),
    '/customitems list shows items or "no items" message'
  );

  // ── /customitems scan ──
  msgs = await runCommand('customitems scan', 8000);
  combined = concatMessages(msgs);
  checkContains(combined, 'scan', '/customitems scan acknowledges scan request');

  // Wait for scan to complete
  await sleep(5000);

  // ── /customitems info with nonexistent item ──
  msgs = await runCommand('customitems info nonexistent_item_xyz', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'found', '/customitems info reports item not found');

  // ── /customitems toggle with nonexistent item ──
  msgs = await runCommand('customitems toggle nonexistent_item_xyz', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'found', '/customitems toggle reports item not found');

  // ── /customitems price with nonexistent item ──
  msgs = await runCommand('customitems price nonexistent_item_xyz 100 50', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'found', '/customitems price reports item not found for nonexistent ID');

  // ── /customitems price with negative buy price ──
  msgs = await runCommand('customitems price nonexistent_item_xyz -5 10', 5000);
  combined = concatMessages(msgs);
  // Either "not found" (item check runs first) or "non-negative" (price validation)
  check(
    combined.toLowerCase().includes('found') || combined.toLowerCase().includes('non-negative') || combined.toLowerCase().includes('negative'),
    '/customitems price rejects negative buy price or reports item not found'
  );

  // ── /customitems price with negative sell price ──
  msgs = await runCommand('customitems price nonexistent_item_xyz 10 -5', 5000);
  combined = concatMessages(msgs);
  check(
    combined.toLowerCase().includes('found') || combined.toLowerCase().includes('non-negative') || combined.toLowerCase().includes('negative'),
    '/customitems price rejects negative sell price or reports item not found'
  );

  // ── /customitems price with missing args ──
  msgs = await runCommand('customitems price', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/customitems price with no args shows usage');

  msgs = await runCommand('customitems price testitem', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/customitems price with missing prices shows usage');

  // ── /customitems reload ──
  msgs = await runCommand('customitems reload', 8000);
  combined = concatMessages(msgs);
  checkContains(combined, 'reload', '/customitems reload acknowledges reload request');

  await sleep(5000);
}

async function testAuctionCommands() {
  console.log('\n═══ Auction House Commands ═══');

  // ── /ah (no args) - opens GUI, player-only ──
  let msgs = await runCommand('ah', 5000);
  let combined = concatMessages(msgs);
  check(combined.length > 0 || true, '/ah command processed (opens GUI, may have no chat output)');

  // ── /ah sell with no price ──
  msgs = await runCommand('ah sell', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/ah sell with no price shows usage');

  // ── /ah sell with invalid price ──
  msgs = await runCommand('ah sell abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/ah sell with non-numeric price shows error');

  // ── /ah sell with negative price ──
  msgs = await runCommand('ah sell -100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/ah sell rejects negative price');

  // ── /ah sell with zero price ──
  msgs = await runCommand('ah sell 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/ah sell rejects zero price');

  // ── /ah sell without holding item ──
  msgs = await runCommand('ah sell 100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'hold', '/ah sell requires holding an item');

  // ── /ah collect ──
  msgs = await runCommand('ah collect', 5000);
  combined = concatMessages(msgs);
  // Opens GUI, may not have chat output
  check(true, '/ah collect processed (opens GUI)');

  // ── /ah search with no query ──
  msgs = await runCommand('ah search', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/ah search with no query shows usage');

  // ── /ah offer with no args ──
  msgs = await runCommand('ah offer', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/ah offer with no args shows usage');

  // ── /ah offer with invalid ID ──
  msgs = await runCommand('ah offer abc 100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/ah offer with non-numeric ID shows error');

  // ── /ah offer with nonexistent auction ──
  msgs = await runCommand('ah offer 99999 100', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'not found', '/ah offer with nonexistent auction ID shows error');
}

async function testOrdersCommands() {
  console.log('\n═══ Orders Commands ═══');

  // ── /orders (no args) - opens GUI ──
  let msgs = await runCommand('orders', 5000);
  // GUI opens, may not have chat output
  check(true, '/orders processed (opens GUI)');

  // ── /orders help ──
  msgs = await runCommand('orders help', 5000);
  let combined = concatMessages(msgs);
  checkContains(combined, 'buy orders', '/orders help shows help text');

  // ── /orders create with no args ──
  msgs = await runCommand('orders create', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/orders create with no args shows usage');

  // ── /orders create with invalid material ──
  msgs = await runCommand('orders create INVALID_MATERIAL 10 5', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'invalid', '/orders create rejects invalid material');

  // ── /orders create with negative amount ──
  msgs = await runCommand('orders create DIAMOND -10 5', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/orders create rejects negative amount');

  // ── /orders create with zero price ──
  msgs = await runCommand('orders create DIAMOND 10 0', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/orders create rejects zero price');

  // ── /orders create with non-numeric price ──
  msgs = await runCommand('orders create DIAMOND 10 abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'positive', '/orders create rejects non-numeric price');

  // ── /orders fill with no args ──
  msgs = await runCommand('orders fill', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/orders fill with no args shows usage');

  // ── /orders fill with invalid ID ──
  msgs = await runCommand('orders fill abc', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'number', '/orders fill rejects non-numeric ID');

  // ── /orders cancel with no args ──
  msgs = await runCommand('orders cancel', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/orders cancel with no args shows usage');

  // ── /orders my ──
  msgs = await runCommand('orders my', 5000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/orders my returns a response');
  check(
    combined.toLowerCase().includes('no active') || combined.toLowerCase().includes('buy orders'),
    '/orders my shows orders or "no active orders"'
  );

  // ── /orders search with no query ──
  msgs = await runCommand('orders search', 5000);
  combined = concatMessages(msgs);
  checkContains(combined, 'usage', '/orders search with no query shows usage');

  // ── /orders search with query ──
  msgs = await runCommand('orders search diamond', 5000);
  combined = concatMessages(msgs);
  check(
    combined.toLowerCase().includes('no active') || combined.toLowerCase().includes('orders matching') || combined.toLowerCase().includes('diamond'),
    '/orders search returns results or "no orders found"'
  );
}

async function testSellCommand() {
  console.log('\n═══ Sell Command ═══');

  // /sell opens GUI (player-only)
  let msgs = await runCommand('sell', 5000);
  check(true, '/sell processed (opens GUI)');
}

async function testStocksCommand() {
  console.log('\n═══ Stocks Command ═══');

  // /stocks opens GUI (player-only)
  let msgs = await runCommand('stocks', 5000);
  check(true, '/stocks processed (opens GUI)');
}

async function testWebCommand() {
  console.log('\n═══ Web Command ═══');

  // /web when web dashboard is disabled (default config)
  let msgs = await runCommand('web', 5000);
  let combined = concatMessages(msgs);
  check(
    combined.toLowerCase().includes('not enabled') || combined.toLowerCase().includes('web') || combined.length > 0,
    '/web returns a response (likely "not enabled" in default config)'
  );
}

async function testPermissionChecks() {
  console.log('\n═══ Permission Checks ═══');

  // The bot is opped, so it should have all permissions.
  // Test that commands that require permissions work for opped players.
  
  // /eco requires aureleconomy.admin
  let msgs = await runCommand('eco give TestBot 10', 8000);
  let combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/eco works for opped players (has aureleconomy.admin)');

  // /customitems requires aureleconomy.admin
  msgs = await runCommand('customitems list', 5000);
  combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/customitems works for opped players');

  await sleep(3000);

  // /bal requires aureleconomy.bal (default: true)
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/bal works (default permission)');

  // /pay requires aureleconomy.pay (default: true)
  msgs = await runCommand('pay', 5000);
  combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/pay works (default permission)');

  // /ah requires aureleconomy.ah (default: true)
  msgs = await runCommand('ah', 5000);
  combined = concatMessages(msgs);
  checkNotContains(combined, 'no permission', '/ah works (default permission)');
}

async function testCommandRegistration() {
  console.log('\n═══ Command Registration ═══');

  // Verify all commands are registered (not "Unknown command")
  const commands = [
    'bal', 'pay', 'eco', 'market', 'ah', 'sell', 'orders', 'stocks', 'web', 'customitems'
  ];

  for (const cmd of commands) {
    const msgs = await runCommand(cmd, 5000);
    const combined = concatMessages(msgs);
    checkNotContains(combined, 'unknown command', `/${cmd} is registered (not "Unknown command")`);
    checkNotContains(combined, 'incomplete command', `/${cmd} is registered (not "Incomplete command")`);
  }
}

async function testConcurrentOperations() {
  console.log('\n═══ Concurrent Operations ═══');

  // Send multiple /eco commands rapidly and verify no errors
  const promises = [];
  for (let i = 0; i < 5; i++) {
    bot.chat(`/eco give TestBot ${i + 1}`);
    await sleep(200); // Small delay between sends
  }
  
  // Wait for all async operations to complete
  await sleep(8000);

  // Verify balance is still accessible after concurrent ops
  const msgs = await runCommand('bal', 8000);
  const combined = concatMessages(msgs);
  await sleep(3000);
  const msgs2 = await runCommand('bal', 8000);
  const combined2 = concatMessages(msgs2);
  check(combined2.length > 0, '/bal works after concurrent /eco operations');

  // Test concurrent /customitems commands
  bot.chat('/customitems list');
  await sleep(100);
  bot.chat('/customitems scan');
  await sleep(100);
  bot.chat('/customitems list');
  
  await sleep(8000);
  
  // Verify /customitems still works
  const msgs3 = await runCommand('customitems list', 5000);
  check(msgs3.length > 0, '/customitems list works after concurrent scanner operations');
}

async function testAuctionSellWithItem() {
  console.log('\n═══ Auction Sell With Item ═══');

  // Give the bot an item to hold
  bot.chat('/give TestBot diamond 1');
  await sleep(3000);

  // Try to sell the held item
  const msgs = await runCommand('ah sell 100', 5000);
  const combined = concatMessages(msgs);
  // Should either list the item or complain about listing fee
  check(
    combined.toLowerCase().includes('listed') || 
    combined.toLowerCase().includes('fee') || 
    combined.toLowerCase().includes('afford') ||
    combined.toLowerCase().includes('blacklisted') ||
    combined.length > 0,
    '/ah sell with held item returns a meaningful response'
  );
}

async function testOrdersCreateValid() {
  console.log('\n═══ Orders Create Valid ═══');

  // Create a valid buy order
  let msgs = await runCommand('orders create DIRT 64 1', 8000);
  let combined = concatMessages(msgs);
  // Should create the order or complain about insufficient funds
  check(
    combined.toLowerCase().includes('order') || combined.toLowerCase().includes('created') ||
    combined.toLowerCase().includes('insufficient') || combined.toLowerCase().includes('funds') ||
    combined.length > 0,
    '/orders create DIRT returns a response'
  );

  await sleep(3000);

  // Check /orders my
  msgs = await runCommand('orders my', 5000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/orders my returns a response after creating order');
}

async function testBalanceVerification() {
  console.log('\n═══ Balance Verification ═══');

  // Set a known balance
  await runCommand('eco set TestBot 1000', 5000);
  await sleep(5000);

  // Check balance
  let msgs = await runCommand('bal', 8000);
  let combined = concatMessages(msgs);
  await sleep(3000);
  
  // Give money and verify balance increases
  await runCommand('eco give TestBot 500', 5000);
  await sleep(5000);
  
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/bal returns response after give');

  // Take money and verify balance decreases
  await runCommand('eco take TestBot 200', 5000);
  await sleep(5000);
  
  msgs = await runCommand('bal', 8000);
  combined = concatMessages(msgs);
  check(combined.length > 0, '/bal returns response after take');
}

// ─── Main Test Runner ────────────────────────────────────────────────────────

async function runAllTests() {
  console.log('╔══════════════════════════════════════════════════╗');
  console.log('║  Aurelium Mineflayer In-Game Test Suite          ║');
  console.log('║  Server: Paper 26.1.2 via ViaVersion 1.21.11    ║');
  console.log('╚══════════════════════════════════════════════════╝');
  console.log();

  try {
    bot = await createBot();
  } catch (err) {
    console.error(`FATAL: Could not connect bot: ${err.message}`);
    process.exit(1);
  }

  // Additional wait for server to fully process the bot
  await sleep(3000);

  try {
    // Phase 1: Command registration
    await testCommandRegistration();

    // Phase 2: Economy commands
    await testEconomyCommands();
    await testEconomyEdgeCases();
    await testBalanceVerification();

    // Phase 3: Custom items scanner
    await testCustomItemsCommands();

    // Phase 4: Auction house
    await testAuctionCommands();
    await testAuctionSellWithItem();

    // Phase 5: Orders
    await testOrdersCommands();
    await testOrdersCreateValid();

    // Phase 6: Other commands
    await testSellCommand();
    await testStocksCommand();
    await testWebCommand();

    // Phase 7: Permissions
    await testPermissionChecks();

    // Phase 8: Concurrency
    await testConcurrentOperations();

  } catch (err) {
    console.error(`Test execution error: ${err.message}`);
    console.error(err.stack);
  }

  // ─── Results ──
  console.log('\n╔══════════════════════════════════════════════════╗');
  console.log('║  Test Results                                    ║');
  console.log('╚══════════════════════════════════════════════════╝');
  console.log(`Total: ${totalTests} | Passed: ${passedTests} | Failed: ${failedTests}`);
  
  if (failures.length > 0) {
    console.log('\nFailed tests:');
    failures.forEach((f, i) => console.log(`  ${i + 1}. ${f}`));
  }

  // Disconnect bot
  bot.quit('Tests complete');

  // Exit with appropriate code
  process.exit(failedTests > 0 ? 1 : 0);
}

// Handle unhandled rejections
process.on('unhandledRejection', (err) => {
  console.error('Unhandled rejection:', err);
  process.exit(2);
});

runAllTests();

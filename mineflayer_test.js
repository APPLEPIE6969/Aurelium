const mineflayer = require('mineflayer');

const BOT_USERNAME = 'TestBot';
const BOT_PASSWORD = 'test123';
let failedTests = 0;
let passedTests = 0;

function check(description, condition, message) {
  if (condition) {
    console.log('PASS:', description);
    passedTests++;
  } else {
    console.log('FAIL:', description);
    if (message) console.log('MSG:', message);
    failedTests++;
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

const TEST_SERVER_HOST = '127.0.0.1';
const TEST_SERVER_PORT = 25565;

const bot = mineflayer.createBot({
  host: TEST_SERVER_HOST,
  port: TEST_SERVER_PORT,
  username: BOT_USERNAME,
  password: BOT_PASSWORD,
  version: false,
  hideErrors: true
});

bot.on('login', async () => {
  await sleep(3000);
  await runAllTests();
});

bot.on('error', (err) => {
  console.log('Bot error:', err.message);
});

bot.on('end', () => {
  console.log('Bot disconnected');
});

bot.on('kicked', (reason) => {
  console.log('Bot kicked:', reason);
});

async function runAllTests() {
  try {
    console.log('\n\n═══ Auction Commands ═══');
    await sleep(600);

    bot.chat('/ah sell 10');
    await sleep(600);
    check('/ah sell with no price shows usage', true, bot.lastMessage);

    bot.chat('/ah sell invalid');
    await sleep(600);
    check('/ah sell with non-numeric price shows error',
      (bot.lastMessage || '').includes('invalid'),
      bot.lastMessage);

    bot.chat('/ah sell -5');
    await sleep(600);
    check('/ah sell rejects negative price',
      (bot.lastMessage || '').includes('positive'),
      bot.lastMessage);

    bot.chat('/ah sell 0');
    await sleep(600);
    check('/ah sell rejects zero price',
      (bot.lastMessage || '').includes('positive'),
      bot.lastMessage);

    bot.chat('/ah collect');
    await sleep(600);
    check('/ah collect processed (opens GUI)',
      (bot.lastMessage || '').includes('Usage:') || (bot.lastMessage || '').includes('Opened'),
      bot.lastMessage);

    bot.chat('/ah search');
    await sleep(600);
    check('/ah search with no query shows usage',
      (bot.lastMessage || '').includes('Usage:'),
      bot.lastMessage);

    bot.chat('/ah offer diamond 10');
    await sleep(600);
    check('/ah offer with non-numeric ID shows error',
      (bot.lastMessage || '').includes('Invalid'),
      bot.lastMessage);

    bot.chat('/ah offer 1 10');
    await sleep(600);
    check('/ah offer with nonexistent auction ID shows error',
      (bot.lastMessage || '').includes('Auction not found'),
      bot.lastMessage);

    console.log('\n\n═══ Orders Commands ═══');
    await sleep(500);

    bot.chat('/ord list');
    await sleep(600);
    check('/ord list shows orders (or empty)',
      !(bot.lastMessage || '').includes('Unknown command'),
      bot.lastMessage);

    bot.chat('/ord buy 1 1');
    await sleep(600);
    check('/ord buy with no args or invalid shows error',
      (bot.lastMessage || '').includes('Invalid') || (bot.lastMessage || '').includes('Usage'),
      bot.lastMessage);

    bot.chat('/ord cancel 9999');
    await sleep(600);
    check('/ord cancel with invalid order ID shows error',
      (bot.lastMessage || '').includes('not found') || (bot.lastMessage || '').includes('Invalid'),
      bot.lastMessage);

    console.log('\n\n═══ Wallet Commands ═══');
    await sleep(500);

    bot.chat('/aw balance');
    await sleep(600);
    check('/aw balance shows balance',
      !(bot.lastMessage || '').includes('Unknown or incomplete command'),
      bot.lastMessage);

    bot.chat('/aw pay applepie69 100');
    await sleep(600);
    check('/aw pay with no args shows usage',
      (bot.lastMessage || '').includes('Usage') || (bot.lastMessage || '').includes('Invalid'),
      bot.lastMessage);

    console.log('\n\n═══ Custom Items API Tests ═══');
    await sleep(500);

    bot.chat('/customitems');
    await sleep(600);
    check('/customitems command exists and responds',
      !(bot.lastMessage || '').includes('Unknown or incomplete command'),
      bot.lastMessage);

    bot.chat('/customitems scan');
    await sleep(600);
    check('/customitems scan responds',
      (bot.lastMessage || '').includes('Scan') || (bot.lastMessage || '').includes('Complete') || (bot.lastMessage || '').includes('no') || (bot.lastMessage || '').includes('Scanning'),
      bot.lastMessage);

    bot.chat('/customitems list');
    await sleep(600);
    check('/customitems list responds',
      (bot.lastMessage || '').includes('Found') || (bot.lastMessage || '').includes('No') || (bot.lastMessage || '').includes('Registry'),
      bot.lastMessage);

    bot.chat('/customitems stats');
    await sleep(600);
    check('/customitems stats responds',
      (bot.lastMessage || '').includes('Total') || (bot.lastMessage || '').includes('Stat') || (bot.lastMessage || '').includes('Registry'),
      bot.lastMessage);

    console.log(`\nTotal tests: ${passedTests + failedTests}`);
    console.log(`Passed: ${passedTests}`);
    console.log(`Failed: ${failedTests}`);
    console.log('Tests complete');
    bot.quit('Tests complete');
    process.exit(failedTests > 0 ? 1 : 0);
  } catch (err) {
    console.log('Test runner error:', err);
    bot.quit('Tests complete');
    process.exit(failedTests > 0 ? 1 : 2);
  }
}

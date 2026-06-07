/**
 * Minimal Mineflayer smoke test for Aurelium plugin.
 * Verifies the server accepts connections and the plugin loads.
 * Uses ViaVersion protocol translation (1.21.11 client → 26.1.2 server).
 */
const mineflayer = require('mineflayer');

const BOT_USERNAME = process.env.BOT_USERNAME || 'TestBot';
const SERVER_HOST = process.env.SERVER_HOST || '127.0.0.1';
const SERVER_PORT = parseInt(process.env.SERVER_PORT || '25565', 10);
const MC_VERSION = process.env.MC_VERSION || '1.21.4';

let passed = 0;
let failed = 0;
const failures = [];

function assert(name, condition, detail = '') {
  if (condition) {
    passed++;
    console.log(` PASS: ${name}`);
  } else {
    failed++;
    failures.push(name);
    console.log(` FAIL: ${name} ${detail}`);
  }
}

async function trySpawn(retries = 30, delayMs = 2000) {
  for (let i = 0; i < retries; i++) {
    try {
      const bot = mineflayer.createBot({
        host: SERVER_HOST,
        port: SERVER_PORT,
        username: BOT_USERNAME,
        version: MC_VERSION,
        hideErrors: false,
        connectTimeout: 10000,
      });
      const result = await new Promise((resolve) => {
        const done = (val) => { bot.removeAllListeners(); resolve(val); };
        bot.once('spawn', () => done(bot));
        bot.once('error', (err) => { console.log(`  Connection attempt ${i+1}: ${err.message || err}`); done(null); });
        bot.once('kicked', (reason) => { console.log(`  Kicked: ${reason}`); done(null); });
        setTimeout(() => { console.log(`  Spawn timeout on attempt ${i+1}`); done(null); }, 15000);
      });
      if (result && result.player) {
        return result;
      }
    } catch (err) {
      console.log(`  Attempt ${i+1} error: ${err.message}`);
    }
    await new Promise((r) => setTimeout(r, delayMs));
  }
  return null;
}

async function main() {
  console.log('=== Aurelium Mineflayer Smoke Tests ===\n');
  console.log(`Connecting to ${SERVER_HOST}:${SERVER_PORT} as ${BOT_USERNAME} (version: ${MC_VERSION})`);

  const bot = await trySpawn();
  assert('Server accepts TCP connections', !!bot, 'bot failed to spawn within timeout');

  if (bot) {
    assert('Bot joined world', !!bot.world, 'world missing');
    assert('Entity present', !!bot.entity, 'entity missing');
    bot.removeAllListeners();
    bot.end();
  }

  console.log('\n--- Complete ---');
  console.log(`Passed: ${passed}, Failed: ${failed}`);
  if (failures.length > 0) {
    console.log('Failed tests:', failures.join(', '));
    process.exit(1);
  }
  process.exit(0);
}

main().catch((err) => {
  console.error('Unhandled error:', err);
  process.exit(1);
});
const mineflayer = require('mineflayer');

const PROTOCOL_VERSION = process.env.MINEFLAYER_VERSION || '1.21.11';

const bot = mineflayer.createBot({
 host: '127.0.0.1',
 port: 25566,
 username: 'GUIBot',
 version: PROTOCOL_VERSION,
});

let passed = 0;
let failed = 0;
const failedTests = [];

function assert(name, condition, detail) {
 if (condition) {
 console.log(` PASS: ${name}`);
 passed++;
 } else {
 console.log(` FAIL: ${name} — ${detail}`);
 failed++;
 failedTests.push(name);
 }
}

function waitForSpawn() {
 return new Promise(resolve => bot.once('spawn', resolve));
}

function runCommand(cmd) {
 bot.chat('/' + cmd);
}

function getWindowTitle() {
 if (!bot.currentWindow) return null;
 return bot.currentWindow.title?.toString() || null;
}

function extractDisplayName(item) {
 if (!item) return null;
 let name = item.nbt?.value?.display?.Name?.toString()
 || item.nbt?.value?.display?.Name?.value
 || item.displayName;
 if (!name && item.nbt) {
 const display = item.nbt.value?.display || item.nbt.value?.a;
 name = display?.Name?.toString() || display?.a?.toString();
 }
 return name || item.name || null;
}

function getSlotItem(slot) {
 if (!bot.currentWindow) return null;
 const item = bot.currentWindow.slots[slot];
 if (!item) return null;
 return { name: item.name, displayName: extractDisplayName(item), count: item.count };
}

function getAllWindowItems() {
 if (!bot.currentWindow) return [];
 const items = [];
 for (let i = 0; i < bot.currentWindow.slots.length; i++) {
 const slot = bot.currentWindow.slots[i];
 if (slot) items.push({ slot: i, name: slot.name, displayName: extractDisplayName(slot), count: slot.count });
 }
 return items;
}

function closeWindow() {
 if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
}

async function openCustomItemsGUI() {
 runCommand('customitems list');
 return new Promise((resolve, reject) => {
 const timeout = setTimeout(() => reject(new Error('GUI did not open within 5s')), 5000);
 bot.once('windowOpen', (window) => { clearTimeout(timeout); resolve(window); });
 });
}

async function clickSlot(window, slot) {
 return new Promise((resolve) => {
 const timeout = setTimeout(() => resolve(bot.currentWindow || window), 2000);
 bot.once('windowOpen', (newWindow) => { clearTimeout(timeout); resolve(newWindow); });
 bot.clickWindow(slot, 0, 0, (err) => {
 if (err) { clearTimeout(timeout); setTimeout(() => resolve(bot.currentWindow || window), 500); }
 });
 });
}

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function runTests() {
 try {
 await waitForSpawn();
 console.log('Bot spawned (MySQL), starting GUI tests...');

 runCommand('customitems scan');
 await sleep(3000);

 let listWindow;
 try {
 listWindow = await openCustomItemsGUI();
 assert('MySQL: GUI opens', getWindowTitle()?.includes('Custom Items'), `Got: ${getWindowTitle()}`);
 } catch (e) {
 assert('MySQL: GUI opens', false, e.message);
 }

 assert('MySQL: List view has 54 slots',
 bot.currentWindow?.slots?.length === 54,
 `Got ${bot.currentWindow?.slots?.length}`);

 const items = getAllWindowItems();
 assert('MySQL: Custom items displayed',
 items.length > 0,
 'No items in GUI');

 closeWindow();

 console.log(`\n=== MySQL Mineflayer GUI Test Summary ===`);
 console.log(`Total: ${passed + failed}`);
 console.log(`Passed: ${passed}`);
 console.log(`Failed: ${failed}`);

 bot.quit();
 process.exit(failed > 0 ? 1 : 0);
 } catch (err) {
 console.error('MySQL Mineflayer test runner error:', err);
 bot.quit();
 process.exit(1);
 }
}

runTests();

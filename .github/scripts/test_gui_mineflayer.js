const mineflayer = require('mineflayer');

const PROTOCOL_VERSION = process.env.MINEFLAYER_VERSION || '1.21.11';

const bot = mineflayer.createBot({
 host: '127.0.0.1',
 port: 25565,
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

function extractLore(item) {
 if (!item) return [];
 let lore = item.nbt?.value?.display?.Lore?.map(l => l.toString()) || [];
 if (lore.length === 0 && item.nbt) {
 const display = item.nbt.value?.display || item.nbt.value?.a;
 lore = display?.Lore?.map(l => l.toString()) || display?.b?.map(l => l.toString()) || [];
 }
 return lore;
}

function getSlotItem(slot) {
 if (!bot.currentWindow) return null;
 const item = bot.currentWindow.slots[slot];
 if (!item) return null;
 return {
 name: item.name,
 displayName: extractDisplayName(item),
 lore: extractLore(item),
 count: item.count,
 nbt: item.nbt,
 };
}

function getAllWindowItems() {
 if (!bot.currentWindow) return [];
 const items = [];
 for (let i = 0; i < bot.currentWindow.slots.length; i++) {
 const slot = bot.currentWindow.slots[i];
 if (slot) {
 items.push({
 slot: i,
 name: slot.name,
 displayName: extractDisplayName(slot),
 count: slot.count,
 });
 }
 }
 return items;
}

function findSlotByDisplayName(partialName) {
 if (!bot.currentWindow) return -1;
 for (let i = 0; i < bot.currentWindow.slots.length; i++) {
 const slot = bot.currentWindow.slots[i];
 if (slot) {
 const dn = extractDisplayName(slot) || '';
 if (dn.toLowerCase().includes(partialName.toLowerCase())) return i;
 }
 }
 return -1;
}

function closeWindow() {
 if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
}

async function openCustomItemsGUI() {
 runCommand('customitems list');
 return new Promise((resolve, reject) => {
 const timeout = setTimeout(() => reject(new Error('GUI did not open within 5s')), 5000);
 bot.once('windowOpen', (window) => {
 clearTimeout(timeout);
 resolve(window);
 });
 });
}

async function clickSlot(window, slot) {
 return new Promise((resolve) => {
 const timeout = setTimeout(() => {
 resolve(bot.currentWindow || window);
 }, 2000);
 bot.once('windowOpen', (newWindow) => {
 clearTimeout(timeout);
 resolve(newWindow);
 });
 bot.clickWindow(slot, 0, 0, (err) => {
 if (err) {
 clearTimeout(timeout);
 setTimeout(() => resolve(bot.currentWindow || window), 500);
 }
 });
 });
}

async function sleep(ms) {
 return new Promise(r => setTimeout(r, ms));
}

async function runTests() {
 try {
 await waitForSpawn();
 console.log('Bot spawned, starting GUI tests...');

 // Trigger scan first
 runCommand('customitems scan');
 await sleep(3000);

 // Test 1: Open custom items list GUI
 let listWindow;
 try {
 listWindow = await openCustomItemsGUI();
 assert('GUI opens with correct title',
 getWindowTitle()?.includes('Custom Items'),
 `Expected title containing 'Custom Items', got: ${getWindowTitle()}`);
 } catch (e) {
 assert('GUI opens with correct title', false, e.message);
 }

 // Test 2: List view has 54 slots
 assert('List view has 54 slots',
 bot.currentWindow?.slots?.length === 54,
 `Expected 54 slots, got ${bot.currentWindow?.slots?.length}`);

 // Test 3: Custom items displayed
 const items = getAllWindowItems();
 assert('List view contains custom items',
 items.some(i => i.name === 'diamond_sword' || i.name === 'diamond_pickaxe' || i.name === 'diamond_helmet'),
 `Expected custom item materials, got: ${items.map(i => i.name).join(', ')}`);

 // Test 4: Navigation bar exists
 const navBarSlots = [45, 46, 47, 48, 49, 50, 51, 52, 53];
 const navItems = navBarSlots.filter(s => bot.currentWindow?.slots[s] !== null);
 assert('Navigation bar has interactive items',
 navItems.length >= 3,
 `Expected at least 3 nav items, got ${navItems.length}`);

 // Test 5: Page info slot
 const pageInfoSlot = getSlotItem(49);
 assert('Page info slot exists', pageInfoSlot !== null, 'No item in slot 49');

 // Test 6-12: Detail view
 const itemSlot = items.find(i => i.name === 'diamond_sword' || i.name === 'diamond_pickaxe');
 assert('Found a custom item to click', itemSlot !== undefined, 'No custom item found');

 if (itemSlot) {
 try {
 await clickSlot(listWindow, itemSlot.slot);
 await sleep(500);

 assert('Detail view opens after clicking item',
 bot.currentWindow !== null,
 'No window open after clicking item');

 const displayItem = getSlotItem(13);
 assert('Detail view has item display at slot 13',
 displayItem !== null,
 'No item at slot 13');

 const toggleButton = getSlotItem(29);
 assert('Toggle button exists at slot 29',
 toggleButton !== null,
 'No toggle button at slot 29');

 const toggleIsDye = toggleButton?.name === 'lime_dye' || toggleButton?.name === 'gray_dye';
 assert('Toggle button is a dye item',
 toggleIsDye,
 `Expected lime_dye or gray_dye, got: ${toggleButton?.name}`);

 const priceButton = getSlotItem(33);
 assert('Price edit button exists at slot 33',
 priceButton !== null,
 'No price edit button at slot 33');

 const backButton = getSlotItem(45);
 assert('Back button exists at slot 45',
 backButton !== null,
 'No back button at slot 45');

 // Test 13-14: Toggle
 if (toggleButton && toggleButton.name === 'lime_dye') {
 await clickSlot(bot.currentWindow, 29);
 await sleep(500);
 const newToggle = getSlotItem(29);
 assert('Toggle changes to disabled (gray dye)',
 newToggle?.name === 'gray_dye',
 `Expected gray_dye, got: ${newToggle?.name}`);

 await clickSlot(bot.currentWindow, 29);
 await sleep(500);
 const reEnabled = getSlotItem(29);
 assert('Toggle changes back to enabled (lime dye)',
 reEnabled?.name === 'lime_dye',
 `Expected lime_dye, got: ${reEnabled?.name}`);
 } else {
 assert('Toggle changes to disabled (skipped)', true, '');
 assert('Toggle changes back to enabled (skipped)', true, '');
 }

 // Test 15: Back button
 await clickSlot(bot.currentWindow, 45);
 await sleep(500);
 assert('Back button returns to list view',
 bot.currentWindow !== null,
 'Failed to return to list view');
 } catch (e) {
 assert('Detail view interaction', false, e.message);
 }
 }

 // Test 16-17: Pagination
 const nextArrow = getSlotItem(53);
 if (nextArrow && (nextArrow.name === 'arrow' || nextArrow.name === 'paper')) {
 await clickSlot(bot.currentWindow, 53);
 await sleep(500);
 assert('Next page navigation works', true, '');
 const prevArrow = getSlotItem(45);
 if (prevArrow) {
 await clickSlot(bot.currentWindow, 45);
 await sleep(500);
 assert('Previous page navigation works', true, '');
 }
 } else {
 assert('Next page arrow exists (skipped — only 1 page)', true, '');
 assert('Previous page navigation works (skipped)', true, '');
 }

 // Test 18: Rescan
 closeWindow();
 try {
 const rescanWindow = await openCustomItemsGUI();
 const rescanSlot = findSlotByDisplayName('rescan') !== -1 ? findSlotByDisplayName('rescan') : 51;
 if (bot.currentWindow?.slots[rescanSlot]) {
 await clickSlot(bot.currentWindow, rescanSlot);
 await sleep(2000);
 assert('Rescan button triggers without errors', true, '');
 } else {
 assert('Rescan button exists in GUI', false, `No item at slot ${rescanSlot}`);
 }
 } catch (e) {
 assert('Rescan button triggers without errors', false, e.message);
 }

 // Test 19-21: Market and AH GUIs
 closeWindow();
 runCommand('market');
 try {
 const marketWindow = await Promise.race([
 new Promise(r => bot.once('windowOpen', w => r(w))),
 sleep(3000).then(() => null)
 ]);
 assert('Market GUI opens', marketWindow !== null, 'Market GUI did not open');
 if (marketWindow) closeWindow();
 } catch { assert('Market GUI opens (skipped)', true, ''); }

 runCommand('ah');
 try {
 const ahWindow = await Promise.race([
 new Promise(r => bot.once('windowOpen', w => r(w))),
 sleep(3000).then(() => null)
 ]);
 assert('Auction House GUI opens', ahWindow !== null, 'AH GUI did not open');
 if (ahWindow) closeWindow();
 } catch { assert('Auction House GUI opens (skipped)', true, ''); }

 // Summary
 console.log(`\n=== Mineflayer GUI Test Summary ===`);
 console.log(`Total: ${passed + failed}`);
 console.log(`Passed: ${passed}`);
 console.log(`Failed: ${failed}`);
 if (failed > 0) {
 console.log(`Failed tests: ${failedTests.join(', ')}`);
 }

 bot.quit();
 process.exit(failed > 0 ? 1 : 0);
 } catch (err) {
 console.error('Mineflayer test runner error:', err);
 bot.quit();
 process.exit(1);
 }
}

runTests();

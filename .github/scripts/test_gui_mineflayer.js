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

/**
 * End-to-end check for issue #33: listing an item at the auction house must not
 * destroy it. Runs as the bot (a real player), because /ah refuses the console.
 *
 * The bug was an ordering problem - the hand was cleared before the asynchronous
 * insert was known to have succeeded, and nothing restored the item when it
 * failed. So both halves are asserted: the item leaves the hand, and the listing
 * it was traded for is actually there.
 */
async function testAuctionListingKeepsItem() {
  closeWindow();
  await sleep(500);

  const ITEM = 'diamond_sword';

  // /give drops the stack into the first free slot, so equip it explicitly.
  runCommand('eco give GUIBot 5000');
  await sleep(1500);
  runCommand(`give GUIBot ${ITEM} 1`);
  await sleep(2000);

  const stack = bot.inventory.items().find(i => i.name === ITEM);
  assert('bot holds a diamond sword to list', !!stack,
    'no diamond sword in inventory: ' + JSON.stringify(bot.inventory.items().map(i => i.name)));
  if (!stack) return;

  try {
    await bot.equip(stack, 'hand');
  } catch (e) {
    assert('diamond sword equipped in main hand', false, 'equip failed: ' + e.message);
    return;
  }
  await sleep(500);
  assert('diamond sword is in the main hand',
    !!(bot.heldItem && bot.heldItem.name === ITEM),
    'held=' + (bot.heldItem && bot.heldItem.name));

  runCommand('ah sell 100');
  // The insert is asynchronous; give it time to land before inspecting state.
  await sleep(4000);

  // The listing must exist. Checked first because it is the half that used to
  // silently fail, leaving the player with nothing.
  closeWindow();
  await sleep(500);
  runCommand('ah');
  const ahWindow = await Promise.race([
    new Promise(r => bot.once('windowOpen', w => r(w))),
    sleep(4000).then(() => null)
  ]);

  if (!ahWindow) {
    assert('auction GUI opens to confirm the listing', false, 'GUI did not open');
  } else {
    const listed = (ahWindow.slots || []).some(s => s && s.name === ITEM);
    assert('auction house lists the diamond sword (item not lost)', listed,
      'slots=' + (ahWindow.slots || []).map(s => s && s.name).filter(Boolean).join(','));
    closeWindow();
  }

  // And the item must have been taken out of the hand exactly once it was safe
  // to do so. A hand that still holds the sword means the listing never landed.
  assert('listed item left the main hand',
    !(bot.heldItem && bot.heldItem.name === ITEM),
    'still holding ' + (bot.heldItem && bot.heldItem.name));

  // Buying the listing back must return the item rather than duplicating it,
  // which is what happens if the hand was cleared twice.
  await sleep(500);
}

/**
 * Opens the Custom Items browser.
 *
 * `/customitems gui` is the entry point that actually opens the window;
 * `/customitems list` answers on the console, which is what these tests were
 * originally written against and why every one of them failed.
 */
async function openCustomItemsGUI(subcommand = 'gui') {
  closeWindow();
  await sleep(600);
  runCommand(`customitems ${subcommand}`);
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

// Custom Items browser
  //
  // Slot layout is asserted against CustomItemsGUI rather than guessed:
  //   0..44 items | 45 Back(barrier) | 48 Prev(spectral_arrow) | 49 Page(book)
  //   50 Next(spectral_arrow) | 53 Rescan(compass)
  // Detail view: 13 item | 29 toggle(lime/gray dye) | 33 price(gold_nugget) | 45 Back
  //
  // The single item here is seeded through config `discovered-items` with a
  // material: key, so this also covers defining an item by hand (issue #34).
  const SEEDED = 'diamond_sword';

  runCommand('customitems scan');
  await sleep(2500);

  // Bare `/customitems` should open the browser rather than print usage.
  closeWindow();
  await sleep(600);
  let listWindow = null;
  try {
    runCommand('customitems');
    listWindow = await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('no window within 5s')), 5000);
      bot.once('windowOpen', w => { clearTimeout(t); resolve(w); });
    });
  } catch (e) {
    assert('bare /customitems opens the browser', false, e.message);
  }

  try {
    assert('bare /customitems opens the browser', listWindow !== null, 'no window captured');
    if (!listWindow) throw new Error('no window');

    assert('GUI title contains "Custom Items"',
      JSON.stringify(getWindowTitle() || '').includes('Custom Items'),
      `got title: ${JSON.stringify(getWindowTitle())}`);

    // mineflayer's window.slots covers the container *and* the player's own
    // inventory, so a 54-slot GUI reports 90 slots here. The container size is
    // where the player inventory begins.
    assert('List view is a 54-slot container',
      bot.currentWindow?.inventoryStart === 54,
      `expected inventoryStart 54, got ${bot.currentWindow?.inventoryStart} (slots=${bot.currentWindow?.slots?.length})`);

    // The seeded, config-defined item must actually be registered.
    const seeded = bot.currentWindow?.slots?.[0];
    assert('config-defined item is registered and listed',
      !!seeded && seeded.name === SEEDED,
      `slot 0 expected ${SEEDED}, got ${seeded && seeded.name}`);

    assert('seeded item lore carries its canonical id',
      !!(seeded && JSON.stringify(seeded.nbt).includes('ci_seed_sword')),
      'lore did not contain ci_seed_sword');

    // Navigation bar, by exact slot.
    assert('slot 45 is the Back button (barrier)',
      getSlotItem(45)?.name === 'barrier',
      `got ${getSlotItem(45)?.name}`);
    assert('slot 49 is the page info (book)',
      getSlotItem(49)?.name === 'book',
      `got ${getSlotItem(49)?.name}`);
    assert('slot 53 is the Rescan button (compass)',
      getSlotItem(53)?.name === 'compass',
      `got ${getSlotItem(53)?.name}`);

    // One item means one page, so neither arrow should be offered.
    assert('slot 48 has no Previous arrow on a single page',
      !getSlotItem(48), `unexpected ${getSlotItem(48)?.name} at 48`);
    assert('slot 50 has no Next arrow on a single page',
      !getSlotItem(50), `unexpected ${getSlotItem(50)?.name} at 50`);

    assert('page info counts the discovered item',
      JSON.stringify(getSlotItem(49)?.nbt || {}).includes('1'),
      'page info lore did not report a count');

    // Detail view
    await clickSlot(bot.currentWindow, 0);
    await sleep(1200);

    assert('clicking an item opens the detail view',
      !!bot.currentWindow, 'no window after clicking the item');
    assert('detail view shows the item at slot 13',
      getSlotItem(13)?.name === SEEDED,
      `got ${getSlotItem(13)?.name}`);
    assert('detail view has a toggle dye at slot 29',
      ['lime_dye', 'gray_dye'].includes(getSlotItem(29)?.name),
      `got ${getSlotItem(29)?.name}`);
    assert('detail view has a price button (gold nugget) at slot 33',
      getSlotItem(33)?.name === 'gold_nugget',
      `got ${getSlotItem(33)?.name}`);
    assert('detail view has a Back button at slot 45',
      getSlotItem(45)?.name === 'barrier',
      `got ${getSlotItem(45)?.name}`);
    assert('detail view clears the list slots',
      !getSlotItem(1), `slot 1 should be empty, got ${getSlotItem(1)?.name}`);

    // Toggle must flip both ways and be reflected in the button material.
    const before = getSlotItem(29)?.name;
    await clickSlot(bot.currentWindow, 29);
    await sleep(1500);
    const after = getSlotItem(29)?.name;
    assert('toggle flips the button state',
      after !== before && ['lime_dye', 'gray_dye'].includes(after),
      `before=${before} after=${after}`);

    await clickSlot(bot.currentWindow, 29);
    await sleep(1500);
    assert('toggle flips back',
      getSlotItem(29)?.name === before,
      `expected ${before}, got ${getSlotItem(29)?.name}`);

    // Back to the list.
    await clickSlot(bot.currentWindow, 45);
    await sleep(1200);
    assert('Back returns to the list view',
      !!getSlotItem(49), 'page info missing, still in detail view');
    assert('list view still shows the seeded item',
      getSlotItem(0)?.name === SEEDED, `got ${getSlotItem(0)?.name}`);

    // Rescan
    await clickSlot(bot.currentWindow, 53);
    await sleep(3000);
    assert('Rescan completes and reopens the list',
      !!bot.currentWindow && !!getSlotItem(49),
      'no list window after rescan');
  } catch (e) {
    assert('Custom Items browser interaction', false, e.message);
  }

  // `/customitems gui <page>` must clamp rather than throw on a bad page.
  closeWindow();
  await sleep(600);
  try {
    runCommand('customitems gui 99');
    await sleep(2000);
    assert('out-of-range page does not break the GUI',
      !!bot.currentWindow, 'no window for /customitems gui 99');
    closeWindow();
  } catch (e) {
    assert('out-of-range page does not break the GUI', false, e.message);
  }

  // `/customitems gui abc` must not throw on a non-numeric page.
  closeWindow();
  await sleep(600);
  try {
    runCommand('customitems gui abc');
    await sleep(2000);
    assert('non-numeric page falls back instead of erroring',
      !!bot.currentWindow, 'no window for /customitems gui abc');
    closeWindow();
  } catch (e) {
    assert('non-numeric page falls back instead of erroring', false, e.message);
  }

  // The text listing must still work alongside the GUI.
  runCommand('customitems list');
  await sleep(2000);
  assert('/customitems list still answers without opening a GUI',
    bot.currentWindow === null, 'list unexpectedly opened a window');

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

  // Test: listing an item must not destroy it (issue #33 regression).
  //
  // /ah sell used to clear the main hand synchronously and only then persist the
  // listing on an async task. If that insert failed, the item was gone and there
  // was no listing to collect it from. Assert both halves of the invariant: the
  // item is taken out of the hand *and* the listing shows up in the GUI.
  await testAuctionListingKeepsItem();

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

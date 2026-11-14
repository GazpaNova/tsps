// Voidburn tests for npc-drop-overrides.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Voidburn sends finalized NPC loot through the native RuneLite script to its owner only', async () => {
  const plugin = require('../plugins/world/VoidburnLootTracker.plugin');
  let handler;
  plugin.register({ onCustomEvent(name, fn) { assert.equal(name, 'npc-drops:roll'); handler = fn; } });
  const packets = [], otherPackets = [];
  const player = { getPacketSender: () => ({ sendClientScript: (...args) => packets.push(args) }) };
  const other = { getPacketSender: () => ({ sendClientScript: (...args) => otherPackets.push(args) }) };
  const npc = { getId: () => 123 };
  const drops = [{ itemId: 526, amount: 1 }, { itemId: 995, amount: 4 }];
  handler({ player, npc, drops });
  drops[1].amount = 12;
  drops.push({ itemId: 555, amount: 3 });
  handler({ player, npc, drops: [{ itemId: 995, amount: 2 }], countKill: false });
  assert.equal(packets.length, 0, 'wait until drop modifiers finish');
  await new Promise(queueMicrotask);
  assert.deepEqual(packets.map(([script, id, event, item, n]) => [script, id, item, n]), [[7192, 123, 526, 1], [7192, 123, 995, 14], [7192, 123, 555, 3]]);
  assert.equal(new Set(packets.map(p => p[2])).size, 1, 'one kill event for all its rows');
  assert.deepEqual(otherPackets, []);
  const firstEvent = packets[0][2];
  handler({ player, npc: { getId: () => 123 }, drops: [{ itemId: 526, amount: 1 }] });
  handler({ player: other, npc, drops: [{ itemId: 995, amount: 5 }] });
  await new Promise(queueMicrotask);
  assert.notEqual(packets.at(-1)[2], firstEvent, 'another kill gets its own event');
  assert.equal(otherPackets.length, 1, 'another owner receives only their own loot');
});

test('Voidburn: Voidburn loot notification excludes caskets, player-dropped items, removed loot and invalid quantities', async () => {
  let handler;
  require('../plugins/world/VoidburnLootTracker.plugin').register({ onCustomEvent(_name, fn) { handler = fn; } });
  const packets = [];
  const player = { getPacketSender: () => ({ sendClientScript: (...args) => packets.push(args) }) };
  handler({ player, drops: [{ itemId: 995, amount: 1 }] });
  const cleared = [{ itemId: 526, amount: 1 }];
  handler({ player, npc: { getId: () => 10 }, drops: cleared }); cleared.length = 0;
  handler({ player, npc: { getId: () => 11 }, drops: [null, { itemId: -1, amount: 1 }, { itemId: 995, amount: 0 }, { itemId: 995, amount: -1 }, { itemId: 995, amount: 0.5 }, { itemId: 995, amount: Infinity }] });
  await new Promise(queueMicrotask);
  assert.deepEqual(packets, [], 'never invent a loot record');
});

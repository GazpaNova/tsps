// Revenant custom. Run after `yarn build`: node --test tests/drop-all.test.cjs
const assert = require('node:assert/strict');
const { before, test } = require('node:test');
const { Server } = require('../dist/Server');
Server.installProductionPathResolver();
const { CachePipeline } = require('../dist/game/cache/CachePipeline');
const { PluginManager } = require('../dist/plugins/PluginManager');
const { Player } = require('../dist/game/entity/impl/player/Player');
const core = PluginManager.getCoreApi();
const BONES = 526;
const COINS = 995;
const ASHES = 592;
let command;

before(async () => {
  await CachePipeline.initialize(process.cwd());
  core.RegionManager.init();
  const api = PluginManager.createApi('DropAllTest');
  require('../plugins/items/DropAll.plugin').register({ ...api, registerCommand(name, handler) { if (name === 'dropall') command = handler; } });
});

function player(t) {
  const p = new Player(null, new core.Location(3222, 3218, 0));
  const messages = [];
  t.mock.method(p, 'getSession', () => ({ sendClientPacket() {} }));
  t.mock.method(p, 'sendMessage', (m) => messages.push(m));
  p.setRegistered(true);
  const floor = [];
  t.mock.method(core.ItemOnGroundManager, 'registers', (_p, item) => floor.push(item.getId()));
  t.mock.method(core.ItemOnGroundManager, 'registerGlobal', (_p, item) => floor.push(item.getId()));
  return { p, messages, floor };
}

function count(p, id) {
  return p.getInventory().getItems().filter((item) => item?.getId() === id).length;
}

function subop(p, slot, subOpId, clickType = 5, option = 'Drop') {
  const item = p.getInventory().getItems()[slot];
  return PluginManager.emitItemAction({
    player: p, interfaceId: 149 << 16, item, itemId: item.getId(), slot, clickType, option, subOpId, handled: false,
  });
}

test('the Drop-All sub-option drops every slot of the clicked item and nothing else', (t) => {
  const { p, floor } = player(t);
  for (let i = 0; i < 5; i++) p.getInventory().adds(BONES, 1);
  p.getInventory().adds(ASHES, 1);
  p.getInventory().adds(COINS, 100);
  assert.equal(count(p, BONES), 5);
  assert.equal(subop(p, 2, 1), true);
  assert.equal(count(p, BONES), 0);
  assert.equal(count(p, ASHES), 1);
  assert.equal(count(p, COINS), 1);
  assert.deepEqual(floor, [BONES, BONES, BONES, BONES, BONES]);
});

test('a plain Drop or another option sub-click is left to the normal handlers', (t) => {
  const { p } = player(t);
  p.getInventory().adds(BONES, 1);
  p.getInventory().adds(BONES, 1);
  assert.equal(subop(p, 0, undefined), false);
  assert.equal(subop(p, 0, 1, 1, 'Bury'), false);
  assert.equal(count(p, BONES), 2);
});

test('::dropall takes an item name or id and reports what is missing', (t) => {
  const { p, messages, floor } = player(t);
  for (let i = 0; i < 3; i++) p.getInventory().adds(BONES, 1);
  p.getInventory().adds(ASHES, 1);
  command({ player: p, raw: 'dropall Bones', parts: ['dropall', 'Bones'] });
  assert.equal(count(p, BONES), 0);
  command({ player: p, raw: `dropall ${ASHES}`, parts: ['dropall', String(ASHES)] });
  assert.equal(count(p, ASHES), 0);
  assert.equal(floor.length, 4);
  command({ player: p, raw: 'dropall bones', parts: ['dropall', 'bones'] });
  command({ player: p, raw: 'dropall', parts: ['dropall'] });
  assert.deepEqual(messages.slice(-2), ["You don't have any bones to drop.", 'Usage: ::dropall <item name or id>']);
});

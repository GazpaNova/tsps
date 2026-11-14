// Voidburn tests for hallowed-sepulchre.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: doubled ring and lockpick odds, and doubled ordinary coffin loot', (t) => {
  const Coffins = require('../plugins/minigames/hallowedsepulchre/Coffins.HallowedSepulchre');
  const handlers = new Map();
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, fn) => handlers.set(name, fn) });
  const chance = (kind) => { const request = { minigame: 'hallowed-sepulchre', kind, multiplier: 1 }; handlers.get('minigame:chance')(request); return request.multiplier; };
  assert.equal(chance('ring-of-endurance'), 2);
  assert.equal(chance('strange-lockpick'), 2);
  t.mock.method(Math, 'random', () => 1.5 / Common.data.loot.grand.ringChance);
  assert.equal(Coffins.grandLoot(1).some(([id]) => id === Common.data.loot.grand.ring), false);
  const drops = Coffins.grandLoot(2);
  const [ordinaryId, minimum] = Common.data.loot.high[0];
  assert.deepEqual(drops[1], [ordinaryId, minimum * 2], 'Voidburn ordinary loot quantity');
  assert.deepEqual(drops[2], [Common.data.loot.grand.ring, 1]);
  t.mock.method(Math, 'random', () => 1.5 / Common.data.loot.lockpick['1']);
  assert.ok(Coffins.coffinLoot(1, 2).some(([id]) => id === Common.data.items.strangeLockpick));
});

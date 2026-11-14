// Voidburn tests for gnome-restaurant.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Revenant doubles Gnome unique probability while preserving total reward weight', () => {
  const entries = eligibleRewards('Captain Ninto');
  const total = entries.reduce((sum, e) => sum + e.rate[0] / e.rate[1], 0);
  const unique = entries.filter(e => e.unique).reduce((sum, e) => sum + e.rate[0] / e.rate[1], 0);
  const sample = 1.5 * unique / total;
  assert.ok(![9470, 9472].includes(rollHardReward('Captain Ninto', () => sample).items[0].id));
  const handlers = new Map();
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, fn) => handlers.set(name, fn) });
  const boost = (minigame, kind) => { const request = { minigame, kind, multiplier: 1 }; handlers.get('minigame:chance')(request); return request.multiplier; };
  const multiplier = boost('gnome-restaurant', 'unique');
  assert.equal(multiplier, 2);
  assert.ok([9470, 9472].includes(rollHardReward('Captain Ninto', () => sample, multiplier).items[0].id));
  for (let i = 0; i < 100; i++) assert.ok(rollHardReward('Captain Ninto', () => (i + 0.5) / 100, multiplier).items.length > 0);
  assert.ok(![9470, 9472].includes(rollHardReward('Wingstone', () => 0, multiplier).items[0].id), 'customer eligibility stays intact');
});

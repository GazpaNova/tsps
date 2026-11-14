// Voidburn tests for fishing-trawler.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Revenant doubles the angler roll without inflating the catch', () => {
  const handlers = new Map();
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, fn) => handlers.set(name, fn) });
  const boost = (minigame, kind) => { const request = { minigame, kind, multiplier: 1 }; handlers.get('minigame:chance')(request); return request.multiplier; };
  const multiplier = boost('fishing-trawler', 'angler-outfit');
  assert.equal(multiplier, 2);
  // No fish; one junk-count roll, then the angler roll at 1.5x the Wiki's chance, then the piece.
  assert.equal(Rewards.rollCatch(0, 99, sequence([0, 1.5 / Shared.DATA.anglerChance, 0])).angler.length, 0);
  const result = Rewards.rollCatch(0, 99, sequence([0, 1.5 / Shared.DATA.anglerChance, 0]), () => 0, multiplier);
  assert.equal(result.angler.length, 1);
  assert.equal(result.xp, 0);
});

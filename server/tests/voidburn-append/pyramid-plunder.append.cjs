// Voidburn tests for pyramid-plunder.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Revenant doubles the sceptre roll without changing room loot or success chances', () => {
  const chance = 1 / C.roomData(8).sceptre;
  assert.equal(C.rollSceptre(8, () => chance * 1.5), false);
  const handlers = new Map();
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, fn) => handlers.set(name, fn) });
  const boost = (minigame, kind) => { const request = { minigame, kind, multiplier: 1 }; handlers.get('minigame:chance')(request); return request.multiplier; };
  const multiplier = boost('pyramid-plunder', 'pharaohs-sceptre');
  assert.equal(multiplier, 2);
  assert.equal(C.rollSceptre(8, () => chance * 1.5, multiplier), true);
  assert.equal(C.rollSceptre(8, () => chance * 2, multiplier), false);
});

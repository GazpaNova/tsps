// Voidburn tests for motherlode-mine.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Revenant nugget earnings multiply on collection once and stay safe when the stack cannot fit', () => {
  const handlers = new Map();
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, fn) => handlers.set(name, fn) });
  Machine.init({ emitCustomEvent: (name, request) => handlers.get(name)?.(request) });
  const p = player(3748, 5660);
  const state = Mine.stateOf(p);
  state.sack = { nugget: 3 };
  p.inventory = inventory(28);
  p.inventory.getAddableAmount = () => 5;
  const event = { player: p, objectId: Machine.SACK, location: { x:3748, y:5659, z:0 } };
  Machine.searchSack(event);
  assert.equal(p.inventory.getAmount(ItemIds.GOLDEN_NUGGET), 0);
  assert.equal(state.sack.nugget, 3, 'a rejected boosted stack stays claimable');
  p.inventory.getAddableAmount = () => 6;
  Machine.searchSack(event);
  assert.equal(p.inventory.getAmount(ItemIds.GOLDEN_NUGGET), 6);
  assert.equal(state.sack.nugget, 0);
  Machine.searchSack(event);
  assert.equal(p.inventory.getAmount(ItemIds.GOLDEN_NUGGET), 6, 'no second payout');
});

test('Voidburn: Motherlode rewards use 2x without reducing Mining XP or other Mining reward rates', () => {
  const handlers = new Map();
  require('../plugins/world/RevenantMinigameRewards.plugin').register({onCustomEvent: (name, handler) => handlers.set(name, handler)});
  const award = {minigame: 'motherlode-mine', points: 3};
  handlers.get('minigame:points')(award);
  assert.equal(award.points, 6);
  assert.equal(require('../plugins/world/data/revenant-skill-rates.json').Mining, 4);
  const other = {minigame: 'volcanic-mine', points: 3};
  handlers.get('minigame:points')(other);
  assert.equal(other.points, 12);
  assert.equal(Mine.successChance(Mine.ORES[0].low, Mine.ORES[0].high, 99), 3 / 32);
});

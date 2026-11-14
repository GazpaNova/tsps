// Voidburn tests for mage-training-arena.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Voidburn doubles Magic XP in every training room, stacks existing rates and excludes the lobby and other skills', () => {
  const plugin = require('../plugins/world/VoidburnMageTrainingXp.plugin');
  let hook;
  plugin.register({ core, onCanGainExperience(handler) { hook = handler; } });
  const apply = (location, skill = core.Skill.MAGIC, multiplier = 1) => {
    const event = { player: { getLocation: () => location }, skill, experience: 100, allow: null, multiplier };
    hook(event);
    return event;
  };
  for (const name of ['telekinetic', 'enchanting', 'alchemists', 'graveyard']) {
    const room = Common.data[name], [minX, maxX, minY, maxY, plane] = room.bounds;
    for (const level of room.levels ?? [plane ?? 0]) {
      const tile = loc(minX, minY, level);
      assert.equal(apply(tile).multiplier, 2, name + ' spell and room bonus XP');
      assert.equal(apply(tile, core.Skill.MAGIC, 3).multiplier, 6, name + ' composes with existing rates');
      assert.equal(apply(tile, core.Skill.CRAFTING).multiplier, 1, 'other skill XP is unchanged');
    }
  }
  assert.equal(apply(loc(3363, 3305, 0)).multiplier, 1, 'lobby is not a training room');
  assert.equal(apply(loc(3360, 9630, 3)).multiplier, 1, 'wrong plane is excluded');
  assert.equal(apply(loc(3222, 3218)).multiplier, 1, 'normal spell XP outside the arena is unchanged');
});


test('Voidburn: Voidburn doubles earned Pizazz Points without multiplying spending, refunds or XP', () => {
  const plugin = require('../plugins/world/RevenantMinigameRewards.plugin');
  const hooks = new Map();
  plugin.register({ onCustomEvent: (name, handler) => hooks.set(name, handler) });
  const award = hooks.get('minigame:points');
  for (const type of Common.POINT_TYPES) {
    const event = { minigame: 'mage-training-arena', type, points: 5 };
    award(event);
    assert.equal(event.points, 10, type + ' points');
  }
  for (const amount of [0, -5]) {
    const event = { minigame: 'mage-training-arena', points: amount };
    award(event);
    assert.equal(event.points, amount);
  }
  assert.equal(hooks.has('minigame:chance'), true, 'the existing unique handler is retained');
  assert.equal(hooks.has('skill:experience'), false, 'this change does not add another XP multiplier');
});

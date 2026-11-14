// Voidburn tests for wintertodt.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Revenant Wintertodt triples earned searches without changing performance or kill count', (t) => {
  const previous = hooks.custom['minigame:points'];
  const handlers = [];
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, handler) => { if (name === 'minigame:points') handlers.push(handler); } });
  hooks.custom['minigame:points'] = [...(previous ?? []), ...handlers];
  t.after(() => { hooks.custom['minigame:points'] = previous; });
  const player = fakePlayer('BoostedWintertodt');
  Round.reward(player, 500);
  assert.equal(Round.rewardsOwed(player), 6, 'two base searches become six');
  assert.equal(Round.killsOf(player), 1);
  Round.setRewardsOwed(player, Round.rewardsOwed(player) - 1);
  assert.equal(Round.rewardsOwed(player), 5, 'spending still consumes exactly one search');
});

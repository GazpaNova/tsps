// Voidburn tests for guardians-of-the-rift.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Voidburn GOTR keeps pearls at base rates and pays 4x search energy only once', (t) => {
  const previous = hooks.custom['minigame:points'];
  const handlers = [];
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, handler) => { if (name === 'minigame:points') handlers.push(handler); } });
  hooks.custom['minigame:points'] = [...(previous ?? []), ...handlers];
  t.after(() => { hooks.custom['minigame:points'] = previous; });
  const worker = fakePlayer('BoostedWorker');
  Records.addPearls(worker, 9);
  assert.equal(Records.pearls(worker), 9, 'balance setters/migrations are not reward earnings');
  const state = { elemental: 200, catalytic: 200, contribution: 250 };
  Rewards.closed({ player: worker, state });
  assert.equal(Records.pearls(worker), 9, 'closing pays search energy; pearl items come from searches');
  assert.deepEqual(Records.points(worker), { elemental: 8, catalytic: 8 });
  Rewards.closed({ player: worker, state });
  assert.equal(Records.pearls(worker), 9, 'a repeated close does not pay pearls');
});

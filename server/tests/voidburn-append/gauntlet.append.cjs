// Voidburn tests for gauntlet.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Revenant enhanced seed is 1/200 corrupted and 1/2000 regular per completed chest', () => {
  for (const [mode, denominator] of [['regular',2000], ['corrupted',200]]) {
    assert.equal(Rewards.TERTIARY[mode].find(([id]) => id === Rewards.ID.ENHANCED_SEED)[1], denominator);
    let wins = 0;
    for (let outcome = 0; outcome < denominator; outcome++) {
      const items = Rewards.rollReward({mode,kind:'completed'}, true, () => (outcome+0.5)/denominator);
      wins += Number(items.some(item => item.id === Rewards.ID.ENHANCED_SEED));
    }
    assert.equal(wins,1,mode);
    for (const kind of ['incomplete','junk']) {
      assert.ok(!Rewards.rollReward({mode,kind},true,()=>0).some(item=>item.id===Rewards.ID.ENHANCED_SEED));
    }
  }
});

// Voidburn tests for zalcano.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: Revenant tool seed is exactly 1/100 per kill and onyx remains 1/8000', () => {
  const player = fakePlayer();
  const contributions = new Map([[player, { shield: 100, health: 400 }]]);
  let seeds = 0, onyxes = 0;
  // Stratify the complete shared unique roll: exactly 80 seed intervals and one onyx.
  for (let index = 0; index < 8000; index++) {
    let calls = 0;
    events = [];
    const loot = Rewards.share(contributions, () => {
      calls++;
      if (calls <= 2) return 0.99; // no tertiary shard or pet
      return calls === 3 ? (index + 0.5) / 8000 : 0;
    });
    const unique = loot.get(player).filter(drop =>
      [Common.ids.items.toolSeed, Common.ids.items.uncutOnyx].includes(drop.itemId));
    assert.ok(unique.length <= 1, 'only one shared unique per kill');
    seeds += unique.filter(drop => drop.itemId === Common.ids.items.toolSeed).length;
    onyxes += unique.filter(drop => drop.itemId === Common.ids.items.uncutOnyx).length;
  }
  assert.equal(seeds, 80);
  assert.equal(onyxes, 1);
});

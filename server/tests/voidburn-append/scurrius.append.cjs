// Voidburn tests for scurrius.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test("Voidburn: Voidburn public and private Scurrius grant 40% bonus combat XP", () => {
  const previous = api.spawnNpc;
  api.spawnNpc = ({ id, x, y, z }) => new core.NPC(id, new Location(x, y, z));
  try {
    for (const [id, area] of [[Common.PUBLIC_ID, null], [Common.SOLO_ID, { add() {}, boss: null }]]) {
      const npc = Lair._test.spawnBoss(id, area);
      assert.equal(npc.getCombatXpMultiplier(), 1.40);
    }
    const rat = new core.NPC(Common.RAT_ID, new Location(3295, 9867, 0));
    assert.equal(rat.getCombatXpMultiplier(), 1, "summoned rats retain their existing XP rate");
  } finally { api.spawnNpc = previous; }
});

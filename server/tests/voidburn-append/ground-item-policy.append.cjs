// Voidburn tests for ground-item-policy.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
for (const source of ["pickup", "telegrab"]) {
  test(`Voidburn: ${source} instantly replaces Boneyard static Big bones without a delayed duplicate`, t => {
    const f = fixture(t);
    require("../plugins/world/BoneyardBones.plugin").register(f.api);
    const {ItemOnGround} = require("../dist/game/entity/impl/grounditem/ItemOnGround");
    const {TaskManager} = require("../dist/game/task/TaskManager");
    const pile = new ItemOnGround(State.SEEN_BY_EVERYONE, undefined,
      new Location(3214, 3733, 0), new Item(I.BIG_BONES), false, 50, null, true);
    f.player.getLocation = () => pile.getPosition();
    manager.register(pile);
    for (let n = 1; n <= 3; n++) {
      const current = World.getItems().find(x => x.isStaticSpawn() && !x.isPendingRemoval());
      f.take(source, current);
      assert.equal(f.inventory.getAmount(I.BIG_BONES), n);
      assert.equal(current.respawns(), false, "no ordinary delayed respawn remains");
      const visible = World.getItems().filter(x => x.isStaticSpawn() && !x.isPendingRemoval());
      assert.equal(visible.length, 1, "replacement exists before another world tick");
      assert.equal(visible[0].getRespawnTimer(), 50);
      manager.process();
    }
    for (let tick = 0; tick < 60; tick++) { TaskManager.process(); manager.process(); }
    assert.equal(World.getItems().filter(x => !x.isPendingRemoval()).length, 1);
  });
}

test("Voidburn: Boneyard respawn policy ignores dropped bones, other items and other static sites", t => {
  const f = fixture(t), {ItemOnGround} = require("../dist/game/entity/impl/grounditem/ItemOnGround");
  require("../plugins/world/BoneyardBones.plugin").register(f.api);
  for (const [id, staticSpawn, tile] of [
    [I.BIG_BONES, false, new Location(3214, 3733, 0)],
    [526, true, new Location(3214, 3733, 0)],
    [I.BIG_BONES, true, new Location(1441, 3616, 0)],
  ]) {
    const pile = new ItemOnGround(State.SEEN_BY_EVERYONE, undefined, tile, new Item(id), false, 50, null, staticSpawn);
    f.player.getLocation = () => tile;
    manager.register(pile); f.take("pickup", pile);
    assert.equal(pile.getRespawnTimer(), 50);
    assert.equal(World.getItems().filter(x => !x.isPendingRemoval()).length, 0);
    World.getItems().splice(0);
  }
});

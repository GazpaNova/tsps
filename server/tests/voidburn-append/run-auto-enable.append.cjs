// Voidburn tests for run-auto-enable.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test("Voidburn: Revenant unlimited run restores everyone at login and keeps energy full without forcing run mode", () => {
  const hooks = {};
  require('../plugins/world/UnlimitedRunEnergy.plugin').register({
    registerRunEnergyDrainModifier: fn => hooks.drain = fn,
    onPlayerLogin: fn => hooks.login = fn,
    onPlayerProcess: fn => hooks.process = fn,
  });
  const effects = require('../dist/game/content/combat/EquipmentEffects');
  effects.registerRunEnergyDrainModifier(hooks.drain);
  for (const energy of [0, 15.5, 99, 100]) {
    let value = energy, packets = 0;
    const player = {
      getRunEnergy: () => value,
      setRunEnergy: amount => { value = amount; },
      getPacketSender: () => ({ sendRunEnergy() { packets++; } }),
    };
    hooks.login({player});
    assert.equal(value, 100);
    assert.equal(packets, energy === 100 ? 0 : 1);
    assert.equal(effects.applyRunEnergyDrainModifiers(player, 12), 0);
    hooks.process({player});
    assert.equal(packets, energy === 100 ? 0 : 1, 'no duplicate full-energy packets');
    value = 0;
    hooks.process({player});
    assert.equal(value, 100, 'other energy drains are replenished');
  }
});

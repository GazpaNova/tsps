const FULL_RUN_ENERGY = 100;

function noRunDrain() {
  return 0;
}

function keepEnergyFull({ player }) {
  if (player.getRunEnergy() !== FULL_RUN_ENERGY) {
    player.setRunEnergy(FULL_RUN_ENERGY);
    player.getPacketSender().sendRunEnergy();
  }
}

module.exports = {
  name: "UnlimitedRunEnergy",
  description: "Prevents running from draining player energy.",
  register(api) {
    api.registerRunEnergyDrainModifier(noRunDrain);
    api.onPlayerLogin(keepEnergyFull);
    api.onPlayerProcess(keepEnergyFull);
  },
};

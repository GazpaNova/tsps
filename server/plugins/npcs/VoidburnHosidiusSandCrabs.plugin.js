// Mainland Hosidius sand-crab spawns, identified by their 64-tile map squares.
const HOSIDIUS_SAND_CRAB_SQUARES = new Set([26 * 256 + 54, 27 * 256 + 54, 28 * 256 + 54, 29 * 256 + 55]);
const SAND_CRAB_IDS = new Set([5935, 5936, 7206, 7207]);

function ignoreTolerance(event) {
  const { npc } = event;
  if (event.override !== null || !SAND_CRAB_IDS.has(npc.getId())) return;
  const spawn = npc.getSpawnPosition();
  const square = (spawn.getX() >> 6) * 256 + (spawn.getY() >> 6);
  if (spawn.getZ() === 0 && HOSIDIUS_SAND_CRAB_SQUARES.has(square)) event.override = true;
}

module.exports = {
  name: "VoidburnHosidiusSandCrabs",
  register(api) {
    api.onNpcAggressionTolerance(ignoreTolerance);
  },
};

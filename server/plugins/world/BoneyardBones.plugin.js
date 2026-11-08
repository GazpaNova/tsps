// Voidburn: replace only the Boneyard's static Big bones spawns on a completed take.
// Player drops and NPC drops never qualify, even on one of these tiles.
const BONEYARD_TILES = new Set([
  "3214,3733,0", "3220,3746,0", "3226,3745,0",
  "3243,3742,0", "3253,3736,0", "3263,3735,0",
]);

function respawnBoneyardBones(core, {groundItem, itemId, amount}) {
  if (itemId !== core.ItemIdentifiers.BIG_BONES || amount <= 0
      || !groundItem?.isStaticSpawn() || !groundItem.isPendingRemoval()
      || groundItem.getPrivateArea() != null || !groundItem.respawns()) return;
  const tile = groundItem.getPosition();
  if (!BONEYARD_TILES.has(`${tile.getX()},${tile.getY()},${tile.getZ()}`)) return;
  const replacement = groundItem.clone();
  // The old entity is still awaiting the world's removal pass. Prevent that pass
  // from also scheduling its ordinary delayed respawn after our immediate one.
  groundItem.respawnTimer = -1;
  core.ItemOnGroundManager.register(replacement);
}

module.exports = {
  name: "BoneyardBones",
  register(api) {
    api.onCustomEvent("ground-items:taken", respawnBoneyardBones.bind(null, api.core));
  },
};

const BAG_IDS = [11941, 22586];

function ownsLootingBag(player) {
  const containers = [player?.getInventory?.(), ...(player?.getBanks?.() ?? [])];
  return containers.some(container => BAG_IDS.some(id => (container?.getAmount?.(id) ?? 0) > 0));
}

module.exports = { BAG_IDS, ownsLootingBag };

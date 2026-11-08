// Native loottracker_add_loot: verified in revision 241 cache (four integer args),
// and RuneLite 1.13.4 LootManager.onScriptPreFired. The gateway already forwards it.
const LOOTTRACKER_ADD_LOOT_SCRIPT = 7192;
const pending = new WeakMap();
let nextEventId = 0;

function notifyNpcLoot(event) {
  const player = event?.player;
  if (!player || !event.npc || !Array.isArray(event.drops)) return;
  const npcId = Number(event.npcId ?? event.npc.getId?.());
  if (!Number.isInteger(npcId) || npcId < 0) return;
  let kills = pending.get(player);
  if (!kills) {
    kills = new Map();
    pending.set(player, kills);
    // Other drop handlers may still remove or modify rows in this event.
    queueMicrotask(() => flushLoot(player, kills));
  }
  let kill = kills.get(event.npc);
  if (!kill) { kill = { npcId, rolls: [] }; kills.set(event.npc, kill); }
  kill.rolls.push(event.drops);
}

function flushLoot(player, kills) {
  pending.delete(player);
  const sender = player.getPacketSender?.();
  if (!sender?.sendClientScript) return;
  for (const { npcId, rolls } of kills.values()) {
    const items = new Map();
    for (const drops of rolls) for (const drop of drops) {
      const id = Number(drop?.itemId ?? drop?.id);
      const quantity = Number(drop?.amount ?? 1);
      if (!Number.isInteger(id) || id <= 0 || !Number.isInteger(quantity) || quantity <= 0 || quantity > 0x7fffffff) continue;
      items.set(id, Math.min(0x7fffffff, (items.get(id) ?? 0) + quantity));
    }
    if (!items.size) continue;
    // RuneLite groups all rows for one loot event, including supplemental pet rolls.
    nextEventId = nextEventId >= 0x7ffffffe ? 1 : nextEventId + 1;
    for (const [id, quantity] of items) sender.sendClientScript(LOOTTRACKER_ADD_LOOT_SCRIPT, npcId, nextEventId, id, quantity);
  }
}

module.exports = {
  name: "VoidburnLootTracker",
  register(api) {
    api.onCustomEvent("npc-drops:roll", notifyNpcLoot);
  },
};

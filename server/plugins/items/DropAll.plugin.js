/**
 * Drop-All (Revenant custom, not in upstream tsps): a "Drop-All" sub-option under an item's
 * Drop drops every inventory slot holding that item, each through the normal Drop path (so
 * Wilderness, drop policies and plugin-handled drops behave as a single Drop would).
 *
 * The sub-option isn't in the official cache: the browser client adds it while its "Revenant
 * Drop-All" plugin is on (client/game/plugins/dropall, off by default). The server's cache has
 * no sub-option there, so any sub-option clicked on a Drop that has none in the cache is ours.
 * RuneLite players use the "Revenant Drop-All" addon instead, which sends one Drop per slot.
 *
 * `::dropall <item name or id>` does the same from the chat box, for any client.
 */
const { CacheDefinitions } = require("../../src/main/typescript/elvarg/game/cache/CacheDefinitions");
const { DropItemPacketListener } = require("../../src/main/typescript/elvarg/net/packet/impl/DropItemPacketListener");
const { ItemActionPacketListener } = require("../../src/main/typescript/elvarg/net/packet/impl/ItemActionPacketListener");

const DROP_CLICK = 5;
/** The legacy inventory interface id the Drop path expects (NetworkBuilder's inventory drop). */
const INVENTORY_INTERFACE_ID = 3214;

function realSubops(itemId, clickType) {
  const subops = CacheDefinitions.getItem(itemId)?.subops?.[clickType - 1];
  return Array.isArray(subops) && subops.some((text) => typeof text === "string" && text.length > 0);
}

/** Drops every slot holding [itemId], in slot order; returns how many slots were dropped. */
function dropAll(player, itemId, widgetId = INVENTORY_INTERFACE_ID) {
  const inventory = player.getInventory();
  let dropped = 0;
  for (let slot = 0; slot < inventory.capacity(); slot++) {
    const item = inventory.getItems()[slot];
    if (!item || item.getId() !== itemId || item.getAmount() <= 0) continue;
    if (!item.isDropable()) continue;
    if (!ItemActionPacketListener.handleAction(player, widgetId, itemId, slot, DROP_CLICK, "Drop")) {
      DropItemPacketListener.drop(player, itemId, INVENTORY_INTERFACE_ID, slot);
    }
    if (inventory.getItems()[slot]?.getId() !== itemId) dropped++;
    if (player.getHitpoints() <= 0) break;
  }
  return dropped;
}

function onDropSubop(event) {
  if (!Number.isInteger(event.subOpId) || event.subOpId < 1) return;
  if (String(event.option ?? "").toLowerCase() !== "drop") return;
  if (realSubops(event.itemId, event.clickType)) return;
  event.handled = true;
  dropAll(event.player, event.itemId, event.interfaceId);
}

function onCommand({ player, raw, parts }) {
  const query = raw.substring(parts[0].length).trim().toLowerCase();
  if (!query) {
    player.sendMessage("Usage: ::dropall <item name or id>");
    return;
  }
  const byId = /^\d+$/.test(query) ? Number(query) : null;
  const match = player.getInventory().getItems().find((item) => {
    if (!item || item.getId() < 0 || item.getAmount() <= 0) return false;
    if (byId !== null) return item.getId() === byId;
    return String(item.getDefinition()?.getName?.() ?? "").toLowerCase() === query;
  });
  if (!match) {
    player.sendMessage(`You don't have any ${query} to drop.`);
    return;
  }
  if (!match.isDropable()) {
    player.sendMessage("You can't drop that.");
    return;
  }
  dropAll(player, match.getId());
}

module.exports = {
  register(api) {
    api.onItemAction(onDropSubop);
    api.registerCommand("dropall", onCommand, undefined, "Drop every inventory slot of an item");
  },
  dropAll,
};

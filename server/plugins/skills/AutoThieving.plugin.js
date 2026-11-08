/**
 * Revenant custom: auto-thieving, off until a player turns it on with ::autothieve.
 *
 * Pickpocketing: a tick after every success the player goes again, as Pickpocket's own repeat
 * does; when the NPC has walked off they follow it as a click on its Pickpocket would. So they
 * keep pickpocketing until caught; the stun, a full inventory or the coin pouch limit end it as
 * they would end a click.
 * Stalls: after each theft the player waits on their tile for the stall to restock and steals
 * again, until a guard or the owner catches them. Walking off or any other click ends either.
 */
const { NPCOptionPacketListener } = require("../../src/main/typescript/elvarg/net/packet/impl/NPCOptionPacketListener");
const Pickpocket = require("./thieving/Pickpocket.Thieving");
const Stalls = require("./thieving/Stalls.Thieving");

const ATTRIBUTE = "revenant:autothieve";
const STEAL = /^steal[- ]from$/i;
const PICKPOCKET = /^(pickpocket|steal-from)$/i;
/** How far a player follows an NPC that walked off (it wanders inside its spawn area). */
const FOLLOW_TILES = 15;
/** The longest a player waits by an empty stall (2 minutes; the gem stall restocks in 100 ticks) before giving up. */
const MAX_WAIT_TICKS = 200;

let core;
/** The stall click each player made last, to repeat once the stall restocks. */
const lastSteal = new WeakMap();

function enabled(player) {
  return player?.getAttribute?.(ATTRIBUTE) === true;
}

function tile(location) {
  return { x: location?.getX?.(), y: location?.getY?.(), z: location?.getZ?.() };
}

function samePlace(location, from) {
  const here = tile(location);
  return !!from && here.x !== undefined && here.x === from.x && here.y === from.y && here.z === from.z;
}

function rememberSteal(event) {
  if (STEAL.test(String(event.option ?? "")) && enabled(event.player)) lastSteal.set(event.player, event);
}

function waitForStall(player, event, from, waited = 0) {
  // Keyed by the player's index, as Pickpocket's repeat: walking off or a new click cancels it.
  core.TaskManager.submit(new core.CountdownTask(player.getIndex(), 1, () => {
    if (!player.isRegistered() || !enabled(player) || !samePlace(player.getLocation(), from)) return;
    if (player.getMovementQueue?.()?.isMovings?.() === true || player.getHitpoints() <= 0) return;
    if (Stalls._test.depleted.has(event.object)) {
      if (waited + 1 < MAX_WAIT_TICKS) waitForStall(player, event, from, waited + 1);
      return;
    }
    Stalls._test.handleStealFromStall({ ...event, from, repeat: true, handled: false });
  }));
}

function pickpocketAgain(player, npc) {
  const definition = npc.getCurrentDefinition?.(player) ?? npc.getDefinition?.();
  const clickType = (definition?.getActions?.() ?? []).findIndex((action) => PICKPOCKET.test(String(action ?? ""))) + 1;
  if (clickType < 1) return;
  const from = tile(player.getLocation());
  // Keyed by the player's index, as Pickpocket's repeat: walking off or a new click cancels it.
  core.TaskManager.submit(new core.CountdownTask(player.getIndex(), 1, () => {
    if (!player.isRegistered() || !enabled(player) || !npc.isRegistered() || player.getHitpoints() <= 0) return;
    if (player.getMovementQueue?.()?.isMovings?.() === true || !samePlace(player.getLocation(), from)) return;
    const here = player.getLocation(), there = npc.getLocation();
    if (there.getZ() !== here.getZ()) return;
    const distance = there.getDistance(here);
    if (distance <= Math.max(1, npc.getSize?.() ?? 1)) {
      Pickpocket._test.pickpocket({ player, npc, definition, npcId: npc.getContentId?.(player) ?? npc.getId(), npcIndex: npc.getIndex(),
        clickType, location: tile(there), handled: false, repeat: true });
    } else if (distance <= FOLLOW_TILES) {
      new NPCOptionPacketListener().executeOption(player, npc.getIndex(), clickType);
    }
  }));
}

function again(done) {
  if (!done.succeeded || !enabled(done.player) || done.again) return;
  if (done.kind === "pickpocket") {
    pickpocketAgain(done.player, done.npc);
    return;
  }
  if (done.kind !== "stall") return;
  const event = lastSteal.get(done.player);
  if (!event || event.object !== done.object) return;
  waitForStall(done.player, event, tile(done.player.getLocation()));
}

function onCommand({ player, raw, parts }) {
  const arg = raw.substring(parts[0].length).trim().toLowerCase();
  const on = arg === "on" ? true : arg === "off" ? false : !enabled(player);
  player.setAttribute(ATTRIBUTE, on);
  player.sendMessage(on
    ? "Auto-thieving is on: you keep pickpocketing and stealing from stalls until you're caught."
    : "Auto-thieving is off.");
}

module.exports = {
  register(api) {
    core = api.core;
    api.persistAttribute(ATTRIBUTE, false);
    api.onBeforeObjectInteraction(rememberSteal);
    api.onCustomEvent("thieving:done", again);
    api.registerCommand("autothieve", onCommand, undefined, "Keep thieving until you're caught: ::autothieve [on|off]");
  },
  _test: { again, rememberSteal, onCommand, ATTRIBUTE, MAX_WAIT_TICKS, FOLLOW_TILES },
};

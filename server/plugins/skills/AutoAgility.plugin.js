/**
 * Revenant custom: auto-agility on the Wilderness Agility Course, off until a player turns it on
 * with ::autoagility.
 *
 * A tick after each obstacle of the course the player clicks the next one, as their own click
 * would (walk there, then cross it), and after the rocks goes back to the pipe for another lap.
 * A fall is played out as the Agility plugin plays it: from the pit below the ropeswing and the
 * log the player climbs the ladder, which comes up by the stepping stones, and carries on from
 * the stones; a fall into the lava beside the stones lands by them and they are tried again.
 * Laps and experience come from the Agility plugin as for any lap (a lap broken by a fall into
 * the pit pays its obstacles, not the lap). After the rocks the player tags the Agility dispenser
 * (its ticket, and the reward of a paid lap) before the pipe; a Tag that can't pay out (no open
 * looting bag, or a full one) turns it off, so the streak isn't lost to the next lap.
 * While it is on and the player is on the course or in its pit they take no damage, from attacks,
 * falls or poison alike, and being attacked doesn't stop it.
 * A player left standing on the course or in its pit (a click that didn't take, a fall that played
 * out otherwise, their own click elsewhere) is put back on it after a few idle ticks: the obstacle
 * that comes next from where they stand is clicked. Nudges that get nowhere turn it off. Walking off
 * the course pauses it until they're back; ::autoagility off ends it.
 */
const { ObjectActionPacketListener } = require("../../src/main/typescript/elvarg/net/packet/impl/ObjectActionPacketListener");
const { COURSES } = require("./agility/courses/Courses.Agility");

const ATTRIBUTE = "revenant:autoagility";
/** Set by the Agility plugin's ObstacleRunner while an obstacle plays out. */
const OBSTACLE_ATTRIBUTE = "agility.obstacle";
const COURSE = COURSES.find((course) => course.key === "wilderness");
/** Obstacle index by object id, and back. */
const INDEX_OF = new Map(COURSE.obstacles.map((obstacle) => [obstacle.object, obstacle.index]));
const OBJECT_AT = new Map(COURSE.obstacles.map((obstacle) => [obstacle.index, obstacle.object]));
const LAST = Math.max(...OBJECT_AT.keys());
const STONES = 3;
/** The tile each obstacle is clicked on (cache placements, yarn dump:loc): the pipe's south end, the middle rock. */
const TILES = { 1: [3004, 3938], 2: [3005, 3952], 3: [3001, 3960], 4: [3001, 3945], 5: [2994, 3936] };
/** The pit's ladder (cache), up to 3005,3962 beside the stepping stones (ClimbLinks: 6400 tiles north). */
const LADDER = 17385;
const LADDER_TILE = [3005, 10363];
const LADDER_TOP = [3005, 3962];
/** The Agility dispenser (cache, yarn dump:loc: 3005,3936, ops Tag|Pay|Redeem), tagged after each lap. */
const DISPENSER = 53224;
const DISPENSER_TILE = [3005, 3936];
const TAG_OPTION = 1;
/** Set by the AgilityDispenser plugin while a lap's ticket waits to be tagged. */
const TICKET_ATTRIBUTE = "agility-dispenser:ticket";
/** How long the walk to the dispenser and the Tag may take before the player is left where they are. */
const MAX_TAG_TICKS = 20;
/** The course, walls included, and the pit below it: outside them nothing is clicked. */
const AREAS = [
  { minX: 2988, maxX: 3010, minY: 3930, maxY: 3966 },
  { minX: 2988, maxX: 3010, minY: 10330, maxY: 10366 },
];
/** How long a landing (forced movement, placement) may hold the player before the next click. */
const MAX_BUSY_TICKS = 5;
/** How long a fall, or the walk to the ladder and the climb, may take before the player is left where they are. */
const MAX_RECOVERY_TICKS = 40;
/** Idle ticks on the course before the player is put back on it, and nudges in a row with no obstacle before it gives up. */
const IDLE_TICKS = 4;
const MAX_NUDGES = 3;
const idleTicks = new WeakMap();
const nudges = new WeakMap();

let core;

function enabled(player) {
  return player?.getAttribute?.(ATTRIBUTE) === true;
}

function onCourse(player) {
  const at = player.getLocation();
  return at.getZ() === 0 && AREAS.some((area) =>
    at.getX() >= area.minX && at.getX() <= area.maxX && at.getY() >= area.minY && at.getY() <= area.maxY);
}

function inPit(player) {
  return player.getLocation().getY() >= AREAS[1].minY;
}

/** Why the player stops, or null to keep going. Attacks don't stop it: the player takes no damage. */
function stopReason(player) {
  if (!onCourse(player)) return "You've left the Wilderness Agility Course: auto-agility stops.";
  return null;
}

/** combat:damage-apply: no damage at all while auto-agility runs on the course. */
function shield({ target, hit }) {
  if (!target?.isPlayer?.()) return;
  const player = target.getAsPlayer?.() ?? target;
  if (enabled(player) && onCourse(player)) hit.setDamage(0);
}

function free(player) {
  return player.getAttribute(OBSTACLE_ATTRIBUTE) == null && !player.busy();
}

/**
 * A tick from now and every tick after, until `ready` or `maxTicks`: runs `action` once the player
 * is ready, unless they turned it off, logged out or have a reason to stop. Keyed by the player's
 * index, as Pickpocket's repeat, so walking off or a new click cancels it; a wait that has to
 * outlast a click of its own (arriving at the ladder cancels the player's tasks) passes its own key.
 */
function when(player, ready, action, maxTicks, key = player.getIndex(), waited = 0) {
  core.TaskManager.submit(new core.CountdownTask(key, 1, () => {
    if (!player.isRegistered() || !enabled(player)) return;
    if (!ready()) {
      if (waited < maxTicks) when(player, ready, action, maxTicks, key, waited + 1);
      return;
    }
    const reason = stopReason(player);
    if (reason) {
      player.sendMessage(reason);
      return;
    }
    action();
  }));
}

function click(player, index) {
  const [x, y] = TILES[index];
  new ObjectActionPacketListener().executeAction(player, OBJECT_AT.get(index), x, y, 1);
}

function clickSoon(player, index) {
  when(player, () => !player.busy(), () => click(player, index), MAX_BUSY_TICKS);
}

function besideDispenser(player) {
  const at = player.getLocation();
  return Math.abs(at.getX() - DISPENSER_TILE[0]) <= 1 && Math.abs(at.getY() - DISPENSER_TILE[1]) <= 1;
}

/** After the rocks: Tag the dispenser, then the pipe. A Tag that left the ticket unclaimed stops it. */
function tagThenPipe(player) {
  when(player, () => !player.busy(), () => {
    new ObjectActionPacketListener().executeAction(player, DISPENSER, ...DISPENSER_TILE, TAG_OPTION);
    // Arriving cancels the player's own tasks, so this wait has a key of its own. The Tag runs at the
    // end of the tick the player arrives in, so the ticket is looked at a tick after arriving.
    let arrived = 0;
    const tagged = () => {
      arrived = besideDispenser(player) && free(player) && player.getMovementQueue().size() === 0 ? arrived + 1 : 0;
      return arrived >= 2;
    };
    when(player, tagged, () => {
      if (Number(player.getAttribute(TICKET_ATTRIBUTE)) === 1) {
        player.setAttribute(ATTRIBUTE, false);
        player.sendMessage("The dispenser couldn't pay out (open your looting bag, or make room in it): auto-agility is off.");
        return;
      }
      click(player, 1);
    }, MAX_TAG_TICKS, `${ATTRIBUTE}:tag:${player.getIndex()}`);
  }, MAX_BUSY_TICKS);
}

function next({ player, objectId }) {
  const index = INDEX_OF.get(objectId);
  if (index === undefined || !enabled(player) || !onCourse(player)) return;
  nudges.delete(player);
  if (index === LAST) tagThenPipe(player);
  else clickSoon(player, index + 1);
}

/** A failed obstacle: once the fall has played out, out of the pit and on from the stones. */
function fallen({ player, objectId, success }) {
  if (!INDEX_OF.has(objectId) || !enabled(player) || !onCourse(player)) return;
  nudges.delete(player);
  if (success) return;
  when(player, () => free(player), () => {
    if (inPit(player)) climbOut(player);
    else click(player, STONES);
  }, MAX_RECOVERY_TICKS);
}

/** Up the pit's ladder, which comes up by the stepping stones, and on from the stones. */
function climbOut(player) {
  new ObjectActionPacketListener().executeAction(player, LADDER, ...LADDER_TILE, 1);
  // Only from the top of the ladder: a player who clicked anything else on the way is left be.
  const top = () => {
    const at = player.getLocation();
    return at.getX() === LADDER_TOP[0] && at.getY() === LADDER_TOP[1] && free(player);
  };
  when(player, top, () => click(player, STONES), MAX_RECOVERY_TICKS, `${ATTRIBUTE}:${player.getIndex()}`);
}

/**
 * The obstacle that comes next from where the player stands (tiles from the course's steps):
 * the stones run west along y 3960 to 2996, the log west along y 3945 to 2994, the rocks south
 * from 2994,3937 to 3933, the pipe north from 3004,3937 to 3950 and the ropeswing on to 3958.
 */
function resume(player) {
  if (inPit(player)) {
    climbOut(player);
    return;
  }
  const at = player.getLocation();
  const x = at.getX();
  const y = at.getY();
  if (x <= 2997 && y >= 3946) click(player, 4); // past the stones
  else if (y >= 3956) click(player, STONES); // past the ropeswing, the ladder's top, the lava's landing
  else if (y >= 3950 && x >= 2998) click(player, 2); // out of the pipe
  else if (x >= 2998 && y >= 3938) click(player, 4); // at the log's east end
  else if (x <= 2997 && y >= 3937) click(player, 5); // past the log
  else if (Number(player.getAttribute(TICKET_ATTRIBUTE)) === 1) tagThenPipe(player); // up the rocks: a lap to tag
  else click(player, 1);
}

/** Every tick: a player left standing on the course with it on is put back on it. */
function watch({ player }) {
  const idle = enabled(player) && onCourse(player) && free(player) && player.getMovementQueue().size() === 0;
  if (!idle) {
    idleTicks.delete(player);
    return;
  }
  const ticks = (idleTicks.get(player) ?? 0) + 1;
  if (ticks < IDLE_TICKS) {
    idleTicks.set(player, ticks);
    return;
  }
  idleTicks.delete(player);
  const tries = (nudges.get(player) ?? 0) + 1;
  if (tries > MAX_NUDGES) {
    nudges.delete(player);
    player.setAttribute(ATTRIBUTE, false);
    player.sendMessage("Auto-agility couldn't get going again from here, so it's off: ::autoagility turns it back on.");
    return;
  }
  nudges.set(player, tries);
  resume(player);
}

function onCommand({ player, raw, parts }) {
  const arg = raw.substring(parts[0].length).trim().toLowerCase();
  const on = arg === "on" ? true : arg === "off" ? false : !enabled(player);
  player.setAttribute(ATTRIBUTE, on);
  if (!on) {
    player.sendMessage("Auto-agility is off.");
    return;
  }
  if (!onCourse(player)) {
    player.sendMessage("Auto-agility is on: it starts once you're on the Wilderness Agility Course.");
    return;
  }
  player.sendMessage("Auto-agility is on: you keep running the Wilderness Agility Course.");
  click(player, 1);
}

module.exports = {
  register(api) {
    core = api.core;
    api.persistAttribute(ATTRIBUTE, false);
    api.onCustomEvent("agility:obstacle", next);
    api.onCustomEvent("agility:obstacle-start", fallen);
    api.onCustomEvent("combat:damage-apply", shield);
    api.onPlayerProcess(watch);
    api.registerCommand("autoagility", onCommand, undefined, "Keep running the Wilderness Agility Course: ::autoagility [on|off]");
  },
  _test: { next, fallen, onCommand, stopReason, shield, watch, ATTRIBUTE, TILES },
};

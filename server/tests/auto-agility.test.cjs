// Revenant custom: ::autoagility (plugins/skills/AutoAgility.plugin.js).
// A real player, the real Agility and Ladders plugins, packet, routing and cache clipping, run cycle by cycle.
const assert = require("node:assert/strict");
const { before, test } = require("node:test");
const path = require("node:path");
const { Server } = require("../dist/Server");
Server.installProductionPathResolver();
const { PluginManager } = require("../dist/plugins/PluginManager");
const { Player } = require("../dist/game/entity/impl/player/Player");
const { Skill } = require("../dist/game/model/Skill");
const { ObjectIds } = require("../dist/util/IdEnums");
const core = PluginManager.getCoreApi();
const AutoAgility = require("../plugins/skills/AutoAgility.plugin");

/** Obstacles to fail, by object id: how many more attempts at each fail. Everything else succeeds. */
const failures = new Map();

before(async () => {
  await require("../dist/game/cache/CachePipeline").CachePipeline.initialize(path.resolve(__dirname, ".."));
  core.RegionManager.init();
  for (const key of ["objectInteractionHooks", "objectRouteHooks"]) PluginManager[key] = [];
  for (const key of ["objectHooksByName", "objectHooksById", "customEventHooks"]) PluginManager[key] = new Map();
  require("../plugins/skills/Agility.plugin").register(PluginManager.createApi("agility"));
  require("../plugins/objects/Ladders.plugin").register(PluginManager.createApi("ladders"));
  require("../plugins/skills/AgilityDispenser.plugin").register(PluginManager.createApi("agility-dispenser"));
  AutoAgility.register(PluginManager.createApi("auto-agility"));
  PluginManager.createApi("rolls").onCustomEvent("agility:roll", (roll) => {
    const left = failures.get(roll.objectId) ?? 0;
    roll.success = left === 0;
    if (left > 0) failures.set(roll.objectId, left - 1);
  });
  core.RegionManager.loadMapFiles(3000, 3945, true);
  core.RegionManager.loadMapFiles(3000, 10345, true);
});

let nextIndex = 2000;
function createPlayer(x, y) {
  const player = new Player(null);
  const index = nextIndex++;
  player.setUsername(`offline${index}`);
  player.setIndex(index);
  player.isRegistered = () => true;
  const messages = [];
  let sender;
  // Obstacle lines are spam (sendTypedMessage); keep them with the plain messages.
  sender = new Proxy({ sendTypedMessage: (type, text) => { messages.push(text); return sender; } },
    { get: (target, key) => target[key] ?? (() => sender) });
  player.getPacketSender = () => sender;
  player.sendMessage = (text) => messages.push(text);
  player.getSkillManager().setMaxLevel(Skill.AGILITY, 70);
  player.getSkillManager().setCurrentLevel(Skill.AGILITY, 70, false);
  player.setLocation(new core.Location(x, y, 0));
  player.setNeedsPlacement(false);
  return { player, messages };
}

function cycle(player) {
  core.World.processCycle = (core.World.processCycle + 1) & 0x7fffffff;
  core.TaskManager.process();
  player.getMovementQueue().beginCycle();
  player.getMovementQueue().process();
  core.TaskManager.processWalkTo(player.getIndex());
  PluginManager.emitPlayerProcess({ player });
  // The world's update phase, at the end of every tick.
  player.resetUpdating();
}

function laps(player) {
  return player.getAttribute("agility.laps")?.wilderness ?? 0;
}

function command(player, raw) {
  AutoAgility._test.onCommand({ player, raw, parts: raw.split(" ") });
}

/** Runs until `laps` laps or 1500 ticks; returns the tiles walked and the ticks taken. */
function run(player, wanted) {
  const trail = [];
  let ticks = 0;
  for (; ticks < 1500 && laps(player) < wanted; ticks++) {
    cycle(player);
    const at = player.getLocation();
    const here = `${at.getX()},${at.getY()}`;
    if (trail.at(-1) !== here) trail.push(here);
  }
  return { trail, ticks };
}

test("::autoagility on the course runs lap after lap with no other click", () => {
  failures.clear();
  const { player, messages } = createPlayer(3004, 3936);
  command(player, "autoagility on");
  const { trail, ticks } = run(player, 3);
  assert.equal(laps(player), 3, `ended at ${trail.slice(-15).join(" ")}\n${messages.join("\n")}`);
  console.log(`3 laps in ${ticks} ticks (${(ticks * 0.6 / 60).toFixed(1)} min), xp ${player.getSkillManager().getExperience(Skill.AGILITY)}`);
});

for (const [name, objectId] of [["ropeswing", ObjectIds.ROPESWING_6], ["log balance", ObjectIds.LOG_BALANCE_23]]) {
  test(`a fall from the ${name} into the pit: up the ladder, on from the stones, and the next laps run`, () => {
    failures.clear();
    failures.set(objectId, 1);
    const { player, messages } = createPlayer(3004, 3936);
    command(player, "autoagility on");
    const { trail, ticks } = run(player, 2);
    assert.ok(trail.some((tile) => Number(tile.split(",")[1]) > 10000), `fell into the pit: ${trail.join(" ")}`);
    assert.equal(laps(player), 2, `ended at ${trail.slice(-15).join(" ")}\n${messages.join("\n")}`);
    console.log(`${name} fall: 2 laps in ${ticks} ticks; ${messages.filter((m) => /fall|slip/i.test(m)).join(" | ")}`);
  });
}

test("a fall from the log balance with low hitpoints: no damage, up the ladder, and on from the stones", () => {
  failures.clear();
  failures.set(ObjectIds.LOG_BALANCE_23, 1);
  const { player, messages } = createPlayer(3004, 3936);
  player.getSkillManager().setMaxLevel(Skill.HITPOINTS, 60);
  player.getSkillManager().setCurrentLevel(Skill.HITPOINTS, 15, false);
  command(player, "autoagility on");
  const { trail } = run(player, 2);
  assert.ok(messages.some((m) => /spikes/.test(m)), messages.join("\n"));
  assert.equal(player.getHitpoints(), 15, "the spikes did no damage");
  assert.equal(laps(player), 2, `ended at ${trail.slice(-15).join(" ")}\n${messages.join("\n")}`);
});

test("a fall into the lava beside the stepping stones: the stones again, and the lap still counts", () => {
  failures.clear();
  failures.set(ObjectIds.STEPPING_STONE_33, 1);
  const { player, messages } = createPlayer(3004, 3936);
  command(player, "autoagility on");
  const { trail } = run(player, 1);
  assert.equal(laps(player), 1, `ended at ${trail.slice(-15).join(" ")}\n${messages.join("\n")}`);
  assert.ok(messages.some((m) => /lava/.test(m)), messages.join("\n"));
});

test("being attacked doesn't stop it, and it takes no damage on the course", () => {
  failures.clear();
  const { player, messages } = createPlayer(3004, 3936);
  command(player, "autoagility on");
  let attacked = false;
  for (let ticks = 0; ticks < 1500 && laps(player) < 1; ticks++) {
    cycle(player);
    if (!attacked && player.getLocation().getY() >= 3950) {
      player.getCombat().getAttacker = () => ({});
      attacked = true;
    }
  }
  assert.ok(attacked);
  assert.equal(laps(player), 1, messages.join("\n"));
  assert.ok(!messages.some((m) => /under attack/.test(m)), messages.join("\n"));
});

test("on the course with it on, a hit does no damage; off, or off the course, it does", () => {
  const { player } = createPlayer(3004, 3936);
  const hp = () => player.getHitpoints();
  command(player, "autoagility on");
  const start = hp();
  player.decrementHealth(new core.HitDamage(5, core.HitMask.RED));
  assert.equal(hp(), start, "no damage on the course");
  player.setLocation(new core.Location(3005, 10350, 0));
  player.decrementHealth(new core.HitDamage(5, core.HitMask.RED));
  assert.equal(hp(), start, "no damage in the pit");
  player.setLocation(new core.Location(2998, 3900, 0));
  player.decrementHealth(new core.HitDamage(3, core.HitMask.RED));
  assert.equal(hp(), start - 3, "damage off the course");
  player.setLocation(new core.Location(3004, 3936, 0));
  command(player, "autoagility off");
  player.decrementHealth(new core.HitDamage(2, core.HitMask.RED));
  assert.equal(hp(), start - 5, "damage with it off");
});

test("after each lap it tags the dispenser for the ticket, then starts the next lap", () => {
  failures.clear();
  const { player, messages } = createPlayer(3004, 3936);
  command(player, "autoagility on");
  const { trail } = run(player, 2);
  assert.equal(laps(player), 2, `ended at ${trail.slice(-15).join(" ")}\n${messages.join("\n")}`);
  const ticket = core.CacheDefinitions.getObjId(require("../plugins/skills/data/agility-dispenser.json").ticket);
  assert.equal(player.getInventory().getAmount(ticket), 1, `first lap's ticket tagged\n${messages.join("\n")}`);
  // The second lap's ticket is tagged once the walk to the dispenser plays out.
  for (let ticks = 0; ticks < 30; ticks++) cycle(player);
  assert.equal(player.getInventory().getAmount(ticket), 2, messages.join("\n"));
});

test("::autoagility off ends it; off by default", () => {
  failures.clear();
  const { player, messages } = createPlayer(3004, 3936);
  assert.equal(player.getAttribute(AutoAgility._test.ATTRIBUTE), undefined);
  command(player, "autoagility on");
  command(player, "autoagility off");
  for (let ticks = 0; ticks < 300; ticks++) cycle(player);
  assert.equal(messages.at(-1), "Auto-agility is off.");
  assert.equal(laps(player), 0);
});

// Where a player can be left standing: by the stones (where a fall from the ropeswing left one in
// play), out of the ropeswing, out of the pipe, past the stones, at the log, past the log, up the
// rocks, and in the pit.
for (const [x, y] of [[3002, 3960], [3005, 3958], [3004, 3950], [2996, 3960], [3002, 3945], [2994, 3945], [2994, 3933], [3005, 10357]]) {
  test(`left standing at ${x},${y} with it on, it picks up from there and laps on`, () => {
    failures.clear();
    const { player, messages } = createPlayer(x, y);
    player.setAttribute(AutoAgility._test.ATTRIBUTE, true);
    const { trail, ticks } = run(player, 1);
    assert.equal(laps(player), 1, `ended at ${trail.slice(-15).join(" ")}\n${messages.join("\n")}`);
    assert.equal(player.getAttribute(AutoAgility._test.ATTRIBUTE), true);
    console.log(`from ${x},${y}: a lap in ${ticks} ticks`);
  });
}

test("nudges that get nowhere turn it off", () => {
  failures.clear();
  const { player, messages } = createPlayer(3004, 3936);
  player.getSkillManager().setMaxLevel(Skill.AGILITY, 1);
  player.getSkillManager().setCurrentLevel(Skill.AGILITY, 1, false);
  player.setAttribute(AutoAgility._test.ATTRIBUTE, true);
  for (let ticks = 0; ticks < 100; ticks++) cycle(player);
  assert.equal(player.getAttribute(AutoAgility._test.ATTRIBUTE), false, messages.join("\n"));
  assert.match(messages.at(-1), /it's off/);
  assert.equal(laps(player), 0);
});

// Run after yarn build: node --test tests/biohazard-watchtower.test.cjs
const assert = require("node:assert/strict");
const path = require("node:path");
const { before, test } = require("node:test");
require("../dist/Server").Server.installProductionPathResolver();
const { CachePipeline } = require("../dist/game/cache/CachePipeline");
const { PluginManager } = require("../dist/plugins/PluginManager");
const { Player } = require("../dist/game/entity/impl/player/Player");
const { PlayerSession } = require("../dist/net/PlayerSession");
const { ItemActionPacketListener } = require("../dist/net/packet/impl/ItemActionPacketListener");
const { UseItemPacketListener } = require("../dist/net/packet/impl/UseItemPacketListener");
const QuestRuntime = require("../plugins/quests/QuestRuntime");
let core, quest;
let index = 4600;
const WATCHTOWER = 37328;

before(async () => {
  await CachePipeline.initialize(path.resolve(__dirname, ".."));
  PluginManager.loadFromDirectory(path.resolve(__dirname, "../plugins"));
  core = PluginManager.getCoreApi();
  core.ObjectDefinition.init();
  core.RegionManager.init();
  core.RegionManager.loadMapFiles(2562, 3301);
  PluginManager.emitServerStartup({});
  quest = QuestRuntime.getRegisteredQuests().find(({ key }) => key === "biohazard");
  assert.ok(quest);
});

function actor(stage = 2, x = 2562, y = 3300, z = 0) {
  const channel = { kind: "test", remoteAddress: "127.0.0.1", isOpen: () => true,
    send: () => true, close() {}, onData() {}, onError() {}, onClose() {} };
  const player = new Player(new PlayerSession(channel));
  player.setIndex(index++);
  player.setUsername("biohazard" + player.getIndex());
  player.setRegistered(true);
  player.setLocation(new core.Location(x, y, z));
  player.setNeedsPlacement(false);
  player.getPacketSender().sendMessage = () => player.getPacketSender();
  quest.setStage(player, stage);
  return player;
}
function supplies(player, feed = 2, cages = 1) {
  if (feed) player.getInventory().adds(core.ItemIdentifiers.BIRD_FEED, feed);
  if (cages) player.getInventory().adds(core.ItemIdentifiers.PIGEON_CAGE, cages);
}
function amount(player, id) { return player.getInventory().getAmount(id); }
function useFeed(player, objectId = WATCHTOWER) {
  const object = core.MapObjects.get(WATCHTOWER, new core.Location(2562, 3301, 0));
  const event = { player, object, itemId: core.ItemIdentifiers.BIRD_FEED, objectId, handled: false };
  PluginManager.emitItemOnObject(event);
  return event;
}
function tick(player, count = 8) {
  for (let i = 0; i < count; i++) {
    player.getMovementQueue().process();
    core.TaskManager.processWalkTo(player.getIndex());
    core.TaskManager.process();
  }
}

test('Voidburn crossings wait for their animation, require the first-entry mask and advance only on arrival', () => {
  const player = actor(4, 2561, 3266);
  const cross = () => PluginManager.emitCustomEvent('npc-dialogue:action', {
    player, npcId: core.NpcIdentifiers.OMART, stepId: 'v1MvBF'
  });
  cross();tick(player,3);
  assert.equal(quest.getStage(player),4);
  player.getEquipment().setItem(core.Equipment.HEAD_SLOT,new core.Item(core.ItemIdentifiers.GAS_MASK));
  cross();cross();
  assert.equal(quest.getStage(player),4);
  assert.equal(player.getMovementQueue().isMovementBlocked(),true);
  tick(player,3);
  assert.equal(quest.getStage(player),5);
  assert.deepEqual([player.getLocation().getX(),player.getLocation().getY()],[2554,3266]);
  assert.equal(player.getMovementQueue().isMovementBlocked(),false);
  PluginManager.emitCustomEvent('npc-dialogue:action',{player,npcId:core.NpcIdentifiers.KILRON,stepId:'PIRzeQ'});
  tick(player,3);
  assert.deepEqual([player.getLocation().getX(),player.getLocation().getY()],[2560,3266]);
});

test('Voidburn fence crossing does not pull a player back after interrupted travel', () => {
  const player=actor(5,2540,3331);
  const object=new core.GameObject(2068,new core.Location(2541,3331,0),0,2);
  const event={player,object,objectId:2068,clickType:1,location:{x:2541,y:3331,z:0},option:'squeeze-through',handled:false};
  PluginManager.emitObjectInteraction(event);
  assert.equal(event.handled,true);
  player.moveTo(new core.Location(3200,3200,0));tick(player,3);
  assert.deepEqual([player.getLocation().getX(),player.getLocation().getY()],[3200,3200]);
  assert.equal(player.getMovementQueue().isMovementBlocked(),false);
});

test('HQ doorway and distillator cage enforce the gown and key, then permit actual passage', () => {
 const player=actor(6,2551,3319);
 const door=()=>PluginManager.emitCustomEvent('door:toggle',{player,objectId:2036,location:{x:2551,y:3320,z:0},handled:false});
 door();tick(player,3);assert.equal(player.getLocation().getY(),3319);
 player.getEquipment().setItem(core.Equipment.BODY_SLOT,new core.Item(core.ItemIdentifiers.MEDICAL_GOWN));
 door();tick(player,3);assert.equal(player.getLocation().getY(),3321);
 door();tick(player,3);assert.equal(player.getLocation().getY(),3319);
 player.setLocation(new core.Location(2551,3320,0));
 door();tick(player,3);assert.equal(player.getLocation().getY(),3321,'a routed click on the door anchor enters the room');
 player.setLocation(new core.Location(2546,3325,1));
 PluginManager.emitCustomEvent('door:toggle',{player,objectId:2034,location:{x:2547,y:3325,z:1},handled:false});
 tick(player,3);assert.equal(player.getLocation().getX(),2548);
 player.setLocation(new core.Location(2550,3326,1));
 const gate=()=>PluginManager.emitCustomEvent('door:toggle',{player,objectId:2058,location:{x:2551,y:3326,z:1},handled:false});
 gate();tick(player,3);assert.equal(player.getLocation().getX(),2550);
 player.getInventory().adds(core.ItemIdentifiers.KEY_2,1);
 gate();tick(player,3);assert.equal(player.getLocation().getX(),2552);
 assert.equal(player.getInventory().getAmount(core.ItemIdentifiers.KEY_2),1);
});

test('door routing preserves the entry side even when the generic route would stand on its anchor', () => {
 const player=actor(6,2550,3327,1);
 const object=new core.GameObject(2034,new core.Location(2547,3325,1),0,0);
 const event={player,object,objectId:2034,clickType:1,sourceLocation:{x:2550,y:3327,z:1},destination:null};
 PluginManager.emitObjectRoute(event);
 assert.deepEqual(event.destination,{x:2548,y:3325,z:1});
 event.sourceLocation={x:2543,y:3326,z:1};event.destination=null;PluginManager.emitObjectRoute(event);
 assert.deepEqual(event.destination,{x:2546,y:3325,z:1});
});

test("normal item-on-fence producer reaches the Biohazard transaction", () => {
  const player = actor();
  supplies(player);
  const feed = core.ItemIdentifiers.BIRD_FEED;
  const slot = player.getInventory().getItems().findIndex((item) => item?.getId() === feed);
  // Client packet entry point, actual map object, route and queued action.
  UseItemPacketListener.itemOnObject(player, 149, WATCHTOWER, feed, slot, 2562, 3301);
  tick(player);
  assert.equal(quest.getStage(player), 3);
  assert.equal(amount(player, feed), 1);
  assert.equal(amount(player, core.ItemIdentifiers.PIGEON_CAGE), 1);
  useFeed(player);
  assert.equal(quest.getStage(player), 3);
  assert.equal(amount(player, feed), 1, "repeated use cannot spend more feed");
});

test("feed use preserves items outside the quest stage, place or plane", () => {
  for (const setup of [[0], [1], [3], [4], [16], [2, 3200, 3200, 0], [2, 2562, 3300, 1]]) {
    const player = actor(...setup);
    supplies(player);
    useFeed(player);
    assert.equal(quest.getStage(player), setup[0]);
    assert.equal(amount(player, core.ItemIdentifiers.BIRD_FEED), 2);
    assert.equal(amount(player, core.ItemIdentifiers.PIGEON_CAGE), 1);
  }
});

test("missing supplies and unrelated objects cannot advance or consume feed", () => {
  for (const [feed, cages] of [[0, 1], [2, 0]]) {
    const player = actor();
    supplies(player, feed, cages);
    useFeed(player);
    assert.equal(quest.getStage(player), 2);
    assert.equal(amount(player, core.ItemIdentifiers.BIRD_FEED), feed);
  }
  const player = actor();
  supplies(player);
  assert.equal(useFeed(player, 2068).handled, false);
  assert.equal(quest.getStage(player), 2);
  assert.equal(amount(player, core.ItemIdentifiers.BIRD_FEED), 2);
});

test("the alternative Investigate action shares the one-time feed transaction", () => {
  const player = actor();
  supplies(player);
  const object = core.MapObjects.get(WATCHTOWER, new core.Location(2562, 3301, 0));
  assert.ok(object);
  PluginManager.emitObjectInteraction({ player, object, objectId: WATCHTOWER,
    definition: core.ObjectDefinition.forId(35947), option: "Investigate", clickType: 1, location: object.getLocation(), handled: false });
  assert.equal(quest.getStage(player), 3);
  useFeed(player);
  assert.equal(amount(player, core.ItemIdentifiers.BIRD_FEED), 1);
});


test("feed use allows the normal cage action once, with plane and repeat protection", () => {
  const player = actor();
  supplies(player, 2, 2);
  const cage = core.ItemIdentifiers.PIGEON_CAGE;
  const empty = core.ItemIdentifiers.PIGEON_CAGE_2;
  const open = () => {
    const slot = player.getInventory().getItems().findIndex((item) => item?.getId() === cage);
    ItemActionPacketListener.handleAction(player, 149, cage, slot, 1, "Open");
  };
  useFeed(player);
  player.setLocation(new core.Location(2562, 3300, 1));
  open();
  assert.equal(quest.getStage(player), 3);
  assert.equal(amount(player, cage), 2);
  player.setLocation(new core.Location(2562, 3300, 0));
  open();
  assert.equal(quest.getStage(player), 4);
  assert.equal(amount(player, cage), 1);
  assert.equal(amount(player, empty), 1);
  open();
  assert.equal(amount(player, cage), 1);
  assert.equal(amount(player, empty), 1);
});

const assert = require('node:assert/strict');
const { before, test } = require('node:test');
require('../dist/Server').Server.installProductionPathResolver();
const { CachePipeline } = require('../dist/game/cache/CachePipeline');
const { PluginManager } = require('../dist/plugins/PluginManager');
const { Player } = require('../dist/game/entity/impl/player/Player');
let core, quest, hooks, itemOnObject, variant, line, action, named, login, logout;
const tasks = [], itemActions = new Map();
before(async () => {
  await CachePipeline.initialize(require('node:path').resolve(__dirname, '..'));
  core = PluginManager.getCoreApi();
  hooks = new Map(); named = new Map();
  const dispatch = (name, event) => { for (const fn of hooks.get(name) ?? []) fn(event); };
  const api = new Proxy({core, persistAttribute() {}, emitCustomEvent: dispatch,
    getTaskManager: () => ({submit(task) { task.setRunning(true); tasks.push(task); }}),
    onPlayerLogin(fn) { login = fn; }, onPlayerLogout(fn) { logout = fn; },
    onItemAction(name, actions) { itemActions.set(name, actions); },
    onCustomEvent(name, fn) { hooks.set(name, [...hooks.get(name) ?? [], fn]); },
    onNpcDialogueVariant(fn) { variant = fn; },
    onItemOnObject(fn) { itemOnObject = fn; },
    onObjectInteraction(name, map) { named.set(name, map); },
  }, {get: (target, key) => key in target ? target[key] : () => {}});
  require('../plugins/quests/quests/PlagueCity.Quest')(api);
  quest = require('../plugins/quests/QuestRuntime').getRegisteredQuests().find(q => q.key === 'plague_city');
  action = event => dispatch('npc-dialogue:action', event);
  line = event => dispatch('npc-dialogue:line', event);
});
function fixture(stage) {
  tasks.length = 0;
  const player = new Player(null), vars = new Map(), messages = [];
  player.setRegistered(true);
  const sender = new Proxy({}, {get: (_, key) => ['getVarbit', 'getVarp'].includes(key) ? () => 0 : key === 'sendVarbit' ? (id, value) => { vars.set(id, value); return sender; } : () => sender});
  player.getPacketSender = () => sender;
  player.sendMessage = text => messages.push(text);
  player.setAttribute('quest.plague_city.stage', stage);
  player.setLocation(new core.Location(2566, 3332, 0));
  return {player, vars, messages, inv: player.getInventory()};
}
function give(inv, id, n = 1) { inv.add(new core.Item(id, n), false); }
function act(player, npcId, stepId) { action({player, npcId, stepId}); }
function tick(n) { while(n-- > 0) for(const task of [...tasks]) if(task.isRunning()) task.execute(); }
test('Alrena handover is atomic, repeat safe, and a cancelled talk spends nothing', () => {
  const {player, inv} = fixture(1), I = core.ItemIdentifiers, npcId = core.NpcIdentifiers.ALRENA;
  give(inv, I.DWELLBERRIES);
  variant({player, npcId});
  assert.equal(inv.getAmount(I.DWELLBERRIES), 1);
  assert.equal(quest.getStage(player), 1);
  act(player, npcId, 'Br9tOd');
  assert.equal(inv.getAmount(I.DWELLBERRIES), 0);
  assert.equal(inv.getAmount(I.GAS_MASK), 1);
  assert.equal(quest.getStage(player), 2);
  act(player, npcId, 'Br9tOd');
  assert.equal(inv.getAmount(I.GAS_MASK), 1);
});
test('Edmond instruction unlocks exactly four conserved bucket exchanges', () => {
  const {player, inv} = fixture(2), I = core.ItemIdentifiers;
  give(inv, I.BUCKET_OF_WATER, 5);
  const water = () => itemOnObject({player, itemId: I.BUCKET_OF_WATER, objectId: core.ObjectIdentifiers.MUD_PATCH_2, location: {x:2566,y:3331,z:0}});
  water(); assert.equal(inv.getAmount(I.BUCKET_OF_WATER), 5);
  line({player, npcId:core.NpcIdentifiers.EDMOND_2, text:"I've already tried digging down to them but the soil is rock hard. You'll need to pour on several buckets of water to soften it up. I reckon four buckets should do it."});
  assert.equal(quest.getStage(player), 3);
  for (let n = 1; n <= 4; n++) { water(); assert.equal(quest.getStage(player), 3+n); }
  water();
  assert.equal(inv.getAmount(I.BUCKET_OF_WATER), 1);
  assert.equal(inv.getAmount(I.BUCKET), 4);
});
test('book action consumes once and rejects absent books', () => {
  const {player, inv} = fixture(20), I = core.ItemIdentifiers, npc = core.NpcIdentifiers.TED_REHNISON;
  act(player,npc,'zQse1w'); assert.equal(quest.getStage(player),20);
  give(inv,I.BOOK_2); act(player,npc,'zQse1w');
  assert.equal(quest.getStage(player),21); assert.equal(inv.getAmount(I.BOOK_2),0);
  act(player,npc,'zQse1w'); assert.equal(quest.getStage(player),21);
});
test('Bravek only consumes cure and grants warrant at the actual transcript handovers', () => {
  const {player, inv} = fixture(26), I=core.ItemIdentifiers, npc=core.NpcIdentifiers.BRAVEK;
  give(inv,I.HANGOVER_CURE);
  variant({player,npcId:npc}); assert.equal(inv.getAmount(I.HANGOVER_CURE),1); assert.equal(quest.getStage(player),26);
  act(player,npc,'nxSgWb'); assert.equal(inv.getAmount(I.A_SCRUFFY_NOTE),1);
  act(player,npc,'Z2OftI'); assert.equal(inv.getAmount(I.HANGOVER_CURE),0); assert.equal(quest.getStage(player),26);
  act(player,npc,'Pn2ogc'); assert.equal(inv.getAmount(I.WARRANT),1); assert.equal(quest.getStage(player),27);
  act(player,npc,'Pn2ogc'); assert.equal(inv.getAmount(I.WARRANT),1);
});
test('barrel is location scoped, and Elena requires the cell unlock with its key', () => {
  const {player, inv}=fixture(27), I=core.ItemIdentifiers, O=core.ObjectIdentifiers, npc=core.NpcIdentifiers.ELENA_2;
  assert.match(variant({player,npcId:npc}),/basement-door.*without-the-key/,'locked in: her Plague City plea, not the Song of the Elves page');
  assert.equal(quest.getStage(player),27);
  const locked={player,npcId:npc,stepId:'Gtrtgc',handled:false};action(locked);assert.equal(locked.handled,true,'talking skips the door message');
  const again={player,npcId:npc,stepId:'Gtrtgc',handled:false};action(again);assert.equal(again.handled,false,'only once per talk');
  const barrel=named.get('Barrel').Search;
  assert.equal(barrel({player,objectId:O.BARREL_11,location:{x:3221,y:9910,z:0}}),false);
  barrel({player,objectId:O.BARREL_11,location:{x:2534,y:3268,z:0}}); assert.equal(inv.getAmount(I.A_SMALL_KEY),1);
  itemOnObject({player,itemId:I.A_SMALL_KEY,objectId:O.DOOR_78,location:{x:2539,y:9672,z:0}});
  assert.match(variant({player,npcId:npc}),/after-unlocking/); assert.equal(quest.getStage(player),28);
  assert.equal(inv.getAmount(I.A_SMALL_KEY),1);
});
test('water and barrel handlers leave unrelated objects and missing supplies alone', () => {
 const {player,inv}=fixture(3),I=core.ItemIdentifiers,O=core.ObjectIdentifiers;
 give(inv,I.BUCKET_OF_WATER);
 const e={player,itemId:I.BUCKET_OF_WATER,objectId:O.MUD_PATCH_2,location:{x:3000,y:3331,z:0}};
 itemOnObject(e);assert.equal(e.handled,undefined);assert.equal(inv.getAmount(I.BUCKET_OF_WATER),1);
 itemOnObject({...e,location:{x:2566,y:3331,z:0}});
 assert.equal(quest.getStage(player),4);
 itemOnObject({...e,location:{x:2566,y:3331,z:0}});assert.equal(quest.getStage(player),4);
});
test('grill requires attempted opening before accepting one rope and publishes native rope/removed morphs', () => {
 const {player,inv,vars}=fixture(8),I=core.ItemIdentifiers,O=core.ObjectIdentifiers,location={x:2514,y:9739,z:0};
 give(inv,I.ROPE);
 // The pre-attempt branch starts the existing transcript; isolate the actual opening and accepted handover here.
 named.get('Grill').Open({player,objectId:11422,location});
 assert.equal(vars.get(1786),1);
 itemOnObject({player,itemId:I.ROPE,objectId:11422,location});
 assert.equal(quest.getStage(player),9);assert.equal(inv.getAmount(I.ROPE),0);assert.equal(vars.get(1787),1);
 itemOnObject({player,itemId:I.ROPE,objectId:11422,location});assert.equal(quest.getStage(player),9);
 player.setLocation(new core.Location(2517,9755,0));
 act(player,core.NpcIdentifiers.EDMOND_2,'oCU2uO');assert.equal(quest.getStage(player),9);
 tick(3);assert.equal(quest.getStage(player),10);assert.equal(vars.get(1787),2);
});
test('mask replacement has capacity and repeat guards, including an equipped mask', () => {
 const {player,inv}=fixture(2),I=core.ItemIdentifiers,npc=core.NpcIdentifiers.ALRENA;
 for(let n=0;n<28;n++)give(inv,I.BUCKET);
 act(player,npc,'jG6mG3');assert.equal(inv.getAmount(I.GAS_MASK),0);
 inv.deleteNumber(I.BUCKET,1);act(player,npc,'jG6mG3');assert.equal(inv.getAmount(I.GAS_MASK),1);
 act(player,npc,'jG6mG3');assert.equal(inv.getAmount(I.GAS_MASK),1);
 inv.deleteNumber(I.GAS_MASK,1);player.getEquipment().setItem(core.Equipment.HEAD_SLOT,new core.Item(I.GAS_MASK));
 act(player,npc,'jG6mG3');assert.equal(inv.getAmount(I.GAS_MASK),0);
});
test('full inventory can exchange its dwellberry for a mask without dropping either item', () => {
 const {player,inv}=fixture(1),I=core.ItemIdentifiers;
 give(inv,I.DWELLBERRIES);for(let n=0;n<27;n++)give(inv,I.BUCKET);
 act(player,core.NpcIdentifiers.ALRENA,'Br9tOd');
 assert.equal(inv.getAmount(I.DWELLBERRIES),0);assert.equal(inv.getAmount(I.GAS_MASK),1);assert.equal(inv.getFreeSlots(),0);
});
test('clerk permission waits for the actual permission line', () => {
 const {player}=fixture(24),npcId=core.NpcIdentifiers.CLERK;
 variant({player,npcId});assert.equal(quest.getStage(player),24);
 line({player,npcId,text:'I suppose they can come in then. If they keep it short.'});assert.equal(quest.getStage(player),25);
});
test('warrant entry crosses only its player without changing shared door morphs or spending the warrant', () => {
 const {player,inv}=fixture(27);
 give(inv,core.ItemIdentifiers.WARRANT);
 player.setLocation(new core.Location(2540,3274,0));
 const object=new core.GameObject(37321,new core.Location(2540,3273,0),0,3);
 const click={player,object,objectId:37321,location:{x:2540,y:3273,z:0},handled:false};
 for(const fn of hooks.get('door:toggle')) fn(click);
 assert.equal(click.handled,true);tick(2);
 assert.deepEqual([player.getLocation().getX(),player.getLocation().getY()],[2540,3272]);
 assert.equal(object.getId(),37321);
 assert.equal(quest.getStage(player),27);
 assert.equal(inv.getAmount(core.ItemIdentifiers.WARRANT),1);
});

test('garden dig is delayed, repeat safe, conserves the spade and switches Edmond only for this player', () => {
 const {player,inv,vars}=fixture(7);give(inv,core.ItemIdentifiers.SPADE);
 const dig=itemActions.get('Spade').Dig;
 dig({player});dig({player});assert.equal(tasks.length,1);assert.equal(quest.getStage(player),7);
 tick(3);assert.equal(quest.getStage(player),8);assert.equal(vars.get(1783),1);
 assert.deepEqual([player.getLocation().getX(),player.getLocation().getY()],[2518,9758]);
 assert.equal(inv.getAmount(core.ItemIdentifiers.SPADE),1);
 const second=fixture(7);login({player:second.player});assert.equal(second.vars.get(1783),0);
 login({player});assert.equal(vars.get(1783),1);
});

test('logout cancels digging without advancing the quest and relog can retry', () => {
 const {player,inv}=fixture(7);give(inv,core.ItemIdentifiers.SPADE);
 itemActions.get('Spade').Dig({player});logout({player});tick(3);
 assert.equal(quest.getStage(player),7);assert.equal(player.getMovementQueue().isMovementBlocked(),false);
 itemActions.get('Spade').Dig({player});tick(3);assert.equal(quest.getStage(player),8);
});

test('pipe requires an opened grill and an equipped gas mask, then the mud-pile route returns safely', () => {
 const {player,inv}=fixture(9);player.setLocation(new core.Location(2514,9740,0));
 const event={player,objectId:2542,location:{x:2514,y:9737,z:0}};
 named.get('Pipe')['Climb-up'](event);tick(3);assert.equal(player.getLocation().getY(),9740);
 quest.setStage(player,10);give(inv,core.ItemIdentifiers.GAS_MASK);
 named.get('Pipe')['Climb-up'](event);assert.equal(tasks.length,0,'carrying the mask is insufficient');
 player.getEquipment().setItem(core.Equipment.HEAD_SLOT,new core.Item(core.ItemIdentifiers.GAS_MASK));
 named.get('Pipe')['Climb-up'](event);tick(3);assert.equal(player.getLocation().getY(),3304);
 player.setLocation(new core.Location(2518,9758,0));
 named.get('Mud pile').Climb({player,objectId:2533,location:{x:2519,y:9759,z:0}});tick(2);
 assert.equal(player.getLocation().getY(),3333);
});

test('plague-house stairs support a round trip and a missing warrant cannot enter', () => {
 const {player}=fixture(27);player.setLocation(new core.Location(2540,3274,0));
 const e={player,objectId:37321,location:{x:2540,y:3273,z:0}};
 for(const fn of hooks.get('door:toggle'))fn(e);assert.equal(tasks.length,0);
 player.setLocation(new core.Location(2538,3269,0));
 named.get('Spooky stairs')['Walk-down']({player,objectId:2522,location:{x:2536,y:3268,z:0}});tick(2);
 assert.equal(player.getLocation().getY(),9670);
 named.get('Spooky stairs')['Walk-up']({player,objectId:2523,location:{x:2536,y:9671,z:0}});tick(2);
 assert.equal(player.getLocation().getY(),3269);
});

test('clerk permission makes Bravek reachable through his quest door', () => {
 const {player}=fixture(25);player.setLocation(new core.Location(2529,3314,0));
 const e={player,objectId:core.ObjectIdentifiers.DOOR_80,location:{x:2530,y:3314,z:0}};
 for(const fn of hooks.get('door:toggle'))fn(e);tick(2);
 assert.equal(e.handled,true);assert.equal(player.getLocation().getX(),2531);
});

test('unlocking Elena cell permits passage and conserves its key', () => {
 const {player,inv}=fixture(27);give(inv,core.ItemIdentifiers.A_SMALL_KEY);
 player.setLocation(new core.Location(2538,9672,0));
 const e={player,objectId:core.ObjectIdentifiers.DOOR_78,location:{x:2539,y:9672,z:0}};
 for(const fn of hooks.get('door:toggle'))fn(e);tick(2);
 assert.equal(e.handled,true);assert.equal(player.getLocation().getX(),2540);
 assert.equal(inv.getAmount(core.ItemIdentifiers.A_SMALL_KEY),1);
 assert.match(variant({player,npcId:core.NpcIdentifiers.ELENA_2}),/after-unlocking/);
 assert.equal(quest.getStage(player),28);
});

test('pulling the grill happens at the grill, not beside Edmond, and only once', () => {
 const {player}=fixture(9);player.setLocation(new core.Location(2517,9755,0));
 act(player,core.NpcIdentifiers.EDMOND_2,'oCU2uO');
 assert.deepEqual([player.getLocation().getX(),player.getLocation().getY()],[2514,9740]);
 act(player,core.NpcIdentifiers.EDMOND_2,'oCU2uO');assert.equal(tasks.length,1);
 tick(3);assert.equal(quest.getStage(player),10);
});

test('quest doors cross without the ladder-climb animation', () => {
 const {player}=fixture(25);player.setLocation(new core.Location(2529,3314,0));
 const animations=[];player.performAnimation=anim=>animations.push(anim?.getId?.() ?? anim?.id);
 const e={player,objectId:core.ObjectIdentifiers.DOOR_80,location:{x:2530,y:3314,z:0}};
 for(const fn of hooks.get('door:toggle'))fn(e);tick(2);
 assert.equal(player.getLocation().getX(),2531);
 assert.ok(!animations.includes(828),'no ladder climb');
});

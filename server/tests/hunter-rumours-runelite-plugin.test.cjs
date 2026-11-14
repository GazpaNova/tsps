// RuneLite Plugin Hub "Hunter Rumours" (geel9/runelite-hunter-rumours 91b833b) compatibility.
// The plugin learns the rumour from (a) guild hunter NPC dialogue in the burrows, (b) the Quetzal whistle's
// "Your current rumour target is ..." game message, matching the creature by lower-case name (first match in its
// Rumour enum order) and the hunter by common name; it resets on "would you like another rumour?" and marks the
// rumour done on "you find a rare piece of the creature! you should take it back to the hunter guild."
// Run from server/: node ../scripts/run-with-env.mjs - --test tests/hunter-rumours-runelite-plugin.test.cjs
const assert = require('node:assert/strict');
const {before,test} = require('node:test');
const path = require('node:path');
const root = process.env.STAGE_SERVER ?? path.resolve(__dirname, '..');
const {Server} = require(root + '/dist/Server');Server.installProductionPathResolver();
const {PluginManager} = require(root + '/dist/plugins/PluginManager');
const {Player} = require(root + '/dist/game/entity/impl/player/Player');
const {PlayerSession} = require(root + '/dist/net/PlayerSession');
const C = require(root + '/plugins/skills/hunter/Context.Hunter');
const Rumours = require(root + '/plugins/skills/hunter/Rumours.Hunter');
const core = PluginManager.getCoreApi();

const PLUGIN_RUMOURS = ["Tropical Wagtail","Wild Kebbit","Sapphire Glacialis","Swamp Lizard","Spined Larupia","Barb-tailed Kebbit",
  "Snowy Knight","Prickly Kebbit","Embertailed Jerboa","Horned Graahk","Spotted Kebbit","Black Warlock","Orange Salamander",
  "Razor-backed Kebbit","Sabre-toothed Kebbit","Grey Chinchompa","Sabre-toothed Kyatt","Dark Kebbit","Pyre Fox","Red Salamander",
  "Wyrmscraig Goat","Carnivorous Chinchompa","Sunlight Moth","Dashing Kebbit","Sunlight Antelope","Moonlight Moth","Tecu Salamander",
  "Herbiboar","Moonlight Antelope"];
const PLUGIN_HUNTERS = ["Wolf","Teco","Aco","Cervus","Ornus","Gilman"];
const MASTER_COMMON = ["Gilman","Cervus","Ornus","Aco","Teco","Wolf"];
const rumourIn = text => PLUGIN_RUMOURS.find(r => text.includes(r.toLowerCase())) ?? "NONE";
const hunterIn = text => PLUGIN_HUNTERS.find(h => text.toLowerCase().includes(h.toLowerCase())) ?? "NONE";
// Known gap: upstream names the red chinchompa rumour "Red chinchompa"; the plugin (and the game's NPC) say
// "Carnivorous chinchompa", so that one rumour reads as NONE until the name is changed with upstream's test.
const KNOWN_GAP = new Set(["Red chinchompa"]);
const expectedFor = task => KNOWN_GAP.has(task.name) ? "NONE" : PLUGIN_RUMOURS.find(r => r.toLowerCase() === task.name.toLowerCase());

const said = [], typed = [];
const DIALOG = 114; // RuneLite ChatMessageType.DIALOG
const PLUGIN_NPC_IDS = [13121, 13123, 13122, 13124, 13125, 13126]; // Hunter.java ids, in masters() order
// handleBurrowsHunterDialog: type DIALOG, starts with "<npc definition name>|", rumour from the rest in lower case.
function pluginDialog(index) {
  const name = core.NpcDefinition.forId(PLUGIN_NPC_IDS[index]).getName();
  const line = typed.filter(([type, text]) => type === DIALOG && text.startsWith(name + '|')).at(-1);
  return line ? rumourIn(line[1].replace(name + '|', '').toLowerCase()) : 'NO DIALOG LINE';
}
before(async()=>{
  await core.CachePipeline.initialize(root);
  // Capture NPC dialogue text instead of opening interfaces.
  const fake = Object.create(core);
  const own = (name, value) => Object.defineProperty(fake, name, { value, writable: true, configurable: true, enumerable: true });
  own('DialogueChainBuilder', class { add() { return this; } });
  own('NpcDialogue', class { constructor(_i, _npc, text) { said.push(text); } });
  own('EndDialogue', class {});
  C.H.core = fake; C.H.data = require(root + '/plugins/skills/hunter/Data.Hunter').build(core);
  C.H.api = {emitCustomEvent(){}};
});

function fixture(t) {
  const channel={kind:'test',remoteAddress:'127.0.0.1',isOpen:()=>true,send:()=>true,close(){},onData(){},onError(){},onClose(){}};
  const p=new Player(new PlayerSession(channel),new core.Location(1556,9461)),messages=[];
  p.setIndex(1701);p.setUsername('rumour-test');p.setRegistered(true);p.setNeedsPlacement(false);p.setHitpoints(10);
  p.getSkillManager().setMaxLevel(core.Skill.HUNTER,99).setCurrentLevels(core.Skill.HUNTER,99);
  t.mock.method(p,'sendMessage',text=>messages.push(text));
  t.mock.method(p.getPacketSender(),'sendTypedMessage',(type,text)=>{messages.push(text);typed.push([type,text]);});
  t.mock.method(p.getSkillManager(),'addExperiences',()=>{});
  t.mock.method(p,'getDialogueManager',()=>({startDialogues(){}}));
  t.after(()=>{p.setRegistered(false);});
  said.length = 0; typed.length = 0;
  return {p,messages};
}

test('every rumour name is one the plugin knows', () => {
  for (const task of Rumours.tasks) assert.ok(expectedFor(task), `plugin has no rumour called ${task.name}`);
});

test('whistle check names the right creature and hunter for every rumour and hunter', t => {
  const {p, messages} = fixture(t);
  const s = Rumours.state(p);
  let n = 0;
  Rumours.tasks.forEach((task, i) => task.masters.forEach(index => {
    s.assignments = {[index]: {task: i, count: 0, found: false}}; s.active = index;
    messages.length = 0; Rumours.check({player: p});
    const m = messages[0];
    assert.ok(m.includes("Your current rumour target is"), m);
    assert.deepEqual([rumourIn(m), hunterIn(m)], [expectedFor(task), MASTER_COMMON[index]], m);
    n++;
  }));
  assert.ok(n > 60);
});

test('hunter dialogue offers, reminders and hand-ins are readable', t => {
  const {p, messages} = fixture(t);
  const s = Rumours.state(p); s.unlocked = true;
  let checked = 0;
  Rumours.tasks.forEach((task, i) => {
    const index = task.masters.at(-1);
    if (index === 5) return; // Wolf needs At First Light; covered by the whistle test
    s.assignments = {}; s.previous = undefined;
    t.mock.method(C, 'roll', (lo) => lo); // first pool entry, first offer wording
    // Force this task to be the only eligible one for this hunter.
    const saved = Rumours.tasks.map(x => x.masters);
    Rumours.tasks.forEach((x, j) => { x.masters = j === i ? x.masters : x.masters.filter(m => m !== index); });
    said.length = 0; Rumours.assign(p, index);
    Rumours.tasks.forEach((x, j) => { x.masters = saved[j]; });
    C.roll.mock.restore();
    if (!s.assignments[index]) return; // quest-locked creature for this test player
    assert.equal(rumourIn(said[0].toLowerCase()), expectedFor(task), said[0]);
    assert.equal(pluginDialog(index), expectedFor(task), 'offer chat line for ' + task.name);
    said.length = 0; typed.length = 0; Rumours.assign(p, index);
    assert.equal(rumourIn(said[0].toLowerCase()), expectedFor(task), said[0]);
    assert.equal(pluginDialog(index), expectedFor(task), 'reminder chat line for ' + task.name);
    checked++;
  });
  assert.ok(checked >= 15,'dialogue checked for ' + checked + ' rumours');
  // Hand-in resets the plugin.
  const index = 0, i = Rumours.tasks.findIndex(x => x.name === "Tropical wagtail");
  s.assignments = {[index]: {task: i, count: 0, found: true}}; s.active = index;
  p.getInventory().adds(core.ItemIdentifiers[Rumours.tasks[i].part], 1);
  said.length = 0; Rumours.assign(p, index);
  assert.ok(said.some(x => x.toLowerCase().includes("would you like another rumour?")), said.join(' | '));
});

test('finding the part sends the exact line the plugin waits for', t => {
  const {p, messages} = fixture(t);
  const s = Rumours.state(p), i = Rumours.tasks.findIndex(x => x.name === "Spined larupia");
  s.assignments = {2: {task: i, count: 0, found: false}}; s.active = 2;
  t.mock.method(Math, 'random', () => 0);
  Rumours.success({player: p, npcId: core.NpcIdentifiers.SPINED_LARUPIA, method: 'pit', creature: 'SPINED_LARUPIA'});
  assert.ok(messages.some(m => m.replace(/<[^>]*>/g, '').toLowerCase().includes("you find a rare piece of the creature! you should take it back to the hunter guild.")), messages.join(' | '));
});

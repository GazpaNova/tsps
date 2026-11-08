// Revenant custom: ::autothieve against the real stall code. Run after yarn build.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { Server } = require('../dist/Server');
Server.installProductionPathResolver();
const { PluginManager } = require('../dist/plugins/PluginManager');
const Thieving = require('../plugins/skills/Thieving.plugin');
const AutoThieving = require('../plugins/skills/AutoThieving.plugin');
const { handleStealFromStall: steal, depleted } = Thieving._test;
const core = PluginManager.getCoreApi();
const O = core.ObjectIdentifiers;
core.ItemDefinition.forId = (id) => ({ getName: () => `item ${id}` });

let tick = 100;
const tasks = [];
const npcs = [];
const hooks = {};
const sharedCore = {
    ...core,
    CacheDefinitions: { getLocNames: () => new Map(), getVarbitId: () => undefined, getSeqId: () => 832 },
    World: { getProcessCycle: () => tick, getNpcsNear: (at, radius) => npcs.filter((npc) => npc.getLocation().getDistance(at) <= radius) },
    RegionManager: { canProjectileAttackBounds: () => true },
    TaskManager: { submit: (task) => { task.setRunning(true); tasks.push(task); } },
    ObjectManager: { register() {}, deregister() {} },
};
const api = {
    core: sharedCore,
    onCustomEvent: (name, handler) => { hooks[name] = handler; },
    onNpcInteraction() {}, onItemAction() {}, log() {}, persistAttribute() {},
    onNpcsInteraction() {}, onPlayerLogin() {}, onPlayerLogout() {}, onObjectInteraction() {},
    onBeforeObjectInteraction: (handler) => { hooks.before = handler; },
    registerCommand: (name, handler) => { hooks[`::${name}`] = handler; },
    emitCustomEvent: (name, request) => hooks[name]?.(request),
};
Thieving.register(api);
AutoThieving.register(api);

function advance(count = 1) {
    for (let i = 0; i < count; i++) {
        tick++;
        for (const task of [...tasks]) if (task.isRunning()) task.tick();
    }
}

function player() {
    const xp = [], attributes = new Map();
    return {
        xp, moving: false,
        getIndex: () => 3, isRegistered: () => true, getHitpoints: () => 10,
        getAttribute: (key) => attributes.get(key), setAttribute: (key, value) => attributes.set(key, value),
        getPacketSender: () => ({ sendVarbit() {}, sendSound() {}, sendTypedMessage() {} }),
        getSkillManager: () => ({ getCurrentLevel: () => 99, addExperiences: (_skill, amount) => xp.push(amount) }),
        getClickDelay: () => ({ elapsedTime: () => true, reset() {} }),
        getInventory: () => ({ isFull: () => false, full() {}, addItem() {} }),
        getMovementQueue() { return { isMovings: () => this.moving }; },
        getLocation: () => new core.Location(3200, 3200), getPrivateArea: () => null,
        setPositionToFace() {}, performAnimation() {}, sendMessage() {},
    };
}

test('a stall: steals, waits for the restock, steals again, until a guard catches the player', () => {
    const who = player();
    hooks['::autothieve']({ player: who, raw: 'autothieve on', parts: ['autothieve', 'on'] });
    const stall = new core.GameObject(O.SPICE_STALL_3, new core.Location(3200, 3201), 10, 2, null);
    const event = { player: who, object: stall, definition: { getName: () => 'Spice stall' }, option: 'Steal-from', handled: false };
    hooks.before(event);
    steal(event);
    advance();
    assert.deepEqual(who.xp, [92], 'the click');
    assert.ok(depleted.has(stall));
    advance(12);
    assert.deepEqual(who.xp, [92, 92], 'again once it restocked (10 ticks)');
    advance(12);
    assert.deepEqual(who.xp, [92, 92, 92]);

    const attacks = [];
    npcs.push({
        getDefinition: () => ({ getName: () => 'Guard', isAttackable: () => true }),
        getLocation: () => new core.Location(3202, 3200), getSize: () => 1, getHitpoints: () => 10,
        forceChat() {}, getCombat: () => ({ getTarget: () => null, attack: (p) => attacks.push(p) }),
    });
    advance(30);
    assert.deepEqual(attacks, [who], 'caught');
    assert.equal(who.xp.length, 3, 'and it stops');
    advance(30);
    assert.equal(who.xp.length, 3);
    assert.equal(tasks.filter((task) => task.isRunning() && task.key === 3).length, 0, 'no wait left pending');
});

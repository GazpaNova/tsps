// Revenant custom: ::autothieve (plugins/skills/AutoThieving.plugin.js).
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { Server } = require('../dist/Server');
Server.installProductionPathResolver();
const { NPCOptionPacketListener } = require('../dist/net/packet/impl/NPCOptionPacketListener');
const Pickpocket = require('../plugins/skills/thieving/Pickpocket.Thieving');
const Stalls = require('../plugins/skills/thieving/Stalls.Thieving');
const AutoThieving = require('../plugins/skills/AutoThieving.plugin');

const tasks = [];
const hooks = {};
const persisted = {};
class CountdownTask {
    constructor(key, ticks, onComplete) { Object.assign(this, { key, ticks, onComplete }); }
}
AutoThieving.register({
    core: { CountdownTask, TaskManager: { submit: (task) => tasks.push(task) } },
    persistAttribute: (key, value) => { persisted[key] = value; },
    onBeforeObjectInteraction: (handler) => { hooks.before = handler; },
    onCustomEvent: (name, handler) => { hooks[name] = handler; },
    registerCommand: (name, handler) => { hooks[`::${name}`] = handler; },
});

function location(x, y, z = 0) {
    return {
        getX: () => x, getY: () => y, getZ: () => z,
        getDistance: (other) => Math.max(Math.abs(other.getX() - x), Math.abs(other.getY() - y)),
    };
}

function npc(at, actions = ['Talk-to', 'Pickpocket', null, null, 'Examine']) {
    const definition = { getName: () => 'Man', getActions: () => actions };
    return {
        at, registered: true,
        getLocation() { return this.at; }, isRegistered() { return this.registered; },
        getSize: () => 1, getIndex: () => 42, getContentId: () => 3106,
        getCurrentDefinition: () => definition,
    };
}

function player() {
    const attributes = new Map();
    const messages = [];
    return {
        at: location(3200, 3200), moving: false, messages,
        getIndex: () => 7, isRegistered: () => true, getHitpoints: () => 10,
        getLocation() { return this.at; },
        getMovementQueue() { return { isMovings: () => this.moving }; },
        getAttribute: (key) => attributes.get(key),
        setAttribute: (key, value) => attributes.set(key, value),
        sendMessage: (text) => messages.push(text),
    };
}

function command(p, raw) {
    hooks['::autothieve']({ player: p, raw, parts: raw.split(' ') });
}

function done(p, kind, succeeded, object, target) {
    const request = { player: p, kind, succeeded, again: false, object, npc: target };
    hooks['thieving:done'](request);
    return request;
}

/** Runs the next pending task (each is a one-tick countdown). */
function tick() {
    const task = tasks.shift();
    assert.ok(task, 'a task is pending');
    assert.equal(task.key, 7, "keyed by the player's index, so walking off or a new click cancels it");
    assert.equal(task.ticks, 1);
    task.onComplete();
}

test('off by default and saved with the player; ::autothieve turns it on and off', () => {
    assert.equal(persisted[AutoThieving._test.ATTRIBUTE], false);
    const p = player();
    done(p, 'pickpocket', true, undefined, npc(location(3201, 3200)));
    assert.equal(tasks.length, 0, 'off by default');
    command(p, 'autothieve');
    assert.match(p.messages.at(-1), /Auto-thieving is on/);
    command(p, 'autothieve');
    assert.equal(p.messages.at(-1), 'Auto-thieving is off.');
    command(p, 'autothieve on');
    command(p, 'autothieve on');
    assert.match(p.messages.at(-1), /is on/, 'on stays on');
    command(p, 'autothieve off');
    assert.equal(p.messages.at(-1), 'Auto-thieving is off.');
});

test('pickpocketing goes again after every success, follows an NPC that walked off, and stops when caught', (t) => {
    const p = player();
    command(p, 'autothieve on');
    const repeats = [], clicks = [];
    t.mock.method(Pickpocket._test, 'pickpocket', (event) => repeats.push(event));
    t.mock.method(NPCOptionPacketListener.prototype, 'executeOption', (...args) => clicks.push(args));
    const man = npc(location(3201, 3200));

    done(p, 'pickpocket', false, undefined, man);
    assert.equal(tasks.length, 0, 'caught: the stun ends it');

    done(p, 'pickpocket', true, undefined, man);
    tick();
    assert.equal(repeats.length, 1, 'still beside it: again on the spot');
    assert.equal(repeats[0].npc, man);
    assert.equal(repeats[0].repeat, true);
    assert.equal(repeats[0].clickType, 2, 'its Pickpocket option');
    assert.equal(repeats[0].definition.getName(), 'Man');

    done(p, 'pickpocket', true, undefined, man);
    man.at = location(3205, 3203);
    tick();
    assert.deepEqual(clicks, [[p, 42, 2]], 'walked off: a click on its Pickpocket, so the player walks after it');
    assert.equal(repeats.length, 1);

    done(p, 'pickpocket', true, undefined, man);
    man.at = location(3200 + AutoThieving._test.FOLLOW_TILES + 1, 3200);
    tick();
    done(p, 'pickpocket', true, undefined, man);
    p.at = location(3199, 3200);
    tick();
    done(p, 'pickpocket', true, undefined, man);
    man.registered = false;
    tick();
    done(p, 'pickpocket', true, undefined, npc(location(3200, 3201), ['Talk-to', null, null, null, 'Examine']));
    assert.equal(tasks.length, 0, 'no Pickpocket option');
    assert.equal(clicks.length, 1, 'too far, the player moved, or the NPC is gone: nothing');
    assert.equal(repeats.length, 1);

    const keep = done(p, 'pickpocket', true, undefined, man);
    assert.equal(keep.again, false, "Pickpocket's own repeat is left alone");
    tasks.length = 0;
});

test('stalls: waits on its tile for the stall to restock, steals again, and stops once it moves', (t) => {
    const p = player();
    command(p, 'autothieve on');
    const stall = { name: 'Bakery stall' };
    const event = { player: p, object: stall, option: 'Steal-from', handled: false };
    hooks.before(event);
    const steals = [];
    t.mock.method(Stalls._test, 'handleStealFromStall', (e) => steals.push(e));
    Stalls._test.depleted.set(stall, 0);
    try {
        done(p, 'stall', true, stall);
        tick();
        tick();
        assert.equal(steals.length, 0, 'still empty');
        Stalls._test.depleted.delete(stall);
        tick();
        assert.equal(steals.length, 1);
        assert.equal(steals[0].object, stall);
        assert.equal(steals[0].repeat, true);
        assert.deepEqual(steals[0].from, { x: 3200, y: 3200, z: 0 });
        assert.equal(tasks.length, 0);

        // The next theft's "thieving:done" waits again; walking off ends it.
        Stalls._test.depleted.set(stall, 0);
        done(p, 'stall', true, stall);
        p.at = location(3201, 3200);
        Stalls._test.depleted.delete(stall);
        tick();
        assert.equal(steals.length, 1, 'moved: no theft');
        assert.equal(tasks.length, 0);
    } finally {
        Stalls._test.depleted.delete(stall);
    }
});

test('stalls: nothing for another stall, while off, or once the wait runs out', (t) => {
    const p = player();
    const stall = { name: 'Silk stall' };
    const steals = [];
    t.mock.method(Stalls._test, 'handleStealFromStall', (e) => steals.push(e));
    hooks.before({ player: p, object: stall, option: 'Steal-from' });
    done(p, 'stall', true, stall);
    assert.equal(tasks.length, 0, 'off: not even remembered');

    command(p, 'autothieve on');
    hooks.before({ player: p, object: stall, option: 'Steal-from' });
    done(p, 'stall', true, { name: 'Gem stall' });
    assert.equal(tasks.length, 0, 'another stall');
    hooks.before({ player: p, object: { name: 'Gem stall' }, option: 'Talk-to' });
    done(p, 'stall', true, stall);
    assert.equal(tasks.length, 1, 'other options are not remembered');

    Stalls._test.depleted.set(stall, 0);
    try {
        for (let i = 0; i < AutoThieving._test.MAX_WAIT_TICKS; i++) tick();
        assert.equal(tasks.length, 0, 'gave up');
        assert.equal(steals.length, 0);
    } finally {
        Stalls._test.depleted.delete(stall);
    }
});

// Voidburn tests for farming-redwood.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
// Load independent timer state without registering another gameplay plugin or adding test exports.
function voidburnTreeRules() {
    const Module = require('node:module');
    const fs = require('node:fs');
    const filename = require.resolve('../plugins/skills/Woodcutting.plugin');
    const isolated = new Module(filename, module);
    isolated.filename = filename;
    isolated.paths = Module._nodeModulePaths(path.dirname(filename));
    isolated._compile(fs.readFileSync(filename, 'utf8') + '\nmodule.exports.lifetimeChecks = { shouldDepleteTree, tickTreeDespawnTimer, regenerateTreeDespawnTimers };', filename);
    return isolated.exports.lifetimeChecks;
}

test('Voidburn: Voidburn regular trees fall on the first log while other resource trees keep their extended lifetime', () => {
    const rules = voidburnTreeRules();
    const Woodcutting = require('../plugins/skills/Woodcutting.plugin');
    const cases = [
        [Woodcutting.TREES.find(tree => tree.name === 'normal tree'), 1760],
        [Woodcutting.TREES.find(tree => tree.name === 'achey tree'), 176],
        [{ logId: 1, multi: false }, 176],
        [{ logId: 1, multi: true, depleteChance: 16 }, 11],
        [{ logId: 1, multi: true, depleteChance: 11 }, 16],
        [{ logId: 1, multi: true }, 22],
        [{ logId: -1, multi: false }, 1760],
        [{ logId: 1, multi: true, depleteChance: 10, interruptMessage: 'spider' }, 176],
        [{ logId: 1, multi: true, depletes: false }, 0],
    ];
    const original = Math.random;
    try {
        for (const [tree, expected] of cases) {
            let falls = 0;
            for (let i = 0; i < 1760; i++) {
                Math.random = () => (i + 0.5) / 1760;
                if (rules.shouldDepleteTree({ tree })) falls++;
            }
            assert.equal(falls, expected, JSON.stringify(tree));
        }
    } finally { Math.random = original; }
});

test('Voidburn: Voidburn timed trees last ten times longer and share one countdown across choppers', () => {
    const rules = voidburnTreeRules();
    const state = { tree: { logId: 1, multi: true, despawnTicks: 45 }, objectId: 1, location: new core.Location(3200, 3200, 0) };
    for (let tick = 1; tick <= 449; tick++) {
        rules.tickTreeDespawnTimer(state, tick);
        rules.tickTreeDespawnTimer(state, tick);
        assert.equal(rules.shouldDepleteTree(state), false, `early fall at tick ${tick}`);
    }
    rules.tickTreeDespawnTimer(state, 450);
    assert.equal(rules.shouldDepleteTree(state), true);
    for (let tick = 451; tick <= 900; tick++) rules.regenerateTreeDespawnTimers(tick);
    rules.tickTreeDespawnTimer(state, 901);
    assert.equal(rules.shouldDepleteTree(state), false, 'idle tree recovers its complete extended lifetime');
});

// Voidburn tests for farming-model.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
// Deployment growth-rate checks; preserve standard behavior for the existing cases above.
try {
    Model.setGrowthMultiplier(10);
    assert.equal(Model.growthDuration(20), 2 * Model.MINUTE);
    const boundary = Date.UTC(2026, 8, 30);
    const fast = planted();
    const key = Data.patchKey(herb);
    fast.patches[key].protected = true;
    const crop = Data.CROPS.get('RANARR');
    const first = fast.patches[key].nextAt;
    const complete = first + (crop.stages - 1) * Model.growthDuration(crop.minutes);
    const offline = JSON.parse(JSON.stringify(fast));
    Model.advanceFarm(fast, complete - 1, () => 1);
    assert.notEqual(fast.patches[key].status, 'grown', 'not ripe before final accelerated stage');
    Model.advanceFarm(fast, complete, () => 1);
    Model.advanceFarm(offline, complete, () => 1);
    assert.equal(fast.patches[key].status, 'grown');
    assert.deepEqual(fast, offline, '10x online/offline catch-up matches');
    const weeds = Model.emptyPatch(boundary, {offset:0,patches:{}});
    assert.equal(weeds.nextAt - boundary, 5 * Model.MINUTE, 'weeds stay at normal speed');
    const tithe = {stage:0,nextAt:boundary,watered:true,fertilized:false};
    Model.advanceTithe(tithe,boundary);
    assert.equal(tithe.nextAt-boundary,Model.MINUTE,'Tithe Farm remains unchanged');
    const existing = {patches:{herb:{crop:'RANARR',status:'growing',nextAt:boundary+20*Model.MINUTE}}};
    Model.applyGrowthRate(existing,boundary);
    assert.equal(existing.patches.herb.nextAt,boundary+2*Model.MINUTE);
    Model.applyGrowthRate(existing,boundary);
    assert.equal(existing.patches.herb.nextAt,boundary+2*Model.MINUTE,'remaining time scales only once');
    Model.setGrowthMultiplier(1);
    Model.applyGrowthRate(existing,boundary);
    assert.equal(existing.patches.herb.nextAt,boundary+20*Model.MINUTE,'changing back preserves remaining growth work');
    console.log('10x farming growth, existing deadlines, offline growth, weeds and Tithe checks passed');
} finally { Model.setGrowthMultiplier(1); }

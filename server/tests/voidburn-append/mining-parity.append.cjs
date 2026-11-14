// Voidburn tests for mining-parity.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test("Voidburn: Revenant persistent mining gathers repeatedly from one rock and stops for movement or a full inventory", () => {
  let keep;
  require('../plugins/world/PersistentMining.plugin').register({onCustomEvent(name,handler){
    assert.equal(name,'mining:deplete-rock'); keep=handler;
  }});
  const h=harness({emit(name,event){ if(name==='mining:deplete-rock') keep(event); }});
  const player=createPlayer();
  const originalRandom=Math.random;
  Math.random=()=>0;
  const mined=mineRock(h,player,'Iron rocks',loc('ironrock2'));
  try {
    h.tick(25);
    assert.ok(player.added.length >= 5,'one click gives multiple successful ores');
    assert.ok(Mining.isMiningActive(player));
    assert.deepEqual(h.registered,[],'no depleted replacement or respawn');
    assert.ok(player.added.every(([id,amount])=>id===ItemIds.IRON_ORE && amount===1));
    assert.ok(player.xp.every(([,amount])=>amount===35),'base XP unchanged; world rate applies in SkillManager');
    const count=player.added.length;
    player.getMovementQueue=()=>({size:()=>1});
    h.tick(5);
    assert.equal(Mining.isMiningActive(player),false);
    assert.equal(player.added.length,count);
  } finally { mined.restore(); Math.random=originalRandom; }

  const h2=harness({emit(name,event){ if(name==='mining:deplete-rock') keep(event); }});
  const p2=createPlayer();
  let full=false;
  const inventory=p2.getInventory();
  inventory.isFull=()=>full;
  p2.getInventory=()=>inventory;
  Math.random=()=>0;
  const mined2=mineRock(h2,p2,'Iron rocks',loc('ironrock1'));
  try {
    h2.tick(10);
    assert.ok(p2.added.length>0);
    const count=p2.added.length;
    full=true;
    h2.tick(5);
    assert.equal(Mining.isMiningActive(p2),false);
    assert.equal(p2.added.length,count);
  } finally { mined2.restore(); Math.random=originalRandom; }
});

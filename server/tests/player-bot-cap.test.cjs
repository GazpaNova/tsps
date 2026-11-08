// Run after `yarn build`: node --test tests/player-bot-cap.test.cjs
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { MobileList } = require('../dist/game/entity/impl/MobileList');

function stubMobile() {
  return {
    registered: false,
    index: -1,
    isRegistered() { return this.registered; },
    setRegistered(value) { this.registered = value; },
    getIndex() { return this.index; },
    setIndex(value) { this.index = value; },
    onAdd() {},
    onRemove() {},
  };
}

test('bots bypass a host-imposed human player cap while capacity still bounds them', () => {
  // The browser host overrides isFull() to cap human logins only. Bots share the list,
  // so without a bypass they are dumped from the add queue once humans reach the cap.
  const list = new MobileList(16);
  let humans = 0;
  list.isFull = () => humans >= 2;
  humans = 2;
  assert.equal(list.add(stubMobile()), false, 'humans respect the cap');
  assert.equal(list.add(stubMobile(), true), true, 'bots bypass the human cap');
  assert.equal(list.sizeReturn(), 1, 'the bot was actually registered');

  // A bypass must not let the list grow past its real slot capacity.
  const tiny = new MobileList(2);
  tiny.isFull = () => true;
  assert.equal(tiny.add(stubMobile(), true), true);
  assert.equal(tiny.add(stubMobile(), true), true);
  assert.equal(tiny.add(stubMobile(), true), false, 'capacity still bounds bots');
});

test('the global no-bots switch also blocks direct minigame bot creation', () => {
  const { Server } = require('../dist/Server');
  Server.installProductionPathResolver();
  const { createBotPlayer } = require('../plugins/bots/behaviours/spawn/PlayerFactory.PlayerBots');
  const original = process.env.DISABLE_PLAYER_BOTS;
  try {
    process.env.DISABLE_PLAYER_BOTS = '1';
    const options = new Proxy({}, { get() { assert.fail('disabled bot touched player/persistence setup'); } });
    assert.equal(createBotPlayer('NoBots', null, options), null);
  } finally {
    if (original === undefined) delete process.env.DISABLE_PLAYER_BOTS;
    else process.env.DISABLE_PLAYER_BOTS = original;
  }
});

test('restricted bot creation allows only explicitly approved minigame activities', () => {
  const { Server } = require('../dist/Server');
  Server.installProductionPathResolver();
  const { botCreationAllowed, createBotPlayer } = require('../plugins/bots/behaviours/spawn/PlayerFactory.PlayerBots');
  const original = process.env.DISABLE_PLAYER_BOTS;
  try {
    process.env.DISABLE_PLAYER_BOTS = '0';
    const api = { getPluginConfig: () => ['last-man-standing', 'bounty-hunter'] };
    for (const activity of [undefined, 'pvp', 'wilderness', 'castle-wars', 'soul-wars', 'woodcutting']) {
      assert.equal(botCreationAllowed({ api, activity }), false, String(activity));
      assert.equal(createBotPlayer('BlockedBot', null, { api, activity }), null);
    }
    for (const activity of ['last-man-standing', 'bounty-hunter']) assert.equal(botCreationAllowed({ api, activity }), true);
    assert.equal(botCreationAllowed({ api: { getPluginConfig: () => [] }, activity: 'last-man-standing' }), false);
    process.env.DISABLE_PLAYER_BOTS = '1';
    assert.equal(botCreationAllowed({ api, activity: 'last-man-standing' }), false, 'global shutdown still overrides exceptions');
  } finally {
    if (original === undefined) delete process.env.DISABLE_PLAYER_BOTS;
    else process.env.DISABLE_PLAYER_BOTS = original;
  }
});

test('restricted bot runtime never registers world-site spawners', () => {
  const { startBotSites } = require('../plugins/bots/brain/SiteSpawner.PlayerBots');
  const api = { getPluginConfig: () => ['last-man-standing', 'bounty-hunter'] };
  startBotSites({ api, get runtime() { assert.fail('world-site setup must be skipped'); } });
});

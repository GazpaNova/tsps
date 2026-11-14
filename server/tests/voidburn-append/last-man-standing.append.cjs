// Voidburn tests for last-man-standing.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
test('Voidburn: LMS requests only its approved bot activity and holds bots in the waiting area', (t) => {
  const Bots = require('../plugins/minigames/lms/Bots.LastManStanding');
  const Lobby = require('../plugins/minigames/lms/Lobby.LastManStanding');
  const { setActiveBotRuntime } = require('../plugins/bots/runtime/RuntimeRegistry.PlayerBots');
  let request, blocked = false;
  const bot = { setAttribute() {}, getMovementQueue: () => ({ setBlockMovement: value => { blocked = value; } }) };
  setActiveBotRuntime({ spawnPvpBot(at, options) { request = options; return bot; } }, { PVP: 'pvp' });
  t.after(() => setActiveBotRuntime(null, null));
  t.mock.method(Lobby, 'follow', () => {});
  t.mock.method(Stash, 'save', () => {});
  assert.equal(Bots.spawnBot(new core.Location(3140, 3630)), bot);
  assert.equal(request.activity, 'last-man-standing');
  assert.equal(blocked, true);
});

test('Voidburn: LMS points earned are x4 through RevenantMinigameRewards, never spending', () => {
  const handlers = new Map();
  require('../plugins/world/RevenantMinigameRewards.plugin').register({ onCustomEvent: (name, fn) => handlers.set(name, fn) });
  const award = (points) => { const request = { minigame: 'last-man-standing', points }; handlers.get('minigame:points')(request); return request.points; };
  assert.equal(award(6), 24);
  assert.equal(award(0), 0);
  assert.equal(award(-5), -5, 'spending is never multiplied');
});

// Voidburn tests for chat-channel.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
function defaultGlobalLogin() {
  let login;
  require('../plugins/world/VoidburnGlobalChat.plugin').register({
    persistAttribute() {},
    onCustomEvent() {},
    onPlayerLogin(handler) { login = handler; },
    emitCustomEvent(name, request) {
      assert.equal(name, 'friends-chat:join');
      request.joined = Manager.join(request.player, request.owner, request.notify);
    },
  });
  return login;
}

test('Voidburn: Voidburn Friends Chat achievements have a black-bracketed blue prefix', () => {
  const achiever = player('Achiever');
  const listener = player('Listener');
  assert.equal(Manager.join(achiever, 'Voidburn', false), true);
  assert.equal(Manager.join(listener, 'Voidburn', false), true);
  Manager.broadcastAchievement(achiever, 'Achiever has completed a quest.');
  assert.match(listener.lines.at(-1), /<col=000000>\[<col=0000ff>Voidburn<col=000000>\]<\/col> Achiever has completed a quest\./);
});

test('Voidburn: Voidburn Global exists without an owner account and ordinary names confer no moderation', () => {
  const member = player('Member'), imposter = player('Voidburn');
  assert.equal(Manager.join(member, 'Voidburn', true), true);
  assert.equal(Manager.channelOf(member).name, 'Voidburn');
  assert.match(member.texts(), /Welcome to Voidburn Global/);
  Manager.join(imposter, 'Voidburn', false);
  assert.equal(Manager.memberRank(Manager.channels.get('voidburn').profile, imposter), FriendsChatRank.Unranked);
  Manager.kick(imposter, 'Member');
  assert.ok(Manager.channelOf(member));
  Manager.refreshOwnedChannel(imposter);
  assert.equal(Manager.channelOf(member).name, 'Voidburn');
});

test('Voidburn: new players join Global once, may leave for a raid, and keep that choice on relog', () => {
  const login = defaultGlobalLogin(), member = player('Newcomer'), raidOwner = player('RaidOwner');
  member.getCreationDate = () => new Date('2099-01-01');
  login({player: member});
  assert.equal(Manager.channelOf(member).name, 'Voidburn');
  Manager.leave(member, false);
  login({player: member});
  assert.equal(Manager.channelOf(member), null, 'leaving is remembered');
  Manager.setOwnChannelName(raidOwner, 'Raid');
  Manager.join(member, 'RaidOwner', false);
  Manager.onLogout(member);
  login({player: member});
  Manager.onLogin(member);
  assert.equal(Manager.channelOf(member).name, 'Raid');
  Manager.leave(member, false);
  assert.equal(Manager.join(member, 'Voidburn', true), true, 'manual rejoin works');
});

test('Voidburn: existing players and saved raid choices are never replaced by the default', () => {
  const login = defaultGlobalLogin(), veteran = player('Veteran'), member = player('NewRaider'), owner = player('RaidOwner');
  veteran.getCreationDate = () => new Date('2000-01-01');
  login({player: veteran});
  assert.equal(Manager.channelOf(veteran), null);
  member.getCreationDate = () => new Date('2099-01-01');
  Manager.setOwnChannelName(owner, 'Raid');
  member.getRelations().setFriendsChatLastOwner('RaidOwner');
  login({player: member});
  Manager.onLogin(member);
  assert.equal(Manager.channelOf(member).name, 'Raid');
});

test('Voidburn: Global retains staff kicks, temporary bans, and the saved Global rejoin', () => {
  const staff = player('Moderator'), member = player('Member');
  staff.getRights = () => ({getId: () => 1});
  Manager.join(staff, 'Voidburn', false);
  Manager.join(member, 'Voidburn', false);
  Manager.onLogout(member); Manager.onLogin(member);
  assert.equal(Manager.channelOf(member).name, 'Voidburn');
  Manager.kick(staff, 'Member');
  assert.equal(Manager.channelOf(member), null);
  assert.equal(Manager.join(member, 'Voidburn', false), false);
});

test('Voidburn: an account named Voidburn cannot rename, restrict, disable, or remove Global members', () => {
  const imposter = player('Voidburn'), member = player('Member');
  Manager.join(member, 'Voidburn', false);
  Manager.setOwnChannelName(imposter, 'Hijacked');
  Manager.changeSetup(imposter, relations => relations.setFriendsChatRanks(7, 7, 7));
  Manager.disableOwnChannel(imposter);
  Manager.handleRelationAction(imposter, {action: 'add_ignore', name: 'Member'});
  runTicks(101);
  assert.equal(Manager.channelOf(member).name, 'Voidburn');
  assert.equal(Manager.channels.get('voidburn').profile.entryRank, -1);
  assert.equal(Manager.pendingSetups.size, 0);
});

test('Voidburn: Voidburn welcomes new humans once in teal chat and native popups, excluding existing characters and bots', () => {
  const plugin = require('../plugins/world/VoidburnWelcome.plugin');
  const config = require('../plugins/world/data/voidburn-welcome.json');
  const cutoff = Date.parse(config.createdAfter);
  let handler;
  const popups = [], saves = [], defaults = [];
  const api = {
    core: { World, GameConstants: { PLAYER_PERSISTENCE: { requestSave: (p) => saves.push(p) } } },
    persistAttribute: (...args) => defaults.push(args),
    onCustomEvent: (name, callback) => { assert.equal(name, 'player:bootstrap-complete'); handler = callback; },
    emitCustomEvent: (name, request) => { assert.equal(name, 'notification:show'); popups.push(request); },
  };
  plugin.register(api);
  const newcomer = player('New Friend');
  newcomer.getCreationDate = () => new Date(cutoff + 1);
  newcomer.getProfile = () => 'main';
  const existing = player('Existing');
  existing.getCreationDate = () => new Date(cutoff - 1);
  const bot = player('Bot');
  bot.isPlayerBot = () => true;
  handler({ player: existing });
  handler({ player: bot });
  assert.equal(popups.length, 0, 'no old-account or bot announcement');
  handler({ player: newcomer });
  assert.match(newcomer.texts(), /<col=00d3a6>Welcome New Friend to Voidburn!<\/col>/);
  assert.match(existing.texts(), /Welcome New Friend/);
  assert.equal(bot.lines.length, 0, 'bots are not recipients');
  assert.equal(popups.length, 2);
  assert.equal(popups[0].colour, 0x00d3a6);
  assert.match(popups[0].text, /Welcome New Friend/);
  handler({ player: newcomer });
  assert.equal(popups.length, 2, 'bootstrap replay/relogin cannot announce twice');
  assert.equal(saves.length, 2, 'old and new characters have their considered flags saved');
  assert.deepEqual(defaults, [['voidburn-welcome:announced', false]]);
  const league = player('League Profile');
  league.getCreationDate = () => new Date(cutoff + 1);
  league.getProfile = () => 'league';
  handler({ player: league });
  assert.equal(popups.length, 2, 'a second profile does not count as a new main character');
});

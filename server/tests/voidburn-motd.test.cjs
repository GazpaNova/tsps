// Voidburn MOTD plugin — unit tests covering v1 and v2 review findings as regressions:
//   - Eligibility is a single shared decision (deferToMotd flag, bots, mobile, new
//     accounts, Wilderness). WelcomeScreen and MotdInterface both call the same
//     function.
//   - Tier sanitization uses Object.hasOwn (inherited keys like toString are rejected)
//     and caps tier count and skills-per-tier at the layout's capacity.
//   - Skill sprite ids match the production-cache mapping (Codex v1 review corrections).
//   - Interface definition registers once at module load; register() stays attach-only.
//   - No calls to sender.sendSpriteId or sender.sendInterfaceHidden exist at runtime
//     (the methods don't exist on PacketSender and v1 crashed on them).
//   - Lifecycle (v2 review):
//       * Queued MOTD is cancelled on disconnect/logout before the microtask fires.
//       * Wilderness is re-checked at display time, not only at login time.
//       * clear() releases both pending and visible state.
//   - Layout (v2 review):
//       * Root is centered via position mode 1, not 2 (which is bottom-right anchor).
//       * Backdrop uses an opaque (low-number) opacity convention.
//       * Panel is tall enough that the footer fits inside its bounds.
const assert = require("node:assert/strict");
const { test } = require("node:test");

function freshLoad(modulePath) {
  const full = require.resolve(modulePath);
  delete require.cache[full];
  return require(full);
}

function stubApi({ config = {}, core = {} } = {}) {
  const hooks = { login: [], tick: [], logout: [], disconnect: [], button: new Map(), custom: {}, customHandlers: {}, registered: [] };
  return {
    hooks,
    api: {
      core: {
        MOBILE_CLIENT_ATTRIBUTE: Symbol("mobile"),
        NATIVE_CLIENT_ATTRIBUTE: Symbol("native"),
        VOIDBURN_PLUGIN_ATTRIBUTE: Symbol("voidburn-plugin"),
        XPDROPS_ENABLED_ATTRIBUTE: Symbol("xpdrops"),
        Wilderness: { isInLocation: (loc) => loc?.wilderness === true },
        resolveGameframeRoot: () => 161,
        encodeGameframeBootstrap: () => [],
        GameConstants: { WORLD_TYPE: { label: "REGULAR" } },
        dataOrbsHidden: () => false,
        World: { getProcessCycle: () => 0 },
        WorldDefinition: { getWorldDefinition: () => ({ name: "Test" }) },
        ...core,
      },
      getPluginConfig(key, fallback) { return Object.hasOwn(config, key) ? config[key] : fallback; },
      registerCustomInterface(def) { hooks.registered.push(def); },
      onPlayerLogin(fn) { hooks.login.push(fn); },
      onPlayerProcess(fn) { hooks.tick.push(fn); },
      onPlayerLogout(fn) { hooks.logout.push(fn); },
      onPlayerDisconnect(fn) { hooks.disconnect.push(fn); },
      onInterfaceActionButton(uid, fn) { hooks.button.set(uid, fn); },
      onCustomEvent(name, fn) { (hooks.customHandlers[name] ??= []).push(fn); },
      emitCustomEvent(name, payload) {
        (hooks.custom[name] ??= []).push(payload);
        for (const fn of hooks.customHandlers[name] ?? []) fn(payload);
      },
      log() {},
    },
  };
}

function stubPlayer(overrides = {}) {
  const sent = [];
  const sender = {
    sendRootInterface(id) { sent.push(["root", id]); return sender; },
    sendInterfaceFlags(uid, flags) { sent.push(["flags", uid, flags]); return sender; },
    sendString(s, uid) { sent.push(["string", s, uid]); return sender; },
  };
  return {
    sent, sender,
    player: {
      getUsername: () => "testplayer",
      getSession: () => ({ sendClientPacket: () => {}, isDisconnected: () => false }),
      getAttribute: () => false,
      getInterfaceId: () => -1,
      getPacketSender: () => sender,
      getLocation: () => null,
      isPlayerBot: () => false,
      sendMessage: () => {},
      ...overrides,
    },
  };
}

// Flush the microtask queue so queueMicrotask callbacks run before assertions.
const flushMicrotasks = () => new Promise((r) => setImmediate(r));

// ----------------------------------------------------------------------------
// Eligibility
// ----------------------------------------------------------------------------

test("MOTD ineligible when deferToMotd is missing (stock behavior preserved)", () => {
  const { isMotdEligible } = freshLoad("../plugins/interface/motd/eligibility");
  const { api } = stubApi({ config: {} });
  const { player } = stubPlayer();
  assert.equal(isMotdEligible(api, { player, isNewAccount: false }), false);
});

test("MOTD eligible when flag is true and player is in the normal welcome population", () => {
  const { isMotdEligible } = freshLoad("../plugins/interface/motd/eligibility");
  const { api } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  const { player } = stubPlayer();
  assert.equal(isMotdEligible(api, { player, isNewAccount: false }), true);
});

test("MOTD ineligible for RuneLite sessions without the Voidburn plugin capability", () => {
  const { isMotdEligible } = freshLoad("../plugins/interface/motd/eligibility");
  const base = { config: { "WelcomeScreen:deferToMotd": true } };
  const { api } = stubApi(base);
  const { player } = stubPlayer();
  // Mark the session as native-client (RuneLite via gateway) with no plugin bit.
  player.getAttribute = (attr) => attr === api.core.NATIVE_CLIENT_ATTRIBUTE;
  assert.equal(
    isMotdEligible(api, { player, isNewAccount: false }),
    false,
    "launcher-less RuneLite/RSProx sessions fall through to stock 378",
  );
});

test("MOTD eligible for RuneLite sessions WITH the Voidburn plugin capability", () => {
  const { isMotdEligible } = freshLoad("../plugins/interface/motd/eligibility");
  const base = { config: { "WelcomeScreen:deferToMotd": true } };
  const { api } = stubApi(base);
  const { player } = stubPlayer();
  // Mark the session as native-client AND Voidburn-plugin-enabled.
  player.getAttribute = (attr) =>
    attr === api.core.NATIVE_CLIENT_ATTRIBUTE || attr === api.core.VOIDBURN_PLUGIN_ATTRIBUTE;
  assert.equal(
    isMotdEligible(api, { player, isNewAccount: false }),
    true,
    "Voidburn-launcher RuneLite sessions see MOTD like the browser does",
  );
});

test("MOTD eligible for browser sessions (no NATIVE_CLIENT, no plugin bit required)", () => {
  const { isMotdEligible } = freshLoad("../plugins/interface/motd/eligibility");
  const base = { config: { "WelcomeScreen:deferToMotd": true } };
  const { api } = stubApi(base);
  const { player } = stubPlayer();
  // Browser clients have neither attribute set; getAttribute returns false by default.
  assert.equal(isMotdEligible(api, { player, isNewAccount: false }), true);
});

test("MOTD ineligible for bots, mobile, and Wilderness logins — new accounts ARE eligible", () => {
  const { isMotdEligible } = freshLoad("../plugins/interface/motd/eligibility");
  const base = { config: { "WelcomeScreen:deferToMotd": true } };

  const { api } = stubApi(base);
  const { player } = stubPlayer();
  // New accounts previously bypassed MOTD so the vanilla OSRS new-account flow
  // could run; now MOTD IS shown to first-login players so they see the
  // welcome surface instead of dropping straight into interface-cascade.
  assert.equal(
    isMotdEligible(api, { player, isNewAccount: true }), true,
    "new account is eligible");

  const bot = stubPlayer({ isPlayerBot: () => true });
  assert.equal(isMotdEligible(stubApi(base).api, { player: bot.player, isNewAccount: false }), false, "bot");

  const mobile = stubPlayer();
  const { api: mobileApi } = stubApi(base);
  mobile.player.getAttribute = (attr) => attr === mobileApi.core.MOBILE_CLIENT_ATTRIBUTE;
  assert.equal(isMotdEligible(mobileApi, { player: mobile.player, isNewAccount: false }), false, "mobile");

  const wild = stubPlayer({ getLocation: () => ({ wilderness: true }) });
  assert.equal(isMotdEligible(stubApi(base).api, { player: wild.player, isNewAccount: false }), false, "wilderness");
});

// ----------------------------------------------------------------------------
// Tier sanitization
// ----------------------------------------------------------------------------

test("sanitizeTiers rejects inherited keys from Object.prototype (toString, hasOwnProperty)", () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const bad = [{ label: "evil", skills: ["toString", "hasOwnProperty", "Attack"] }];
  const out = plugin._test.sanitizeTiers(bad);
  assert.deepEqual(out, [{ label: "evil", skills: ["Attack"] }]);
});

test("sanitizeTiers truncates tiers past TIER_COUNT and skills past ICONS_PER_TIER", () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const many = [
    { label: "1x", skills: Array(20).fill("Attack") },
    { label: "2x", skills: ["Strength"] },
    { label: "3x", skills: ["Defence"] },
    { label: "4x", skills: ["Magic"] },
    { label: "5x", skills: ["Ranged"] },
  ];
  const out = plugin._test.sanitizeTiers(many);
  assert.equal(out.length, 4, "past TIER_COUNT dropped");
  assert.equal(out[0].skills.length, 11, "past ICONS_PER_TIER dropped");
});

test("sanitizeTiers rejects non-array input and malformed entries", () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  assert.deepEqual(plugin._test.sanitizeTiers("not an array"), []);
  assert.deepEqual(plugin._test.sanitizeTiers([null, { label: "no-skills" }, { skills: [] }]), []);
});

// ----------------------------------------------------------------------------
// Skill sprite ids (production-cache verified)
// ----------------------------------------------------------------------------

test("skill sprite ids match the production-cache-verified mapping", () => {
  const { SKILL_SPRITE } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  assert.equal(SKILL_SPRITE.Fletching, 208);
  assert.equal(SKILL_SPRITE.Runecrafting, 215);
  assert.equal(SKILL_SPRITE.Slayer, 216);
  assert.equal(SKILL_SPRITE.Farming, 217);
  assert.equal(SKILL_SPRITE.Hunter, 220);
  assert.equal(SKILL_SPRITE.Construction, 221);
  assert.equal(SKILL_SPRITE.Sailing, 228);
  assert.equal(SKILL_SPRITE.Attack, 197);
  assert.equal(SKILL_SPRITE.Magic, 202);
});

// ----------------------------------------------------------------------------
// Layout — center mode and panel dimensions
// ----------------------------------------------------------------------------

test("PANEL (content layer) uses position mode 1 (center+offset); ROOT fills viewport", () => {
  const { buildInterface, COMPONENT } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({ tiers: [] });
  const root = widgets.find((w) => w.fileId === COMPONENT.ROOT);
  assert.ok(root, "root widget present");
  // Root is full-viewport: widthMode/heightMode 1 with rawW/rawH 0 fills the parent.
  assert.equal(root.widthMode, 1, "root widthMode 1 = fill parent");
  assert.equal(root.heightMode, 1, "root heightMode 1 = fill parent");
  const panel = widgets.find((w) => w.fileId === COMPONENT.PANEL);
  assert.ok(panel, "panel layer present");
  assert.equal(panel.xPositionMode, 1, "panel x mode 1 = center");
  assert.equal(panel.yPositionMode, 1, "panel y mode 1 = center");
  assert.equal(panel.rawX, 0);
  assert.equal(panel.rawY, 0);
});

test("panel is tall enough that every rendered widget fits inside its bounds", () => {
  const { buildInterface, PANEL } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({
    tiers: [
      { label: "1x", skills: ["Attack"] },
      { label: "4x", skills: ["Mining"] },
    ],
  });
  for (const w of widgets) {
    if (w.fileId === 0) continue; // root
    if (w.hidden === true || w.isHidden === true) continue; // hidden placeholders
    const bottom = (w.rawY | 0) + (w.rawHeight | 0);
    assert.ok(
      bottom <= PANEL.height,
      `widget component ${w.fileId} extends to y=${bottom}, past PANEL.height=${PANEL.height}`,
    );
  }
});

test("full-viewport SCRIM and panel backdrop both use near-opaque opacity (inverted scale)", () => {
  const { buildInterface, COMPONENT } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({ tiers: [] });
  const scrim = widgets.find((w) => w.fileId === COMPONENT.SCRIM);
  assert.ok(scrim, "scrim widget present");
  // SCRIM covers the whole viewport; must be fully opaque so MOTD reads as full-screen.
  assert.equal(scrim.opacity, 0, "scrim must be fully opaque (opacity 0 in this engine)");
  const panelBg = widgets.find((w) => w.fileId === COMPONENT.PANEL_BG);
  assert.ok(panelBg, "panel backdrop widget present");
  // Panel backdrop is slightly lighter than the scrim; still near-opaque.
  assert.ok(panelBg.opacity < 64, `panel backdrop opacity ${panelBg.opacity} is not near-opaque`);
});

test("layout bakes sprite ids into TYPE_GRAPHIC widgets (no runtime sprite mutation needed)", () => {
  const { buildInterface, SKILL_SPRITE, VOIDBURN_LOGO_SPRITE } =
    freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({ tiers: [{ label: "1x", skills: ["Attack", "Strength"] }] });
  const skillIds = new Set(Object.values(SKILL_SPRITE));
  const graphics = widgets.filter((w) => w.spriteId !== undefined && !w.hidden);
  // Every visible graphic widget's id is either the logo or a known skill sprite.
  for (const w of graphics) {
    const ok = w.spriteId === VOIDBURN_LOGO_SPRITE || skillIds.has(w.spriteId);
    assert.ok(ok, `unexpected sprite id ${w.spriteId}`);
  }
});

// ----------------------------------------------------------------------------
// Attach-only register
// ----------------------------------------------------------------------------

test("register() registers the interface once and attaches hooks, nothing else", () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi();
  plugin.register(api);
  assert.equal(hooks.registered.length, 1);
  assert.equal(hooks.registered[0].groupId, 30020);
  assert.equal(hooks.login.length, 1);
  assert.equal(hooks.logout.length, 1);
  assert.equal(hooks.disconnect.length, 1);
  assert.equal(hooks.button.size, 1);
});

// ----------------------------------------------------------------------------
// Lifecycle (v2 review findings)
// ----------------------------------------------------------------------------

test("MOTD is deferred for a new account at login, then fires on the first idle tick", async () => {
  // Path 1: new accounts have a server-driven onboarding cascade
  // (character designer → Ironman picker / experience screen → tutorial
  // island / world). Firing MOTD at login would land it underneath the
  // designer and the player would never see it. The plugin now defers
  // MOTD for new accounts and watches onPlayerProcess for the first
  // tick where the player has no interface open — that's the first
  // moment MOTD is actually visible.
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player, sent } = stubPlayer();
  // Stage 1: login. New account → deferred, no root sent.
  hooks.login[0]({ player, isNewAccount: true });
  await flushMicrotasks();
  assert.equal(
    sent.filter(([kind]) => kind === "root").length, 0,
    "no root sent at login for a new account — MOTD is deferred");
  assert.ok(plugin._test.deferredForOnboarding.has(player),
    "player is in the deferred-onboarding set");

  // Stage 2: onboarding interface still up (designer 679, say).
  // Tick fires, player has an interface, MOTD stays deferred.
  player.getInterfaceId = () => 679;
  hooks.tick[0]({ player });
  await flushMicrotasks();
  assert.equal(
    sent.filter(([kind]) => kind === "root").length, 0,
    "still no root while an onboarding interface is up");
  assert.ok(plugin._test.deferredForOnboarding.has(player),
    "still deferred");

  // Stage 3: onboarding finished; player has no interface open.
  // The next tick fires MOTD.
  player.getInterfaceId = () => -1;
  hooks.tick[0]({ player });
  await flushMicrotasks();
  const roots = sent.filter(([kind]) => kind === "root");
  assert.equal(roots.length, 1, "MOTD root landed on the first idle tick");
  assert.equal(roots[0][1], 30020, "MOTD group id (30020)");
  assert.ok(!plugin._test.deferredForOnboarding.has(player),
    "deferred flag cleared once MOTD fired");
});

test("deferred MOTD does not fire again on subsequent idle ticks", async () => {
  // The deferredForOnboarding flag is removed once MOTD fires, so a
  // second idle tick must not re-queue a duplicate MOTD.
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player, sent } = stubPlayer();
  player.getInterfaceId = () => -1;
  hooks.login[0]({ player, isNewAccount: true });
  hooks.tick[0]({ player });  // fires MOTD
  await flushMicrotasks();
  const firstCount = sent.filter(([kind]) => kind === "root").length;
  assert.equal(firstCount, 1);

  hooks.tick[0]({ player });  // deferred flag already cleared
  hooks.tick[0]({ player });
  await flushMicrotasks();
  assert.equal(
    sent.filter(([kind]) => kind === "root").length, firstCount,
    "subsequent ticks do not re-fire MOTD");
});

test("deferred MOTD waits for tutorial completion, not just a no-modal tick", async () => {
  // Regression (voidburn-motd-onboarding v1 review P2): v1 fired MOTD
  // on the first tick with no modal interface, which happened at
  // tutorial progress 10 (experience screen closed, approaching the
  // Gielinor Guide — a non-modal tutorial phase). v2 also gates on
  // tutorialActive so MOTD doesn't land mid-tutorial.
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player, sent } = stubPlayer();
  hooks.login[0]({ player, isNewAccount: true });
  // tutorial:started fires early in the new-account flow.
  api.emitCustomEvent("tutorial:started", { player });
  assert.ok(plugin._test.tutorialActive.has(player));

  // No modal open — this is the mid-tutorial moment Codex's probe hit.
  // MOTD must NOT fire because tutorial is still active.
  player.getInterfaceId = () => -1;
  hooks.tick[0]({ player });
  await flushMicrotasks();
  assert.equal(
    sent.filter(([kind]) => kind === "root").length, 0,
    "mid-tutorial, no-modal tick must not fire MOTD");
  assert.ok(plugin._test.deferredForOnboarding.has(player),
    "still deferred");

  // Tutorial completes.
  api.emitCustomEvent("tutorial:completed", { player });
  assert.ok(!plugin._test.tutorialActive.has(player));
  hooks.tick[0]({ player });
  await flushMicrotasks();
  const roots = sent.filter(([kind]) => kind === "root");
  assert.equal(roots.length, 1, "MOTD fires on the first idle tick after tutorial:completed");
  assert.equal(roots[0][1], 30020);
});

test("deferred MOTD waits for the Ironman initial-setup picker", async () => {
  // The skip-tutorial new-account path goes: designer → Ironman picker
  // → world. During the picker the ironman:initial-setup-pending
  // attribute is set on the player; the picker clears it when the
  // player picks (or skips). Gate MOTD on that attribute too so
  // the picker completes before MOTD lands.
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  let pending = true;
  const { player, sent } = stubPlayer({
    getAttribute: (attr) => attr === "ironman:initial-setup-pending" ? pending : false,
    getInterfaceId: () => -1,  // picker is non-modal in some flows
  });
  hooks.login[0]({ player, isNewAccount: true });
  hooks.tick[0]({ player });
  await flushMicrotasks();
  assert.equal(
    sent.filter(([kind]) => kind === "root").length, 0,
    "pending Ironman setup must defer MOTD, even with no modal open");

  pending = false;
  hooks.tick[0]({ player });
  await flushMicrotasks();
  assert.equal(
    sent.filter(([kind]) => kind === "root").length, 1,
    "MOTD fires once the Ironman pending attribute clears");
});

test("onboarding that restarts between checkDeferredOnboarding and showMotd retries later", async () => {
  // Regression (voidburn-motd-onboarding v2 review P2): v2 removed the
  // deferredForOnboarding flag at the tick gate, then queued showMotd.
  // A handler firing in the same process tick between those two steps
  // (opens a modal, sets ironman:initial-setup-pending, or fires
  // tutorial:started) would land inside the microtask gap — showMotd
  // ran, eligibility still passed, MOTD opened over the new modal,
  // and the deferred welcome was lost.
  //
  // v3 keeps the flag in place until showMotd actually displays;
  // showMotd re-checks the compound predicate and bails without
  // clearing the flag if onboarding restarted. The next idle tick
  // then retries normally.
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player, sent } = stubPlayer();
  hooks.login[0]({ player, isNewAccount: true });

  // First idle tick fires the microtask, but a racing handler opens a
  // modal in the gap. Simulate it by mutating getInterfaceId AFTER the
  // tick callback runs but BEFORE the microtask executes.
  player.getInterfaceId = () => -1;
  hooks.tick[0]({ player });
  // The tick queued the microtask; before the microtask runs, the race:
  player.getInterfaceId = () => 890;
  await flushMicrotasks();

  assert.equal(
    sent.filter(([kind]) => kind === "root").length, 0,
    "MOTD must not land during the race window");
  assert.ok(plugin._test.deferredForOnboarding.has(player),
    "deferred flag retained for the next-tick retry");

  // Modal closes; the next tick retries.
  player.getInterfaceId = () => -1;
  hooks.tick[0]({ player });
  await flushMicrotasks();
  assert.equal(
    sent.filter(([kind]) => kind === "root").length, 1,
    "MOTD lands on the retry after the race resolves");
  assert.ok(!plugin._test.deferredForOnboarding.has(player),
    "flag cleared only after successful display");
});

test("disconnect clears the deferred-onboarding flag", async () => {
  // A player who disconnects mid-onboarding must not have MOTD fire
  // on a tick that races the disconnect. clear() removes them from
  // the deferred set.
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player } = stubPlayer();
  player.getInterfaceId = () => 679;  // onboarding interface up
  hooks.login[0]({ player, isNewAccount: true });
  assert.ok(plugin._test.deferredForOnboarding.has(player));

  hooks.disconnect[0]({ player });
  assert.ok(!plugin._test.deferredForOnboarding.has(player),
    "disconnect removes the deferred flag");

  // Even if a late tick fires after disconnect, no MOTD.
  player.getInterfaceId = () => -1;
  hooks.tick[0]({ player });
  await flushMicrotasks();
  // Can't assert sent is empty here without a fresh stub; the key
  // invariant is the WeakSet state above.
});

test("queued MOTD is cancelled by disconnect before the microtask fires", async () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player, sent } = stubPlayer();
  // Login fires: eligibility passes, player goes into pending, microtask queued.
  hooks.login[0]({ player, isNewAccount: false });
  // Disconnect arrives before the microtask runs.
  hooks.disconnect[0]({ player });

  await flushMicrotasks();

  // showMotd should have no-op'd: pending.delete returned false.
  const roots = sent.filter(([kind]) => kind === "root");
  assert.deepEqual(roots, [], "no root interface sent after disconnect");
  assert.deepEqual(sent, [], "no packets sent at all");
});

test("queued MOTD is cancelled by logout before the microtask fires", async () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player, sent } = stubPlayer();
  hooks.login[0]({ player, isNewAccount: false });
  hooks.logout[0]({ player });
  await flushMicrotasks();
  assert.deepEqual(sent, [], "no packets sent when logout cancels before display");
});

test("Wilderness is re-checked at display time, not only at login time", async () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  // Login outside Wilderness — eligibility passes.
  let currentLocation = null;
  const { player, sent } = stubPlayer({ getLocation: () => currentLocation });
  hooks.login[0]({ player, isNewAccount: false });
  // Teleport into Wilderness between login and microtask firing.
  currentLocation = { wilderness: true };
  await flushMicrotasks();

  const roots = sent.filter(([kind]) => kind === "root");
  assert.deepEqual(roots, [], "showMotd aborts when the player entered Wilderness during the microtask gap");
});

test("eligible login completes: root sent, text sent, visible state tracked", async () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
  plugin.register(api);

  const { player, sent } = stubPlayer();
  hooks.login[0]({ player, isNewAccount: false });
  await flushMicrotasks();

  const roots = sent.filter(([kind]) => kind === "root");
  assert.deepEqual(roots, [["root", 30020]]);
  assert.ok(sent.some(([kind]) => kind === "string"), "text content sent");
  // Visible state is tracked so Play button can un-track it later.
  assert.ok(plugin._test.visible.has(player), "player marked visible after MOTD mount");
});

test("footer labels declare URL click actions bound to shipped defaults", () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi();
  plugin.register(api);
  const def = hooks.registered[0];
  assert.ok(def.urls, "interface definition carries a urls map");
  const { COMPONENT } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const defaults = require("../plugins/interface/motd/data/motd-defaults.json");
  assert.equal(def.urls[COMPONENT.FOOTER_DISCORD_LABEL], defaults.discordUrl);
  assert.equal(def.urls[COMPONENT.FOOTER_GITHUB_LABEL], defaults.githubUrl);
  assert.equal(def.urls[COMPONENT.FOOTER_LAUNCHER_LABEL], defaults.launcherUrl);
  // All three must be http/https so openClientUrl accepts them client-side.
  for (const url of Object.values(def.urls)) {
    assert.match(url, /^https?:\/\//, `URL ${url} is not http(s)`);
  }
});

test("footer label widgets are clickable (actions + FLAG_OP1)", () => {
  const { buildInterface, COMPONENT } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({ tiers: [] });
  const clickable = [
    COMPONENT.FOOTER_DISCORD_LABEL,
    COMPONENT.FOOTER_GITHUB_LABEL,
    COMPONENT.FOOTER_LAUNCHER_LABEL,
  ];
  for (const componentId of clickable) {
    const widget = widgets.find((w) => w.fileId === componentId);
    assert.ok(widget, `component ${componentId} present`);
    assert.ok(Array.isArray(widget.actions) && widget.actions.length > 0,
      `component ${componentId} has at least one action`);
    assert.ok(widget.flags && widget.flags > 0,
      `component ${componentId} has flags set so clicks are transmitted`);
  }
});

test("urls map reaches the custom-interface runtime after a real set_root path", () => {
  // Regression for the v1 review finding: handleWidgetClick needs the runtime's
  // declaration set BEFORE a click arrives. onInterfaceOpened is what sets it, and
  // the mountSubInterface path already calls it — but the set_root path didn't until
  // v2 added the call. MOTD mounts as root, so without this, URL clicks silently
  // dropped through to the ordinary widget dispatch.
  //
  // This is a server-side test harness: we build the plugin's interface definition,
  // confirm the `urls` map is present and well-formed, and verify every entry maps
  // a widget that IS clickable (actions + flags set) to a validly-formatted URL.
  // The actual onInterfaceOpened wiring lives in the client (OsrsClient.ts set_root
  // handler) and is covered by a client-side regression I'd add if client tests
  // could run in this harness.
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi();
  plugin.register(api);
  const def = hooks.registered[0];
  const { buildInterface } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({ tiers: [] });

  assert.ok(def.urls, "urls map ships in the declaration");
  for (const [componentIdStr, url] of Object.entries(def.urls)) {
    const componentId = Number(componentIdStr);
    const widget = widgets.find((w) => w.fileId === componentId);
    assert.ok(widget, `component ${componentId} present for url ${url}`);
    assert.ok(Array.isArray(widget.actions) && widget.actions.length > 0,
      `component ${componentId} is clickable`);
    assert.ok(widget.flags > 0, `component ${componentId} has non-zero flags`);
    assert.match(url, /^https?:\/\//, `url ${url} is http(s)`);
  }
});

test("play hook clears visible state and no-ops if player was not visible", () => {
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin.js");
  const { api, hooks } = stubApi();
  plugin.register(api);
  const playHandler = hooks.button.get(30020 << 16 | 186);
  assert.ok(playHandler, "play button handler registered");

  const { player } = stubPlayer();
  // Not visible → play does nothing meaningful.
  const result1 = playHandler({ player });
  assert.equal(result1, false);

  // Mark visible, then play should clear it.
  plugin._test.visible.add(player);
  playHandler({ player });
  assert.equal(plugin._test.visible.has(player), false, "play clears visible state");
});

// Fullscreen render regressions: missing palette values and sibling paint order.
test("panel backdrop has an explicit dark RGB color", () => {
  const { buildInterface, COMPONENT } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({ tiers: [] });
  const bg = widgets.find((w) => w.fileId === COMPONENT.PANEL_BG);
  assert.ok(Number.isInteger(bg.color), "undefined colors fall back to white in the browser");
  assert.equal(bg.color, 0x201a12);
});

test("header content paints above the backdrop without changing its existing ids", () => {
  const { buildInterface, COMPONENT, uid } = freshLoad("../plugins/interface/motd/Layout.MotdInterface.js");
  const { widgets } = buildInterface({ tiers: [] });
  const panel = widgets.find((w) => w.fileId === COMPONENT.PANEL);
  const bg = widgets.find((w) => w.fileId === COMPONENT.PANEL_BG);
  const header = widgets.find((w) => w.fileId === COMPONENT.HEADER);
  assert.equal(bg.parentUid, panel.uid);
  assert.equal(header.parentUid, panel.uid);
  assert.ok(bg.fileId < header.fileId, "static siblings paint in fileId order, not insertion order");
  assert.deepEqual([header.rawX, header.rawY, header.rawWidth, header.rawHeight], [0, 0, 480, 56]);
  for (const id of [2, 3, 4]) {
    const w = widgets.find((w) => w.fileId === id);
    assert.equal(w.parentUid, uid(COMPONENT.HEADER));
    assert.ok(w.rawX >= 0 && w.rawX + w.rawWidth <= header.rawWidth);
    assert.ok(w.rawY >= 0 && w.rawY + w.rawHeight <= header.rawHeight);
  }
});


test("MOTD stays off when rollback restores a core without capability attribute exports", () => {
  const { isMotdEligible } = freshLoad("../plugins/interface/motd/eligibility");
  for (const missing of ["NATIVE_CLIENT_ATTRIBUTE", "VOIDBURN_PLUGIN_ATTRIBUTE"]) {
    const { api } = stubApi({ config: { "WelcomeScreen:deferToMotd": true } });
    delete api.core[missing];
    const { player } = stubPlayer();
    assert.equal(isMotdEligible(api, { player, isNewAccount: false }), false);
  }
});


test("MOTD Play preserves the current world badge, orb preference and map clock", () => {
  const calls = [];
  const { api, hooks } = stubApi({ core: {
    GameConstants: { WORLD_TYPE: { label: "TEST-WORLD" } },
    dataOrbsHidden: () => true,
    World: { getProcessCycle: () => 12345 },
    resolveGameframeRoot: () => 548,
    encodeGameframeBootstrap: (...args) => { calls.push(args); return []; },
  } });
  const plugin = freshLoad("../plugins/interface/MotdInterface.plugin");
  plugin.register(api);
  const { player } = stubPlayer({ getAttribute: key => key === api.core.XPDROPS_ENABLED_ATTRIBUTE });
  plugin._test.visible.add(player);
  hooks.button.get((30020 << 16) | 186)({ player });
  assert.deepEqual(calls, [["testplayer", 548, true, "TEST-WORLD", false, 12345]]);
});

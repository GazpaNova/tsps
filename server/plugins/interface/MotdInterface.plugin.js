/**
 * Voidburn MOTD plugin — v3.
 *
 * Shows a Voidburn-branded welcome MOTD at login in place of the vanilla 378 screen.
 * Opt-in via WelcomeScreen:deferToMotd. Eligibility is a single shared decision the
 * WelcomeScreen plugin also uses, so exactly one welcome root opens per login.
 *
 * Supported runtime configuration (world.local.json Motd:* keys — all content strings):
 *   Motd:title, Motd:tagline
 *   Motd:aboutHeading, Motd:aboutBody
 *   Motd:ratesHeading
 *   Motd:changesHeading, Motd:changesBody
 *   Motd:whatsNewHeading, Motd:whatsNewBody
 *   Motd:discordUrl, Motd:githubUrl, Motd:launcherUrl
 *   Motd:playButtonLabel
 *
 * Not configurable at runtime (intentionally):
 *   - Tier structure (which skills are on which rate row) — lives in
 *     motd-defaults.json. Change it there + restart.
 *   - Logo sprite id — always -1000, resolved to a client sprite override served at
 *     /voidburn/logo.png. To change the logo image: replace that PNG file.
 *
 * v1/v2 history: v1 called sender.sendSpriteId and sender.sendInterfaceHidden which
 * don't exist on PacketSender and crashed every login. v2 fixed the crashes but used
 * position mode 2 (anchors to bottom-right, not center) and opacity 230 (near-fully
 * transparent, not dark), and advertised ratesTiers/logoSpriteId overrides it never
 * wired up. v3 fixes the layout math by reading alignPosition rather than guessing,
 * drops the misleading override contract, and restores explicit per-login
 * cancellation so a queued MOTD can't fire into a disconnected or logged-out session.
 */
const {
  GROUP_ID, PLAY_BUTTON_UID, SKILL_SPRITE,
  TIER_COUNT, ICONS_PER_TIER, COMPONENT, uid, buildInterface,
} = require("./motd/Layout.MotdInterface");
const { isMotdEligible } = require("./motd/eligibility");

const DEFAULTS = Object.freeze(require("./motd/data/motd-defaults.json"));
const CONFIG_PREFIX = "Motd";
const FIRST_OPTION_FLAG = 1 << 1;

const pending = new WeakSet();
const visible = new WeakSet();
/**
 * New accounts whose MOTD has been deferred until their onboarding
 * cascade finishes. Entries are added at login (isNewAccount=true,
 * passing eligibility) and removed either when onPlayerProcess fires
 * MOTD, or when the player disconnects/logs out.
 *
 * "Onboarding complete" = NOT in tutorial AND no pending initial
 * Ironman mode choice AND no modal interface open. See
 * {@link isOnboardingActive} for the exact definition. The first
 * server-process tick at which that compound predicate flips to
 * false fires MOTD. A bare no-modal check is not enough because
 * Tutorial Island has long-lived non-modal phases between steps;
 * see the v1 review's regression probe.
 */
const deferredForOnboarding = new WeakSet();

/**
 * Players for whom {@code tutorial:started} has fired and
 * {@code tutorial:completed} has not. Used by
 * {@link isOnboardingActive} to keep MOTD off the screen for the
 * whole tutorial — including its many stretches with no modal
 * interface up — not just its modal moments.
 */
const tutorialActive = new WeakSet();

/**
 * Player attribute set by the Ironman onboarding plugin while the
 * initial account-mode choice is pending. We read it here so MOTD
 * waits for the picker to resolve on the non-tutorial new-account
 * track, not just for whatever modal it happens to open.
 */
const IRONMAN_SETUP_PENDING = "ironman:initial-setup-pending";

let api = null;

/**
 * Sanitize a tier array against the layout's capacity. Used at module load against
 * the shipped defaults; also exposed for tests. Rejects:
 *   - non-array top level
 *   - non-object entries, entries without a string label, entries whose skills field
 *     isn't an array
 *   - skill names that aren't own properties of SKILL_SPRITE (Object.hasOwn — so
 *     inherited keys like "toString" never pass)
 *   - entries past TIER_COUNT and skills past ICONS_PER_TIER (truncated, not padded)
 */
function sanitizeTiers(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const tier of raw.slice(0, TIER_COUNT)) {
    if (!tier || typeof tier.label !== "string" || !Array.isArray(tier.skills)) continue;
    const skills = [];
    for (const skillName of tier.skills.slice(0, ICONS_PER_TIER)) {
      if (typeof skillName === "string" && Object.hasOwn(SKILL_SPRITE, skillName)) {
        skills.push(skillName);
      }
    }
    out.push({ label: tier.label, skills });
  }
  return out;
}

// Built once at module load from the shipped defaults. Tier structure is not
// overridable via Motd:* keys (see docstring); the widget tree's sprite ids are
// baked in and the file is the source of truth.
//
// The `urls` map wires the three footer label components to their click destinations.
// The runtime (CustomInterfaceRuntime.handleWidgetClick) opens these via openClientUrl
// on click. URLs come from the shipped defaults at build time — Motd:*Url overrides in
// world.local.json still control the TEXT displayed via sendString, but the click
// destination is baked into the declaration. If you need to change where the buttons
// POINT, edit motd-defaults.json + restart.
const INTERFACE_DEFINITION = (() => {
  const tiers = sanitizeTiers(DEFAULTS.ratesTiers);
  const { widgets } = buildInterface({ tiers });
  const urls = {
    [COMPONENT.FOOTER_DISCORD_LABEL]: DEFAULTS.discordUrl,
    [COMPONENT.FOOTER_GITHUB_LABEL]: DEFAULTS.githubUrl,
    [COMPONENT.FOOTER_LAUNCHER_LABEL]: DEFAULTS.launcherUrl,
  };
  return { groupId: GROUP_ID, widgets, urls };
})();

function readString(key, fallback) {
  const raw = api.getPluginConfig(`${CONFIG_PREFIX}:${key}`, undefined);
  return typeof raw === "string" && raw.length > 0 ? raw : fallback;
}

function readFooterLabels() {
  return {
    discord: `Discord: ${readString("discordUrl", DEFAULTS.discordUrl)}`,
    github: `Source: ${readString("githubUrl", DEFAULTS.githubUrl)}`,
    launcher: `Launcher: ${readString("launcherUrl", DEFAULTS.launcherUrl)}`,
  };
}

function login({ player, isNewAccount }) {
  if (!isMotdEligible(api, { player, isNewAccount })) return;
  if (isNewAccount) {
    // Defer MOTD until the new-account onboarding cascade (character
    // designer, Ironman picker, experience screen, tutorial island)
    // finishes. The onPlayerProcess tick below watches for the first
    // idle moment — no interface open — and fires MOTD then. Firing at
    // login would land MOTD underneath the designer and the player
    // would never see it. See the deferredForOnboarding docstring for
    // the per-track flow.
    deferredForOnboarding.add(player);
    return;
  }
  // Add to pending BEFORE queuing the microtask, so a disconnect that arrives first
  // can delete it and showMotd will no-op when the microtask runs. This mirrors the
  // vanilla WelcomeScreen lifecycle that v2 lost.
  pending.add(player);
  queueMicrotask(() => showMotd(player));
}

/**
 * Onboarding is "active" for a new-account player until all of these
 * are true at the same tick:
 *   - not inside Tutorial Island ({@link tutorialActive} is driven
 *     by the tutorial plugin's own {@code tutorial:started} and
 *     {@code tutorial:completed} custom events)
 *   - the Ironman initial-setup choice is not pending
 *     ({@link IRONMAN_SETUP_PENDING} attribute cleared by the
 *     Ironman onboarding plugin once a mode is chosen or skipped)
 *   - no modal / root interface is currently open
 *     ({@code getInterfaceId() <= 0}).
 *
 * The first two gates are specific onboarding signals; the third is
 * a conservative catch-all so a future onboarding flow with its own
 * modal picks up this defer-until-done behaviour without this
 * plugin needing to know about it.
 *
 * v1 review (voidburn-motd-onboarding REVIEW.md P2) called out that
 * a bare no-modal check fires MOTD mid-tutorial at progress 10,
 * right after the player closes the experience screen but while
 * the Gielinor Guide approach is still a tutorial step. The
 * compound predicate fixes that.
 */
function isOnboardingActive(player) {
  if (tutorialActive.has(player)) return true;
  if (player.getAttribute?.(IRONMAN_SETUP_PENDING) === true) return true;
  if (player.getInterfaceId() > 0) return true;
  return false;
}

/**
 * Per-tick check for deferred-MOTD new accounts. Fires MOTD at the
 * first tick where onboarding is complete.
 *
 * Cheap: the WeakSet.has guard short-circuits for every player who
 * isn't deferred (every returning player and every new player who
 * has already had MOTD fire), so the per-tick cost is a WeakSet
 * lookup per logged-in player.
 *
 * Eligibility is re-checked here with isNewAccount=false because
 * the onboarding gate has nothing to do with eligibility anymore —
 * what mattered at login was that the player qualified; what
 * matters at fire time is that they still qualify (not in
 * Wilderness, not a bot, etc.).
 */
function checkDeferredOnboarding({ player }) {
  if (!deferredForOnboarding.has(player)) return;
  if (isOnboardingActive(player)) return;
  if (!isMotdEligible(api, { player, isNewAccount: false })) return;
  // Leave the deferredForOnboarding flag in place until showMotd
  // actually displays. Another handler in the same process tick can
  // start onboarding (open a modal, set ironman:initial-setup-pending,
  // fire tutorial:started) between this check and when the microtask
  // runs — showMotd's own re-check would then skip the display and
  // the flag would already have been cleared, so the deferred
  // welcome would be lost. Keeping the flag means the next idle
  // tick can retry. v2 review P2 regression.
  pending.add(player);
  queueMicrotask(() => showMotd(player));
}

/** tutorial:started event handler: mark player as mid-tutorial. */
function tutorialStarted({ player }) {
  tutorialActive.add(player);
}

/** tutorial:completed event handler: clear the mid-tutorial flag. */
function tutorialCompleted({ player }) {
  tutorialActive.delete(player);
}

function showMotd(player) {
  // Atomic test-and-remove: pending.delete returns false if clear() already fired.
  if (!pending.delete(player)) return;
  // Re-check eligibility at display time so a position change between login and
  // this microtask (e.g. teleported into Wilderness) still skips MOTD. The
  // isNewAccount flag only applies at login time, so pass false here.
  if (!isMotdEligible(api, { player, isNewAccount: false })) return;
  // Compound onboarding re-check for deferred first-login displays.
  // A handler in the same process tick as checkDeferredOnboarding could
  // start onboarding (open a modal, set ironman:initial-setup-pending,
  // fire tutorial:started) between the tick's gate and this microtask.
  // In that case leave the deferredForOnboarding flag in place and
  // bail so the next idle tick can retry; losing the flag would retire
  // the welcome. Returning-player displays aren't in deferredForOnboarding
  // so this branch is a no-op for them (they never deferred).
  if (deferredForOnboarding.has(player)) {
    if (isOnboardingActive(player)) return;
    deferredForOnboarding.delete(player);
  }
  visible.add(player);

  const sender = player.getPacketSender();
  sender.sendRootInterface(GROUP_ID);
  sender.sendInterfaceFlags(PLAY_BUTTON_UID, FIRST_OPTION_FLAG);

  // Header + body text — all sendString(text, uid), the only runtime mutation path
  // PacketSender exposes for text widgets.
  sender.sendString(readString("title", DEFAULTS.title), uid(COMPONENT.TITLE));
  sender.sendString(readString("tagline", DEFAULTS.tagline), uid(COMPONENT.TAGLINE));
  sender.sendString(readString("aboutHeading", DEFAULTS.aboutHeading), uid(COMPONENT.ABOUT_HEADING));
  sender.sendString(readString("aboutBody", DEFAULTS.aboutBody), uid(COMPONENT.ABOUT_BODY));
  sender.sendString(readString("ratesHeading", DEFAULTS.ratesHeading), uid(COMPONENT.RATES_HEADING));
  sender.sendString(readString("changesHeading", DEFAULTS.changesHeading), uid(COMPONENT.CHANGES_HEADING));
  sender.sendString(readString("changesBody", DEFAULTS.changesBody), uid(COMPONENT.CHANGES_BODY));
  sender.sendString(readString("whatsNewHeading", DEFAULTS.whatsNewHeading), uid(COMPONENT.NEW_HEADING));
  sender.sendString(readString("whatsNewBody", DEFAULTS.whatsNewBody), uid(COMPONENT.NEW_BODY));

  const footer = readFooterLabels();
  sender.sendString(footer.discord, uid(COMPONENT.FOOTER_DISCORD_LABEL));
  sender.sendString(footer.github, uid(COMPONENT.FOOTER_GITHUB_LABEL));
  sender.sendString(footer.launcher, uid(COMPONENT.FOOTER_LAUNCHER_LABEL));

  sender.sendString(readString("playButtonLabel", DEFAULTS.playButtonLabel), uid(COMPONENT.PLAY_LABEL));

  api.emitCustomEvent("motd:shown", { player });
}

function play({ player }) {
  if (!visible.delete(player)) return false;
  const root = api.core.resolveGameframeRoot(player);
  for (const packet of api.core.encodeGameframeBootstrap(
      player.getUsername(), root,
      player.getAttribute(api.core.XPDROPS_ENABLED_ATTRIBUTE) === true,
      api.core.GameConstants.WORLD_TYPE.label,
      !api.core.dataOrbsHidden(player), api.core.World.getProcessCycle())) {
    player.getSession().sendClientPacket(packet);
  }
  api.emitCustomEvent("gameframe:ready", { player });
  return false;
}

function clear({ player }) {
  // Cancels any queued microtask (showMotd no-ops when pending.delete fails) and
  // removes any visibility state. Called on disconnect AND logout. Also drops
  // the deferred-onboarding flag so a disconnect mid-onboarding doesn't leave
  // a stale entry that would fire on a future reconnect from a different
  // Player instance (WeakSet keys by identity, so a reconnection creates a
  // new Player and is independently reconsidered at login).
  pending.delete(player);
  visible.delete(player);
  deferredForOnboarding.delete(player);
  tutorialActive.delete(player);
}

function registerPlugin(pluginApi) {
  api = pluginApi;
  api.registerCustomInterface(INTERFACE_DEFINITION);
  api.onPlayerLogin(login);
  api.onPlayerProcess(checkDeferredOnboarding);
  api.onCustomEvent("tutorial:started", tutorialStarted);
  api.onCustomEvent("tutorial:completed", tutorialCompleted);
  api.onInterfaceActionButton(PLAY_BUTTON_UID, play);
  api.onPlayerDisconnect(clear);
  api.onPlayerLogout(clear);
  api.log("registered");
}

module.exports = {
  name: "MotdInterface",
  description: "Voidburn welcome MOTD at login (opt-in via WelcomeScreen:deferToMotd); content from Motd:* keys in world.local.json.",
  dependsOn: ["Wilderness"],
  register: registerPlugin,
  _test: { sanitizeTiers, isMotdEligible, pending, visible, deferredForOnboarding, tutorialActive },
};

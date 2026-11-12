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
  // Add to pending BEFORE queuing the microtask, so a disconnect that arrives first
  // can delete it and showMotd will no-op when the microtask runs. This mirrors the
  // vanilla WelcomeScreen lifecycle that v2 lost.
  pending.add(player);
  queueMicrotask(() => showMotd(player));
}

function showMotd(player) {
  // Atomic test-and-remove: pending.delete returns false if clear() already fired.
  if (!pending.delete(player)) return;
  // Re-check eligibility at display time so a position change between login and
  // this microtask (e.g. teleported into Wilderness) still skips MOTD. The
  // isNewAccount flag only applies at login time, so pass false here.
  if (!isMotdEligible(api, { player, isNewAccount: false })) return;
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
  // removes any visibility state. Called on disconnect AND logout.
  pending.delete(player);
  visible.delete(player);
}

function registerPlugin(pluginApi) {
  api = pluginApi;
  api.registerCustomInterface(INTERFACE_DEFINITION);
  api.onPlayerLogin(login);
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
  _test: { sanitizeTiers, isMotdEligible, pending, visible },
};

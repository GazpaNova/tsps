/**
 * Shared eligibility for the Voidburn MOTD welcome flow.
 *
 * Both the MotdInterface plugin (which may show MOTD) and the vanilla WelcomeScreen
 * plugin (which may show 378) check this before scheduling their respective roots, so
 * exactly one of them ever fires for a given login:
 *
 *   - isMotdEligible returns true  → MotdInterface shows MOTD, WelcomeScreen bails.
 *   - isMotdEligible returns false → WelcomeScreen shows 378, MotdInterface bails.
 *
 * Eligibility: MOTD is on only when ALL of these hold:
 *   - pluginConfig `WelcomeScreen:deferToMotd` is strict boolean true
 *   - the player is not a bot
 *   - the session is not mobile (MOBILE_CLIENT_ATTRIBUTE)
 *   - the player is not logging in inside the Wilderness
 *   - the session is either the browser client (not NATIVE_CLIENT_ATTRIBUTE) or a
 *     RuneLite session that has advertised the Voidburn plugin capability
 *     (VOIDBURN_PLUGIN_ATTRIBUTE). The gateway forwards the capability bit via
 *     HANDSHAKE_CAPABILITY_VOIDBURN_PLUGIN when the connected RuneLite has the
 *     Voidburn plugin installed — see the companion gateway + plugin work.
 *
 * New accounts: MOTD IS shown to first-login players. The earlier eligibility
 * excluded `isNewAccount` because the vanilla OSRS new-account flow (tutorial
 * island, past-experience screen, name picker) runs a cascade of root
 * interfaces that would collide with MOTD. The Voidburn flow instead treats
 * MOTD as the welcome surface and lets whichever plugin runs its microtask
 * last win — in practice MOTD's own showMotd queueMicrotask runs after the
 * tutorial system's own setup, so new players see MOTD and click Play to
 * enter whatever game-state the server lands them on. If this is undesirable
 * for a specific onboarding flow in the future, gate it per-flow rather than
 * reintroducing a blanket isNewAccount bypass here.
 *
 * The native-client-without-plugin check is the production activation gate Codex
 * flagged across the MOTD v1/v2/v3 reviews: without it, enabling `deferToMotd` on
 * a mixed-client world would leave launcher-less RuneLite users (and anyone
 * connecting via RSProx without the Voidburn plugin) staring at an unreachable
 * custom-interface root. With it, those sessions fall through to WelcomeScreen
 * and see stock 378 exactly as they do today.
 *
 * Mobile is included in the ineligibility list for the same reason it is in
 * WelcomeScreen's own bypass: mobile clients enter the world directly without a
 * welcome step. The check is the same here so switching `deferToMotd` on never
 * forces a welcome screen on sessions that should bypass it.
 */
const CONFIG_KEY = "WelcomeScreen:deferToMotd";

function isMotdEligible(api, { player, isNewAccount: _isNewAccount }) {
  if (api.getPluginConfig(CONFIG_KEY, false) !== true) return false;
  // A code rollback may retain the source customization while restoring older
  // compiled core/browser artifacts. Fail closed until that core supports the gate.
  if (api.core.NATIVE_CLIENT_ATTRIBUTE == null || api.core.VOIDBURN_PLUGIN_ATTRIBUTE == null) return false;
  // isNewAccount is accepted for call-site compatibility with the pre-change
  // signature (callers in MotdInterface.plugin.js and WelcomeScreen.plugin.js
  // still pass it) but is no longer used; new accounts now follow the same
  // eligibility rules as returning players. See the docstring.
  if (player.isPlayerBot?.() === true) return false;
  if (player.getAttribute(api.core.MOBILE_CLIENT_ATTRIBUTE) === true) return false;
  // Capability-aware fallback: RuneLite without the Voidburn plugin cannot render
  // server-defined custom interfaces, so MOTD is not eligible for those sessions.
  // Browser clients (no NATIVE_CLIENT_ATTRIBUTE) pass this check unconditionally.
  if (player.getAttribute(api.core.NATIVE_CLIENT_ATTRIBUTE) === true
      && player.getAttribute(api.core.VOIDBURN_PLUGIN_ATTRIBUTE) !== true) {
    return false;
  }
  const location = player.getLocation?.();
  if (location && api.core.Wilderness.isInLocation(location)) return false;
  return true;
}

module.exports = { isMotdEligible, CONFIG_KEY };

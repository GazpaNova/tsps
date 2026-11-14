/**
 * Voidburn MOTD welcome interface — v3.
 *
 * A 2D layout shown at login in place of the vanilla 378 welcome screen. Sections:
 *   1. Header — Voidburn logo (client sprite override at id -1000) + title + tagline
 *   2. About — heading + body
 *   3. XP rates — heading + four tier rows with skill icons from cache
 *   4. Our changes — heading + body
 *   5. What's new — heading + body
 *   6. Footer — Discord / GitHub / Launcher URLs as plain text, "Enter Voidburn"
 *      button on the right. Clickable URL actions are a separately-reviewed companion
 *      change; this version renders URLs as text so players can type them.
 *
 * Positioning: the panel is a fixed 480 x 400 rectangle centered on its parent.
 * `xPositionMode: 1` / `yPositionMode: 1` means "center + rawX" / "center + rawY"
 * per client/widgets/layout/WidgetLayout.ts `alignPosition`. v2 used mode 2 which
 * anchors to the far edge (bottom-right); the fix came from reading `alignPosition`
 * rather than guessing. v3 matches the pattern used by CustomShops, TeleportInterface
 * and MaxHitsInterface.
 *
 * Opacity: this engine uses the legacy OSRS convention where 0 is opaque and higher
 * values are more transparent. The backdrop uses `opacity: 16` (near-opaque) to match
 * MaxHitsInterface's main panel. v2 used `opacity: 230` which is nearly fully
 * transparent; the world showed straight through.
 *
 * Sprite ids: skill icons come from the production cache ids verified during the v1
 * review of the earlier MOTD attempt (Codex 2026-11-11). v3 ships the corrected set:
 * Fletching 208, Runecrafting 215, Slayer 216, Farming 217, Hunter 220,
 * Construction 221, Sailing 228. Baked into the widget definition at build time;
 * there is no runtime sprite mutation API on PacketSender.
 */
const { FLAG_OP1, TYPE_GRAPHIC, TYPE_RECTANGLE, TYPE_TEXT, createWidgetGroup } = require("../widgetGroup");

const GROUP_ID = 30020;

/** The Voidburn logo is a client sprite override served at /voidburn/logo.png. */
const VOIDBURN_LOGO_SPRITE = -1000;

/** Production-cache-verified skill → sprite id map. */
const SKILL_SPRITE = Object.freeze({
  Attack: 197, Strength: 198, Defence: 199, Ranged: 200, Prayer: 201, Magic: 202,
  Hitpoints: 203, Agility: 204, Herblore: 205, Thieving: 206, Crafting: 207,
  Fletching: 208, Mining: 209, Smithing: 210, Fishing: 211, Cooking: 212,
  Firemaking: 213, Woodcutting: 214, Runecrafting: 215, Slayer: 216, Farming: 217,
  Construction: 221, Hunter: 220, Sailing: 228,
});

/** Voidburn brand palette as 0xRRGGBB (not ARGB — color is separate from opacity). */
const COLOR = Object.freeze({
  BG_DARK: 0x14100b,
  BG_PANEL: 0x201a12,
  BG_SECTION: 0x2b241b,
  BORDER: 0x3a3125,
  GOLD: 0xd4b81b,
  TEAL: 0x2fd4b4,
  PURPLE: 0x6b16e8,
  TEXT_BODY: 0xe8ded0,
  TEXT_MUTED: 0xb8ab94,
  TEXT_TIER: 0xd4b81b,
});

const PANEL = Object.freeze({ width: 480, height: 460 });

// Layout numbers. Row stride ≥ icon height so icons never overlap the next row's
// bounds — fixed from v2 where 24-tall icons sat on a 20-tall row.
const ICON_SIZE = 24;
const ICON_GAP = 4;
const TIER_ROW_HEIGHT = 28;
const TIER_LABEL_WIDTH = 32;
const TIER_COUNT = 4;
const ICONS_PER_TIER = 11;

/** Component ids. Ranges reserved per section so inserts don't shift existing ids. */
const C = Object.freeze({
  ROOT: 0,              // full-viewport container; no visible content of its own
  SCRIM: 1,             // full-viewport opaque backdrop covering the world
  PANEL: 5,             // 480x460 centered layer holding every content widget
  PANEL_BG: 6,          // panel backdrop, painted before HEADER and the remaining content
  HEADER: 7,            // keeps existing header ids 2..4 above the backdrop
  LOGO: 2, TITLE: 3, TAGLINE: 4,
  ABOUT_HEADING: 10, ABOUT_BODY: 11,
  RATES_HEADING: 20,
  RATES_TIER_BG_START: 30,       // 30..33   per-tier background strip
  RATES_TIER_LABEL_START: 40,    // 40..43   tier label text
  RATES_TIER_ICON_START: 100,    // 100..143 up to 4 tiers × 11 slots
  CHANGES_HEADING: 160, CHANGES_BODY: 161,
  NEW_HEADING: 170, NEW_BODY: 171,
  FOOTER_DISCORD_LABEL: 180,
  FOOTER_GITHUB_LABEL: 181,
  FOOTER_LAUNCHER_LABEL: 182,
  PLAY_BUTTON: 186, PLAY_LABEL: 187,
});

const uid = (component) => (GROUP_ID << 16) | component;
const PLAY_BUTTON_UID = uid(C.PLAY_BUTTON);

const text = (x, y, width, height, overrides = {}) => ({
  type: TYPE_TEXT, rawX: x, rawY: y, rawWidth: width, rawHeight: height, width, height,
  text: "", fontId: 494, textColor: COLOR.TEXT_BODY, textShadowed: true, ...overrides,
});
const box = (x, y, width, height, overrides = {}) => ({
  type: TYPE_RECTANGLE, rawX: x, rawY: y, rawWidth: width, rawHeight: height, width, height,
  filled: true, color: COLOR.BG_SECTION, opacity: 32, ...overrides,
});
const graphic = (x, y, width, height, spriteId, overrides = {}) => ({
  type: TYPE_GRAPHIC, rawX: x, rawY: y, rawWidth: width, rawHeight: height, width, height,
  spriteId, itemQuantityMode: 0, ...overrides,
});

/**
 * Build the MOTD widget tree. `tiers` is an array of `{ label, skills }` already
 * sanitized by the caller (the plugin). Returns `{ widgets }` ready for
 * api.registerCustomInterface.
 */
function buildInterface({ tiers = [] } = {}) {
  const { widgets, add } = createWidgetGroup(GROUP_ID);

  // Full-viewport root. widthMode/heightMode 1 with rawW/rawH 0 means "parent size
  // minus 0" per alignSize, so this fills whatever game canvas the client has.
  const root = add(C.ROOT, -1, {
    rawX: 0, rawY: 0,
    rawWidth: 0, rawHeight: 0,
    widthMode: 1, heightMode: 1,
  });

  // SCRIM: full-viewport opaque backdrop. Covers the whole game area so MOTD reads
  // as a full-screen welcome experience, not a modal sitting on top of visible world.
  // Same inverted-opacity convention: 0 = fully opaque.
  add(C.SCRIM, root, {
    type: TYPE_RECTANGLE, rawX: 0, rawY: 0, rawWidth: 0, rawHeight: 0,
    widthMode: 1, heightMode: 1,
    filled: true, color: COLOR.BG_DARK, opacity: 0,
  });

  // PANEL: 480x460 centered layer that hosts every content widget. Content widgets
  // position themselves relative to this layer's top-left, so their x/y math is
  // unchanged from the previous centered-panel version.
  const panel = add(C.PANEL, root, {
    type: 0, // TYPE_LAYER
    rawX: 0, rawY: 0,
    rawWidth: PANEL.width, rawHeight: PANEL.height,
    width: PANEL.width, height: PANEL.height,
    widthMode: 0, heightMode: 0,
    xPositionMode: 1, yPositionMode: 1,
  });

  // Panel backdrop inside the PANEL layer. Slightly lighter than the scrim so the
  // content area reads as distinct from the full-viewport fill behind it.
  add(C.PANEL_BG, panel, box(0, 0, PANEL.width, PANEL.height, { color: COLOR.BG_PANEL, opacity: 24 }));

  // All content widgets below are children of PANEL. Their x/y are relative to the
  // 480x460 panel's top-left, so the numbers are unchanged from the centered-panel
  // version — only the parent uid changes.

  // Static siblings paint in fileId order. Put header ids 2..4 inside layer 7
  // so PANEL_BG (6) is drawn first without renumbering the existing header ids.
  const header = add(C.HEADER, panel, {
    type: 0, rawX: 0, rawY: 0,
    rawWidth: PANEL.width, rawHeight: 56,
    widthMode: 0, heightMode: 0,
  });

  // Header (y 10-56).
  add(C.LOGO, header, graphic(16, 10, 44, 44, VOIDBURN_LOGO_SPRITE));
  add(C.TITLE, header, text(68, 10, PANEL.width - 84, 24, {
    fontId: 496, textColor: COLOR.GOLD, yTextAlignment: 1,
  }));
  add(C.TAGLINE, header, text(68, 36, PANEL.width - 84, 18, {
    textColor: COLOR.TEAL, yTextAlignment: 1,
  }));

  // About (y 64-146). Heading 16, body 64 — fits up to 4 lines of body text.
  add(C.ABOUT_HEADING, panel, text(16, 64, PANEL.width - 32, 16, {
    fontId: 495, textColor: COLOR.GOLD,
  }));
  add(C.ABOUT_BODY, panel, text(16, 82, PANEL.width - 32, 64));

  // XP rates (y 152-280). Heading 16, then 4 tier rows at 28-stride.
  add(C.RATES_HEADING, panel, text(16, 152, PANEL.width - 32, 16, {
    fontId: 495, textColor: COLOR.GOLD,
  }));
  const TIER_Y_START = 170;
  for (let tierIndex = 0; tierIndex < TIER_COUNT; tierIndex++) {
    const tier = tiers[tierIndex] ?? { label: "", skills: [] };
    const rowY = TIER_Y_START + tierIndex * TIER_ROW_HEIGHT;

    add(C.RATES_TIER_BG_START + tierIndex, panel, box(16, rowY, PANEL.width - 32, TIER_ROW_HEIGHT - 2, {
      color: COLOR.BG_SECTION, opacity: tierIndex % 2 === 0 ? 48 : 64,
    }));
    add(C.RATES_TIER_LABEL_START + tierIndex, panel, text(20, rowY, TIER_LABEL_WIDTH, TIER_ROW_HEIGHT - 2, {
      text: tier.label, textColor: COLOR.TEXT_TIER, yTextAlignment: 1, fontId: 495,
    }));

    for (let slot = 0; slot < ICONS_PER_TIER; slot++) {
      const componentId = C.RATES_TIER_ICON_START + tierIndex * ICONS_PER_TIER + slot;
      const skillName = tier.skills[slot];
      const spriteId = skillName ? SKILL_SPRITE[skillName] : undefined;
      const iconX = 20 + TIER_LABEL_WIDTH + slot * (ICON_SIZE + ICON_GAP);
      const iconY = rowY + 1;
      if (spriteId !== undefined) {
        add(componentId, panel, graphic(iconX, iconY, ICON_SIZE, ICON_SIZE, spriteId));
      } else {
        // Hidden placeholder so component ids stay dense; nothing draws.
        add(componentId, panel, graphic(iconX, iconY, ICON_SIZE, ICON_SIZE, 0, {
          hidden: true, isHidden: true,
        }));
      }
    }
  }

  // Our Changes (y 288-332). Heading 16, body 26 — two lines.
  add(C.CHANGES_HEADING, panel, text(16, 288, PANEL.width - 32, 16, {
    fontId: 495, textColor: COLOR.GOLD,
  }));
  add(C.CHANGES_BODY, panel, text(16, 306, PANEL.width - 32, 26));

  // What's New (y 338-390). Body height grew to 34 so ~2 lines of text fit cleanly.
  add(C.NEW_HEADING, panel, text(16, 338, PANEL.width - 32, 16, {
    fontId: 495, textColor: COLOR.GOLD,
  }));
  add(C.NEW_BODY, panel, text(16, 356, PANEL.width - 32, 34));

  // Footer (y 398-444). Three URL lines stacked, play button right-aligned.
  // Each URL gets full width minus the play button's reserved column so long URLs
  // show cleanly instead of wrapping into the play button's area.
  const PLAY_W = 144;
  const PLAY_H = 28;
  const URL_W = PANEL.width - 32 - PLAY_W - 8;
  add(C.FOOTER_DISCORD_LABEL,  panel, text(16, 398, URL_W, 14, { textColor: COLOR.TEAL }));
  add(C.FOOTER_GITHUB_LABEL,   panel, text(16, 414, URL_W, 14, { textColor: COLOR.TEAL }));
  add(C.FOOTER_LAUNCHER_LABEL, panel, text(16, 430, URL_W, 14, { textColor: COLOR.TEAL }));

  // "Enter Voidburn" — centered vertically against the three URL stack.
  const PLAY_Y = 406;
  add(C.PLAY_BUTTON, panel, box(PANEL.width - PLAY_W - 16, PLAY_Y, PLAY_W, PLAY_H, {
    color: COLOR.PURPLE, mouseOverColor: COLOR.TEAL, opacity: 16,
    actions: ["Play"], flags: FLAG_OP1,
  }));
  add(C.PLAY_LABEL, panel, text(PANEL.width - PLAY_W - 16, PLAY_Y, PLAY_W, PLAY_H, {
    textColor: COLOR.GOLD, xTextAlignment: 1, yTextAlignment: 1, fontId: 495,
  }));

  return { widgets };
}

module.exports = {
  GROUP_ID,
  PLAY_BUTTON_UID,
  SKILL_SPRITE,
  VOIDBURN_LOGO_SPRITE,
  TIER_COUNT,
  ICONS_PER_TIER,
  PANEL,
  COMPONENT: C,
  uid,
  buildInterface,
};

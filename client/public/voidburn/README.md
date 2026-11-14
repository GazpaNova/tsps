# Voidburn custom sprite assets

Static PNGs served at `/voidburn/*.png` and registered as sprite overrides by
`client/game/plugins/voidburn/VoidburnSprites.ts`.

The ids these PNGs are registered under come from the Voidburn-reserved range
**-1000 to -1999**. Keep that mapping in `VoidburnSprites.ts` as the single source of
truth; a new file goes in with the next free id.

To swap the Voidburn logo: replace `logo.png` with the new art at the same path and
reload the client. Any transparent PNG works; 128×128 or larger with transparency is
ideal.

The current `logo.png` is a placeholder (hex outline with "VB" wordmark) so the MOTD
welcome screen has something visible to render during review.

/**
 * Voidburn custom sprites: image overrides rendered under negative sprite ids the cache
 * does not use (same mechanism as Bank Tags' tab backgrounds — see
 * client/widgets/gl/spriteOverrides.ts and client/game/plugins/banktags/tabs/TabSprites.ts).
 *
 * Reserved range for Voidburn: -1000 to -1999.
 *   - -1000  Voidburn logo (shown on the MOTD welcome screen)
 *
 * Pick the next free id from the range for any new Voidburn custom sprite. Do not reuse
 * ids 0+ (real OSRS cache sprites) and do not touch -201..-205 (Bank Tags).
 *
 * The PNGs live under client/public/voidburn/ and are served statically. The browser
 * client registers them at module-import time. The Voidburn RuneLite plugin (future work)
 * will register the same ids through `SpriteManager.addSpriteOverrides` with the same
 * PNGs bundled in its JAR so both clients see one Voidburn.
 */
import { addSpriteOverride } from "../../../widgets/gl/spriteOverrides";

export const VoidburnSprites = {
    LOGO: -1000,
} as const;

const FILES: Record<number, string> = {
    [VoidburnSprites.LOGO]: "logo.png",
};

function url(file: string): string {
    const base = (process.env.PUBLIC_URL ?? "").replace(/\/$/, "");
    return `${base}/voidburn/${file}`;
}

/** Register every Voidburn sprite override. Called once at module import. */
export function registerVoidburnSprites(): void {
    for (const [id, file] of Object.entries(FILES)) {
        addSpriteOverride(Number(id), url(file));
    }
}

// Self-register on import so a top-level `import "./voidburn/VoidburnSprites"` is enough.
registerVoidburnSprites();

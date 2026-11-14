import { ObjStackability } from "../../rs/config/objtype/ObjStackability";
import type { ObjType } from "../../rs/config/objtype/ObjType";
import type { ObjTypeLoader } from "../../rs/config/objtype/ObjTypeLoader";

/** Revenant custom: the sub-option the server's DropAll plugin answers. */
export const DROP_ALL_SUBOP = "Drop-All";
const DROP_OP = 4;
const SUBOP_SLOTS = 20;

export type DropAllFilter = (obj: ObjType) => boolean;

/** Items that can carry Drop-All, so a filter change reaches the ones already loaded. */
const eligible = new Set<ObjType>();
let filter: DropAllFilter | null = null;

function eligibleFor(obj: ObjType): boolean {
    if (!obj.name || obj.name === "null") return false;
    if (obj.inventoryActions?.[DROP_OP]?.toLowerCase() !== "drop") return false;
    if (obj.stackability === ObjStackability.ALWAYS) return false;
    if ((obj.noteTemplate | 0) !== -1 || (obj.placeholderTemplate | 0) !== -1) return false;
    if (((obj as any).notedId ?? -1) !== -1) return false;
    return !obj.subops?.[DROP_OP]?.some((text) => !!text);
}

function apply(obj: ObjType): void {
    if (filter && filter(obj)) {
        const subops = obj.subops ?? new Array(5).fill(null);
        subops[DROP_OP] = new Array(SUBOP_SLOTS).fill(null);
        subops[DROP_OP]![0] = DROP_ALL_SUBOP;
        obj.subops = subops;
    } else if (obj.subops) {
        obj.subops[DROP_OP] = null;
    }
}

/**
 * Every non-stackable item whose fifth op is Drop can get a "Drop-All" sub-option under it; the
 * Revenant Drop-All plugin (game/plugins/dropall) decides which through its filter. The inventory
 * scripts read it with oc_isubop like any cache sub-option; the server drops every slot of the
 * item (server/plugins/items/DropAll.plugin.js).
 */
export function addDropAllSubop(obj: ObjType): ObjType {
    const anyObj = obj as any;
    if (anyObj.__dropAll === true) return obj;
    anyObj.__dropAll = true;
    if (!eligibleFor(obj)) return obj;
    anyObj.__dropAllEligible = true;
    eligible.add(obj);
    apply(obj);
    return obj;
}

export function isDropAllEligible(obj: ObjType): boolean {
    return (obj as any).__dropAllEligible === true;
}

/** null turns Drop-All off everywhere. */
export function setDropAllFilter(value: DropAllFilter | null): void {
    filter = value;
    for (const obj of eligible) apply(obj);
}

export class DropAllObjTypeLoader implements ObjTypeLoader {
    constructor(private readonly base: ObjTypeLoader) {}

    load(id: number): ObjType {
        return addDropAllSubop(this.base.load(id));
    }

    getCount(): number {
        return this.base.getCount();
    }

    clearCache(): void {
        this.base.clearCache();
    }
}

import { ConfigChanged } from "@runelite/api/events";
import { ConfigManager } from "@runelite/client/config/ConfigManager";
import { Plugin, type PluginDescriptor } from "@runelite/client/plugins/Plugin";
import { CLIENT_TOKEN, inject } from "@runelite/client/plugins/PluginInjector";
import { DROP_ALL_SUBOP, isDropAllEligible, setDropAllFilter } from "../../../custom/items/DropAllObjTypeLoader";
import type { MenuTransformContext } from "../../../ui/menu/menuTransforms";
import type { SimpleMenuEntry } from "../../../ui/menu/MenuEngine";
import { ClientState } from "../../ClientState";
import type { OsrsClient } from "../../OsrsClient";
import { markInvTransmit } from "../../TransmitCycles";
import { ItemList, NONE, splitList } from "../grounditems/groundItemRules";
import { DropAllConfig } from "./DropAllConfig";

/** The inventory (inv 93, group 149): re-running its scripts rebuilds every item's sub-options. */
const INVENTORY = 93;
const INVENTORY_GROUP = 149;
export const ADD_OPTION = "Add to Drop-All";
export const REMOVE_OPTION = "Remove from Drop-All";

function normalize(name: string): string {
    return name.trim().replace(/^"(.*)"$/, "$1").trim().toLowerCase().replace(/\s+/g, " ");
}

/** The list without [name]'s exact entries, or with [name] added when it had none. */
export function toggleListed(csv: string, name: string): string {
    const entries = splitList(csv);
    const kept = entries.filter((entry) => entry.includes("*") || normalize(entry) !== normalize(name));
    if (kept.length === entries.length) kept.push(name);
    return kept.join(", ");
}

export function isListed(csv: string, name: string): boolean {
    return splitList(csv).some((entry) => !entry.includes("*") && normalize(entry) === normalize(name));
}

/**
 * Revenant custom: Drop -> Drop-All on the inventory items listed in the plugin's settings (or on
 * every item with "All items"), off until a player turns it on. The sub-option comes from
 * custom/items/DropAllObjTypeLoader.ts and the server's DropAll plugin drops every slot of the
 * item. Shift + right-click an inventory item to add it to the list or remove it, like Inventory
 * Tags. With "Shift-click Drop-All", Shift + left-click on a listed item is Drop-All.
 */
export class DropAllPlugin extends Plugin {
    static descriptor: PluginDescriptor = {
        name: "Revenant Drop-All",
        description: "Add Drop-All under Drop for the items you list, to drop every one in your inventory.",
        tags: ["drop", "inventory", "items", "revenant"],
        enabledByDefault: false,
        configKey: "revenantdropall",
    };

    static config = DropAllConfig;

    private readonly configManager = inject(ConfigManager);
    private readonly config = this.configManager.getConfig(DropAllConfig);
    private readonly osrsClient: OsrsClient;

    constructor(osrsClient?: OsrsClient) {
        super();
        this.osrsClient = osrsClient ?? inject<OsrsClient>(CLIENT_TOKEN);
    }

    protected async startUp(): Promise<void> {
        this.refresh();
    }

    protected async shutDown(): Promise<void> {
        setDropAllFilter(null);
        markInvTransmit(INVENTORY);
    }

    onConfigChanged(event: ConfigChanged): void {
        if (event.getGroup() !== DropAllConfig.group || !this.isStarted()) return;
        this.refresh();
    }

    private items(): string {
        return String(this.config.items() ?? "");
    }

    private refresh(): void {
        const all = this.config.allItems() === true;
        const list = new ItemList(this.items());
        setDropAllFilter((obj) => all || list.matches(obj.name, 1) !== NONE);
        markInvTransmit(INVENTORY);
    }

    /**
     * Shift-click Drop-All: when Shift is held over an inventory item whose Drop carries the
     * Drop-All sub-option (a listed item), the left-click is that sub-option. The primary click
     * sends a pinned entry's opSubIndex (game/widgets/input/widgetPrimaryAction.ts).
     */
    private shiftClick(entries: SimpleMenuEntry[], context: MenuTransformContext): SimpleMenuEntry[] {
        if (this.config.shiftClick() !== true || context.surface === "world") return entries;
        if ((context.widgetId ?? 0) >>> 16 !== INVENTORY_GROUP) return entries;
        const drop = entries.find((e) => String(e.option).toLowerCase() === "drop");
        const sub = ((drop as any)?.subOptions ?? []) as string[];
        const at = sub.indexOf(DROP_ALL_SUBOP);
        if (!drop || at < 0) return entries;
        const pinned = { ...drop, option: DROP_ALL_SUBOP, opSubIndex: at + 1, swapPinned: true, deprioritized: false } as SimpleMenuEntry;
        return [pinned, ...entries.map((e) => ((e as any).swapPinned ? { ...e, swapPinned: undefined } : e))];
    }

    private toggle(name: string): void {
        this.configManager.setConfiguration(DropAllConfig.group, "items", toggleListed(this.items(), name));
        this.refresh();
    }

    transformMenuEntries(entries: SimpleMenuEntry[], context: MenuTransformContext): SimpleMenuEntry[] {
        if (!this.isStarted() || !ClientState.isShiftPressed()) return entries;
        if (!context.menu) return this.shiftClick(entries, context);
        if (context.surface === "world" || this.config.allItems() === true) return entries;
        const itemId = context.itemId ?? -1;
        const widgetId = context.widgetId ?? 0;
        if (!(itemId >= 0) || widgetId >>> 16 !== INVENTORY_GROUP) return entries;
        const loader: any = (this.osrsClient as any)?.objTypeLoader;
        const obj = loader?.load(itemId);
        if (!obj || !isDropAllEligible(obj)) return entries;
        if (entries.some((e) => e.option === ADD_OPTION || e.option === REMOVE_OPTION)) return entries;
        const examine = entries.findIndex((e) => String(e.option).toLowerCase() === "examine");
        const entry: SimpleMenuEntry = {
            option: isListed(this.items(), obj.name) ? REMOVE_OPTION : ADD_OPTION,
            target: examine >= 0 ? entries[examine].target : obj.name,
            onClick: () => this.toggle(obj.name),
        } as SimpleMenuEntry;
        // Under Examine, where Inventory Tags puts its own Shift entry.
        const at = examine >= 0 ? examine + 1 : Math.max(entries.length - 1, 0);
        return [...entries.slice(0, at), entry, ...entries.slice(at)];
    }
}

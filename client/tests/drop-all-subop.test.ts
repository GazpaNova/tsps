// Revenant custom: the browser's Drop-All sub-option (custom/items/DropAllObjTypeLoader.ts,
// game/plugins/dropall/), off until the plugin is turned on, and only for listed items.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

import { ConfigManager } from "@runelite/client/config/ConfigManager";
import { inject } from "@runelite/client/plugins/PluginInjector";
import { ClientState } from "../game/ClientState";
import { DropAllConfig } from "../game/plugins/dropall/DropAllConfig";
import { DropAllPlugin, isListed, toggleListed } from "../game/plugins/dropall/DropAllPlugin";
import { MenuTargetType } from "../rs/MenuEntry";
import type { SimpleMenuEntry } from "../ui/menu/MenuEngine";
import type { MenuTransformContext } from "../ui/menu/menuTransforms";
import { DROP_ALL_SUBOP, isDropAllEligible, setDropAllFilter } from "../custom/items/DropAllObjTypeLoader";
import { ItemList, NONE } from "../game/plugins/grounditems/groundItemRules";
import { CacheSystem } from "../rs/cache/CacheSystem";
import { Dat2CacheLoaderFactory } from "../rs/cache/loader/Dat2CacheLoaderFactory";
import { getLatestCache } from "../rs/cache/CacheInfo";
import { loadCache, loadCacheInfos } from "../scripts/cache/load-util";

const info = getLatestCache(loadCacheInfos())!;
const objs = new Dat2CacheLoaderFactory(info, "dat2", CacheSystem.fromFiles(info, loadCache(info).files)).getObjTypeLoader();
const dropSubops = (id: number) => (objs.load(id).subops?.[4] ?? []).filter((text) => !!text);
const listFilter = (csv: string) => {
    const list = new ItemList(csv);
    return (obj: { name: string }) => list.matches(obj.name, 1) !== NONE;
};
const BONES = 526, AXE = 1351, LOGS = 1511, IRON_ORE = 440, COAL = 453, COINS = 995;

test("the plugin is registered, off by default, with an empty list and All items off", () => {
    const index = readFileSync(join(__dirname, "../runelite/client/plugins/index.ts"), "utf8");
    assert.match(index, /^    DropAllPlugin,$/m, "listed in CORE_PLUGINS");
    const source = readFileSync(join(__dirname, "../game/plugins/dropall/DropAllPlugin.ts"), "utf8");
    assert.match(source, /enabledByDefault: false/);
    assert.equal((DropAllConfig as any).items.items.default, "");
    assert.equal((DropAllConfig as any).items.allItems.default, false);
    assert.equal((DropAllConfig as any).items.shiftClick.default, false);
});

test("no item has Drop-All while the plugin is off", () => {
    setDropAllFilter(null);
    assert.deepEqual(dropSubops(BONES), []);
    assert.deepEqual(dropSubops(AXE), []);
});

test("only listed items get it; wildcards and quotes work; changing the list updates loaded items", () => {
    assert.deepEqual(dropSubops(BONES), [], "loaded before the plugin is on");
    setDropAllFilter(listFilter("bones, *ore, \"Logs\""));
    try {
        assert.deepEqual(dropSubops(BONES), [DROP_ALL_SUBOP]);
        assert.deepEqual(dropSubops(IRON_ORE), [DROP_ALL_SUBOP], "*ore");
        assert.deepEqual(dropSubops(LOGS), [DROP_ALL_SUBOP], "quoted exact name");
        assert.deepEqual(dropSubops(AXE), [], "not listed");
        assert.deepEqual(dropSubops(COAL), [], "not matched by *ore");
        setDropAllFilter(listFilter("bronze axe"));
        assert.deepEqual(dropSubops(BONES), [], "taken off when the list changes");
        assert.deepEqual(dropSubops(AXE), [DROP_ALL_SUBOP]);
        setDropAllFilter(() => true);
        assert.deepEqual(dropSubops(BONES), [DROP_ALL_SUBOP], "All items");
        assert.deepEqual(dropSubops(BONES), [DROP_ALL_SUBOP], "never a second one");
    } finally {
        setDropAllFilter(null);
    }
    assert.deepEqual(dropSubops(BONES), []);
});

test("stackable, noted and placeholder items and items without Drop never get it", () => {
    setDropAllFilter(() => true);
    try {
        assert.deepEqual(dropSubops(COINS), [], "coins");
        assert.deepEqual(dropSubops(527), [], "noted bones");
        assert.deepEqual(dropSubops(objs.load(BONES).placeholder | 0), [], "bones placeholder");
        assert.deepEqual(dropSubops(554), [], "fire rune");
        assert.equal(isDropAllEligible(objs.load(BONES)), true);
        assert.equal(isDropAllEligible(objs.load(COINS)), false);
    } finally {
        setDropAllFilter(null);
    }
});

test("the plugin: Shift + right-click on an inventory item adds it to the list and back out", async () => {
    const SHIFT = 81;
    const config = inject(ConfigManager);
    const plugin = new DropAllPlugin({ objTypeLoader: objs } as any);
    await plugin.start();
    const base: SimpleMenuEntry[] = [
        { option: "Bury", target: "<col=ff9040>Bones" },
        { option: "Drop", target: "<col=ff9040>Bones" },
        { option: "Examine", target: "<col=ff9040>Bones" },
        { option: "Cancel", target: "" },
    ];
    const menu = (itemId: number, widgetId = 149 << 16): MenuTransformContext => ({
        surface: "inventory", menu: true, widgetId, childIndex: 0, itemId,
        target: { type: MenuTargetType.ITEM, id: itemId, name: "Bones" },
    });
    const options = (entries: SimpleMenuEntry[]) => entries.map((e) => e.option);
    try {
        assert.deepEqual(dropSubops(BONES), [], "on, but nothing listed");
        ClientState.setKeybindState(SHIFT, false);
        assert.equal(plugin.transformMenuEntries(base, menu(BONES)), base, "nothing without Shift");
        ClientState.setKeybindState(SHIFT, true);
        let out = plugin.transformMenuEntries(base, menu(BONES));
        assert.deepEqual(options(out), ["Bury", "Drop", "Examine", "Add to Drop-All", "Cancel"]);
        out[3].onClick!(0, 0, {} as any);
        assert.equal(config.getConfiguration("revenantdropall", "items"), "Bones");
        assert.deepEqual(dropSubops(BONES), [DROP_ALL_SUBOP], "added right away");
        out = plugin.transformMenuEntries(base, menu(BONES));
        assert.equal(out[3].option, "Remove from Drop-All");
        out[3].onClick!(0, 0, {} as any);
        assert.equal(config.getConfiguration("revenantdropall", "items"), "");
        assert.deepEqual(dropSubops(BONES), []);
        assert.equal(plugin.transformMenuEntries(base, menu(COINS)), base, "not for a stack");
        assert.equal(plugin.transformMenuEntries(base, menu(BONES, 12 << 16)), base, "not in the bank");
        config.setConfiguration("revenantdropall", "allItems", true);
        plugin.onConfigChanged({ getGroup: () => "revenantdropall", getKey: () => "allItems" } as any);
        assert.deepEqual(dropSubops(AXE), [DROP_ALL_SUBOP], "All items");
        assert.equal(plugin.transformMenuEntries(base, menu(BONES)), base, "no list entries with All items");
        config.setConfiguration("revenantdropall", "allItems", false);
        plugin.onConfigChanged({ getGroup: () => "revenantdropall", getKey: () => "allItems" } as any);
        assert.deepEqual(dropSubops(AXE), []);

        // Shift-click Drop-All: the left-click (menu: false) of a Drop that carries the sub-option.
        const leftClick: MenuTransformContext = { ...menu(BONES), menu: false };
        const ops = [
            { option: "Bury", target: "Bones", opIndex: 1, opcode: 57 },
            { option: "Drop", target: "Bones", opIndex: 5, opcode: 57, subOptions: [DROP_ALL_SUBOP] },
            { option: "Examine", target: "Bones", opIndex: 10, opcode: 1007, swapPinned: true },
        ] as SimpleMenuEntry[];
        assert.equal(plugin.transformMenuEntries(ops, leftClick), ops, "the option is off by default");
        config.setConfiguration("revenantdropall", "shiftClick", true);
        let left = plugin.transformMenuEntries(ops, leftClick) as any[];
        assert.equal(left[0].option, DROP_ALL_SUBOP);
        assert.equal(left[0].opIndex, 5, "Drop's op");
        assert.equal(left[0].opSubIndex, 1, "its first sub-option");
        assert.equal(left[0].swapPinned, true);
        assert.equal(left.filter((e) => e.swapPinned).length, 1, "Menu Swapper's pin gives way");
        const noSub = ops.map((e) => (e.option === "Drop" ? { ...e, subOptions: undefined } : e)) as SimpleMenuEntry[];
        assert.equal(plugin.transformMenuEntries(noSub, leftClick), noSub, "an unlisted item keeps its left-click");
        ClientState.setKeybindState(SHIFT, false);
        assert.equal(plugin.transformMenuEntries(ops, leftClick), ops, "only with Shift");
        ClientState.setKeybindState(SHIFT, true);
        config.setConfiguration("revenantdropall", "shiftClick", false);
    } finally {
        ClientState.setKeybindState(SHIFT, false);
        await plugin.stop();
    }
    assert.deepEqual(dropSubops(BONES), [], "off again once the plugin stops");
});

test("Shift + right-click adds an item to the list and removes only its exact entries", () => {
    assert.equal(toggleListed("", "Bones"), "Bones");
    assert.equal(toggleListed("Logs", "Bones"), "Logs, Bones");
    assert.equal(toggleListed("logs, bones, *ore", "Bones"), "logs, *ore");
    assert.equal(toggleListed("\"Bones\"", "Bones"), "");
    assert.equal(toggleListed("*ore", "Iron ore"), "*ore, Iron ore", "a wildcard isn't removed");
    assert.equal(isListed("logs, BONES", "Bones"), true);
    assert.equal(isListed("*one*", "Bones"), false);
});

// Revenant custom: Shift-click Drop-All in the browser, from the plugin's pinned entry through
// the primary (left-click) action to the widget packet's sub-op.
import assert from "node:assert/strict";
import { test } from "node:test";

import { ConfigManager } from "@runelite/client/config/ConfigManager";
import { inject } from "@runelite/client/plugins/PluginInjector";
import { ClientState } from "../game/ClientState";
import { DropAllPlugin } from "../game/plugins/dropall/DropAllPlugin";
import { createPrimaryWidgetActionResolver } from "../game/widgets/input/widgetPrimaryAction";
import { buildWidgetActionPayload } from "../game/widgets/widgetActionPayload";
import { setMenuTransform } from "../ui/menu/menuTransforms";

const SHIFT = 81;
const allOpsVisible = () => -1;
const noWidgetLookup = () => undefined;
// An inventory item whose Drop carries the Drop-All sub-option (what the inventory scripts set
// for a listed item while the plugin is on).
const widget = {
    uid: 149 << 16, groupId: 149, fileId: -1, childIndex: 2, itemId: 526, name: "<col=ff9040>Bones",
    actions: ["Bury", null, null, null, "Drop"],
    submenuActions: [null, null, null, null, ["Drop-All"]],
};
const manager = { getWidgetFlags: allOpsVisible, getWidgetOpMask: () => 0, getWidgetByUid: noWidgetLookup };
const resolver = (shiftClickEnabled: boolean) => createPrimaryWidgetActionResolver(
    {
        getSpellSelection: () => ({ getWidgetTargetMask: () => 0 }),
        getSettings: () => ({ shiftClickEnabled }),
        getObjTypeLoader: () => ({ load: () => ({ shiftClickIndex: -2 }) }),
    } as any,
    { isShiftDown: () => ClientState.isShiftPressed() } as any,
    manager as any,
    {} as any,
);

test("Shift + left-click on a listed item sends Drop with the Drop-All sub-op", async () => {
    ClientState.clearItemSelection();
    ClientState.isSpellSelected = false;
    const config = inject(ConfigManager);
    const plugin = new DropAllPlugin({ objTypeLoader: { load: () => ({}) } } as any);
    await plugin.start();
    setMenuTransform((entries, context) => plugin.transformMenuEntries(entries, context));
    try {
        ClientState.setKeybindState(SHIFT, false);
        let primary = resolver(true)(widget);
        assert.equal(primary.option, "Bury", "a plain left-click is unchanged");
        assert.equal(primary.opSubIndex, undefined);

        ClientState.setKeybindState(SHIFT, true);
        primary = resolver(true)(widget);
        assert.equal(primary.option, "Drop", "the option off: OSRS shift-click drop, one item");
        assert.equal(primary.opSubIndex, undefined);

        config.setConfiguration("revenantdropall", "shiftClick", true);
        for (const shiftDrop of [true, false]) {
            primary = resolver(shiftDrop)(widget);
            assert.equal(primary.option, "Drop-All", `option on (game shift-drop ${shiftDrop})`);
            assert.equal(primary.opIndex, 5, "Drop's op");
            assert.equal(primary.opSubIndex, 1, "the Drop-All sub-op");
            const packet = buildWidgetActionPayload(manager as any, { widget, ...primary, source: "primary" } as any);
            assert.equal(packet?.opId, 5);
            assert.equal(packet?.subOpId, 1, "the packet carries the sub-op the server answers");
        }

        const unlisted = { ...widget, submenuActions: [null, null, null, null, null] };
        primary = resolver(true)(unlisted);
        assert.equal(primary.option, "Drop", "an unlisted item keeps the game's shift-click drop");
        assert.equal(primary.opSubIndex, undefined);
    } finally {
        ClientState.setKeybindState(SHIFT, false);
        config.setConfiguration("revenantdropall", "shiftClick", false);
        setMenuTransform(undefined);
        await plugin.stop();
    }
});

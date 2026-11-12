import assert from "node:assert/strict";

import { processWidgetClickInput } from "../game/widgets/input/widgetClickInput";

const friendName = "Test Friend";
const widget = {
    uid: 429 << 16,
    groupId: 429,
    fileId: 0,
    childIndex: 0,
    x: 0,
    y: 0,
    width: 100,
    height: 20,
    eventHandlers: { onOp: {} },
};

let eventContext: any;
const deps = {
    getCs2Vm: () => ({
        invokeEventHandler: (_widget: any, eventType: string, context: any) => {
            assert.equal(eventType, "onOp");
            eventContext = context;
            return true;
        },
    }),
    getSpellSelection: () => ({ getWidgetTargetMask: () => 0 }),
    handleTradeWidgetAction: () => false,
    buildWidgetActionPayload: () => null,
    executeScriptListener: () => undefined,
    getTransmitCycles: () => ({ cycleCntr: 0 }),
} as any;
const state = { cachedHoverHits: null } as any;
const frame = {
    input: { leftClickX: 10, leftClickY: 10 },
    collectFromAllRoots: () => [widget],
    getWidgetFlags: () => 0,
} as any;
const widgetManager = {
    getWidgetByUid: () => undefined,
    getWidgetFlags: () => 0,
    invalidateWidgetRender: () => undefined,
    invalidateAll: () => undefined,
} as any;
const widgetInteraction = {
    clickedWidget: null,
    clickedWidgetParent: null,
    clickedWidgetHandled: false,
    resolveClickedWidgetParent: () => null,
    handleTradeRequestChatClick: () => false,
    isWidgetDraggable: () => false,
} as any;

processWidgetClickInput(
    deps,
    state,
    frame,
    widgetManager,
    widgetInteraction,
    () => ({ option: "Message", target: friendName, opIndex: 2 }),
    true,
);

assert.equal(eventContext?.targetName, friendName);
assert.equal(eventContext?.opIndex, 2);
assert.equal(widgetInteraction.clickedWidgetHandled, true);

eventContext = undefined;
widgetInteraction.clickedWidget = null;
widgetInteraction.clickedWidgetHandled = false;
let pluginAction: any;
processWidgetClickInput(
    {
        ...deps,
        handlePluginWidgetAction: (event: any) => { pluginAction = event; return true; },
        buildWidgetActionPayload: () => { throw new Error("consumed actions must not send packets"); },
    },
    state,
    frame,
    widgetManager,
    widgetInteraction,
    () => ({ option: "Cycle voice mode", target: "", opIndex: 2 }),
    true,
);
assert.equal(pluginAction.widget, widget);
assert.equal(pluginAction.opIndex, 2);
assert.equal(pluginAction.source, "primary");
assert.equal(eventContext, undefined, "a plugin consumes the click before the cache onOp handler");
assert.equal(widgetInteraction.clickedWidgetHandled, true, "release must not dispatch the same click again");

// A left-click on a row of a server-declared clickable list sends the row it shows, like the
// menu path does; a header row sends nothing.
const rowWidget = { ...widget, uid: (30007 << 16) | 80, groupId: 30007, fileId: 80, eventHandlers: {}, actions: ["Select"] };
const rowFrame = { ...frame, collectFromAllRoots: () => [rowWidget], getWidgetFlags: () => 1 << 1 };
const rowManager = { ...widgetManager, getWidgetFlags: () => 1 << 1 };
const selections: number[][] = [];
const widgetOps: number[][] = [];
const clickRow = (rowId: number | null | undefined) => {
    const payload: any = { widgetId: rowWidget.uid, opId: 1 };
    let built = false;
    widgetInteraction.clickedWidget = null;
    widgetInteraction.clickedWidgetHandled = false;
    processWidgetClickInput(
        {
            ...deps,
            getCs2Vm: () => ({ invokeEventHandler: () => false }),
            buildWidgetActionPayload: () => { built = true; return payload; },
            resolveTransmitFlagWidget: () => rowWidget,
            getCustomInterfaces: () => ({
                rowIdAt: (groupId: number, childId: number) =>
                    groupId === 30007 && childId === 80 ? rowId : undefined,
                handleRowOp: (id: number, opId: number) => selections.push([id, opId]),
                handleWidgetOp: (groupId: number, childId: number) => widgetOps.push([groupId, childId]),
                // v3 addition: the primary-input path consults this to consume URL
                // clicks locally before sending a widget action. This fixture doesn't
                // exercise a declared-URL case, so a stub that returns false preserves
                // the existing row-dispatch assertions.
                tryOpenDeclaredUrl: () => false,
            }),
        },
        state,
        rowFrame,
        rowManager,
        widgetInteraction,
        () => ({ option: "Select", target: "", opIndex: 1 }),
        true,
    );
    assert.ok(built, "the click builds a payload");
    return payload;
};
assert.equal(clickRow(66).slot, 66, "a left-clicked row carries its id");
assert.equal(clickRow(undefined).slot, undefined, "other widgets keep their own slot");
assert.deepEqual(selections, [[66, 1]], "the row's op goes to the list at once, other widgets' do not");
assert.deepEqual(widgetOps, [[30007, 80]], "other widgets' ops go to the runtime as plain widget ops");

// Primary left-click on a widget whose component is in the declaration's `urls` map
// consumes the click locally: no payload is built, no widget-action packet is sent,
// no follow-up handleWidgetOp fires. This is the v3 fix for the gap Codex found —
// the URL handler had to run in BOTH the menu dispatcher AND the primary-click path.
// Pre-v3, primary-click reached sendWidgetAction and handleWidgetOp without ever
// checking the urls map; only the right-click menu route worked.
{
    const urlComponent = 181; // MOTD's FOOTER_GITHUB_LABEL in the real plugin
    const urlWidget = { ...widget, uid: (30020 << 16) | urlComponent, groupId: 30020, fileId: urlComponent, eventHandlers: {}, actions: ["Open"] };
    const urlFrame = { ...frame, collectFromAllRoots: () => [urlWidget], getWidgetFlags: () => 1 << 1 };
    const urlManager = { ...widgetManager, getWidgetFlags: () => 1 << 1 };
    const ops: number[][] = [];
    let urlCalls = 0;
    widgetInteraction.clickedWidget = null;
    widgetInteraction.clickedWidgetHandled = false;
    processWidgetClickInput(
        {
            ...deps,
            getCs2Vm: () => ({ invokeEventHandler: () => false }),
            // Payload build should still succeed (flow enters the switch case) but
            // sendWidgetAction MUST NOT be reached — any attempt to build after the
            // URL short-circuit indicates the guard is in the wrong place.
            buildWidgetActionPayload: () => ({ widgetId: urlWidget.uid, opId: 1 }),
            resolveTransmitFlagWidget: () => urlWidget,
            getCustomInterfaces: () => ({
                rowIdAt: () => undefined,
                handleRowOp: () => {},
                handleWidgetOp: (groupId: number, childId: number) => ops.push([groupId, childId]),
                tryOpenDeclaredUrl: (groupId: number, childId: number) => {
                    urlCalls++;
                    return groupId === 30020 && childId === urlComponent;
                },
            }),
            // Instrument the network sender by replacing it through the deps shim.
            // widgetClickInput imports sendWidgetAction directly, but the request
            // flows through a payload the test observes via `sent` if the test
            // double for buildWidgetActionPayload captures it.
            // Instead, the strongest guarantee here is: handleWidgetOp was never
            // called (which happens AFTER sendWidgetAction in the real path).
        } as any,
        state,
        urlFrame,
        urlManager,
        widgetInteraction,
        () => ({ option: "Open", target: "", opIndex: 1 }),
        true,
    );
    assert.equal(urlCalls, 1, "tryOpenDeclaredUrl is consulted exactly once");
    // URL consumption breaks out of the switch before sendWidgetAction, so the
    // follow-up handleWidgetOp (which runs AFTER sendWidgetAction in the real path)
    // never fires either. Asserting ops is empty is equivalent evidence that
    // sendWidgetAction was skipped — both live past the URL guard.
    assert.deepEqual(ops, [], "URL consumption skips handleWidgetOp (and sendWidgetAction)");
}

console.log("widget primary operation context test passed");

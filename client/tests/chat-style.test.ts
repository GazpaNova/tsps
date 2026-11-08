import assert from "node:assert/strict";

import { parseOutgoingPublicChat } from "../common/chat/chatFormatting";
import { ClientBinaryEncoder } from "../network/packet/ClientBinaryEncoder";
import { decodeClientPacket } from "../../server/src/main/typescript/elvarg/net/protocol/ClientProtocol";

/** Public chat's colour and effect ("red:wave:") reach the server with the text. */
const encoder = new ClientBinaryEncoder();
const send = (typed: string) => {
    const formatting = parseOutgoingPublicChat(typed);
    return decodeClientPacket(Buffer.from(encoder.encodeChat(formatting.text, "public", false, formatting)));
};

const plain = send("hello") as any;
assert.equal(plain.text, "hello");
assert.equal(plain.style, undefined, "no style without a prefix");

const styled = send("red:wave:hello") as any;
assert.equal(styled.text, "hello");
assert.deepEqual(styled.style, { colour: 1, effect: 1, pattern: undefined });
console.log("Chat style tests passed");

// Deployment regression: Spanish chat survives filtering and the existing wire format.
import { sanitizeChatText, isValidChatChar } from "../common/chat/chatFormatting";
const spanish = "\u00e1\u00e9\u00ed\u00f3\u00fa\u00fc\u00f1 \u00c1\u00c9\u00cd\u00d3\u00da\u00dc\u00d1";
assert.equal(sanitizeChatText(spanish), spanish);
for (const letter of spanish) assert.equal(isValidChatChar(letter), true);
const formatted = parseOutgoingPublicChat("red:wave:" + sanitizeChatText(spanish));
const received = decodeClientPacket(Buffer.from(encoder.encodeChat(formatted.text, "public", false, formatted))) as any;
assert.equal(received.text, spanish);
assert.equal(received.style.colour, 1);
assert.equal(received.style.effect, 1);
assert.equal(sanitizeChatText("  Hello, world!  "), "Hello, world!");
assert.equal(sanitizeChatText("A\u0000\n\u007f\u0080B"), "AB");
assert.equal(isValidChatChar("\u4e2d"), false, "do not enable unsupported multibyte characters");
assert.equal(isValidChatChar(""), false);
console.log("Spanish chat filter and client-to-server round-trip checks passed");

// Deployment regression: friends-list styling changes status labels, never world data or names.
import { FriendsOnlinePlugin } from "../game/plugins/friendsonline/FriendsOnlinePlugin";
import { ConfigManager } from "../runelite/client/config/ConfigManager";
import { CLIENT_TOKEN, PluginInjector } from "../runelite/client/plugins/PluginInjector";
const friendRows: any[] = [
    { text: "World 1", xTextAlignment: 0, textColor: 0xffffff },
    { text: "World 1", xTextAlignment: 2, textColor: 0x0dc10d },
    { text: "<col=ffff00>World 2", xTextAlignment: 2, textColor: 0xffff00 },
    { text: "Offline", xTextAlignment: 2, textColor: 0xff0000 },
];
let friendInvalidations = 0;
const friendPlugin = new PluginInjector(new ConfigManager(undefined))
    .provide(CLIENT_TOKEN, { widgetManager: {
        getWidgetByUid: (uid: number) => { assert.equal(uid, (429 << 16) | 11); return {}; },
        getDynamicChildrenByParent: () => friendRows,
        invalidateWidgetRender: () => { friendInvalidations++; },
    } })
    .runWith(() => new FriendsOnlinePlugin());
friendPlugin.onScriptFinished(124);
assert.equal(friendInvalidations, 0);
friendPlugin.onScriptFinished(125);
assert.equal(friendRows[0].text, "World 1", "a player named World 1 stays unchanged");
assert.equal(friendRows[0].textColor, 0xffffff);
for (const row of friendRows.slice(1, 3)) {
    assert.equal(row.text, "Online");
    assert.equal(row.textColor, 0x00ff00);
}
assert.equal(friendRows[3].text, "Offline");
assert.equal(friendRows[3].textColor, 0xff0000);
friendPlugin.onScriptFinished(125);
assert.equal(friendInvalidations, 2, "unchanged labels do not trigger redundant redraws");
friendRows[1].text = "Offline";
friendRows[1].textColor = 0xff0000;
friendPlugin.onScriptFinished(125);
assert.equal(friendRows[1].text, "Offline", "a friend logging out stays offline");
friendRows[1].text = "World 1";
friendPlugin.onScriptFinished(125);
assert.equal(friendRows[1].text, "Online", "a friend logging back in is restyled");
console.log("Friends-list green Online labels preserve names, offline state and redraw behavior");

import assert from "node:assert/strict";

import { isValidChatChar, parseOutgoingPublicChat, sanitizeChatText } from "../common/chat/chatFormatting";
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

// Chat accepts CP-1252's printable upper half (160-255), and it reaches the server unchanged.
const latin1 = Array.from({ length: 96 }, (_, i) => String.fromCharCode(160 + i)).join("");
for (const char of latin1) assert.equal(isValidChatChar(char), true, `U+${char.charCodeAt(0).toString(16)}`);
const accented = "¿Qué tal, señor Müller? ¡Ça coûte £5 à Århus!";
assert.equal(sanitizeChatText(accented), accented);
const typed = "a" + latin1 + "z";
assert.equal(sanitizeChatText(typed), typed);
const received = send("red:wave:" + sanitizeChatText(typed)) as any;
assert.equal(received.text, typed);
assert.deepEqual(received.style, { colour: 1, effect: 1, pattern: undefined });
assert.equal(send(accented).text, accented);
// Control characters (C0, DEL, C1) and characters outside one byte stay out.
assert.equal(sanitizeChatText("A\u0000\n\u007f\u0080\u009fB"), "AB");
for (const char of ["\u20ac", "\u0152", "\u0100", "\u4e2d", "\ud83d\ude00", ""])
    assert.equal(isValidChatChar(char), false, JSON.stringify(char));
console.log("Chat style tests passed");

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

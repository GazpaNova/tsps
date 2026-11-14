import { Plugin, type PluginDescriptor } from "@runelite/client/plugins/Plugin";
import { CLIENT_TOKEN, inject } from "@runelite/client/plugins/PluginInjector";
import type { OsrsClient } from "../../OsrsClient";

// Revision 241: friends_update (125), FRIEND_LIST_NAMES_CONTAINER (429:11).
const FRIENDS_UPDATE_SCRIPT = 125;
const FRIEND_ROWS_UID = (429 << 16) | 11;
const GREEN = 0x00ff00;

/** Only the right-aligned status column changes; names and world numbers stay intact. */
export class FriendsOnlinePlugin extends Plugin {
    static descriptor: PluginDescriptor = {
        name: "Revenant Online Status",
        description: "Show online friends with a green Online label.",
        hidden: true,
        enabledByDefault: true,
        configKey: "revenantfriendsonline",
    };

    private readonly client = inject<OsrsClient>(CLIENT_TOKEN);

    onScriptFinished(scriptId: number): void {
        if (scriptId !== FRIENDS_UPDATE_SCRIPT) return;
        const manager = this.client.widgetManager;
        if (!manager?.getWidgetByUid(FRIEND_ROWS_UID)) return;
        for (const widget of manager.getDynamicChildrenByParent(FRIEND_ROWS_UID)) {
            if (widget.xTextAlignment !== 2) continue;
            const text = (widget.text ?? "").replace(/<[^>]*>/g, "");
            if (!/^(?:World|Classic) \d+$/.test(text)) continue;
            widget.text = "Online";
            widget.textColor = GREEN;
            manager.invalidateWidgetRender(widget, "revenant-friends-online");
        }
    }
}

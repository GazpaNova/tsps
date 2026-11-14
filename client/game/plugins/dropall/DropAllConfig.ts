import { ConfigGroup, ConfigItem } from "@runelite/client/config/ConfigItem";

/** Revenant custom: which items get Drop-All (game/plugins/dropall/DropAllPlugin.ts). */
export const DropAllConfig = ConfigGroup("revenantdropall", {
    items: ConfigItem({
        name: "Items",
        description:
            "Items that get Drop-All under Drop. Format: item1, item2, *ore, \"exact name\". " +
            "Shift + right-click an inventory item to add or remove it.",
        position: 0,
        textArea: true,
        default: "",
    }),
    allItems: ConfigItem({
        name: "All items",
        description: "Give every droppable, non-stackable item Drop-All, ignoring the list",
        position: 1,
        default: false,
    }),
    shiftClick: ConfigItem({
        name: "Shift-click Drop-All",
        description: "Shift + left-click drops every one of a listed item (over Menu Swapper's shift-click for those items)",
        position: 2,
        default: false,
    }),
});

package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Integration-style test for the decode→apply chain using the exact frame sequence
 * the Voidburn server emits to open the MOTD interface (group 30000) as a modal root.
 *
 * Mirrors the gateway's actual forwarding shape for custom groups as of Phase 2c-1:
 * one widget_open with modal=true, one widget_set_root, and one widget_open_sub that
 * mounts the MOTD body into the gameframe. After this sequence the plugin should see
 * group 30000 as the current root and have one sub-mount tracked.
 */
class MotdFrameSequenceTest {

    @Test
    void motdOpenSequenceResultsInRootAndSubMount() {
        InterfaceState state = new InterfaceState();

        // 1. Server sends widget_open(30000, modal=true).
        String openJson = """
            {"type":"widget_open","group":30000,"modal":true}""";
        state.apply(FrameDecoder.decode(openJson));

        // 2. Server sends widget_set_root(30000).
        String setRootJson = """
            {"type":"widget_set_root","group":30000}""";
        state.apply(FrameDecoder.decode(setRootJson));

        // 3. Server mounts a sub-interface under the MOTD root. Target UID packs
        //    the parent group (30000) into the high 16 bits.
        int motdBodyTarget = (30000 << 16) | 10;
        String openSubJson = String.format("""
            {"type":"widget_open_sub","rawTarget":%d,"target":%d,"group":30005,
             "interfaceType":0,"hiddenUids":[]}""", motdBodyTarget, motdBodyTarget);
        state.apply(FrameDecoder.decode(openSubJson));

        // Final state.
        assertEquals(30000, state.rootGroup().orElseThrow());
        assertTrue(state.isOpen(30000));
        InterfaceState.Interface motd = state.get(30000).orElseThrow();
        assertTrue(motd.modal());
        assertEquals(1, motd.subMounts().size());
        InterfaceState.SubMount body = motd.subMounts().get(motdBodyTarget);
        assertEquals(30005, body.group());
        assertEquals(0, body.interfaceType());
        assertTrue(body.hiddenUids().isEmpty());
    }

    @Test
    void motdOpenWithTextAndHiddenUpdatesReachesComponentState() {
        // Phase 2c-3b extends the sequence: the server follows the structural
        // open/sub frames with per-component sendString and sendInterfaceHiddenFlag
        // for the MOTD title, body and a toggled error panel. All of those land
        // in the component table of the MOTD Interface.
        InterfaceState state = new InterfaceState();
        int titleUid = (30000 << 16) | 1;
        int bodyUid = (30000 << 16) | 2;
        int errorUid = (30000 << 16) | 3;

        state.apply(FrameDecoder.decode(
            """
            {"type":"widget_open","group":30000,"modal":true}"""));
        state.apply(FrameDecoder.decode(
            """
            {"type":"widget_set_root","group":30000}"""));
        state.apply(FrameDecoder.decode(String.format(
            """
            {"type":"widget_set_text","widgetId":%d,"text":"Welcome back, Miguel!"}""",
            titleUid)));
        state.apply(FrameDecoder.decode(String.format(
            """
            {"type":"widget_set_text","widgetId":%d,"text":"Server news and updates."}""",
            bodyUid)));
        state.apply(FrameDecoder.decode(String.format(
            """
            {"type":"widget_set_hidden","widgetId":%d,"hidden":true}""",
            errorUid)));

        assertEquals(30000, state.rootGroup().orElseThrow());
        InterfaceState.Interface motd = state.get(30000).orElseThrow();
        assertEquals("Welcome back, Miguel!", motd.components().get(1).text());
        assertEquals("Server news and updates.", motd.components().get(2).text());
        assertEquals(true, motd.components().get(3).hidden());
    }

    @Test
    void unknownFrameMidSequenceDoesNotDisturbState() {
        // Forward-compat: a frame type the decoder doesn't yet know
        // (e.g. a future widget_set_item) is simply ignored, not fatal. We use
        // widget_set_item here because widget_set_text and widget_set_hidden
        // are both now known types.
        InterfaceState state = new InterfaceState();
        state.apply(FrameDecoder.decode("""
            {"type":"widget_open","group":30000,"modal":true}"""));
        state.apply(FrameDecoder.decode("""
            {"type":"widget_set_item","widgetId":123,"obj":4151,"count":1}"""));
        state.apply(FrameDecoder.decode("""
            {"type":"widget_set_root","group":30000}"""));
        assertEquals(30000, state.rootGroup().orElseThrow());
        assertTrue(state.isOpen(30000));
    }
}

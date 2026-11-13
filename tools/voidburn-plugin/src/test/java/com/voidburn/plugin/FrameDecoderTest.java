package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FrameDecoder is the gateway-to-plugin wire boundary. These tests pin:
 *  - every frame type currently forwarded by the gateway decodes to the right record
 *    with the right field values,
 *  - malformed input decodes to Unknown (not thrown, not partial),
 *  - unknown {@code type} values decode to Unknown so the plugin stays forward-
 *    compatible when the gateway grows new frame types,
 *  - field-type mismatches (wrong JSON type for a required field) never produce a
 *    partially-populated record.
 */
class FrameDecoderTest {

    @Test
    void widgetOpenWithGroupAndModal() {
        Frame f = FrameDecoder.decode("""
            {"type":"widget_open","group":30000,"modal":true}""");
        Frame.WidgetOpen o = assertInstanceOf(Frame.WidgetOpen.class, f);
        assertEquals(30000, o.group());
        assertTrue(o.modal());
    }

    @Test
    void widgetOpenNonModal() {
        Frame f = FrameDecoder.decode("""
            {"type":"widget_open","group":30007,"modal":false}""");
        Frame.WidgetOpen o = assertInstanceOf(Frame.WidgetOpen.class, f);
        assertEquals(30007, o.group());
        assertEquals(false, o.modal());
    }

    @Test
    void widgetSetRoot() {
        Frame f = FrameDecoder.decode("""
            {"type":"widget_set_root","group":30000}""");
        Frame.WidgetSetRoot r = assertInstanceOf(Frame.WidgetSetRoot.class, f);
        assertEquals(30000, r.group());
    }

    @Test
    void widgetOpenSubCarriesEveryField() {
        Frame f = FrameDecoder.decode("""
            {"type":"widget_open_sub","rawTarget":12345,"target":67890,
             "group":30000,"interfaceType":0,"hiddenUids":[100,200,300]}""");
        Frame.WidgetOpenSub s = assertInstanceOf(Frame.WidgetOpenSub.class, f);
        assertEquals(12345, s.rawTarget());
        assertEquals(67890, s.target());
        assertEquals(30000, s.group());
        assertEquals(0, s.interfaceType());
        assertEquals(List.of(100, 200, 300), s.hiddenUids());
    }

    @Test
    void widgetOpenSubWithEmptyHiddenList() {
        Frame f = FrameDecoder.decode("""
            {"type":"widget_open_sub","rawTarget":1,"target":2,
             "group":30001,"interfaceType":0,"hiddenUids":[]}""");
        Frame.WidgetOpenSub s = assertInstanceOf(Frame.WidgetOpenSub.class, f);
        assertTrue(s.hiddenUids().isEmpty());
    }

    @Test
    void unknownTypeDecodesToUnknown() {
        // Forward-compat: the gateway may add widget_set_text in Phase 2c-3; this
        // plugin shouldn't crash before the decoder case lands.
        Frame f = FrameDecoder.decode("""
            {"type":"widget_set_text","widgetId":123,"text":"hello"}""");
        Frame.Unknown u = assertInstanceOf(Frame.Unknown.class, f);
        assertEquals("widget_set_text", u.type());
    }

    @Test
    void missingTypeDecodesToUnknown() {
        Frame f = FrameDecoder.decode("""
            {"group":30000,"modal":true}""");
        Frame.Unknown u = assertInstanceOf(Frame.Unknown.class, f);
        assertEquals("", u.type());
    }

    @Test
    void malformedJsonDecodesToUnknown() {
        Frame f = FrameDecoder.decode("not json at all");
        assertInstanceOf(Frame.Unknown.class, f);
    }

    @Test
    void nullOrEmptyDecodesToUnknown() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(null));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(""));
    }

    @Test
    void nonObjectTopLevelDecodesToUnknown() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("[]"));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("\"widget_open\""));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("42"));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("null"));
    }

    @Test
    void widgetOpenMissingGroupDecodesToUnknown() {
        Frame f = FrameDecoder.decode("""
            {"type":"widget_open","modal":true}""");
        Frame.Unknown u = assertInstanceOf(Frame.Unknown.class, f);
        assertEquals("widget_open", u.type());
    }

    @Test
    void widgetOpenMissingModalDecodesToUnknown() {
        Frame f = FrameDecoder.decode("""
            {"type":"widget_open","group":30000}""");
        assertInstanceOf(Frame.Unknown.class, f);
    }

    @Test
    void widgetOpenWrongFieldTypeDecodesToUnknown() {
        // Regression: a stringy "group" or non-boolean "modal" must NOT produce a
        // partially-filled WidgetOpen. Strict typing matches the gateway's own
        // inbound-parser strictness.
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("""
            {"type":"widget_open","group":"30000","modal":true}"""));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("""
            {"type":"widget_open","group":30000,"modal":"true"}"""));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("""
            {"type":"widget_open","group":30000,"modal":1}"""));
    }

    @Test
    void widgetOpenFractionalGroupDecodesToUnknown() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("""
            {"type":"widget_open","group":30000.5,"modal":true}"""));
    }

    @Test
    void widgetOpenOutOfRangeGroupDecodesToUnknown() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("""
            {"type":"widget_open","group":9999999999,"modal":true}"""));
    }

    @Test
    void widgetSetRootMissingGroupDecodesToUnknown() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("""
            {"type":"widget_set_root"}"""));
    }

    @Test
    void widgetOpenSubMissingAnyRequiredFieldDecodesToUnknown() {
        String[] malformed = {
            """
            {"type":"widget_open_sub","target":2,"group":30000,"interfaceType":0,"hiddenUids":[]}""",
            """
            {"type":"widget_open_sub","rawTarget":1,"group":30000,"interfaceType":0,"hiddenUids":[]}""",
            """
            {"type":"widget_open_sub","rawTarget":1,"target":2,"interfaceType":0,"hiddenUids":[]}""",
            """
            {"type":"widget_open_sub","rawTarget":1,"target":2,"group":30000,"hiddenUids":[]}""",
            """
            {"type":"widget_open_sub","rawTarget":1,"target":2,"group":30000,"interfaceType":0}""",
        };
        for (String m : malformed) {
            assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(m), "should be Unknown: " + m);
        }
    }

    @Test
    void widgetOpenSubHiddenUidsWithNonNumberDecodesToUnknown() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode("""
            {"type":"widget_open_sub","rawTarget":1,"target":2,
             "group":30000,"interfaceType":0,"hiddenUids":[100,"not-a-number"]}"""));
    }

    @Test
    void extraUnknownFieldsAreIgnored() {
        // The gateway may add new fields to existing frames. As long as the required
        // fields are present and well-typed, decoding succeeds.
        Frame f = FrameDecoder.decode("""
            {"type":"widget_open","group":30000,"modal":true,"future_field":"extra"}""");
        Frame.WidgetOpen o = assertInstanceOf(Frame.WidgetOpen.class, f);
        assertEquals(30000, o.group());
    }

    @Test
    void unknownCarriesRawJsonForLogging() {
        String json = """
            {"type":"some_new_frame","x":1}""";
        Frame.Unknown u = assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(json));
        assertEquals(json, u.rawJson());
    }

    // --- Strict-syntax regressions (Phase 2c-2b review P2) ---

    @Test
    void unquotedKeysAreRejected() {
        // Regression: Gson 2.8.5's JsonParser convenience path transiently enables
        // leniency; the strict JsonReader-driven parser must refuse unquoted keys.
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{type:'widget_set_root',group:30000}"));
    }

    @Test
    void jsStyleCommentsAreRejected() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"widget_set_root\",/*comment*/\"group\":30000}"));
    }

    @Test
    void singleQuotedStringsAreRejected() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{'type':'widget_set_root','group':30000}"));
    }

    @Test
    void duplicateTopLevelKeysAreRejected() {
        // Regression: v1 silently took the last value for a duplicate key, which
        // would let a hostile frame shadow an earlier field. Any duplicate at the
        // top level rejects the whole frame.
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"future_event\",\"type\":\"widget_set_root\",\"group\":30000}"));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"widget_set_root\",\"group\":1,\"group\":30000}"));
    }

    @Test
    void trailingContentAfterObjectIsRejected() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"widget_set_root\",\"group\":30000} extra"));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"widget_set_root\",\"group\":30000}{\"type\":\"other\"}"));
    }

    @Test
    void trailingCommaInObjectIsRejected() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"widget_set_root\",\"group\":30000,}"));
    }

    @Test
    void nanAndInfinityInNumbersAreRejected() {
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"widget_open\",\"group\":NaN,\"modal\":true}"));
        assertInstanceOf(Frame.Unknown.class, FrameDecoder.decode(
            "{\"type\":\"widget_open\",\"group\":Infinity,\"modal\":true}"));
    }
}

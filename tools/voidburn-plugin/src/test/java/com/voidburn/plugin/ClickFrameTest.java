package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClickFrameTest {
    @Test
    void shape_matches_gateway_parser_expectations() {
        // Play button uid = (30020 << 16) | 186 = 1967390906. The gateway
        // rejects widgetIds whose group is < 30000, so a MOTD click on
        // component 186 lands in the custom-group whitelist by
        // construction.
        String frame = ClickFrame.build(1967390906, 1);
        assertEquals("{\"type\":\"click\",\"widgetId\":1967390906,\"op\":1}", frame);
    }

    @Test
    void omits_optional_sub_and_obj() {
        // The gateway parser defaults sub and obj to NONE (0xFFFF) when
        // absent. Including them would be redundant for a widget-root
        // click and would make the frame larger for no benefit.
        String frame = ClickFrame.build(1967390906, 1);
        // Positive: required keys present.
        assertEquals(1, countOccurrences(frame, "\"widgetId\""));
        assertEquals(1, countOccurrences(frame, "\"op\""));
        // Negative: absent keys actually absent.
        assertEquals(0, countOccurrences(frame, "\"sub\""));
        assertEquals(0, countOccurrences(frame, "\"obj\""));
    }

    @Test
    void negative_widget_id_serializes_as_signed_decimal() {
        // A high-bit-set uid encodes as a negative signed int. The gateway
        // parser accepts negative widgetIds (the custom-group check uses
        // an unsigned shift), so the frame should keep the minus sign.
        String frame = ClickFrame.build(-1, 1);
        assertEquals("{\"type\":\"click\",\"widgetId\":-1,\"op\":1}", frame);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}

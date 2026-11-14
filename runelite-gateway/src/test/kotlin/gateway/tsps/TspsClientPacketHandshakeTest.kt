package gateway.tsps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the handshake's wire layout and the capability-byte encoding.
 *
 * Layout (TspsWriter.varByte):
 *
 *   byte 0        = opcode (HANDSHAKE)
 *   byte 1        = body length (payload bytes that follow)
 *   bytes 2..n-4  = username (ISO_8859_1 + trailing NUL)
 *   byte n-3      = 0 (no appearance block)
 *   byte n-2      = CLIENT_TYPE_DESKTOP (= 0)
 *   byte n-1      = capability byte = CAPABILITY_NATIVE_CLIENT OR extraCapabilities
 *
 * The capability byte is the last byte of the frame (frame.last()).
 * Earlier shape of this file read frame[frame.size - 2]; that's the
 * client-type byte, not the capability byte — see the v1 review.
 */
class TspsClientPacketHandshakeTest {
    private val HANDSHAKE_OPCODE: Byte = 202.toByte()

    @Test
    fun `handshake with voidburn plugin advertises both capability bits`() {
        val frame = TspsClientPacket.handshake(
            username = "Miguel",
            extraCapabilities = TspsClientPacket.CAPABILITY_VOIDBURN_PLUGIN,
        )
        assertHandshakeFraming(frame, username = "Miguel")
        val capByte = frame.last().toInt() and 0xFF
        assertEquals(
            TspsClientPacket.CAPABILITY_NATIVE_CLIENT or TspsClientPacket.CAPABILITY_VOIDBURN_PLUGIN,
            capByte,
            "handshake capability byte must include both NATIVE_CLIENT (always) and VOIDBURN_PLUGIN",
        )
    }

    @Test
    fun `handshake without extra capabilities carries only native_client`() {
        val frame = TspsClientPacket.handshake(username = "Miguel")
        assertHandshakeFraming(frame, username = "Miguel")
        val capByte = frame.last().toInt() and 0xFF
        assertEquals(
            TspsClientPacket.CAPABILITY_NATIVE_CLIENT, capByte,
            "default handshake must be NATIVE_CLIENT only",
        )
    }

    @Test
    fun `voidburn plugin bit is distinct from native client bit`() {
        // Belt-and-suspenders constant check so a well-meaning cleanup can't
        // unify the two into a single mask value.
        assertNotEquals(
            TspsClientPacket.CAPABILITY_NATIVE_CLIENT,
            TspsClientPacket.CAPABILITY_VOIDBURN_PLUGIN,
        )
        assertTrue(
            (TspsClientPacket.CAPABILITY_NATIVE_CLIENT and TspsClientPacket.CAPABILITY_VOIDBURN_PLUGIN) == 0,
            "capability bits must occupy disjoint positions",
        )
    }

    /**
     * Assert the frame matches the varByte layout documented at the top of
     * this file, independent of whichever capability bits the caller asked
     * for. Fails loudly if a future codec change moves bytes around under
     * our feet.
     */
    private fun assertHandshakeFraming(frame: ByteArray, username: String) {
        assertEquals(HANDSHAKE_OPCODE, frame[0], "opcode byte")
        val bodyLen = frame[1].toInt() and 0xFF
        assertEquals(frame.size - 2, bodyLen, "length byte == remaining body size")
        // Username bytes + trailing NUL.
        val usernameBytes = username.toByteArray(Charsets.ISO_8859_1)
        for ((i, b) in usernameBytes.withIndex()) {
            assertEquals(b, frame[2 + i], "username byte $i")
        }
        assertEquals(0.toByte(), frame[2 + usernameBytes.size], "username NUL terminator")
        // Three trailing fixed bytes: appearance (0), client type (0), capability (variable).
        assertEquals(0.toByte(), frame[frame.size - 3], "appearance byte")
        assertEquals(0.toByte(), frame[frame.size - 2], "CLIENT_TYPE_DESKTOP byte")
        // Capability byte is frame.last(); asserted by the caller.
    }
}

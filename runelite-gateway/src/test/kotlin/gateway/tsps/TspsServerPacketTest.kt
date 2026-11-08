package gateway.tsps

import gateway.headless.TestCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** The gateway's copy of tsps's server opcode table must match ServerPackets.ts. */
class TspsServerPacketTest {
    @Test
    fun `lengths match ServerPackets ts`() {
        val source = Files.readString(TestCache.config.root.resolve("server/src/main/typescript/elvarg/net/protocol/ServerPackets.ts"))
        val (enumPart, lengthPart) = source.split("SERVER_PACKET_LENGTHS", limit = 2)
        val ids =
            Regex("""^\s+([A-Z0-9_]+)\s*=\s*(\d+),""", RegexOption.MULTILINE)
                .findAll(enumPart)
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val lengths =
            Regex("""\[ServerPacketId\.([A-Z0-9_]+)]:\s*(-?\d+)""")
                .findAll(lengthPart)
                .associate { ids.getValue(it.groupValues[1]) to it.groupValues[2].toInt() }
        assertEquals(lengths, TspsServerPacket.LENGTHS)
    }
    @Test
    fun `expanded run energy preserves exact units and the next packet boundary`() {
        val energy = TspsWriter().u8(73).u8(1).u16(7345).u16(65524).u16(50).u16(1)
            .fixed(TspsServerPacket.RUN_ENERGY)
        val next = TspsWriter().u8(7).fixed(TspsServerPacket.ATTACK_TIMER)
        val packets = TspsCodec.split(energy + next)
        assertEquals(listOf(TspsServerPacket.RUN_ENERGY, TspsServerPacket.ATTACK_TIMER), packets.map { it.opcode })
        val reader = packets[0].reader()
        assertEquals(73, reader.u8())
        assertEquals(1, reader.u8())
        assertEquals(7345, reader.u16())
        assertEquals(-12, reader.s16())
        assertEquals(50, reader.u16())
        assertEquals(1, reader.u16())
        assertEquals(0, reader.remaining)
        assertEquals(7, packets[1].reader().u8())
    }

}

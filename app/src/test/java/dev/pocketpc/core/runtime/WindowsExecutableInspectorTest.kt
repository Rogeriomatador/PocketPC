package dev.pocketpc.core.runtime

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WindowsExecutableInspectorTest {
    private fun inspect(machine: Int = 0x8664, magic: Int = 0x20b, flags: Int = 2, subsystem: Int = 2,
                        modify: (ByteBuffer) -> Unit = {}): WindowsExecutableInspection {
        val bytes = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putShort(0, 0x5a4d.toShort())
        bytes.putInt(0x3c, 128)
        bytes.putInt(128, 0x4550)
        bytes.putShort(132, machine.toShort())
        bytes.putShort(134, 1)
        bytes.putShort(148, 240)
        bytes.putShort(150, flags.toShort())
        bytes.putShort(152, magic.toShort())
        bytes.putShort(220, subsystem.toShort())
        modify(bytes)
        val path = Files.createTempFile("pocketpc-pe-", ".exe")
        return try {
            Files.write(path, bytes.array())
            WindowsExecutableInspector.inspect(path.toFile())
        } finally { Files.delete(path) }
    }

    @Test fun acceptsX64GuiAndConsole() {
        assertTrue(inspect().runnable)
        assertTrue(inspect(subsystem = 3).runnable)
        assertEquals(WindowsExecutableArchitecture.X64, inspect().architecture)
    }
    @Test fun identifiesX86AndArm64WithoutClaimingExecutionSupport() {
        assertEquals("WINDOWS_X86_WOW64_NOT_VALIDATED", inspect(0x14c, 0x10b).blocker)
        assertEquals("WINDOWS_ARM64_RUNTIME_NOT_IMPLEMENTED", inspect(0xaa64).blocker)
    }
    @Test fun rejectsDllsDriversAndMismatchedMachineHeaders() {
        assertEquals("WINDOWS_TARGET_NOT_APPLICATION", inspect(flags = 0x2002).blocker)
        assertEquals("WINDOWS_TARGET_SUBSYSTEM_UNSUPPORTED", inspect(subsystem = 1).blocker)
        assertEquals("WINDOWS_TARGET_PE_ARCHITECTURE_MISMATCH", inspect(magic = 0x10b).blocker)
    }
    @Test fun rejectsUnsignedOrTruncatedOffsetsWithoutSeekingOutsideFile() {
        assertEquals("WINDOWS_TARGET_PE_OFFSET_INVALID", inspect { it.putInt(0x3c, -1) }.blocker)
        assertEquals("WINDOWS_TARGET_PE_OFFSET_INVALID", inspect { it.putInt(0x3c, 10) }.blocker)
        assertEquals("WINDOWS_TARGET_PE_HEADER_TRUNCATED", inspect { it.putShort(148, 65000.toShort()) }.blocker)
        assertEquals("WINDOWS_TARGET_PE_HEADER_TRUNCATED", inspect { it.putShort(134, 0) }.blocker)
        assertEquals("WINDOWS_TARGET_NOT_PE", inspect { it.putShort(0, 0) }.blocker)
    }
}

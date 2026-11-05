package dev.pocketpc.core.runtime

import java.io.File
import java.io.RandomAccessFile

enum class WindowsExecutableArchitecture {
    X86, X64, ARM64, UNKNOWN,
}

data class WindowsExecutableInspection(
    val architecture: WindowsExecutableArchitecture,
    val runnable: Boolean,
    val blocker: String? = null,
)

/** Reads bounded PE headers only; a filename never establishes executable support. */
object WindowsExecutableInspector {
    fun inspect(file: File): WindowsExecutableInspection = try {
        RandomAccessFile(file, "r").use { input ->
            fun rejected(reason: String, arch: WindowsExecutableArchitecture = WindowsExecutableArchitecture.UNKNOWN) =
                WindowsExecutableInspection(arch, false, reason)
            fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
            if (input.length() < 64 || u16() != 0x5a4d) {
                return rejected("WINDOWS_TARGET_NOT_PE")
            }
            input.seek(0x3c)
            val peOffset = u32()
            if (peOffset < 64 || peOffset > input.length() - 24) {
                return rejected("WINDOWS_TARGET_PE_OFFSET_INVALID")
            }
            input.seek(peOffset)
            if (u32() != 0x00004550L) return rejected("WINDOWS_TARGET_PE_SIGNATURE_INVALID")
            val machine = u16()
            val sections = u16()
            input.skipBytes(12)
            val optionalSize = u16()
            val characteristics = u16()
            val architecture = when (machine) {
                0x14c -> WindowsExecutableArchitecture.X86
                0x8664 -> WindowsExecutableArchitecture.X64
                0xaa64 -> WindowsExecutableArchitecture.ARM64
                else -> WindowsExecutableArchitecture.UNKNOWN
            }
            if (sections !in 1..96 || optionalSize < 70 || peOffset + 24 + optionalSize + sections * 40L > input.length()) {
                return rejected("WINDOWS_TARGET_PE_HEADER_TRUNCATED", architecture)
            }
            val magic = u16()
            if ((architecture == WindowsExecutableArchitecture.X86 && magic != 0x10b) ||
                (architecture in setOf(WindowsExecutableArchitecture.X64, WindowsExecutableArchitecture.ARM64) && magic != 0x20b)) {
                return rejected("WINDOWS_TARGET_PE_ARCHITECTURE_MISMATCH", architecture)
            }
            if (characteristics and 0x0002 == 0 || characteristics and 0x2000 != 0) {
                return rejected("WINDOWS_TARGET_NOT_APPLICATION", architecture)
            }
            input.seek(peOffset + 24 + 68)
            val subsystem = u16()
            if (subsystem !in setOf(2, 3)) return rejected("WINDOWS_TARGET_SUBSYSTEM_UNSUPPORTED", architecture)
            when (architecture) {
                WindowsExecutableArchitecture.X64 -> WindowsExecutableInspection(architecture, true)
                WindowsExecutableArchitecture.X86 -> rejected("WINDOWS_X86_WOW64_NOT_VALIDATED", architecture)
                WindowsExecutableArchitecture.ARM64 -> rejected("WINDOWS_ARM64_RUNTIME_NOT_IMPLEMENTED", architecture)
                else -> rejected("WINDOWS_TARGET_MACHINE_UNSUPPORTED", architecture)
            }
        }
    } catch (_: Exception) {
        WindowsExecutableInspection(WindowsExecutableArchitecture.UNKNOWN, false, "WINDOWS_TARGET_PE_READ_FAILED")
    }
}

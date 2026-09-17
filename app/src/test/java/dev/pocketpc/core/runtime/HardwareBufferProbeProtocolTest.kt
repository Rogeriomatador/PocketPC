package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareBufferProbeProtocolTest {
    private val validSend =
        "ahb-xproc-send=ok;protocol=1;pid=100;width=64;height=64;" +
            "layers=1;format=1;stride=64;lock=0;unlock=0;send=0"

    private val validReceive =
        "ahb-xproc-recv=ok;protocol=1;pid=101;width=64;height=64;" +
            "layers=1;format=1;stride=64;recv=0;lock=0;unlock=0;" +
            "descriptor_match=yes;pattern_match=yes"

    @Test
    fun validVersionedRecordsAreSuccessful() {
        val send =
            HardwareBufferProbeProtocol.parse(
                validSend,
                HardwareBufferProbeSide.SEND,
            )
        val receive =
            HardwareBufferProbeProtocol.parse(
                validReceive,
                HardwareBufferProbeSide.RECEIVE,
            )

        assertTrue(send?.successful == true)
        assertTrue(receive?.successful == true)
    }

    @Test
    fun oldProtocolCannotBeSuccessful() {
        val record =
            HardwareBufferProbeProtocol.parse(
                validSend.replace("protocol=1", "protocol=0"),
                HardwareBufferProbeSide.SEND,
            )

        assertFalse(record?.successful == true)
    }

    @Test
    fun duplicateFieldIsRejected() {
        assertNull(
            HardwareBufferProbeProtocol.parse(
                "$validSend;pid=100",
                HardwareBufferProbeSide.SEND,
            ),
        )
    }

    @Test
    fun mixedSendAndReceiveRecordIsRejected() {
        assertNull(
            HardwareBufferProbeProtocol.parse(
                "$validSend;ahb-xproc-recv=ok",
                HardwareBufferProbeSide.SEND,
            ),
        )
    }

    @Test
    fun receiveWithoutPatternProofCannotBeSuccessful() {
        val record =
            HardwareBufferProbeProtocol.parse(
                validReceive.replace(
                    "pattern_match=yes",
                    "pattern_match=no",
                ),
                HardwareBufferProbeSide.RECEIVE,
            )

        assertFalse(record?.successful == true)
    }

    @Test
    fun invalidPidOrStrideCannotBeSuccessful() {
        val invalidPid =
            HardwareBufferProbeProtocol.parse(
                validSend.replace("pid=100", "pid=0"),
                HardwareBufferProbeSide.SEND,
            )
        val invalidStride =
            HardwareBufferProbeProtocol.parse(
                validSend.replace("stride=64", "stride=63"),
                HardwareBufferProbeSide.SEND,
            )

        assertFalse(invalidPid?.successful == true)
        assertFalse(invalidStride?.successful == true)
    }

    @Test
    fun controlCharactersAreRejected() {
        assertNull(
            HardwareBufferProbeProtocol.parse(
                "$validSend\nforged=true",
                HardwareBufferProbeSide.SEND,
            ),
        )
    }
}

package dev.pocketpc.core.runtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import java.util.concurrent.atomic.AtomicBoolean

data class HardwareBufferCrossProcessEvidence(
    val senderPid: Int,
    val receiverPid: Int,
    val senderResult: String,
    val receiverResult: String,
    val distinctProcesses: Boolean,
    val handleTransportVerified: Boolean,
    val vulkanWsiValidated: Boolean = false,
    val error: String? = null,
)

object HardwareBufferCrossProcessProbe {
    private const val TIMEOUT_MILLIS = 8_000L

    fun run(
        context: Context,
        onComplete: (HardwareBufferCrossProcessEvidence) -> Unit,
    ) {
        val appContext = context.applicationContext
        val mainPid = Process.myPid()
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val stateLock = Any()

        var senderResult: String? = null
        var receiverPid: Int? = null
        var receiverResult: String? = null
        var pendingError: String? = null
        var timeout: Runnable? = null

        fun complete(
            forcedError: String? = null,
            forcedReceiverPid: Int? = null,
            forcedReceiverResult: String? = null,
        ) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            timeout?.let(handler::removeCallbacks)

            val snapshot =
                synchronized(stateLock) {
                    ProbeSnapshot(
                        senderResult =
                            senderResult
                                ?: "ahb-xproc-send=not-executed",
                        receiverPid =
                            forcedReceiverPid
                                ?: receiverPid
                                ?: -1,
                        receiverResult =
                            forcedReceiverResult
                                ?: receiverResult
                                ?: "ahb-xproc-recv=not-executed",
                        error = forcedError ?: pendingError,
                    )
                }

            val sendRecord =
                HardwareBufferProbeProtocol.parse(
                    raw = snapshot.senderResult,
                    expectedSide = HardwareBufferProbeSide.SEND,
                )
            val receiveRecord =
                HardwareBufferProbeProtocol.parse(
                    raw = snapshot.receiverResult,
                    expectedSide = HardwareBufferProbeSide.RECEIVE,
                )

            val distinct =
                snapshot.receiverPid > 0 &&
                    snapshot.receiverPid != mainPid
            val recordPidsMatch =
                sendRecord?.pid == mainPid &&
                    receiveRecord?.pid == snapshot.receiverPid
            val descriptorRoundTripMatches =
                sendRecord != null &&
                    receiveRecord != null &&
                    sendRecord.width == receiveRecord.width &&
                    sendRecord.height == receiveRecord.height &&
                    sendRecord.layers == receiveRecord.layers &&
                    sendRecord.format == receiveRecord.format &&
                    sendRecord.stride == receiveRecord.stride

            val validationError =
                snapshot.error
                    ?: when {
                        sendRecord == null ->
                            "AHARDWAREBUFFER_SEND_EVIDENCE_PARSE_FAILED"

                        receiveRecord == null ->
                            "AHARDWAREBUFFER_RECEIVE_EVIDENCE_PARSE_FAILED"

                        !sendRecord.successful ->
                            "AHARDWAREBUFFER_SEND_EVIDENCE_NOT_SUCCESSFUL"

                        !receiveRecord.successful ->
                            "AHARDWAREBUFFER_RECEIVE_EVIDENCE_NOT_SUCCESSFUL"

                        !recordPidsMatch ->
                            "AHARDWAREBUFFER_NATIVE_PID_MISMATCH"

                        !distinct ->
                            "AHARDWAREBUFFER_DISTINCT_PROCESS_NOT_PROVEN"

                        !descriptorRoundTripMatches ->
                            "AHARDWAREBUFFER_DESCRIPTOR_ROUNDTRIP_MISMATCH"

                        else -> null
                    }

            onComplete(
                HardwareBufferCrossProcessEvidence(
                    senderPid = mainPid,
                    receiverPid = snapshot.receiverPid,
                    senderResult = snapshot.senderResult,
                    receiverResult = snapshot.receiverResult,
                    distinctProcesses = distinct,
                    handleTransportVerified =
                        validationError == null,
                    vulkanWsiValidated = false,
                    error = validationError,
                ),
            )
        }

        fun tryCompleteWhenBothSidesRecorded() {
            val ready =
                synchronized(stateLock) {
                    senderResult != null &&
                        receiverResult != null
                }
            if (ready) {
                complete()
            }
        }

        fun recordSender(
            result: String,
            error: String? = null,
        ) {
            synchronized(stateLock) {
                if (senderResult == null) {
                    senderResult = result
                }
                if (pendingError == null && error != null) {
                    pendingError = error
                }
            }
            tryCompleteWhenBothSidesRecorded()
        }

        fun recordReceiver(
            pid: Int,
            result: String,
            error: String? = null,
        ) {
            synchronized(stateLock) {
                if (receiverResult == null) {
                    receiverPid = pid
                    receiverResult = result
                }
                if (pendingError == null && error != null) {
                    pendingError = error
                }
            }
            tryCompleteWhenBothSidesRecorded()
        }

        timeout =
            Runnable {
                complete(
                    forcedError =
                        "AHARDWAREBUFFER_CROSS_PROCESS_TIMEOUT",
                    forcedReceiverResult =
                        synchronized(stateLock) {
                            receiverResult
                                ?: "ahb-xproc-recv=timeout"
                        },
                )
            }
        handler.postDelayed(
            requireNotNull(timeout),
            TIMEOUT_MILLIS,
        )

        val receiver =
            object : ResultReceiver(handler) {
                override fun onReceiveResult(
                    resultCode: Int,
                    resultData: Bundle?,
                ) {
                    if (
                        resultCode !=
                            HardwareBufferCrossProcessProbeService
                                .RESULT_RECEIVED
                    ) {
                        recordReceiver(
                            pid = -1,
                            result =
                                "ahb-xproc-recv=invalid-result-code",
                            error =
                                "AHARDWAREBUFFER_CROSS_PROCESS_RESULT_INVALID",
                        )
                        return
                    }

                    val remotePid =
                        resultData?.getInt(
                            HardwareBufferCrossProcessProbeService
                                .KEY_RECEIVER_PID,
                            -1,
                        ) ?: -1
                    val echoedSenderPid =
                        resultData?.getInt(
                            HardwareBufferCrossProcessProbeService
                                .KEY_SENDER_PID,
                            -1,
                        ) ?: -1
                    val result =
                        resultData?.getString(
                            HardwareBufferCrossProcessProbeService
                                .KEY_RECEIVE_RESULT,
                        ) ?: "ahb-xproc-recv=missing-result"

                    recordReceiver(
                        pid = remotePid,
                        result = result,
                        error =
                            if (echoedSenderPid != mainPid) {
                                "AHARDWAREBUFFER_SENDER_PID_MISMATCH"
                            } else {
                                null
                            },
                    )
                }
            }

        val pair =
            runCatching {
                ParcelFileDescriptor
                    .createReliableSocketPair()
            }.getOrElse { error ->
                complete(
                    forcedError =
                        "AHARDWAREBUFFER_SOCKETPAIR_FAILED:${error.javaClass.simpleName}",
                    forcedReceiverPid = -1,
                    forcedReceiverResult =
                        "ahb-xproc-recv=socketpair-failed",
                )
                return
            }

        val senderSocket = pair[0]
        val receiverSocket = pair[1]
        try {
            val intent =
                Intent(
                    appContext,
                    HardwareBufferCrossProcessProbeService::class.java,
                ).apply {
                    putExtra(
                        HardwareBufferCrossProcessProbeService
                            .EXTRA_SOCKET,
                        receiverSocket,
                    )
                    putExtra(
                        HardwareBufferCrossProcessProbeService
                            .EXTRA_RESULT_RECEIVER,
                        receiver,
                    )
                    putExtra(
                        HardwareBufferCrossProcessProbeService
                            .EXTRA_SENDER_PID,
                        mainPid,
                    )
                }

            val started =
                runCatching {
                    appContext.startService(intent)
                }.getOrNull()
            if (started == null) {
                complete(
                    forcedError =
                        "AHARDWAREBUFFER_PROBE_SERVICE_START_FAILED",
                    forcedReceiverPid = -1,
                    forcedReceiverResult =
                        "ahb-xproc-recv=service-start-failed",
                )
                return
            }

            runCatching {
                receiverSocket.close()
            }

            val result =
                NativeRuntimeHost
                    .sendHardwareBufferCrossProcessProbe(
                        senderSocket.fd,
                    )
            recordSender(
                result = result,
            )
        } catch (error: Throwable) {
            recordSender(
                result =
                    "ahb-xproc-send=probe-failed;" +
                        "error=${error.javaClass.simpleName}",
                error =
                    "AHARDWAREBUFFER_CROSS_PROCESS_FAILED:${error.javaClass.simpleName}",
            )
        } finally {
            runCatching {
                senderSocket.close()
            }
            runCatching {
                receiverSocket.close()
            }
        }
    }

    private data class ProbeSnapshot(
        val senderResult: String,
        val receiverPid: Int,
        val receiverResult: String,
        val error: String?,
    )
}

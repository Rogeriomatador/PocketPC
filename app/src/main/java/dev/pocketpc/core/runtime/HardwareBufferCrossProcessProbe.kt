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
        var senderResult = "ahb-xproc-send=not-executed"

        fun finish(
            receiverPid: Int,
            receiverResult: String,
            error: String? = null,
        ) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            val distinct =
                receiverPid > 0 &&
                    receiverPid != mainPid
            val sendOk =
                senderResult.startsWith(
                    "ahb-xproc-send=ok;",
                )
            val receiveOk =
                receiverResult.startsWith(
                    "ahb-xproc-recv=ok;",
                ) &&
                    receiverResult.contains(
                        ";descriptor_match=yes",
                    ) &&
                    receiverResult.contains(
                        ";pattern_match=yes",
                    )
            onComplete(
                HardwareBufferCrossProcessEvidence(
                    senderPid = mainPid,
                    receiverPid = receiverPid,
                    senderResult = senderResult,
                    receiverResult = receiverResult,
                    distinctProcesses = distinct,
                    handleTransportVerified =
                        distinct &&
                            sendOk &&
                            receiveOk &&
                            error == null,
                    vulkanWsiValidated = false,
                    error = error,
                ),
            )
        }

        val timeout =
            Runnable {
                finish(
                    receiverPid = -1,
                    receiverResult =
                        "ahb-xproc-recv=timeout",
                    error =
                        "AHARDWAREBUFFER_CROSS_PROCESS_TIMEOUT",
                )
            }
        handler.postDelayed(
            timeout,
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
                        finish(
                            receiverPid = -1,
                            receiverResult =
                                "ahb-xproc-recv=invalid-result-code",
                            error =
                                "AHARDWAREBUFFER_CROSS_PROCESS_RESULT_INVALID",
                        )
                        return
                    }
                    val receiverPid =
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
                    val receiveResult =
                        resultData?.getString(
                            HardwareBufferCrossProcessProbeService
                                .KEY_RECEIVE_RESULT,
                        ) ?: "ahb-xproc-recv=missing-result"

                    if (echoedSenderPid != mainPid) {
                        finish(
                            receiverPid = receiverPid,
                            receiverResult = receiveResult,
                            error =
                                "AHARDWAREBUFFER_SENDER_PID_MISMATCH",
                        )
                        return
                    }
                    finish(
                        receiverPid = receiverPid,
                        receiverResult = receiveResult,
                    )
                }
            }

        val pair =
            runCatching {
                ParcelFileDescriptor
                    .createReliableSocketPair()
            }.getOrElse { error ->
                finish(
                    receiverPid = -1,
                    receiverResult =
                        "ahb-xproc-recv=socketpair-failed",
                    error =
                        "AHARDWAREBUFFER_SOCKETPAIR_FAILED:${error.javaClass.simpleName}",
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
                finish(
                    receiverPid = -1,
                    receiverResult =
                        "ahb-xproc-recv=service-start-failed",
                    error =
                        "AHARDWAREBUFFER_PROBE_SERVICE_START_FAILED",
                )
                return
            }

            runCatching {
                receiverSocket.close()
            }
            senderResult =
                NativeRuntimeHost
                    .sendHardwareBufferCrossProcessProbe(
                        senderSocket.fd,
                    )
            if (
                !senderResult.startsWith(
                    "ahb-xproc-send=ok;",
                )
            ) {
                finish(
                    receiverPid = -1,
                    receiverResult =
                        "ahb-xproc-recv=sender-failed",
                    error =
                        "AHARDWAREBUFFER_CROSS_PROCESS_SEND_FAILED",
                )
            }
        } catch (error: Throwable) {
            finish(
                receiverPid = -1,
                receiverResult =
                    "ahb-xproc-recv=probe-failed",
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
}

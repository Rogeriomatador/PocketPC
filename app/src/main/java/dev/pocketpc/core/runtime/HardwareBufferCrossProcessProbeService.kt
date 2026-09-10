package dev.pocketpc.core.runtime

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver

class HardwareBufferCrossProcessProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val socket =
            intent?.getParcelableExtra<ParcelFileDescriptor>(
                EXTRA_SOCKET,
            )
        val receiver =
            intent?.getParcelableExtra<ResultReceiver>(
                EXTRA_RESULT_RECEIVER,
            )
        val senderPid =
            intent?.getIntExtra(
                EXTRA_SENDER_PID,
                -1,
            ) ?: -1

        if (socket == null || receiver == null) {
            socket?.close()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Thread(
            {
                val remotePid = Process.myPid()
                val nativeResult =
                    try {
                        NativeRuntimeHost
                            .receiveHardwareBufferCrossProcessProbe(
                                socket.fd,
                            )
                    } finally {
                        runCatching { socket.close() }
                    }
                val data =
                    Bundle().apply {
                        putInt(KEY_SENDER_PID, senderPid)
                        putInt(KEY_RECEIVER_PID, remotePid)
                        putString(
                            KEY_RECEIVE_RESULT,
                            nativeResult,
                        )
                    }
                receiver.send(
                    RESULT_RECEIVED,
                    data,
                )
                stopSelf(startId)
            },
            "PocketPC-AHB-XProc-Recv",
        ).apply {
            isDaemon = true
            start()
        }

        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_SOCKET =
            "dev.pocketpc.extra.AHB_SOCKET"
        const val EXTRA_RESULT_RECEIVER =
            "dev.pocketpc.extra.AHB_RESULT_RECEIVER"
        const val EXTRA_SENDER_PID =
            "dev.pocketpc.extra.AHB_SENDER_PID"
        const val KEY_SENDER_PID =
            "sender_pid"
        const val KEY_RECEIVER_PID =
            "receiver_pid"
        const val KEY_RECEIVE_RESULT =
            "receive_result"
        const val RESULT_RECEIVED = 1
    }
}

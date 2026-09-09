#if !(defined(GO) && defined(GOM) && defined(GO2) && defined(DATA))
#error Meh...
#endif

/*
 * PocketPC deliberately wraps only the AHardwareBuffer subset needed by
 * the Vulkan transport experiment. AHardwareBuffer remains opaque to the
 * x86_64 guest; allocation/description/handle transport stay in libandroid.
 */
GO(AHardwareBuffer_allocate, iFpp)
GO(AHardwareBuffer_acquire, vFp)
GO(AHardwareBuffer_release, vFp)
GO(AHardwareBuffer_describe, vFpp)
GO(AHardwareBuffer_isSupported, iFp)
GO(AHardwareBuffer_lock, iFpLipp)
GO(AHardwareBuffer_unlock, iFpp)
GO(AHardwareBuffer_sendHandleToUnixSocket, iFpi)
GO(AHardwareBuffer_recvHandleFromUnixSocket, iFip)

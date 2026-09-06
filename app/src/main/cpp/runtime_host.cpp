#include <jni.h>
#include <sys/utsname.h>
#include <unistd.h>

#include <sstream>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_NativeRuntimeHost_nativeProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    struct utsname info {};
    const int uname_result = uname(&info);

    std::ostringstream output;
    output << "native-host=loaded";
    output << ";pid=" << getpid();

#if defined(__aarch64__)
    output << ";abi=arm64-v8a";
#elif defined(__x86_64__)
    output << ";abi=x86_64";
#else
    output << ";abi=unknown";
#endif

    if (uname_result == 0) {
        output << ";kernel=" << info.release;
        output << ";machine=" << info.machine;
    } else {
        output << ";kernel=unavailable";
    }

#if defined(__ANDROID_API__)
    output << ";ndk-api=" << __ANDROID_API__;
#endif

    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}

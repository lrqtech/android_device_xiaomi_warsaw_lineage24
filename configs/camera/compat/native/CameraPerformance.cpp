#include <jni.h>
#include <vector>

// The original OS4 libqti-perfd-client_system C ABI. Resource IDs and values
// pass through unchanged; only the real HAL can issue/release a lock handle.
extern "C" int perf_lock_acq(int handle, int duration, int* resources, int count);
extern "C" int perf_lock_rel(int handle);

extern "C" JNIEXPORT jint JNICALL
Java_com_qualcomm_qti_Performance_nativeAcquire(JNIEnv* env, jclass, jint handle,
        jint duration, jintArray resources) {
    if (resources == nullptr) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        if (error != nullptr) env->ThrowNew(error, "Performance resources must not be null");
        return -1;
    }
    jsize count = env->GetArrayLength(resources);
    std::vector<jint> values(count);
    env->GetIntArrayRegion(resources, 0, count, values.data());
    if (env->ExceptionCheck()) return -1;
    return perf_lock_acq(handle, duration, values.data(), count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_qualcomm_qti_Performance_nativeRelease(JNIEnv*, jclass, jint handle) {
    return perf_lock_rel(handle);
}

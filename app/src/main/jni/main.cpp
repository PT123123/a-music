#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <locale.h>
#include <atomic>
#include <dlfcn.h>

#include <android/fdsan.h>
#include <mpv/client.h>

#include <pthread.h>

#include "log.h"
#include "jni_utils.h"
#include "event.h"

#define ARRAYLEN(a) (sizeof(a)/sizeof(a[0]))

extern "C" {
    jni_func(void, create, jobject appctx);
    jni_func(void, init);
    jni_func(void, destroy);

    jni_func(void, command, jobjectArray jarray);
};

JavaVM *g_vm;
mpv_handle *g_mpv;
std::atomic<bool> g_event_thread_request_exit(false);

static pthread_t event_thread_id;
static jobject global_appctx;

// Rapid track switches make mpv / AudioTrack rebuild the audio chain while the previous
// one is still tearing down. On some devices that races fd ownership inside the driver
// stack and trips Android's fdsan, whose default level (FATAL) aborts the whole process —
// the app dies mid-试听. This is the same crash class as the Adreno GL driver's fdsan
// abort that forced software bitmaps. Downgrade to WARN_ALWAYS (log, don't abort); the
// functions only exist on API 29+, so resolve them dynamically for older devices.
// NOTE: the symbols are android_fdsan_set_error_level / android_fdsan_get_error_level —
// without the android_ prefix dlsym silently finds nothing.
static void relax_fdsan() {
    using SetLevelFn = int (*)(int);
    using GetLevelFn = int (*)();
    void *set_sym = dlsym(RTLD_DEFAULT, "android_fdsan_set_error_level");
    if (!set_sym) {
        ALOGE("android_fdsan_set_error_level unavailable (pre-API 29); fdsan stays at default");
        return;
    }
    reinterpret_cast<SetLevelFn>(set_sym)(ANDROID_FDSAN_ERROR_LEVEL_WARN_ALWAYS);
    if (void *get_sym = dlsym(RTLD_DEFAULT, "android_fdsan_get_error_level")) {
        int level = reinterpret_cast<GetLevelFn>(get_sym)();
        ALOGE("fdsan relaxed: level is now %d (2 == WARN_ALWAYS)", level);
    }
}

static void prepare_environment(JNIEnv *env, jobject appctx) {
    setlocale(LC_NUMERIC, "C");

    g_vm = NULL;
    env->GetJavaVM(&g_vm);
    if (!g_vm)
        die("failed to get jvm");

    if (global_appctx)
        env->DeleteGlobalRef(global_appctx);
    global_appctx = env->NewGlobalRef(appctx);

    init_methods_cache(env);
}

jni_func(void, create, jobject appctx) {
    if (g_mpv)
        die("mpv is already initialized");

    relax_fdsan();
    prepare_environment(env, appctx);

    g_mpv = mpv_create();
    if (!g_mpv)
        die("context init failed");

    // request verbose messages so --msg-level can adjust later
    mpv_request_log_messages(g_mpv, "terminal-default");
    mpv_set_option_string(g_mpv, "msg-level", "all=v");
}

jni_func(void, init) {
    if (!g_mpv)
        die("mpv is not created");

    if (mpv_initialize(g_mpv) < 0)
        die("mpv init failed");

    g_event_thread_request_exit = false;
    if (pthread_create(&event_thread_id, NULL, event_thread, NULL) != 0)
        die("thread create failed");
    pthread_setname_np(event_thread_id, "event_thread");
}

jni_func(void, destroy) {
    if (!g_mpv) {
        ALOGV("mpv destroy called but it's already destroyed");
        return;
    }

    // poke event thread and wait for it to exit
    g_event_thread_request_exit = true;
    mpv_wakeup(g_mpv);
    pthread_join(event_thread_id, NULL);

    mpv_terminate_destroy(g_mpv);
    g_mpv = NULL;
}

jni_func(void, command, jobjectArray jarray) {
    CHECK_MPV_INIT();

    jstring strings[64] = {0};
    const char *arguments[64] = {0};
    jsize len = env->GetArrayLength(jarray);
    if (len >= ARRAYLEN(arguments)) // null-terminated
        die("too many command arguments");

    for (jsize i = 0; i < len; ++i) {
        strings[i] = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(strings[i], NULL);
    }

    mpv_command(g_mpv, arguments);

    for (jsize i = 0; i < len; ++i) {
        env->ReleaseStringUTFChars(strings[i], arguments[i]);
        env->DeleteLocalRef(strings[i]);
    }
}

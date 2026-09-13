LOCAL_PATH := $(call my-dir)

# Where the prebuilt libmpv + its headers live.
# Override with: mpv.dir in gradle.properties  -> passed as MPV_DIR by AGP.
MPV_DIR ?= $(LOCAL_PATH)/../prebuilt

# --- Our thin JNI wrapper (audio-only; no video/render/thumbnail) ---
include $(CLEAR_VARS)
LOCAL_MODULE := player
LOCAL_SRC_FILES := main.cpp property.cpp event.cpp log.cpp jni_utils.cpp
LOCAL_LDLIBS := -llog -lz
LOCAL_C_INCLUDES := $(MPV_DIR)/include
LOCAL_SHARED_LIBRARIES := mpv
include $(BUILD_SHARED_LIBRARY)

# --- Prebuilt libmpv produced by the mpv-android buildscripts (audio-only) ---
include $(CLEAR_VARS)
LOCAL_MODULE := mpv
LOCAL_SRC_FILES := $(MPV_DIR)/$(TARGET_ARCH_ABI)/libmpv.so
include $(PREBUILT_SHARED_LIBRARY)

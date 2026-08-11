LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := signalplay_srt
LOCAL_SRC_FILES := signalplay_srt.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)

GSTREAMER_PLUGINS := $(GSTREAMER_PLUGINS_CORE) $(GSTREAMER_PLUGINS_PLAYBACK) $(GSTREAMER_PLUGINS_CODECS) $(GSTREAMER_PLUGINS_NET) $(GSTREAMER_PLUGINS_SYS) mpegtsdemux
GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0
include $(GSTREAMER_ROOT)/share/gst-android/ndk-build/gstreamer-1.0.mk

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>
#include <pthread.h>
#include <string.h>

typedef struct {
    jobject owner;
    GstElement *playbin;
    ANativeWindow *window;
    pthread_t thread;
    gboolean thread_started;
    gboolean stopping;
    gchar *uri;
    gint de_jitter_ms;
} PlayerData;

static JavaVM *java_vm;
static jfieldID native_handle_field;
static jmethodID state_method;
static jmethodID diagnostics_method;
static pthread_mutex_t player_lock = PTHREAD_MUTEX_INITIALIZER;

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if ((*java_vm)->GetEnv(java_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        (*java_vm)->AttachCurrentThread(java_vm, &env, NULL);
    return env;
}

static void send_text(PlayerData *data, jmethodID method, const gchar *first, const gchar *second) {
    JNIEnv *env = get_env();
    jstring a = (*env)->NewStringUTF(env, first ? first : "");
    if (second) {
        jstring b = (*env)->NewStringUTF(env, second);
        (*env)->CallVoidMethod(env, data->owner, method, a, b);
        (*env)->DeleteLocalRef(env, b);
    } else {
        (*env)->CallVoidMethod(env, data->owner, method, a);
    }
    (*env)->DeleteLocalRef(env, a);
}

static void send_state(PlayerData *data, const gchar *state, const gchar *detail) {
    send_text(data, state_method, state, detail);
}

static void send_diagnostics(PlayerData *data, const gchar *report) {
    send_text(data, diagnostics_method, report, NULL);
}

static gchar *stream_report(GstStreamCollection *collection) {
    GString *out = g_string_new("");
    guint count = gst_stream_collection_get_size(collection);
    g_string_append_printf(out, "Streams detectados: %u\n", count);
    for (guint i = 0; i < count; i++) {
        GstStream *stream = gst_stream_collection_get_stream(collection, i);
        GstStreamType type = gst_stream_get_stream_type(stream);
        const gchar *kind = (type & GST_STREAM_TYPE_VIDEO) ? "Vídeo" :
                            (type & GST_STREAM_TYPE_AUDIO) ? "Áudio" :
                            (type & GST_STREAM_TYPE_TEXT) ? "Texto" : "Outro";
        const gchar *id = gst_stream_get_stream_id(stream);
        GstCaps *caps = gst_stream_get_caps(stream);
        gchar *caps_text = caps ? gst_caps_to_string(caps) : g_strdup("indisponível");
        g_string_append_printf(out, "\n%s %u\nStream ID/PID: %s\nCaps: %s\n",
                               kind, i + 1, id ? id : "indisponível", caps_text);
        g_free(caps_text);
        if (caps) gst_caps_unref(caps);
    }
    return g_string_free(out, FALSE);
}

static GstBusSyncReply bus_sync(GstBus *bus, GstMessage *message, gpointer user_data) {
    PlayerData *data = user_data;
    if (gst_is_video_overlay_prepare_window_handle_message(message) && data->window) {
        GstVideoOverlay *overlay = GST_VIDEO_OVERLAY(GST_MESSAGE_SRC(message));
        gst_video_overlay_set_window_handle(overlay, (guintptr) data->window);
        gst_message_unref(message);
        return GST_BUS_DROP;
    }
    return GST_BUS_PASS;
}

static void *player_thread(void *opaque) {
    PlayerData *data = opaque;
    data->playbin = gst_element_factory_make("playbin3", "signal-play-srt");
    if (!data->playbin) data->playbin = gst_element_factory_make("playbin", "signal-play-srt");
    if (!data->playbin) {
        send_state(data, "ERROR", "GStreamer não criou o pipeline de reprodução.");
        return NULL;
    }

    /*
     * Broadcast MPEG-TS feeds can carry PCR/PTS values that are valid for the
     * encoder clock but far ahead of Android's playback clock.  A synchronized
     * video sink then renders the first preroll frame and waits indefinitely.
     * SRT is live, so render frames as soon as they reach the device.
     */
    GstElement *video_sink = gst_element_factory_make("glimagesink", "signal-play-video");
    GstElement *audio_sink = gst_element_factory_make("openslessink", "signal-play-audio");
    if (video_sink) {
        g_object_set(video_sink,
                     "sync", FALSE,
                     "async", FALSE,
                     "qos", FALSE,
                     "enable-last-sample", FALSE,
                     NULL);
    }
    if (audio_sink) {
        g_object_set(audio_sink, "sync", TRUE, "async", FALSE, NULL);
    }

    g_object_set(data->playbin,
                 "uri", data->uri,
                 "video-sink", video_sink,
                 "audio-sink", audio_sink,
                 NULL);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(data->playbin), "volume"))
        g_object_set(data->playbin, "volume", 1.0, NULL);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(data->playbin), "mute"))
        g_object_set(data->playbin, "mute", FALSE, NULL);
    if (video_sink) gst_object_unref(video_sink);
    if (audio_sink) gst_object_unref(audio_sink);
    if (data->de_jitter_ms > 0 &&
        g_object_class_find_property(G_OBJECT_GET_CLASS(data->playbin), "buffer-duration")) {
        g_object_set(data->playbin,
                     "buffer-duration", (gint64) data->de_jitter_ms * GST_MSECOND,
                     NULL);
    }

    GstBus *bus = gst_element_get_bus(data->playbin);
    gst_bus_set_sync_handler(bus, bus_sync, data, NULL);
    send_state(data, "CONNECTING", "SRT inicializado; aguardando conexão e mídia.");
    gst_element_set_state(data->playbin, GST_STATE_PLAYING);

    while (!data->stopping) {
        GstMessage *message = gst_bus_timed_pop(bus, 250 * GST_MSECOND);
        if (!message) continue;
        switch (GST_MESSAGE_TYPE(message)) {
            case GST_MESSAGE_ERROR: {
                GError *err = NULL; gchar *debug = NULL;
                gst_message_parse_error(message, &err, &debug);
                gchar *detail = g_strdup_printf("%s\n%s", err ? err->message : "Erro SRT", debug ? debug : "");
                send_state(data, "ERROR", detail);
                g_free(detail); g_clear_error(&err); g_free(debug);
                data->stopping = TRUE;
                break;
            }
            case GST_MESSAGE_BUFFERING: {
                gint percent = 0; gst_message_parse_buffering(message, &percent);
                gchar *detail = g_strdup_printf("Buffer %d%%", percent);
                send_state(data, "BUFFERING", detail); g_free(detail);
                break;
            }
            case GST_MESSAGE_QOS: {
                gboolean live = FALSE;
                guint64 running_time = GST_CLOCK_TIME_NONE;
                guint64 stream_time = GST_CLOCK_TIME_NONE;
                guint64 timestamp = GST_CLOCK_TIME_NONE;
                guint64 duration = GST_CLOCK_TIME_NONE;
                gst_message_parse_qos(message, &live, &running_time, &stream_time,
                                      &timestamp, &duration);
                gchar *detail = g_strdup_printf(
                    "QoS: live=%s, timestamp=%" GST_TIME_FORMAT ", duração=%" GST_TIME_FORMAT,
                    live ? "sim" : "não",
                    GST_TIME_ARGS(timestamp), GST_TIME_ARGS(duration));
                send_diagnostics(data, detail);
                g_free(detail);
                break;
            }
            case GST_MESSAGE_STATE_CHANGED:
                if (GST_MESSAGE_SRC(message) == GST_OBJECT(data->playbin)) {
                    GstState old_s, new_s, pending;
                    gst_message_parse_state_changed(message, &old_s, &new_s, &pending);
                    if (new_s == GST_STATE_PLAYING) send_state(data, "PLAYING", "Pipeline SRT em reprodução.");
                }
                break;
            case GST_MESSAGE_STREAM_COLLECTION: {
                GstStreamCollection *collection = NULL;
                gst_message_parse_stream_collection(message, &collection);
                gchar *report = stream_report(collection);
                send_diagnostics(data, report);
                gchar *detail = g_strdup_printf(
                    "Mídia detectada; de-jitter configurado em %d ms.", data->de_jitter_ms);
                send_state(data, "MEDIA", detail);
                g_free(detail);
                g_free(report); gst_object_unref(collection);
                break;
            }
            case GST_MESSAGE_EOS:
                send_state(data, "ENDED", "O fluxo SRT foi encerrado pelo emissor.");
                data->stopping = TRUE;
                break;
            default: break;
        }
        gst_message_unref(message);
    }
    gst_element_set_state(data->playbin, GST_STATE_NULL);
    gst_bus_set_sync_handler(bus, NULL, NULL, NULL);
    gst_object_unref(bus);
    gst_object_unref(data->playbin);
    data->playbin = NULL;
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeClassInit(JNIEnv *env, jclass klass) {
    native_handle_field = (*env)->GetFieldID(env, klass, "nativeHandle", "J");
    if (!native_handle_field) {
        /* Kotlin does not declare the field; native state is held globally per activity instance. */
        (*env)->ExceptionClear(env);
    }
    state_method = (*env)->GetMethodID(env, klass, "onNativeState", "(Ljava/lang/String;Ljava/lang/String;)V");
    diagnostics_method = (*env)->GetMethodID(env, klass, "onNativeDiagnostics", "(Ljava/lang/String;)V");
    return state_method && diagnostics_method;
}

static PlayerData *singleton;

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeCreate(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&player_lock);
    if (!singleton) {
        singleton = g_new0(PlayerData, 1);
        singleton->owner = (*env)->NewGlobalRef(env, thiz);
    }
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativePlay(JNIEnv *env, jobject thiz, jstring uri, jobject surface, jint de_jitter_ms) {
    if (!singleton) return;
    if (singleton->thread_started) {
        singleton->stopping = TRUE;
        pthread_join(singleton->thread, NULL);
        singleton->thread_started = FALSE;
    }
    g_free(singleton->uri);
    const char *value = (*env)->GetStringUTFChars(env, uri, NULL);
    singleton->uri = g_strdup(value);
    (*env)->ReleaseStringUTFChars(env, uri, value);
    if (singleton->window) ANativeWindow_release(singleton->window);
    singleton->window = ANativeWindow_fromSurface(env, surface);
    singleton->de_jitter_ms = de_jitter_ms < 0 ? 0 : de_jitter_ms;
    singleton->stopping = FALSE;
    singleton->thread_started = pthread_create(&singleton->thread, NULL, player_thread, singleton) == 0;
}

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeStop(JNIEnv *env, jobject thiz) {
    if (!singleton || !singleton->thread_started) return;
    singleton->stopping = TRUE;
    pthread_join(singleton->thread, NULL);
    singleton->thread_started = FALSE;
}

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeRelease(JNIEnv *env, jobject thiz) {
    if (!singleton) return;
    Java_com_isaque_signalplay_SrtPlayer_nativeStop(env, thiz);
    if (singleton->window) ANativeWindow_release(singleton->window);
    g_free(singleton->uri);
    (*env)->DeleteGlobalRef(env, singleton->owner);
    g_free(singleton);
    singleton = NULL;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    java_vm = vm;
    return JNI_VERSION_1_6;
}

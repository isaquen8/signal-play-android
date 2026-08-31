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
    GstElement *video_sink;
    GstElement *audio_sink;
    ANativeWindow *window;
    pthread_t thread;
    gboolean thread_started;
    gint stopping;
    gchar *uri;
    gint de_jitter_ms;
    jlong session_id;

    /*
     * A/V sync state.
     *
     * MPEG-TS carried over SRT can arrive with perfectly valid encoder PTS/PCR
     * values whose time origin is far away from the Android playback running
     * time. We preserve the encoder's relative A/V timestamps, but translate
     * the complete timeline once so the first valid buffer is close to the
     * current pipeline running time. The same offset is then applied to both
     * sinks and both sinks synchronize against the same GstPipeline clock.
     */
    GMutex sync_lock;
    gboolean sync_anchor_set;
    gint64 sync_offset_ns;

    /* Protects the shared playbin pointer while stop/switch races with cleanup. */
    GMutex pipeline_lock;
} PlayerData;

typedef struct {
    PlayerData *player;
    gboolean audio;
    GstSegment segment;
    gboolean have_segment;
} SyncPadContext;

static JavaVM *java_vm;
static jfieldID native_handle_field;
static jmethodID state_method;
static jmethodID diagnostics_method;
static pthread_mutex_t player_lock = PTHREAD_MUTEX_INITIALIZER;
static PlayerData *singleton;

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if ((*java_vm)->GetEnv(java_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        (*java_vm)->AttachCurrentThread(java_vm, &env, NULL);
    return env;
}

static GstElement *ref_active_playbin(PlayerData *data) {
    GstElement *playbin = NULL;
    g_mutex_lock(&data->pipeline_lock);
    if (data->playbin) playbin = gst_object_ref(data->playbin);
    g_mutex_unlock(&data->pipeline_lock);
    return playbin;
}

static void publish_playbin(PlayerData *data, GstElement *playbin) {
    g_mutex_lock(&data->pipeline_lock);
    if (data->playbin) gst_object_unref(data->playbin);
    data->playbin = playbin ? gst_object_ref(playbin) : NULL;
    g_mutex_unlock(&data->pipeline_lock);
}

static void clear_published_playbin(PlayerData *data, GstElement *playbin) {
    g_mutex_lock(&data->pipeline_lock);
    if (data->playbin == playbin) {
        gst_object_unref(data->playbin);
        data->playbin = NULL;
    }
    g_mutex_unlock(&data->pipeline_lock);
}

static void send_text(PlayerData *data, jmethodID method, const gchar *first, const gchar *second) {
    /* During an explicit stop/switch, never let callbacks from the old session escape. */
    if (g_atomic_int_get(&data->stopping)) return;

    JNIEnv *env = get_env();
    jstring a = (*env)->NewStringUTF(env, first ? first : "");
    if (second) {
        jstring b = (*env)->NewStringUTF(env, second);
        (*env)->CallVoidMethod(env, data->owner, method, data->session_id, a, b);
        (*env)->DeleteLocalRef(env, b);
    } else {
        (*env)->CallVoidMethod(env, data->owner, method, data->session_id, a);
    }
    (*env)->DeleteLocalRef(env, a);

    /* A callback must never poison the native playback thread. */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
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

static void apply_shared_sync_offset(PlayerData *data, gint64 offset_ns) {
    if (data->video_sink &&
        g_object_class_find_property(G_OBJECT_GET_CLASS(data->video_sink), "ts-offset")) {
        g_object_set(data->video_sink, "ts-offset", offset_ns, NULL);
    }
    if (data->audio_sink &&
        g_object_class_find_property(G_OBJECT_GET_CLASS(data->audio_sink), "ts-offset")) {
        g_object_set(data->audio_sink, "ts-offset", offset_ns, NULL);
    }
}

static GstPadProbeReturn av_sync_probe(GstPad *pad, GstPadProbeInfo *info, gpointer user_data) {
    SyncPadContext *context = user_data;
    PlayerData *data = context->player;
    GstPadProbeType type = GST_PAD_PROBE_INFO_TYPE(info);

    if (g_atomic_int_get(&data->stopping)) return GST_PAD_PROBE_OK;

    if (type & GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM) {
        GstEvent *event = GST_PAD_PROBE_INFO_EVENT(info);
        if (event && GST_EVENT_TYPE(event) == GST_EVENT_SEGMENT) {
            const GstSegment *segment = NULL;
            gst_event_parse_segment(event, &segment);
            if (segment && segment->format == GST_FORMAT_TIME) {
                context->segment = *segment;
                context->have_segment = TRUE;
            }
        } else if (event && GST_EVENT_TYPE(event) == GST_EVENT_FLUSH_STOP) {
            context->have_segment = FALSE;
            gst_segment_init(&context->segment, GST_FORMAT_TIME);
        }
        return GST_PAD_PROBE_OK;
    }

    if (!(type & GST_PAD_PROBE_TYPE_BUFFER) || !context->have_segment)
        return GST_PAD_PROBE_OK;

    GstBuffer *buffer = GST_PAD_PROBE_INFO_BUFFER(info);
    if (!buffer || !GST_BUFFER_PTS_IS_VALID(buffer))
        return GST_PAD_PROBE_OK;

    GstClockTime stream_running_time = gst_segment_to_running_time(
        &context->segment,
        GST_FORMAT_TIME,
        GST_BUFFER_PTS(buffer));
    if (!GST_CLOCK_TIME_IS_VALID(stream_running_time))
        return GST_PAD_PROBE_OK;

    gboolean anchored_now = FALSE;
    gint64 offset_ns = 0;

    g_mutex_lock(&data->sync_lock);
    if (!data->sync_anchor_set && !g_atomic_int_get(&data->stopping)) {
        GstElement *playbin = ref_active_playbin(data);
        if (playbin) {
            GstClock *clock = gst_element_get_clock(playbin);
            if (clock) {
                GstClockTime clock_time = gst_clock_get_time(clock);
                GstClockTime base_time = gst_element_get_base_time(playbin);
                GstClockTime pipeline_running_time =
                    (GST_CLOCK_TIME_IS_VALID(base_time) && clock_time >= base_time)
                        ? clock_time - base_time
                        : 0;

                data->sync_offset_ns =
                    (gint64) pipeline_running_time - (gint64) stream_running_time;
                data->sync_anchor_set = TRUE;
                offset_ns = data->sync_offset_ns;
                apply_shared_sync_offset(data, offset_ns);
                anchored_now = TRUE;
                gst_object_unref(clock);
            }
            gst_object_unref(playbin);
        }
    }
    g_mutex_unlock(&data->sync_lock);

    if (anchored_now) {
        gchar *report = g_strdup_printf(
            "A/V clock: âncora=%s; offset comum=%+.1f ms; áudio/vídeo sincronizados pelo clock do pipeline.",
            context->audio ? "áudio" : "vídeo",
            (gdouble) offset_ns / (gdouble) GST_MSECOND);
        send_diagnostics(data, report);
        g_free(report);
    }

    return GST_PAD_PROBE_OK;
}

static void install_sync_probe(PlayerData *data, GstElement *sink, gboolean audio) {
    if (!sink) return;
    GstPad *pad = gst_element_get_static_pad(sink, "sink");
    if (!pad) return;

    SyncPadContext *context = g_new0(SyncPadContext, 1);
    context->player = data;
    context->audio = audio;
    gst_segment_init(&context->segment, GST_FORMAT_TIME);

    gst_pad_add_probe(
        pad,
        GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM | GST_PAD_PROBE_TYPE_BUFFER,
        av_sync_probe,
        context,
        g_free);
    gst_object_unref(pad);
}

static void reset_sync_anchor(PlayerData *data) {
    g_mutex_lock(&data->sync_lock);
    data->sync_anchor_set = FALSE;
    data->sync_offset_ns = 0;
    apply_shared_sync_offset(data, 0);
    g_mutex_unlock(&data->sync_lock);
}

static GstBusSyncReply bus_sync(GstBus *bus, GstMessage *message, gpointer user_data) {
    PlayerData *data = user_data;
    if (!g_atomic_int_get(&data->stopping) &&
        gst_is_video_overlay_prepare_window_handle_message(message) && data->window) {
        GstVideoOverlay *overlay = GST_VIDEO_OVERLAY(GST_MESSAGE_SRC(message));
        gst_video_overlay_set_window_handle(overlay, (guintptr) data->window);
        gst_message_unref(message);
        return GST_BUS_DROP;
    }
    return GST_BUS_PASS;
}

static void *player_thread(void *opaque) {
    PlayerData *data = opaque;
    GstElementFactory *aac_factory = gst_element_factory_find("avdec_aac");
    gboolean aac_available = aac_factory != NULL;
    if (aac_factory) {
        gst_plugin_feature_set_rank(GST_PLUGIN_FEATURE(aac_factory), GST_RANK_PRIMARY + 100);
        gst_object_unref(aac_factory);
    }

    /* Use a thread-owned pipeline ref and publish a second guarded ref for stop(). */
    GstElement *playbin = gst_element_factory_make("playbin", "signal-play-srt");
    if (!playbin) playbin = gst_element_factory_make("playbin3", "signal-play-srt");
    if (!playbin) {
        send_state(data, "ERROR", "GStreamer não criou o pipeline de reprodução.");
        return NULL;
    }
    publish_playbin(data, playbin);

    /*
     * Keep both sinks clock-synchronised. A pad probe establishes one shared
     * timeline offset from the first valid decoded PTS, which prevents the old
     * MPEG-TS far-future timestamp stall without throwing away A/V timing.
     */
    data->video_sink = gst_element_factory_make("glimagesink", "signal-play-video");
    data->audio_sink = gst_element_factory_make("openslessink", "signal-play-audio");
    if (data->video_sink) {
        g_object_set(data->video_sink,
                     "sync", TRUE,
                     "async", FALSE,
                     "qos", TRUE,
                     "enable-last-sample", FALSE,
                     NULL);
        if (g_object_class_find_property(G_OBJECT_GET_CLASS(data->video_sink), "max-lateness"))
            g_object_set(data->video_sink, "max-lateness", (gint64) 120 * GST_MSECOND, NULL);
    }
    if (data->audio_sink) {
        g_object_set(data->audio_sink, "sync", TRUE, "async", FALSE, NULL);
    }

    reset_sync_anchor(data);
    install_sync_probe(data, data->video_sink, FALSE);
    install_sync_probe(data, data->audio_sink, TRUE);

    g_object_set(playbin,
                 "uri", data->uri,
                 "video-sink", data->video_sink,
                 "audio-sink", data->audio_sink,
                 NULL);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(playbin), "volume"))
        g_object_set(playbin, "volume", 1.0, NULL);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(playbin), "mute"))
        g_object_set(playbin, "mute", FALSE, NULL);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(playbin), "current-audio"))
        g_object_set(playbin, "current-audio", 0, NULL);
    if (data->de_jitter_ms > 0 &&
        g_object_class_find_property(G_OBJECT_GET_CLASS(playbin), "buffer-duration")) {
        g_object_set(playbin,
                     "buffer-duration", (gint64) data->de_jitter_ms * GST_MSECOND,
                     NULL);
    }

    GstBus *bus = gst_element_get_bus(playbin);
    gst_bus_set_sync_handler(bus, bus_sync, data, NULL);
    gchar *audio_report = g_strdup_printf(
        "Áudio: decoder AAC software=%s; saída OpenSL ES=%s; faixa selecionada=1; sync=clock compartilhado.",
        aac_available ? "disponível" : "indisponível",
        data->audio_sink ? "disponível" : "automática");
    send_diagnostics(data, audio_report);
    g_free(audio_report);
    send_state(data, "CONNECTING", "SRT inicializado; aguardando conexão e mídia.");
    gst_element_set_state(playbin, GST_STATE_PLAYING);

    while (!g_atomic_int_get(&data->stopping)) {
        GstMessage *message = gst_bus_timed_pop(bus, 250 * GST_MSECOND);
        if (!message) continue;
        if (g_atomic_int_get(&data->stopping)) {
            gst_message_unref(message);
            break;
        }

        switch (GST_MESSAGE_TYPE(message)) {
            case GST_MESSAGE_ERROR: {
                GError *err = NULL; gchar *debug = NULL;
                gst_message_parse_error(message, &err, &debug);
                gchar *detail = g_strdup_printf("%s\n%s", err ? err->message : "Erro SRT", debug ? debug : "");
                send_state(data, "ERROR", detail);
                g_free(detail); g_clear_error(&err); g_free(debug);
                g_atomic_int_set(&data->stopping, TRUE);
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
            case GST_MESSAGE_NEW_CLOCK: {
                GstClock *clock = NULL;
                gst_message_parse_new_clock(message, &clock);
                if (clock) {
                    gchar *detail = g_strdup_printf(
                        "A/V clock mestre selecionado: %s.", GST_OBJECT_NAME(clock));
                    send_diagnostics(data, detail);
                    g_free(detail);
                }
                break;
            }
            case GST_MESSAGE_CLOCK_LOST:
                reset_sync_anchor(data);
                gst_element_set_state(playbin, GST_STATE_PAUSED);
                gst_element_set_state(playbin, GST_STATE_PLAYING);
                send_diagnostics(data, "A/V clock foi perdido; pipeline resincronizado e nova âncora será calculada.");
                break;
            case GST_MESSAGE_STATE_CHANGED:
                if (GST_MESSAGE_SRC(message) == GST_OBJECT(playbin)) {
                    GstState old_s, new_s, pending;
                    gst_message_parse_state_changed(message, &old_s, &new_s, &pending);
                    if (new_s == GST_STATE_PLAYING)
                        send_state(data, "PLAYING", "Pipeline SRT em reprodução com A/V clock compartilhado.");
                }
                break;
            case GST_MESSAGE_STREAM_COLLECTION: {
                GstStreamCollection *collection = NULL;
                gst_message_parse_stream_collection(message, &collection);
                gchar *report = stream_report(collection);
                send_diagnostics(data, report);
                gchar *detail = g_strdup_printf(
                    "Mídia detectada; de-jitter configurado em %d ms; A/V sync ativo.", data->de_jitter_ms);
                send_state(data, "MEDIA", detail);
                g_free(detail);
                g_free(report); gst_object_unref(collection);
                break;
            }
            case GST_MESSAGE_EOS:
                send_state(data, "ENDED", "O fluxo SRT foi encerrado pelo emissor.");
                g_atomic_int_set(&data->stopping, TRUE);
                break;
            default: break;
        }
        gst_message_unref(message);
    }

    /* Stop callbacks first, then detach the Android surface before releasing EGL. */
    gst_bus_set_sync_handler(bus, NULL, NULL, NULL);
    if (data->video_sink && GST_IS_VIDEO_OVERLAY(data->video_sink))
        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(data->video_sink), 0);
    gst_element_set_state(playbin, GST_STATE_NULL);

    clear_published_playbin(data, playbin);
    gst_object_unref(bus);
    gst_object_unref(playbin);

    if (data->video_sink) {
        gst_object_unref(data->video_sink);
        data->video_sink = NULL;
    }
    if (data->audio_sink) {
        gst_object_unref(data->audio_sink);
        data->audio_sink = NULL;
    }
    return NULL;
}

static void stop_thread_locked(PlayerData *data) {
    if (!data || !data->thread_started) return;

    /* Mark the session stale before forcing the network pipeline down. */
    g_atomic_int_set(&data->stopping, TRUE);

    GstElement *playbin = ref_active_playbin(data);
    if (playbin) {
        /* This wakes SRT/network elements instead of waiting for their next packet. */
        gst_element_set_state(playbin, GST_STATE_NULL);
        gst_object_unref(playbin);
    }

    pthread_join(data->thread, NULL);
    data->thread_started = FALSE;
}

static void release_window_locked(PlayerData *data) {
    if (data->window) {
        ANativeWindow_release(data->window);
        data->window = NULL;
    }
}

JNIEXPORT jboolean JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeClassInit(JNIEnv *env, jclass klass) {
    native_handle_field = (*env)->GetFieldID(env, klass, "nativeHandle", "J");
    if (!native_handle_field) {
        /* Kotlin does not declare the field; native state is held globally per activity instance. */
        (*env)->ExceptionClear(env);
    }
    state_method = (*env)->GetMethodID(env, klass, "onNativeState", "(JLjava/lang/String;Ljava/lang/String;)V");
    diagnostics_method = (*env)->GetMethodID(env, klass, "onNativeDiagnostics", "(JLjava/lang/String;)V");
    return state_method && diagnostics_method;
}

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeCreate(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&player_lock);
    if (!singleton) {
        singleton = g_new0(PlayerData, 1);
        singleton->owner = (*env)->NewGlobalRef(env, thiz);
        g_mutex_init(&singleton->sync_lock);
        g_mutex_init(&singleton->pipeline_lock);
        g_atomic_int_set(&singleton->stopping, TRUE);
    }
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativePlay(
    JNIEnv *env,
    jobject thiz,
    jstring uri,
    jobject surface,
    jint de_jitter_ms,
    jlong session_id) {

    pthread_mutex_lock(&player_lock);
    if (!singleton) {
        pthread_mutex_unlock(&player_lock);
        return;
    }

    stop_thread_locked(singleton);
    release_window_locked(singleton);

    g_free(singleton->uri);
    const char *value = (*env)->GetStringUTFChars(env, uri, NULL);
    singleton->uri = g_strdup(value);
    (*env)->ReleaseStringUTFChars(env, uri, value);

    singleton->window = ANativeWindow_fromSurface(env, surface);
    singleton->de_jitter_ms = de_jitter_ms < 0 ? 0 : de_jitter_ms;
    singleton->session_id = session_id;
    g_atomic_int_set(&singleton->stopping, FALSE);

    singleton->thread_started = pthread_create(
        &singleton->thread, NULL, player_thread, singleton) == 0;

    if (!singleton->thread_started) {
        send_state(singleton, "ERROR", "Não foi possível criar a thread do player SRT.");
        g_atomic_int_set(&singleton->stopping, TRUE);
    }
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeStop(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&player_lock);
    if (singleton) {
        stop_thread_locked(singleton);
        release_window_locked(singleton);
    }
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT void JNICALL Java_com_isaque_signalplay_SrtPlayer_nativeRelease(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&player_lock);
    if (!singleton) {
        pthread_mutex_unlock(&player_lock);
        return;
    }

    stop_thread_locked(singleton);
    release_window_locked(singleton);
    g_free(singleton->uri);
    singleton->uri = NULL;

    (*env)->DeleteGlobalRef(env, singleton->owner);
    singleton->owner = NULL;
    g_mutex_clear(&singleton->pipeline_lock);
    g_mutex_clear(&singleton->sync_lock);
    g_free(singleton);
    singleton = NULL;
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    java_vm = vm;
    return JNI_VERSION_1_6;
}

#include <jni.h>
#include <ghostty/vt.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>

extern void ghostty_muxy_events(GhosttyTerminal, void (*)(void *, uint32_t, const uint8_t *, size_t), void *);
extern GhosttyResult ghostty_muxy_cell(GhosttyTerminal, uint16_t, uint16_t, uint32_t, uint64_t);
extern GhosttyResult ghostty_muxy_scroll(GhosttyTerminal);
extern GhosttyResult ghostty_muxy_cursor(GhosttyTerminal, uint16_t, uint16_t);
extern GhosttyResult ghostty_muxy_advance(GhosttyTerminal, uint16_t, uint16_t);
extern uint32_t ghostty_muxy_keyboard(GhosttyTerminal);
extern void ghostty_muxy_reset_colors(GhosttyTerminal);
extern bool ghostty_muxy_take_failure(GhosttyTerminal);

#define MAX_CELLS ((size_t)1024 * 1024)
#define MAX_ROW_BYTES ((size_t)16 * 1024 * 1024)
#define ROW_MAGIC UINT32_C(0x4d585231)
#define JNI_NAME(name) Java_com_termux_terminal_NativeTerminal_##name

typedef struct {
    GhosttyTerminal terminal;
    GhosttyKeyEncoder encoder;
    GhosttyRenderState render;
    JavaVM *vm;
    jobject listener;
    jmethodID write;
    jmethodID bell;
    jmethodID title;
    jmethodID clipboard;
    jmethodID event;
    jint scroll_shift;
    uint32_t cell_width, cell_height;
} MuxyTerminal;

static void on_event(void *userdata, uint32_t kind, const uint8_t *data, size_t length);

static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) {
        (*env)->ThrowNew(env, type, message);
        (*env)->DeleteLocalRef(env, type);
    }
}

static MuxyTerminal *terminal(JNIEnv *env, jlong handle) {
    if (!handle) {
        fail(env, "Terminal is closed");
        return NULL;
    }
    return (MuxyTerminal *)(uintptr_t)handle;
}

static bool check(JNIEnv *env, GhosttyResult result) {
    if (result == GHOSTTY_SUCCESS) return true;
    fail(env, "Native terminal operation failed");
    return false;
}

static JNIEnv *callback_env(MuxyTerminal *state) {
    JNIEnv *env = NULL;
    if ((*state->vm)->GetEnv(state->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return NULL;
    return env;
}

static jbyteArray bytes(JNIEnv *env, const void *data, size_t length) {
    if (length > INT_MAX || (length && !data)) {
        fail(env, "Native terminal payload is too large");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (result && length) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, data);
    return result;
}

static void deliver(MuxyTerminal *state, jmethodID method, const void *data, size_t length) {
    JNIEnv *env = callback_env(state);
    if (!env || (*env)->ExceptionCheck(env)) return;
    jbyteArray value = bytes(env, data, length);
    if (!value) return;
    (*env)->CallVoidMethod(env, state->listener, method, value);
    (*env)->DeleteLocalRef(env, value);
}

static void on_event(void *userdata, uint32_t kind, const uint8_t *data, size_t length) {
    MuxyTerminal *state = userdata;
    JNIEnv *env = callback_env(state);
    if (!env || (*env)->ExceptionCheck(env)) return;
    jbyteArray value = bytes(env, data, length);
    if (!value) return;
    (*env)->CallVoidMethod(env, state->listener, state->event, (jint)kind, value);
    (*env)->DeleteLocalRef(env, value);
}

JNIEXPORT void JNICALL JNI_NAME(nativeSetCell)(JNIEnv *env, jclass clazz, jlong handle,
                                               jint x, jint y, jint cp, jlong style) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state) return;
    if (x < 0 || y < 0 || x > UINT16_MAX || y > UINT16_MAX || cp < 0) {
        fail(env, "Invalid terminal cell");
        return;
    }
    check(env, ghostty_muxy_cell(state->terminal, (uint16_t)x, (uint16_t)y, (uint32_t)cp, (uint64_t)style));
}

JNIEXPORT void JNICALL JNI_NAME(nativeAdvance)(JNIEnv *env, jclass clazz, jlong handle, jint count, jint x) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state) return;
    if (count < 0 || count > UINT16_MAX || x < 0 || x > UINT16_MAX) { fail(env, "Invalid image cursor movement"); return; }
    check(env, ghostty_muxy_advance(state->terminal, (uint16_t)count, (uint16_t)x));
}

JNIEXPORT void JNICALL JNI_NAME(nativeSetCursor)(JNIEnv *env, jclass clazz, jlong handle, jint x, jint y) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state) return;
    if (x < 0 || y < 0 || x > UINT16_MAX || y > UINT16_MAX) { fail(env, "Invalid cursor position"); return; }
    check(env, ghostty_muxy_cursor(state->terminal, (uint16_t)x, (uint16_t)y));
}

JNIEXPORT void JNICALL JNI_NAME(nativeScroll)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (state) check(env, ghostty_muxy_scroll(state->terminal));
}

static void on_write(GhosttyTerminal value, void *userdata, const uint8_t *data, size_t length) {
    (void)value;
    MuxyTerminal *state = userdata;
    deliver(state, state->write, data, length);
}

static void on_bell(GhosttyTerminal value, void *userdata) {
    (void)value;
    MuxyTerminal *state = userdata;
    JNIEnv *env = callback_env(state);
    if (env && !(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, state->listener, state->bell);
}

static void on_title(GhosttyTerminal value, void *userdata) {
    MuxyTerminal *state = userdata;
    GhosttyString title = {0};
    if (ghostty_terminal_get(value, GHOSTTY_TERMINAL_DATA_TITLE, &title) == GHOSTTY_SUCCESS) {
        deliver(state, state->title, title.ptr, title.len);
    }
}

static GhosttyClipboardWriteResult on_clipboard(GhosttyTerminal value, void *userdata,
                                               const GhosttyClipboardWrite *request) {
    (void)value;
    MuxyTerminal *state = userdata;
    if (!request || request->size < sizeof(*request)) return GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA;
    if (!request->contents_len) {
        deliver(state, state->clipboard, NULL, 0);
    } else {
        if (!request->contents) return GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA;
        const GhosttyClipboardContent *content = &request->contents[0];
        const char prefix[] = "text/plain";
        if (content->mime.len < sizeof(prefix) - 1 || !content->mime.ptr ||
            memcmp(content->mime.ptr, prefix, sizeof(prefix) - 1) != 0 ||
            (content->mime.len > sizeof(prefix) - 1 && content->mime.ptr[sizeof(prefix) - 1] != ';')) {
            return GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
        }
        deliver(state, state->clipboard, content->data.ptr, content->data.len);
    }
    JNIEnv *env = callback_env(state);
    return env && !(*env)->ExceptionCheck(env) ? GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS
                                              : GHOSTTY_CLIPBOARD_WRITE_RESULT_IO_ERROR;
}

static bool on_size(GhosttyTerminal value, void *userdata, GhosttySizeReportSize *out) {
    MuxyTerminal *state = userdata;
    if (!out) return false;
    *out = (GhosttySizeReportSize){.cell_width = state->cell_width, .cell_height = state->cell_height};
    return ghostty_terminal_get(value, GHOSTTY_TERMINAL_DATA_COLS, &out->columns) == GHOSTTY_SUCCESS &&
        ghostty_terminal_get(value, GHOSTTY_TERMINAL_DATA_ROWS, &out->rows) == GHOSTTY_SUCCESS;
}

static bool dimensions(JNIEnv *env, jint columns, jint rows) {
    if (columns <= 0 || columns > UINT16_MAX || rows <= 0 || rows > UINT16_MAX ||
        (size_t)columns * (size_t)rows > MAX_CELLS) {
        fail(env, "Invalid terminal dimensions");
        return false;
    }
    return true;
}

static void free_terminal(JNIEnv *env, MuxyTerminal *state) {
    if (!state) return;
    if (state->terminal) ghostty_terminal_free(state->terminal);
    if (state->encoder) ghostty_key_encoder_free(state->encoder);
    if (state->render) ghostty_render_state_free(state->render);
    if (state->listener) (*env)->DeleteGlobalRef(env, state->listener);
    free(state);
}

JNIEXPORT jlong JNICALL JNI_NAME(nativeCreate)(JNIEnv *env, jclass clazz, jint columns,
                                               jint rows, jint history, jobject listener) {
    (void)clazz;
    if (!dimensions(env, columns, rows) || history < 0 || !listener) return 0;
    MuxyTerminal *state = calloc(1, sizeof(*state));
    if (!state) {
        fail(env, "Cannot allocate native terminal");
        return 0;
    }
    if ((*env)->GetJavaVM(env, &state->vm) != JNI_OK) goto error;
    state->listener = (*env)->NewGlobalRef(env, listener);
    if (!state->listener) goto error;
    jclass type = (*env)->GetObjectClass(env, listener);
    if (!type) goto error;
    state->write = (*env)->GetMethodID(env, type, "onWrite", "([B)V");
    state->bell = (*env)->GetMethodID(env, type, "onBell", "()V");
    state->title = (*env)->GetMethodID(env, type, "onTitle", "([B)V");
    state->clipboard = (*env)->GetMethodID(env, type, "onClipboard", "([B)V");
    state->event = (*env)->GetMethodID(env, type, "onEvent", "(I[B)V");
    (*env)->DeleteLocalRef(env, type);
    if ((*env)->ExceptionCheck(env) || !state->write || !state->bell || !state->title || !state->clipboard || !state->event) goto error;
    if (!check(env, ghostty_terminal_new(NULL, &state->terminal, (uint16_t)columns, (uint16_t)rows)) ||
        !check(env, ghostty_key_encoder_new(NULL, &state->encoder)) ||
        !check(env, ghostty_render_state_new(NULL, &state->render))) goto error;
    size_t limit = (size_t)history;
    size_t zero = 0;
    uint64_t image_limit = 0;
    bool allow_external_images = false;
    if (!check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &image_limit)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE, &allow_external_images)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM, &allow_external_images)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE, NULL)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
                                       history == 0 ? &zero : NULL)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &limit)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, state)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)on_write)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_BELL, (const void *)on_bell)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_SIZE, (const void *)on_size)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, (const void *)on_title)) ||
        !check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, (const void *)on_clipboard))) goto error;
    ghostty_muxy_events(state->terminal, on_event, state);
    return (jlong)(uintptr_t)state;
error:
    free_terminal(env, state);
    fail(env, "Cannot create native terminal");
    return 0;
}

JNIEXPORT void JNICALL JNI_NAME(nativeDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    if (handle) free_terminal(env, (MuxyTerminal *)(uintptr_t)handle);
}

JNIEXPORT void JNICALL JNI_NAME(nativeWrite)(JNIEnv *env, jclass clazz, jlong handle,
                                             jbyteArray input, jint offset, jint length) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || !input) return;
    jsize size = (*env)->GetArrayLength(env, input);
    if (offset < 0 || length < 0 || offset > size - length) {
        fail(env, "Invalid terminal input range");
        return;
    }
    jbyte *data = (*env)->GetByteArrayElements(env, input, NULL);
    if (!data) return;
    GhosttyTrackedGridRef anchor = NULL;
    GhosttyPoint top = {.tag = GHOSTTY_POINT_TAG_ACTIVE, .value = {.coordinate = {.x = 0, .y = 0}}};
    if (!check(env, ghostty_terminal_grid_ref_track(state->terminal, top, &anchor))) {
        (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
        return;
    }
    ghostty_terminal_vt_write(state->terminal, (const uint8_t *)data + offset, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
    GhosttyPointCoordinate coordinate = {0};
    size_t history = 0;
    uint16_t rows = 0;
    ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &history);
    ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
    GhosttyResult location = ghostty_tracked_grid_ref_point(anchor, GHOSTTY_POINT_TAG_SCREEN, &coordinate);
    state->scroll_shift = location == GHOSTTY_SUCCESS && coordinate.y <= history
        ? (jint)(history - coordinate.y) : (jint)(history + rows);
    ghostty_tracked_grid_ref_free(anchor);
    // A failed sequence is dropped and processing continues; Java logs it.
    if (ghostty_muxy_take_failure(state->terminal)) on_event(state, 11, NULL, 0);
}

JNIEXPORT void JNICALL JNI_NAME(nativeResize)(JNIEnv *env, jclass clazz, jlong handle,
                                              jint columns, jint rows, jint width, jint height) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || !dimensions(env, columns, rows)) return;
    if (width < 0 || height < 0 || (uint64_t)columns * (uint32_t)width > UINT32_MAX ||
        (uint64_t)rows * (uint32_t)height > UINT32_MAX) {
        fail(env, "Invalid terminal pixel dimensions");
        return;
    }
    if (check(env, ghostty_terminal_resize(state->terminal, (uint16_t)columns, (uint16_t)rows,
                                      (uint32_t)width, (uint32_t)height))) {
        state->cell_width = (uint32_t)width;
        state->cell_height = (uint32_t)height;
        if (ghostty_muxy_take_failure(state->terminal)) on_event(state, 11, NULL, 0);
    }
}

JNIEXPORT void JNICALL JNI_NAME(nativeReset)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (state) ghostty_terminal_reset(state->terminal);
}

JNIEXPORT void JNICALL JNI_NAME(nativeClearHistory)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state) return;
    size_t zero = 0, limit = 0;
    if (!check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_MAX_LINES, &limit))) return;
    if (check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &zero))) {
        check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
                                       limit == 0 ? &zero : NULL));
    }
}

JNIEXPORT void JNICALL JNI_NAME(nativeSetHistoryRows)(JNIEnv *env, jclass clazz, jlong handle, jint rows) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || rows < 0) return;
    size_t value = (size_t)rows, zero = 0;
    if (!check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &value))) return;
    check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
                                   rows == 0 ? &zero : NULL));
}

JNIEXPORT jboolean JNICALL JNI_NAME(nativeMode)(JNIEnv *env, jclass clazz, jlong handle, jint mode, jboolean ansi) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || mode < 0 || mode > 32767) return JNI_FALSE;
    GhosttyTerminalModeConfig config = {.mode = ghostty_mode_new((uint16_t)mode, ansi), .value = false};
    return ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_MODE, &config) == GHOSTTY_SUCCESS && config.value;
}

JNIEXPORT void JNICALL JNI_NAME(nativeSetMode)(JNIEnv *env, jclass clazz, jlong handle,
                                               jint mode, jboolean ansi, jboolean value) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || mode < 0 || mode > 32767) return;
    GhosttyTerminalModeConfig config = {.mode = ghostty_mode_new((uint16_t)mode, ansi), .value = value};
    check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_MODE, &config));
}

JNIEXPORT void JNICALL JNI_NAME(nativeSetCursorStyle)(JNIEnv *env, jclass clazz, jlong handle, jint style) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || style < 0 || style > 2) return;
    const GhosttyTerminalCursorStyle styles[] = {GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK,
        GHOSTTY_TERMINAL_CURSOR_STYLE_UNDERLINE, GHOSTTY_TERMINAL_CURSOR_STYLE_BAR};
    check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE, &styles[style]));
}

JNIEXPORT jint JNICALL JNI_NAME(nativeKeyboard)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    return state ? (jint)ghostty_muxy_keyboard(state->terminal) : 0;
}

JNIEXPORT jintArray JNICALL JNI_NAME(nativeState)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state) return NULL;
    uint16_t columns = 0, rows = 0, x = 0, y = 0;
    size_t history = 0;
    bool visible = false, mouse = false, blink = false, pending_wrap = false;
    GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    GhosttyTerminalScrollbar bar = {0};
    GhosttyRenderStateCursorVisualStyle style = GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;
    GhosttyTerminalModeConfig reverse = {.mode = GHOSTTY_MODE_REVERSE_COLORS};
    if (!check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_COLS, &columns)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_X, &x)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_PENDING_WRAP, &pending_wrap)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &history)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE, &visible)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_MODE, &reverse)) ||
        !check(env, ghostty_render_state_update(state->render, state->terminal)) ||
        !check(env, ghostty_render_state_get(state->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &style)) ||
        !check(env, ghostty_render_state_get(state->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING, &blink))) return NULL;
    if (history > INT_MAX || bar.len > bar.total || bar.offset > bar.total - bar.len ||
        bar.total - bar.len - bar.offset > INT_MAX) {
        fail(env, "Native terminal history is too large");
        return NULL;
    }
    int cursor = style == GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR ? 2
                 : style == GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE ? 1 : 0;
    jint values[] = {columns, rows, x, y, cursor, visible, screen, (jint)history,
        -(jint)(bar.total - bar.len - bar.offset), mouse, reverse.value, blink, state->scroll_shift, pending_wrap};
    jintArray result = (*env)->NewIntArray(env, 14);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 14, values);
    return result;
}

static GhosttyColorRgb color(jint value) {
    return (GhosttyColorRgb){.r = (uint8_t)((uint32_t)value >> 16),
        .g = (uint8_t)((uint32_t)value >> 8), .b = (uint8_t)value};
}

static jint argb(GhosttyColorRgb value) {
    return (jint)(UINT32_C(0xff000000) | (uint32_t)value.r << 16 | (uint32_t)value.g << 8 | value.b);
}

JNIEXPORT void JNICALL JNI_NAME(nativeSetColors)(JNIEnv *env, jclass clazz, jlong handle, jintArray colors) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || !colors || (*env)->GetArrayLength(env, colors) != 259) return;
    jint values[259];
    (*env)->GetIntArrayRegion(env, colors, 0, 259, values);
    if ((*env)->ExceptionCheck(env)) return;
    GhosttyColorRgb palette[256];
    for (size_t i = 0; i < 256; i++) palette[i] = color(values[i]);
    if (!check(env, ghostty_terminal_set(state->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette))) return;
    const GhosttyTerminalOption options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
        GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR};
    for (size_t i = 0; i < 3; i++) {
        GhosttyColorRgb value = color(values[256 + i]);
        if (!check(env, ghostty_terminal_set(state->terminal, options[i], &value))) return;
    }
    // A new scheme replaces program overrides, as Termux's color reset did.
    ghostty_muxy_reset_colors(state->terminal);
}

JNIEXPORT void JNICALL JNI_NAME(nativeReadColors)(JNIEnv *env, jclass clazz, jlong handle, jintArray colors) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || !colors || (*env)->GetArrayLength(env, colors) != 259) return;
    jint values[259];
    GhosttyColorRgb palette[256];
    if (!check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE, palette))) return;
    for (size_t i = 0; i < 256; i++) values[i] = argb(palette[i]);
    const GhosttyTerminalData fields[] = {GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND,
        GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, GHOSTTY_TERMINAL_DATA_COLOR_CURSOR};
    for (size_t i = 0; i < 3; i++) {
        GhosttyColorRgb value = {0};
        if (!check(env, ghostty_terminal_get(state->terminal, fields[i], &value))) return;
        values[256 + i] = argb(value);
    }
    (*env)->SetIntArrayRegion(env, colors, 0, 259, values);
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nativeTitle)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state) return NULL;
    GhosttyString title = {0};
    GhosttyResult result = ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_TITLE, &title);
    if (result == GHOSTTY_NO_VALUE) return NULL;
    if (!check(env, result)) return NULL;
    return bytes(env, title.ptr, title.len);
}

JNIEXPORT void JNICALL JNI_NAME(nativeSetTopRow)(JNIEnv *env, jclass clazz, jlong handle, jint row) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || row > 0) return;
    size_t history = 0;
    if (!check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &history))) return;
    uint64_t back = (uint64_t)(-(int64_t)row);
    GhosttyTerminalScrollViewport scroll = {.tag = GHOSTTY_SCROLL_VIEWPORT_ROW,
        .value = {.row = back > history ? 0 : history - (size_t)back}};
    ghostty_terminal_scroll_viewport(state->terminal, scroll);
}

JNIEXPORT jlong JNICALL JNI_NAME(nativeCompressionActivity)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    uint64_t token = 0;
    if (state) check(env, ghostty_terminal_compression_activity(state->terminal, &token));
    return (jlong)token;
}

JNIEXPORT jboolean JNICALL JNI_NAME(nativeCompressHistory)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    GhosttyTerminalCompressionResult result = GHOSTTY_TERMINAL_COMPRESSION_RESULT_UNSUPPORTED;
    if (state) check(env, ghostty_terminal_compress(state->terminal, GHOSTTY_TERMINAL_COMPRESSION_MODE_INCREMENTAL, &result));
    return result == GHOSTTY_TERMINAL_COMPRESSION_RESULT_PENDING;
}

static uint64_t encoded_style(GhosttyStyle *style, bool protected_cell) {
    uint64_t result = (style->bold ? 1 : 0) | (style->italic ? 2 : 0) | (style->underline ? 4 : 0) |
        (style->blink ? 8 : 0) | (style->inverse ? 16 : 0) | (style->invisible ? 32 : 0) |
        (style->strikethrough ? 64 : 0) | (protected_cell ? 128 : 0) | (style->faint ? 256 : 0);
    GhosttyStyleColor values[] = {style->fg_color, style->bg_color};
    const unsigned shifts[] = {40, 16};
    for (size_t i = 0; i < 2; i++) {
        uint64_t value = i == 0 ? 256 : 257;
        if (values[i].tag == GHOSTTY_STYLE_COLOR_PALETTE) value = values[i].value.palette;
        if (values[i].tag == GHOSTTY_STYLE_COLOR_RGB) {
            GhosttyColorRgb rgb = values[i].value.rgb;
            value = (uint32_t)rgb.r << 16 | (uint32_t)rgb.g << 8 | rgb.b;
            result |= UINT64_C(1) << (9 + i);
        }
        result |= value << shifts[i];
    }
    return result;
}

JNIEXPORT jlong JNICALL JNI_NAME(nativeCursorStyle)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
    if (!state || !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_STYLE, &style))) return 0;
    return (jlong)encoded_style(&style, false);
}

static void put32(uint8_t **cursor, uint32_t value) {
    for (unsigned i = 0; i < 4; i++) *(*cursor)++ = (uint8_t)(value >> (8 * i));
}

static void put64(uint8_t **cursor, uint64_t value) {
    for (unsigned i = 0; i < 8; i++) *(*cursor)++ = (uint8_t)(value >> (8 * i));
}

JNIEXPORT jint JNICALL JNI_NAME(nativeReadRow)(JNIEnv *env, jclass clazz, jlong handle, jint row, jobject buffer) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || !buffer) return 0;
    uint16_t columns = 0, rows = 0;
    size_t history = 0;
    if (!check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_COLS, &columns)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows)) ||
        !check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &history))) return 0;
    if (row >= rows || (row < 0 && (uint64_t)(-(int64_t)row) > history)) {
        fail(env, "Terminal row is out of range");
        return 0;
    }
    GhosttyPoint point = {.tag = row < 0 ? GHOSTTY_POINT_TAG_HISTORY : GHOSTTY_POINT_TAG_ACTIVE,
        .value = {.coordinate = {.x = 0, .y = row < 0 ? (uint32_t)(history - (size_t)(-(int64_t)row)) : (uint32_t)row}}};
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (!check(env, ghostty_terminal_grid_ref(state->terminal, point, &ref))) return 0;
    GhosttyRow native_row = 0;
    bool wrap = false;
    if (!check(env, ghostty_grid_ref_row(&ref, &native_row)) ||
        !check(env, ghostty_row_get(native_row, GHOSTTY_ROW_DATA_WRAP, &wrap))) return 0;
    size_t required = 12;
    for (uint32_t x = 0; x < columns; x++) {
        ref.x = (uint16_t)x;
        size_t count = 0;
        GhosttyResult result = ghostty_grid_ref_graphemes(&ref, NULL, 0, &count);
        if (result != GHOSTTY_SUCCESS && result != GHOSTTY_OUT_OF_SPACE) {
            check(env, result);
            return 0;
        }
        if (count > (MAX_ROW_BYTES - 16) / 4 || required > MAX_ROW_BYTES - 16 - count * 4) {
            fail(env, "Native terminal row is too large");
            return 0;
        }
        required += 16 + count * 4;
    }
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    uint8_t *output = (*env)->GetDirectBufferAddress(env, buffer);
    if (capacity < 0 || !output) {
        fail(env, "Terminal row requires a direct buffer");
        return 0;
    }
    if ((uint64_t)capacity < required) return -(jint)required;
    uint8_t *cursor = output;
    put32(&cursor, ROW_MAGIC);
    put32(&cursor, columns);
    put32(&cursor, wrap);
    uint32_t *graphemes = NULL;
    size_t available = 0;
    for (uint32_t x = 0; x < columns; x++) {
        ref.x = (uint16_t)x;
        GhosttyCell cell = 0;
        GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        GhosttyCellContentTag tag = GHOSTTY_CELL_CONTENT_CODEPOINT;
        bool protected_cell = false;
        size_t count = 0;
        if (!check(env, ghostty_grid_ref_cell(&ref, &cell)) ||
            !check(env, ghostty_grid_ref_style(&ref, &style)) ||
            !check(env, ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide)) ||
            !check(env, ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CONTENT_TAG, &tag)) ||
            !check(env, ghostty_cell_get(cell, GHOSTTY_CELL_DATA_PROTECTED, &protected_cell))) goto row_error;
        // Erased cells carry their background in the cell, not in a style.
        if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
            GhosttyColorPaletteIndex index = 0;
            if (!check(env, ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_PALETTE, &index))) goto row_error;
            style.bg_color.tag = GHOSTTY_STYLE_COLOR_PALETTE;
            style.bg_color.value.palette = index;
        } else if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB) {
            GhosttyColorRgb rgb = {0};
            if (!check(env, ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_RGB, &rgb))) goto row_error;
            style.bg_color.tag = GHOSTTY_STYLE_COLOR_RGB;
            style.bg_color.value.rgb = rgb;
        }
        GhosttyResult result = ghostty_grid_ref_graphemes(&ref, graphemes, available, &count);
        if (result == GHOSTTY_OUT_OF_SPACE) {
            uint32_t *grown = realloc(graphemes, count * sizeof(*graphemes));
            if (!grown) {
                fail(env, "Cannot allocate native row graphemes");
                goto row_error;
            }
            graphemes = grown;
            available = count;
            result = ghostty_grid_ref_graphemes(&ref, graphemes, available, &count);
        }
        if (!check(env, result)) goto row_error;
        uint64_t packed_style = encoded_style(&style, protected_cell);
        uint8_t uri[64];
        size_t uri_length = 0;
        if (ghostty_grid_ref_hyperlink_uri(&ref, uri, sizeof(uri) - 1, &uri_length) == GHOSTTY_SUCCESS &&
            uri_length == 32 && memcmp(uri, "muxy-cell-image:", 16) == 0) {
            uint64_t image = 0;
            bool valid = true;
            for (size_t i = 16; i < uri_length; i++) {
                uint8_t c = uri[i];
                if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) { valid = false; break; }
                image = (image << 4) | (uint64_t)(c <= '9' ? c - '0' : c - 'a' + 10);
            }
            if (valid && (image & 0x8000)) packed_style = image;
        }
        put64(&cursor, packed_style);
        put32(&cursor, (uint32_t)wide);
        put32(&cursor, (uint32_t)count);
        for (size_t i = 0; i < count; i++) put32(&cursor, graphemes[i]);
    }
    free(graphemes);
    return (jint)(cursor - output);
row_error:
    free(graphemes);
    return 0;
}

JNIEXPORT jintArray JNICALL JNI_NAME(nativeClusterLayout)(JNIEnv *env, jclass clazz,
                                                          jintArray input, jboolean grapheme_mode) {
    (void)clazz;
    if (!input) return NULL;
    jsize count = (*env)->GetArrayLength(env, input);
    if (count > INT_MAX / 3 || (size_t)count > SIZE_MAX / (3 * sizeof(jint))) {
        fail(env, "Composition is too large");
        return NULL;
    }
    if (!count) return (*env)->NewIntArray(env, 0);
    jint *cps = (*env)->GetIntArrayElements(env, input, NULL);
    if (!cps) return NULL;
    jint *values = malloc((size_t)count * 3 * sizeof(*values));
    if (!values) {
        (*env)->ReleaseIntArrayElements(env, input, cps, JNI_ABORT);
        fail(env, "Cannot allocate composition layout");
        return NULL;
    }
    for (jsize i = 0; i < count; i++) {
        if (cps[i] < 0 || cps[i] > 0x10ffff || (cps[i] >= 0xd800 && cps[i] <= 0xdfff)) {
            (*env)->ReleaseIntArrayElements(env, input, cps, JNI_ABORT);
            free(values);
            fail(env, "Invalid composition codepoint");
            return NULL;
        }
    }
    size_t output = 0;
    for (size_t i = 0; i < (size_t)count;) {
        uint8_t width = 0;
        size_t consumed = ghostty_unicode_grapheme_width((const uint32_t *)cps + i, (size_t)count - i, &width);
        if (!consumed || consumed > (size_t)count - i) {
            fail(env, "Invalid composition cluster");
            break;
        }
        jint cells = width;
        if (!grapheme_mode) {
            cells = 0;
            for (size_t j = 0; j < consumed; j++) cells += ghostty_unicode_codepoint_width((uint32_t)cps[i + j]);
        }
        values[output++] = (jint)i;
        values[output++] = (jint)consumed;
        values[output++] = cells;
        i += consumed;
    }
    (*env)->ReleaseIntArrayElements(env, input, cps, JNI_ABORT);
    jintArray result = NULL;
    if (!(*env)->ExceptionCheck(env)) {
        result = (*env)->NewIntArray(env, (jsize)output);
        if (result) (*env)->SetIntArrayRegion(env, result, 0, (jsize)output, values);
    }
    free(values);
    return result;
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nativeEncodeKey)(JNIEnv *env, jclass clazz, jlong handle,
                                                       jint key, jint modifiers, jbyteArray text,
                                                       jint action, jboolean composing, jint unshifted) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state) return NULL;
    if (key < GHOSTTY_KEY_UNIDENTIFIED || key > GHOSTTY_KEY_PASTE || action < 0 || action > 2 ||
        modifiers < 0 || modifiers > 1023 || unshifted < 0 || unshifted > 0x10ffff ||
        (unshifted >= 0xd800 && unshifted <= 0xdfff)) {
        fail(env, "Invalid terminal key event");
        return NULL;
    }
    GhosttyKeyEvent event = NULL;
    jbyte *utf8 = NULL;
    uint8_t *output = NULL;
    jbyteArray encoded = NULL;
    if (!check(env, ghostty_key_event_new(NULL, &event))) return NULL;
    ghostty_key_event_set_key(event, (GhosttyKey)key);
    ghostty_key_event_set_action(event, (GhosttyKeyAction)action);
    ghostty_key_event_set_mods(event, (GhosttyMods)modifiers);
    ghostty_key_event_set_composing(event, composing);
    ghostty_key_event_set_unshifted_codepoint(event, (uint32_t)unshifted);
    if (text) {
        jsize length = (*env)->GetArrayLength(env, text);
        utf8 = (*env)->GetByteArrayElements(env, text, NULL);
        if (!utf8) goto key_done;
        ghostty_key_event_set_utf8(event, (const char *)utf8, (size_t)length);
    }
    ghostty_key_encoder_setopt_from_terminal(state->encoder, state->terminal);
    size_t required = 0;
    GhosttyResult result = ghostty_key_encoder_encode(state->encoder, event, NULL, 0, &required);
    if (result != GHOSTTY_SUCCESS && result != GHOSTTY_OUT_OF_SPACE) {
        check(env, result);
        goto key_done;
    }
    if (required > INT_MAX) {
        fail(env, "Encoded key is too large");
        goto key_done;
    }
    if (!required) {
        encoded = bytes(env, NULL, 0);
        goto key_done;
    }
    output = malloc(required);
    if (!output) {
        fail(env, "Cannot allocate encoded key");
        goto key_done;
    }
    if (check(env, ghostty_key_encoder_encode(state->encoder, event, (char *)output, required, &required))) {
        encoded = bytes(env, output, required);
    }
key_done:
    if (utf8) (*env)->ReleaseByteArrayElements(env, text, utf8, JNI_ABORT);
    free(output);
    ghostty_key_event_free(event);
    return encoded;
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nativeEncodePaste)(JNIEnv *env, jclass clazz,
                                                         jlong handle, jbyteArray text) {
    (void)clazz;
    MuxyTerminal *state = terminal(env, handle);
    if (!state || !text) return NULL;
    jsize length = (*env)->GetArrayLength(env, text);
    jbyte *input = (*env)->GetByteArrayElements(env, text, NULL);
    if (!input) return NULL;
    GhosttyTerminalModeConfig mode = {.mode = GHOSTTY_MODE_BRACKETED_PASTE};
    jbyteArray encoded = NULL;
    char *output = NULL;
    if (!check(env, ghostty_terminal_get(state->terminal, GHOSTTY_TERMINAL_DATA_MODE, &mode))) goto paste_done;
    size_t required = 0;
    GhosttyResult result = ghostty_paste_encode((char *)input, (size_t)length, mode.value, NULL, 0, &required);
    if (result != GHOSTTY_SUCCESS && result != GHOSTTY_OUT_OF_SPACE) {
        check(env, result);
        goto paste_done;
    }
    if (required > INT_MAX) {
        fail(env, "Encoded paste is too large");
        goto paste_done;
    }
    output = malloc(required ? required : 1);
    if (!output) {
        fail(env, "Cannot allocate encoded paste");
        goto paste_done;
    }
    if (check(env, ghostty_paste_encode((char *)input, (size_t)length, mode.value, output, required, &required))) {
        encoded = bytes(env, output, required);
    }
paste_done:
    free(output);
    (*env)->ReleaseByteArrayElements(env, text, input, JNI_ABORT);
    return encoded;
}

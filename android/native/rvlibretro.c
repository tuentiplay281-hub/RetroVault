// RetroVault: reproductor libretro nativo mínimo (para Nintendo DS con melonDS DS).
// Carga el núcleo (.so) con dlopen, dibuja por software en un ANativeWindow y entrega el audio a Java (AudioTrack).
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "libretro.h"

#define TAG "RetroVault"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// --- Funciones del núcleo ---
static void *core;
static void (*c_init)(void), (*c_deinit)(void), (*c_run)(void), (*c_unload)(void);
static void (*c_get_system_info)(struct retro_system_info *), (*c_get_av_info)(struct retro_system_av_info *);
static bool (*c_load_game)(const struct retro_game_info *);
static void *(*c_get_memory_data)(unsigned); static size_t (*c_get_memory_size)(unsigned);
static size_t (*c_serialize_size)(void); static bool (*c_serialize)(void *, size_t), (*c_unserialize)(const void *, size_t);
static void (*c_set_env)(retro_environment_t), (*c_set_video)(retro_video_refresh_t), (*c_set_audio)(retro_audio_sample_t),
            (*c_set_audio_batch)(retro_audio_sample_batch_t), (*c_set_poll)(retro_input_poll_t), (*c_set_state)(retro_input_state_t),
            (*c_set_controller)(unsigned, unsigned);

// --- Estado ---
static char sys_dir[512], save_dir[512], sram_path[600];
static enum retro_pixel_format pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static ANativeWindow *window; static pthread_mutex_t win_lock = PTHREAD_MUTEX_INITIALIZER;
static int win_w, win_h;
static volatile int buttons;                 // máscara RETRO_DEVICE_ID_JOYPAD_*
static volatile int ptr_x, ptr_y, ptr_down;  // táctil, en coordenadas libretro (-0x7fff..0x7fff)
// Pulsaciones "recordadas" hasta el siguiente frame: un toque rápido (50 ms) entre dos frames no se pierde
static volatile int latched_buttons, latched_touch;
static int16_t audio_buf[48000];             // estéreo intercalado; se vacía en cada frame
static size_t audio_len;
static double sample_rate = 32768;

static void core_log(enum retro_log_level level, const char *fmt, ...) {
  va_list ap; va_start(ap, fmt); __android_log_vprint(level >= RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "rv-core", fmt, ap); va_end(ap);
}

static bool env(unsigned cmd, void *data) {
  switch (cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL) {
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)data = true; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
      enum retro_pixel_format f = *(enum retro_pixel_format *)data;
      if (f == RETRO_PIXEL_FORMAT_XRGB8888 || f == RETRO_PIXEL_FORMAT_RGB565 || f == RETRO_PIXEL_FORMAT_0RGB1555) { pixfmt = f; return true; }
      return false;
    }
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char **)data = sys_dir; return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **)data = save_dir; return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)data)->log = core_log; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
      sample_rate = ((struct retro_system_av_info *)data)->timing.sample_rate; return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY: return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)data = false; return true;
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS: case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
      return true;
    default: return false; // HW render (OpenGL), opciones v2, VFS...: no soportado -> el núcleo usa sus valores por defecto / software
  }
}

// Convierte una línea del formato del núcleo a RGBX_8888 (bytes R,G,B,X) del ANativeWindow
static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
  if (!data) return; // frame duplicado: dejar el anterior en pantalla
  pthread_mutex_lock(&win_lock);
  if (window) {
    if ((int)w != win_w || (int)h != win_h) { ANativeWindow_setBuffersGeometry(window, w, h, WINDOW_FORMAT_RGBX_8888); win_w = w; win_h = h; }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, NULL) == 0) {
      for (unsigned y = 0; y < h && y < (unsigned)buf.height; y++) {
        uint32_t *dst = (uint32_t *)buf.bits + (size_t)y * buf.stride;
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        for (unsigned x = 0; x < w && x < (unsigned)buf.width; x++) {
          uint32_t r, g, b;
          if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) { uint32_t p = ((const uint32_t *)row)[x]; r = (p >> 16) & 255; g = (p >> 8) & 255; b = p & 255; }
          else if (pixfmt == RETRO_PIXEL_FORMAT_RGB565) { uint16_t p = ((const uint16_t *)row)[x]; r = (p >> 11) << 3; g = ((p >> 5) & 63) << 2; b = (p & 31) << 3; }
          else { uint16_t p = ((const uint16_t *)row)[x]; r = ((p >> 10) & 31) << 3; g = ((p >> 5) & 31) << 3; b = (p & 31) << 3; }
          dst[x] = r | (g << 8) | (b << 16) | 0xFF000000u;
        }
      }
      ANativeWindow_unlockAndPost(window);
    }
  }
  pthread_mutex_unlock(&win_lock);
}

static size_t audio_batch(const int16_t *d, size_t frames) {
  size_t n = frames * 2;
  if (audio_len + n > sizeof audio_buf / 2) n = sizeof audio_buf / 2 - audio_len; // no desbordar
  memcpy(audio_buf + audio_len, d, n * 2); audio_len += n;
  return frames;
}
static void audio_sample(int16_t l, int16_t r) { int16_t s[2] = {l, r}; audio_batch(s, 1); }
static void poll(void) {}
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
  if (port != 0) return 0;
  int b = buttons | latched_buttons;
  if (device == RETRO_DEVICE_JOYPAD) return id == RETRO_DEVICE_ID_JOYPAD_MASK ? (int16_t)b : (b >> id) & 1;
  if (device == RETRO_DEVICE_POINTER) {
    if (id == RETRO_DEVICE_ID_POINTER_X) return ptr_x;
    if (id == RETRO_DEVICE_ID_POINTER_Y) return ptr_y;
    if (id == RETRO_DEVICE_ID_POINTER_PRESSED) return ptr_down | latched_touch;
  }
  return 0;
}

#define SYM(var, name) if (!(*(void **)&var = dlsym(core, name))) { LOG("falta %s", name); return false; }
static bool load_symbols(void) {
  SYM(c_init, "retro_init") SYM(c_deinit, "retro_deinit") SYM(c_run, "retro_run") SYM(c_unload, "retro_unload_game")
  SYM(c_get_system_info, "retro_get_system_info") SYM(c_get_av_info, "retro_get_system_av_info") SYM(c_load_game, "retro_load_game")
  SYM(c_get_memory_data, "retro_get_memory_data") SYM(c_get_memory_size, "retro_get_memory_size")
  SYM(c_set_env, "retro_set_environment") SYM(c_set_video, "retro_set_video_refresh") SYM(c_set_audio, "retro_set_audio_sample")
  SYM(c_set_audio_batch, "retro_set_audio_sample_batch") SYM(c_set_poll, "retro_set_input_poll") SYM(c_set_state, "retro_set_input_state")
  SYM(c_set_controller, "retro_set_controller_port_device")
  SYM(c_serialize_size, "retro_serialize_size") SYM(c_serialize, "retro_serialize") SYM(c_unserialize, "retro_unserialize")
  return true;
}

// Partida guardada "clásica" (SRAM) si el núcleo la expone; melonDS DS además guarda solo en save_dir
static void sram_io(bool write) {
  if (!c_get_memory_size || !sram_path[0]) return;
  size_t n = c_get_memory_size(RETRO_MEMORY_SAVE_RAM); void *p = c_get_memory_data(RETRO_MEMORY_SAVE_RAM);
  if (!n || !p) return;
  FILE *f = fopen(sram_path, write ? "wb" : "rb");
  if (!f) return;
  if (write) fwrite(p, 1, n, f); else fread(p, 1, n, f);
  fclose(f);
}

static void *rom_data;
JNIEXPORT jdouble JNICALL Java_com_jugarswf_DsActivity_nativeStart(JNIEnv *e, jclass c, jstring jcore, jstring jrom, jstring jsys, jstring jsave) {
  const char *corep = (*e)->GetStringUTFChars(e, jcore, 0), *rom = (*e)->GetStringUTFChars(e, jrom, 0);
  const char *s = (*e)->GetStringUTFChars(e, jsys, 0), *sv = (*e)->GetStringUTFChars(e, jsave, 0);
  snprintf(sys_dir, sizeof sys_dir, "%s", s); snprintf(save_dir, sizeof save_dir, "%s", sv);
  double rate = 0;
  core = dlopen(corep, RTLD_NOW | RTLD_LOCAL);
  if (!core) { LOG("dlopen: %s", dlerror()); goto out; }
  if (!load_symbols()) goto out;
  c_set_env(env); c_set_video(video); c_set_audio(audio_sample); c_set_audio_batch(audio_batch); c_set_poll(poll); c_set_state(input_state);
  c_init();
  struct retro_system_info si = {0}; c_get_system_info(&si);
  struct retro_game_info gi = { .path = rom };
  if (!si.need_fullpath) { // el núcleo quiere el contenido en memoria
    FILE *f = fopen(rom, "rb"); if (!f) { LOG("no abre %s", rom); goto out; }
    fseek(f, 0, SEEK_END); gi.size = ftell(f); fseek(f, 0, SEEK_SET);
    rom_data = malloc(gi.size); if (!rom_data || fread(rom_data, 1, gi.size, f) != gi.size) { fclose(f); goto out; }
    fclose(f); gi.data = rom_data;
  }
  if (!c_load_game(&gi)) { LOG("retro_load_game falló"); goto out; }
  c_set_controller(0, RETRO_DEVICE_JOYPAD);
  struct retro_system_av_info av; c_get_av_info(&av); sample_rate = av.timing.sample_rate;
  const char *base = strrchr(rom, '/'); snprintf(sram_path, sizeof sram_path, "%s/%s.srm", save_dir, base ? base + 1 : rom);
  sram_io(false);
  rate = sample_rate;
out:
  (*e)->ReleaseStringUTFChars(e, jcore, corep); (*e)->ReleaseStringUTFChars(e, jrom, rom);
  (*e)->ReleaseStringUTFChars(e, jsys, s); (*e)->ReleaseStringUTFChars(e, jsave, sv);
  return rate; // 0 = error
}

// Ejecuta un frame y devuelve cuántas muestras (int16) de audio dejó en 'out'
JNIEXPORT jint JNICALL Java_com_jugarswf_DsActivity_nativeRunFrame(JNIEnv *e, jclass c, jshortArray out) {
  audio_len = 0;
  c_run();
  latched_buttons = 0; latched_touch = 0; // el juego ya las vio en este frame
  jsize cap = (*e)->GetArrayLength(e, out);
  jint n = audio_len < (size_t)cap ? (jint)audio_len : cap;
  (*e)->SetShortArrayRegion(e, out, 0, n, audio_buf);
  return n;
}

JNIEXPORT void JNICALL Java_com_jugarswf_DsActivity_nativeSetSurface(JNIEnv *e, jclass c, jobject surface) {
  pthread_mutex_lock(&win_lock);
  if (window) { ANativeWindow_release(window); window = NULL; }
  if (surface) { window = ANativeWindow_fromSurface(e, surface); win_w = win_h = 0; }
  pthread_mutex_unlock(&win_lock);
}

JNIEXPORT void JNICALL Java_com_jugarswf_DsActivity_nativeInput(JNIEnv *e, jclass c, jint mask, jint x, jint y, jint down) {
  buttons = mask; ptr_x = x; ptr_y = y; ptr_down = down;
  latched_buttons |= mask; if (down) latched_touch = 1;
}

JNIEXPORT void JNICALL Java_com_jugarswf_DsActivity_nativeSave(JNIEnv *e, jclass c) { sram_io(true); }

// Estado completo (save state) a/desde archivo: autoguardado al salir, "Continuar" al volver. Devuelve true si funcionó.
JNIEXPORT jboolean JNICALL Java_com_jugarswf_DsActivity_nativeState(JNIEnv *e, jclass c, jstring jpath, jboolean save) {
  if (!core) return JNI_FALSE;
  const char *path = (*e)->GetStringUTFChars(e, jpath, 0);
  bool ok = false;
  size_t n = c_serialize_size();
  void *buf = n ? malloc(n) : NULL;
  if (buf) {
    if (save) {
      if (c_serialize(buf, n)) {
        char tmp[700]; snprintf(tmp, sizeof tmp, "%s.tmp", path); // escribir aparte y renombrar: nunca un estado a medias
        FILE *f = fopen(tmp, "wb");
        if (f) { ok = fwrite(buf, 1, n, f) == n; fclose(f); if (ok) ok = rename(tmp, path) == 0; }
      }
    } else {
      FILE *f = fopen(path, "rb");
      if (f) { size_t got = fread(buf, 1, n, f); fclose(f); ok = got > 0 && c_unserialize(buf, got); }
    }
    free(buf);
  }
  (*e)->ReleaseStringUTFChars(e, jpath, path);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_jugarswf_DsActivity_nativeStop(JNIEnv *e, jclass c) {
  if (!core) return;
  sram_io(true);
  if (c_unload) c_unload();
  if (c_deinit) c_deinit();
  dlclose(core); core = NULL; free(rom_data); rom_data = NULL;
}

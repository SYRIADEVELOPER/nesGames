// nesGames native libretro frontend: core loading, run loop, video (GLES2), audio (AAudio), input.
#include <jni.h>
#include <unistd.h>
#include <dlfcn.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <GLES3/gl32.h>
#include <EGL/egl.h>
#include <condition_variable>
#include <deque>
#include <functional>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "libretro.h"

#define TAG "nesGames"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct CoreApi {
    void *handle = nullptr;
    std::string path;
    void (*init)();
    void (*deinit)();
    unsigned (*api_version)();
    void (*get_system_info)(retro_system_info *);
    void (*get_system_av_info)(retro_system_av_info *);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*set_controller_port_device)(unsigned, unsigned);
    void (*reset)();
    void (*run)();
    size_t (*serialize_size)();
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    bool (*load_game)(const retro_game_info *);
    void (*unload_game)();
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
};

// Lock-free single-producer/single-consumer ring of interleaved stereo int16 frames.
class AudioRing {
public:
    void init(size_t frames) { cap_ = frames; buf_.assign(frames * 2, 0); r_ = 0; w_ = 0; }
    size_t capacity() const { return cap_; }
    size_t avail() const { return w_.load(std::memory_order_acquire) - r_.load(std::memory_order_acquire); }
    size_t write(const int16_t *d, size_t frames) {
        size_t n = std::min(frames, cap_ - avail());
        size_t wi = w_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < n; i++) {
            size_t idx = ((wi + i) % cap_) * 2;
            buf_[idx] = d[i * 2];
            buf_[idx + 1] = d[i * 2 + 1];
        }
        w_.store(wi + n, std::memory_order_release);
        return n;
    }
    size_t read(int16_t *out, size_t frames) {
        size_t n = std::min(frames, avail());
        size_t ri = r_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < n; i++) {
            size_t idx = ((ri + i) % cap_) * 2;
            out[i * 2] = buf_[idx];
            out[i * 2 + 1] = buf_[idx + 1];
        }
        r_.store(ri + n, std::memory_order_release);
        return n;
    }
private:
    std::vector<int16_t> buf_;
    size_t cap_ = 0;
    std::atomic<size_t> r_{0}, w_{0};
};

struct State {
    CoreApi core;
    std::mutex coreMutex;          // serialises every call into the core
    bool loaded = false;
    std::string systemDir, saveDir, sramPath, tempRomPath;
    std::vector<uint8_t> romData;
    retro_pixel_format pixFmt = RETRO_PIXEL_FORMAT_0RGB1555;
    double fps = 60.0, sampleRate = 48000.0;
    float aspect = 4.f / 3.f;

    // video: core thread writes `pending`, GL thread consumes
    std::mutex videoMutex;
    std::vector<uint32_t> pending;
    unsigned pw = 0, ph = 0;
    bool newFrame = false;

    std::atomic<uint32_t> input[2]{{0}, {0}};
    std::atomic<int16_t> analog[2][2][2]{};   // [port][stick: 0 left, 1 right][axis: 0 x, 1 y], -32767..32767
    std::atomic<bool> running{false}, paused{false};
    std::thread emuThread;

    AAudioStream *stream = nullptr;
    AudioRing ring;

    // QA statistics
    std::atomic<int64_t> frames{0}, videoFrames{0}, audioFrames{0}, underruns{0}, inputFrames{0};
    std::atomic<uint32_t> lastHash{0}, centerPixel{0};

    // Hardware-rendered cores (e.g. ARMSX2 / PS2): the core draws with OpenGL ES into our FBO on the GL thread.
    bool hw = false;                   // core asked for SET_HW_RENDER and we accepted
    retro_hw_render_callback hwcb{};
    bool hwReady = false;              // context_reset done for the current EGL context
    GLuint hwFbo = 0, hwTex = 0;
    int hwTexW = 0, hwTexH = 0;
    unsigned maxW = 0, maxH = 0;
    std::atomic<unsigned> hwW{0}, hwH{0};
    double hwAcc = 0;
    std::chrono::steady_clock::time_point hwLast{};
    std::mutex jobMutex;
    std::condition_variable jobCv;
    std::deque<std::function<void()>> jobs;   // work that must run on the GL thread (save/load/readback/shutdown)
    std::atomic<bool> glAlive{false};

    // GL
    GLuint prog = 0, tex = 0;
    GLint locPos = -1, locUv = -1;
    int viewW = 1, viewH = 1, texW = 0, texH = 0;
    int scaleMode = 2;   // 0 original aspect, 1 integer, 2 full screen (default), 3 forced 4:3
} g;

void coreLog(enum retro_log_level level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    __android_log_print(level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "nesGamesCore", "%s", buf);
}

uintptr_t hwGetFramebuffer() { return g.hwFbo; }

retro_proc_address_t hwGetProc(const char *sym) {
    auto p = (retro_proc_address_t) eglGetProcAddress(sym);
    if (!p) {   // core GLES entry points are not always returned by eglGetProcAddress
        static void *gles = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
        if (gles) p = (retro_proc_address_t) dlsym(gles, sym);
    }
    return p;
}

// Core option overrides this frontend needs. Anything else: the core's documented default.
const char *optionOverride(const char *key) {
    if (!key) return nullptr;
    if (!strcmp(key, "armsx2_renderer")) return "OpenGL";   // our HW path is OpenGL ES; Vulkan negotiation is not implemented
    return nullptr;
}

bool environment(unsigned cmd, void *data) {
    switch (cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char **) data = g.systemDir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **) data = g.saveDir.c_str(); return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto f = *(const retro_pixel_format *) data;
            if (f != RETRO_PIXEL_FORMAT_0RGB1555 && f != RETRO_PIXEL_FORMAT_RGB565 && f != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            g.pixFmt = f;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((retro_log_callback *) data)->log = coreLog; return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *) data = true; return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *) data = false; return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS & ~RETRO_ENVIRONMENT_EXPERIMENTAL: return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE: *(unsigned *) data = RETRO_LANGUAGE_ENGLISH; return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: if (data) *(int *) data = 3; return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            auto *geo = (const retro_game_geometry *) data;
            g.aspect = geo->aspect_ratio > 0 ? geo->aspect_ratio : (float) geo->base_width / (float) geo->base_height;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto *cb = (retro_hw_render_callback *) data;
            bool gles = cb->context_type == RETRO_HW_CONTEXT_OPENGLES3 || cb->context_type == RETRO_HW_CONTEXT_OPENGLES2 ||
                        (cb->context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION && cb->version_major == 3 && cb->version_minor <= 2);
            if (!gles) { LOGI("HW render refused: type=%d %u.%u", cb->context_type, cb->version_major, cb->version_minor); return false; }
            cb->get_current_framebuffer = hwGetFramebuffer;
            cb->get_proc_address = hwGetProc;
            g.hwcb = *cb;
            g.hw = true;
            LOGI("HW render accepted: GLES %u.%u depth=%d stencil=%d bottomLeft=%d", cb->version_major, cb->version_minor, cb->depth, cb->stencil, cb->bottom_left_origin);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: *(unsigned *) data = RETRO_HW_CONTEXT_OPENGLES_VERSION; return true;
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT & ~RETRO_ENVIRONMENT_EXPERIMENTAL: return true;   // id carries the EXPERIMENTAL bit
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto *v = (retro_variable *) data;
            const char *o = optionOverride(v->key);
            if (!o) return false;
            v->value = o;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto *av = (const retro_system_av_info *) data;
            if (av->timing.fps > 1) g.fps = av->timing.fps;
            if (av->geometry.aspect_ratio > 0) g.aspect = av->geometry.aspect_ratio;
            if (av->geometry.max_width > g.maxW) g.maxW = av->geometry.max_width;
            if (av->geometry.max_height > g.maxH) g.maxH = av->geometry.max_height;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
            return true;
        default:
            if ((cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL) != RETRO_ENVIRONMENT_GET_VARIABLE) LOGI("env cmd %u not handled", cmd);
            return false;   // GET_VARIABLE etc.: core falls back to its documented defaults
    }
}

inline uint32_t rgba(uint8_t r, uint8_t gg, uint8_t b) { return r | (gg << 8) | (b << 16) | 0xFF000000u; }

void videoRefresh(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {   // drawn into our FBO by the core
        g.hwW = w; g.hwH = h; g.videoFrames++;
        return;
    }
    if (!data) return;  // duped frame: keep previous image
    std::vector<uint32_t> out(w * h);
    const auto *src = (const uint8_t *) data;
    for (unsigned y = 0; y < h; y++) {
        const uint8_t *row = src + y * pitch;
        uint32_t *dst = &out[y * w];
        switch (g.pixFmt) {
            case RETRO_PIXEL_FORMAT_XRGB8888:
                for (unsigned x = 0; x < w; x++) {
                    uint32_t p = ((const uint32_t *) row)[x];
                    dst[x] = rgba(p >> 16, p >> 8, p);
                }
                break;
            case RETRO_PIXEL_FORMAT_RGB565:
                for (unsigned x = 0; x < w; x++) {
                    uint16_t p = ((const uint16_t *) row)[x];
                    dst[x] = rgba(((p >> 11) & 31) * 255 / 31, ((p >> 5) & 63) * 255 / 63, (p & 31) * 255 / 31);
                }
                break;
            default:
                for (unsigned x = 0; x < w; x++) {
                    uint16_t p = ((const uint16_t *) row)[x];
                    dst[x] = rgba(((p >> 10) & 31) * 255 / 31, ((p >> 5) & 31) * 255 / 31, (p & 31) * 255 / 31);
                }
        }
    }
    uint32_t hash = 2166136261u;
    for (uint32_t p: out) { hash ^= p; hash *= 16777619u; }
    g.lastHash = hash;
    g.centerPixel = out[(h / 2) * w + w / 2];
    g.videoFrames++;
    std::lock_guard<std::mutex> lk(g.videoMutex);
    g.pending.swap(out);
    g.pw = w;
    g.ph = h;
    g.newFrame = true;
}

size_t audioBatch(const int16_t *data, size_t frames) {
    g.audioFrames += (int64_t) frames;
    g.ring.write(data, frames);
    return frames;
}

void audioSample(int16_t l, int16_t r) {
    int16_t s[2] = {l, r};
    audioBatch(s, 1);
}

void inputPoll() {}

int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port > 1) return 0;
    if (device == RETRO_DEVICE_ANALOG) {
        if (index > 1 || id > 1) return 0;   // index 2 = analog buttons: not provided
        return g.analog[port][index][id].load(std::memory_order_relaxed);
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    uint32_t m = g.input[port].load(std::memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) m;
    return (int16_t) ((m >> id) & 1);
}

aaudio_data_callback_result_t audioCallback(AAudioStream *, void *, void *audioData, int32_t numFrames) {
    auto *out = (int16_t *) audioData;
    size_t got = g.ring.read(out, (size_t) numFrames);
    if (got < (size_t) numFrames) {
        memset(out + got * 2, 0, ((size_t) numFrames - got) * 4);
        if (!g.paused) g.underruns++;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void audioError(AAudioStream *, void *, aaudio_result_t err) { LOGE("AAudio error %d", err); }

void openAudio() {
    AAudioStreamBuilder *b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setSampleRate(b, (int32_t) std::lround(g.sampleRate));
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(b, audioCallback, nullptr);
    AAudioStreamBuilder_setErrorCallback(b, audioError, nullptr);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &g.stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGE("openStream failed %d", r); g.stream = nullptr; return; }
    AAudioStream_requestStart(g.stream);
    LOGI("audio open: %d Hz", AAudioStream_getSampleRate(g.stream));
}

void closeAudio() {
    if (!g.stream) return;
    AAudioStream_requestStop(g.stream);
    AAudioStream_close(g.stream);
    g.stream = nullptr;
}

// Runs the core at its own reported frame rate. Small (+/-0.5%) nudges keep the audio
// buffer near its target so audio does not drift; game logic is never accelerated.
void emuLoop() {
    using clock = std::chrono::steady_clock;
    const auto frameDur = std::chrono::duration<double>(1.0 / g.fps);
    auto next = clock::now();
    const size_t target = (size_t) (g.sampleRate * 0.06);   // ~60 ms of buffered audio
    while (g.running) {
        if (g.paused) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            next = clock::now();
            continue;
        }
        {
            std::lock_guard<std::mutex> lk(g.coreMutex);
            if (g.paused) continue;   // re-check under the lock: no frame may run once a pause is in effect
            g.core.run();
            if (g.input[0].load(std::memory_order_relaxed)) g.inputFrames++;
        }
        g.frames++;
        double adj = 1.0;
        size_t fill = g.ring.avail();
        if (fill > target + target / 2) adj = 1.005;
        else if (fill < target / 2) adj = 0.995;
        next += std::chrono::duration_cast<clock::duration>(frameDur * adj);
        auto now = clock::now();
        if (now > next + std::chrono::duration_cast<clock::duration>(frameDur * 4)) next = now;  // stalled: resync, no catch-up burst
        std::this_thread::sleep_until(next);
    }
}

template<typename T>
bool sym(T &fn, const char *name) {
    fn = reinterpret_cast<T>(dlsym(g.core.handle, name));
    if (!fn) LOGE("missing symbol %s", name);
    return fn != nullptr;
}

std::string loadCoreLib(const std::string &path) {
    if (g.core.handle && g.core.path == path) return {};
    if (g.core.handle) {  // previous game already unloaded + deinit'ed by stopAndUnload()
        dlclose(g.core.handle);
        g.core = CoreApi{};
    }
    g.core.handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!g.core.handle) return std::string("dlopen failed: ") + dlerror();
    auto &c = g.core;
    bool ok = sym(c.init, "retro_init") && sym(c.deinit, "retro_deinit") && sym(c.api_version, "retro_api_version") &&
              sym(c.get_system_info, "retro_get_system_info") && sym(c.get_system_av_info, "retro_get_system_av_info") &&
              sym(c.set_environment, "retro_set_environment") && sym(c.set_video_refresh, "retro_set_video_refresh") &&
              sym(c.set_audio_sample, "retro_set_audio_sample") && sym(c.set_audio_sample_batch, "retro_set_audio_sample_batch") &&
              sym(c.set_input_poll, "retro_set_input_poll") && sym(c.set_input_state, "retro_set_input_state") &&
              sym(c.set_controller_port_device, "retro_set_controller_port_device") && sym(c.reset, "retro_reset") &&
              sym(c.run, "retro_run") && sym(c.serialize_size, "retro_serialize_size") && sym(c.serialize, "retro_serialize") &&
              sym(c.unserialize, "retro_unserialize") && sym(c.load_game, "retro_load_game") && sym(c.unload_game, "retro_unload_game") &&
              sym(c.get_memory_data, "retro_get_memory_data") && sym(c.get_memory_size, "retro_get_memory_size");
    if (!ok) { dlclose(c.handle); c.handle = nullptr; return "library is not a valid libretro core"; }
    if (c.api_version() != RETRO_API_VERSION) { dlclose(c.handle); c.handle = nullptr; return "libretro API version mismatch"; }
    c.path = path;
    return {};
}

bool writeFile(const std::string &path, const void *data, size_t size) {
    std::string tmp = path + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return false;
    bool ok = fwrite(data, 1, size, f) == size;
    ok = (fflush(f) == 0) && ok;
    ok = (fsync(fileno(f)) == 0) && ok;   // on disk before the rename makes it visible
    fclose(f);
    if (!ok) { remove(tmp.c_str()); return false; }
    return ok && rename(tmp.c_str(), path.c_str()) == 0;   // atomic replace
}

bool readFile(const std::string &path, std::vector<uint8_t> &out, size_t maxSize) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz < 0 || (size_t) sz > maxSize) { fclose(f); return false; }
    out.resize((size_t) sz);
    bool ok = fread(out.data(), 1, out.size(), f) == out.size();
    fclose(f);
    return ok;
}

/** Runs fn on the GL thread (HW cores) and waits for it; returns false on timeout or if the GL thread is gone. */
bool runOnGl(std::function<void()> fn, int timeoutMs = 4000) {
    if (!g.glAlive) return false;
    auto done = std::make_shared<bool>(false);
    {
        std::lock_guard<std::mutex> lk(g.jobMutex);
        g.jobs.emplace_back([fn, done] { fn(); std::lock_guard<std::mutex> l2(g.jobMutex); *done = true; g.jobCv.notify_all(); });
    }
    std::unique_lock<std::mutex> lk(g.jobMutex);
    return g.jobCv.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return *done; });
}

void drainJobs() {
    for (;;) {
        std::function<void()> j;
        {
            std::lock_guard<std::mutex> lk(g.jobMutex);
            if (g.jobs.empty()) return;
            j = std::move(g.jobs.front());
            g.jobs.pop_front();
        }
        j();
    }
}

void hwDestroyLocked() {   // GL thread, coreMutex held
    if (g.hwReady && g.hwcb.context_destroy) g.hwcb.context_destroy();
    if (g.hwFbo) glDeleteFramebuffers(1, &g.hwFbo);
    if (g.hwTex) glDeleteTextures(1, &g.hwTex);
    g.hwFbo = g.hwTex = 0; g.hwTexW = g.hwTexH = 0;
    g.hwReady = false;
}

bool saveSramLocked() {
    if (!g.loaded || g.sramPath.empty()) return false;
    size_t sz = g.core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *p = g.core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!sz || !p) return false;
    return writeFile(g.sramPath, p, sz);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

void stopAndUnload() {
    if (g.hw && g.hwReady) runOnGl([] { std::lock_guard<std::mutex> lk(g.coreMutex); hwDestroyLocked(); }, 3000);
    g.running = false;
    if (g.emuThread.joinable()) g.emuThread.join();
    closeAudio();
    std::lock_guard<std::mutex> lk(g.coreMutex);
    if (g.loaded) {
        saveSramLocked();
        g.core.unload_game();
        g.core.deinit();
        g.loaded = false;
    }
    g.hw = false; g.hwReady = false; g.hwW = 0; g.hwH = 0; g.maxW = g.maxH = 0;
    if (!g.tempRomPath.empty()) { remove(g.tempRomPath.c_str()); g.tempRomPath.clear(); }
    g.romData.clear();
    g.romData.shrink_to_fit();
}

const char *VS = "attribute vec2 p; attribute vec2 t; varying vec2 v; void main(){ gl_Position = vec4(p,0.0,1.0); v = t; }";
const char *FS = "precision mediump float; varying vec2 v; uniform sampler2D s; void main(){ gl_FragColor = texture2D(s, v); }";

GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    return s;
}

}  // namespace


extern "C" JNIEXPORT jstring JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeLoad(JNIEnv *env, jobject, jstring corePath, jstring romName, jbyteArray rom,
                        jstring systemDir, jstring saveDir, jstring sramPath, jstring tempDir) {
    stopAndUnload();
    std::string err = loadCoreLib(jstr(env, corePath));
    if (!err.empty()) return env->NewStringUTF(err.c_str());
    g.systemDir = jstr(env, systemDir);
    g.saveDir = jstr(env, saveDir);
    g.sramPath = jstr(env, sramPath);
    jsize n = env->GetArrayLength(rom);
    g.romData.resize((size_t) n);
    env->GetByteArrayRegion(rom, 0, n, (jbyte *) g.romData.data());
    g.frames = g.videoFrames = g.audioFrames = g.underruns = g.inputFrames = 0;

    std::lock_guard<std::mutex> lk(g.coreMutex);
    auto &c = g.core;
    g.hw = false; g.hwReady = false; g.hwW = 0; g.hwH = 0; g.maxW = g.maxH = 0; g.hwAcc = 0; g.hwLast = {};
    c.set_environment(environment);
    c.set_video_refresh(videoRefresh);
    c.set_audio_sample(audioSample);
    c.set_audio_sample_batch(audioBatch);
    c.set_input_poll(inputPoll);
    c.set_input_state(inputState);
    c.init();
    retro_system_info si{};
    c.get_system_info(&si);
    std::string name = jstr(env, romName);
    retro_game_info gi{};
    if (g.romData.empty()) {  // file-backed content (e.g. CUE+BIN discs): pass the path through
        gi.path = name.c_str();
        if (!si.need_fullpath && !readFile(name, g.romData, 64u << 20)) { c.deinit(); return env->NewStringUTF("cannot read content file"); }
        gi.data = g.romData.empty() ? nullptr : g.romData.data();
        gi.size = g.romData.size();
    } else if (si.need_fullpath) {   // core wants a real file: write to app-private temp dir
        g.tempRomPath = jstr(env, tempDir) + "/" + name;
        if (!writeFile(g.tempRomPath, g.romData.data(), g.romData.size())) { c.deinit(); return env->NewStringUTF("cannot stage ROM"); }
        gi.path = g.tempRomPath.c_str();
    } else {
        gi.path = name.c_str();
        gi.data = g.romData.data();
        gi.size = g.romData.size();
    }
    LOGI("load_game path=%s need_fullpath=%d size=%zu lib=%s", gi.path ? gi.path : "(null)", si.need_fullpath, gi.size, si.library_name);
    if (!c.load_game(&gi)) { c.deinit(); return env->NewStringUTF("The core rejected this ROM (unsupported or corrupt)"); }
    g.loaded = true;
    retro_system_av_info av{};
    c.get_system_av_info(&av);
    g.fps = av.timing.fps > 1 ? av.timing.fps : 60.0;
    g.sampleRate = av.timing.sample_rate > 1000 ? av.timing.sample_rate : 48000.0;
    g.aspect = av.geometry.aspect_ratio > 0 ? av.geometry.aspect_ratio
                                            : (float) av.geometry.base_width / (float) av.geometry.base_height;
    c.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    c.set_controller_port_device(1, RETRO_DEVICE_JOYPAD);
    std::vector<uint8_t> sram;
    size_t sz = c.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *p = c.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (sz && p && readFile(g.sramPath, sram, sz) && sram.size() == sz) { memcpy(p, sram.data(), sz); LOGI("SRAM restored (%zu bytes)", sz); }
    LOGI("loaded %s with %s %s fps=%.3f rate=%.1f", name.c_str(), si.library_name, si.library_version, g.fps, g.sampleRate);
    g.ring.init((size_t) (g.sampleRate * 0.25));
    openAudio();
    g.paused = false;
    g.running = true;
    if (av.geometry.max_width > g.maxW) g.maxW = av.geometry.max_width;
    if (av.geometry.max_height > g.maxH) g.maxH = av.geometry.max_height;
    if (g.hw) LOGI("HW core: frames are driven by the GL thread (max %ux%u)", g.maxW, g.maxH);
    else g.emuThread = std::thread(emuLoop);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeUnload(JNIEnv *, jobject) { stopAndUnload(); }

extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSetPaused(JNIEnv *, jobject, jboolean p) {
    g.paused = p;
    if (p) { std::lock_guard<std::mutex> lk(g.coreMutex); }   // wait for an in-flight frame: state is frozen when this returns
    if (g.stream) { if (p) AAudioStream_requestPause(g.stream); else AAudioStream_requestStart(g.stream); }
}

extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSetAnalog(JNIEnv *, jobject, jint port, jint stick, jint x, jint y) {
    if (port < 0 || port > 1 || stick < 0 || stick > 1) return;
    auto clamp = [](jint v) { return (int16_t) std::max(-32767, std::min(32767, (int) v)); };
    g.analog[port][stick][0] = clamp(x);
    g.analog[port][stick][1] = clamp(y);
}

extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSetInput(JNIEnv *, jobject, jint port, jint mask) { if (port >= 0 && port < 2) g.input[port] = (uint32_t) mask; }

static void resetImpl() { std::lock_guard<std::mutex> lk(g.coreMutex); if (g.loaded) g.core.reset(); }

extern "C" JNIEXPORT jboolean JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSaveSram(JNIEnv *, jobject) { std::lock_guard<std::mutex> lk(g.coreMutex); return saveSramLocked(); }

static bool saveStateImpl(const std::string &path) {
    std::lock_guard<std::mutex> lk(g.coreMutex);
    if (!g.loaded) return false;
    size_t sz = g.core.serialize_size();
    if (!sz) return false;
    std::vector<uint8_t> buf(sz);
    if (!g.core.serialize(buf.data(), sz)) return false;
    return writeFile(path, buf.data(), sz);
}

static bool loadStateImpl(const std::string &path) {
    std::lock_guard<std::mutex> lk(g.coreMutex);
    if (!g.loaded) return false;
    std::vector<uint8_t> buf;
    if (!readFile(path, buf, 256u << 20)) return false;
    size_t expect = g.core.serialize_size();
    if (buf.empty() || buf.size() != expect) {   // truncated / foreign file: never hand it to the core
        LOGE("state rejected: %zu bytes, core expects %zu", buf.size(), expect);
        return false;
    }
    std::vector<uint8_t> backup(expect);
    bool haveBackup = g.core.serialize(backup.data(), expect);
    if (g.core.unserialize(buf.data(), buf.size())) return true;
    LOGE("unserialize failed; rolling back");
    if (haveBackup) g.core.unserialize(backup.data(), expect);
    return false;
}

// FNV-1a of the emulated system RAM: QA evidence that a load restored the real game position.
static long long ramHashImpl() {
    std::lock_guard<std::mutex> lk(g.coreMutex);
    if (!g.loaded) return -1;
    size_t sz = g.core.get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
    auto *p = (const uint8_t *) g.core.get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
    if (!sz || !p) return -1;
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < sz; i++) { h ^= p[i]; h *= 16777619u; }
    return (long long) h;
}


template<typename F>
static auto onCoreThread(F f) -> decltype(f()) {
    if (!g.hw) return f();
    decltype(f()) r{};
    if (!runOnGl([&] { r = f(); })) LOGE("GL thread did not run the request in time");
    return r;
}
extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeReset(JNIEnv *, jobject) { onCoreThread([] { resetImpl(); return 0; }); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSaveState(JNIEnv *env, jobject, jstring path) {
    std::string p = jstr(env, path); return onCoreThread([&] { return saveStateImpl(p); });
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeLoadState(JNIEnv *env, jobject, jstring path) {
    std::string p = jstr(env, path); return onCoreThread([&] { return loadStateImpl(p); });
}
extern "C" JNIEXPORT jlong JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeRamHash(JNIEnv *, jobject) { return (jlong) onCoreThread([] { return ramHashImpl(); }); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeIsHw(JNIEnv *, jobject) { return g.hw; }
extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSetScaleMode(JNIEnv *, jobject, jint mode) { g.scaleMode = mode; }

extern "C" JNIEXPORT jlongArray JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeGetStats(JNIEnv *env, jobject) {
    jlong v[9] = {g.frames, g.videoFrames, g.audioFrames, g.underruns, (jlong) g.lastHash.load(),
                  (jlong) g.centerPixel.load(), (jlong) g.pw, (jlong) g.ph, g.inputFrames};
    jlongArray a = env->NewLongArray(9);
    env->SetLongArrayRegion(a, 0, 9, v);
    return a;
}

extern "C" JNIEXPORT jdouble JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeGetFps(JNIEnv *, jobject) { return g.fps; }

extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSurfaceCreated(JNIEnv *, jobject) {
    GLuint vs = compile(GL_VERTEX_SHADER, VS), fs = compile(GL_FRAGMENT_SHADER, FS);
    g.prog = glCreateProgram();
    glAttachShader(g.prog, vs);
    glAttachShader(g.prog, fs);
    glLinkProgram(g.prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    g.locPos = glGetAttribLocation(g.prog, "p");
    g.locUv = glGetAttribLocation(g.prog, "t");
    glGenTextures(1, &g.tex);
    glBindTexture(GL_TEXTURE_2D, g.tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);   // crisp pixels
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g.texW = g.texH = 0;
    // A new EGL context: any previous HW objects are gone with the old one; context_reset runs again on the next frame.
    g.hwReady = false; g.hwFbo = g.hwTex = 0; g.hwTexW = g.hwTexH = 0;
    g.glAlive = true;
    std::lock_guard<std::mutex> lk(g.videoMutex);
    g.newFrame = !g.pending.empty();   // re-upload after context loss
}

extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeSurfaceChanged(JNIEnv *, jobject, jint w, jint h) { g.viewW = w; g.viewH = h; }

// Viewport for a w x h image by the current scale mode, anchored to the top in portrait.
static void placeImage(int texW, int texH, int &x, int &y, int &w, int &h) {
    float a = g.aspect;
    w = g.viewW; h = (int) (g.viewW / a);
    if (h > g.viewH) { h = g.viewH; w = (int) (g.viewH * a); }
    if (g.scaleMode == 3) {   // classic 4:3 TV shape
        a = 4.f / 3.f;
        w = g.viewW; h = (int) (g.viewW / a);
        if (h > g.viewH) { h = g.viewH; w = (int) (g.viewH * a); }
    }
    if (g.scaleMode == 2) { w = g.viewW; h = g.viewH; }
    else if (g.scaleMode == 1 && texW > 0 && texH > 0) {
        int s = std::max(1, std::min(g.viewW / texW, g.viewH / texH));
        h = texH * s;
        w = (int) (h * a);
    }
    x = (g.viewW - w) / 2;
    y = g.viewH > g.viewW ? g.viewH - h : (g.viewH - h) / 2;
}

static void drawQuad(const float *quad) {
    glUseProgram(g.prog);
    glVertexAttribPointer(g.locPos, 2, GL_FLOAT, GL_FALSE, 16, quad);
    glVertexAttribPointer(g.locUv, 2, GL_FLOAT, GL_FALSE, 16, quad + 2);
    glEnableVertexAttribArray(g.locPos);
    glEnableVertexAttribArray(g.locUv);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

// HW cores: create our FBO, hand the context to the core, run frames at the core's rate, present the FBO.
static void hwFrame() {
    if (!g.hwReady) {
        unsigned w = std::max(g.maxW, 640u), h = std::max(g.maxH, 448u);
        glGenTextures(1, &g.hwTex);
        glBindTexture(GL_TEXTURE_2D, g.hwTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, (GLsizei) w, (GLsizei) h);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &g.hwFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, g.hwFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g.hwTex, 0);
        if (g.hwcb.depth) {
            GLuint rb; glGenRenderbuffers(1, &rb); glBindRenderbuffer(GL_RENDERBUFFER, rb);
            glRenderbufferStorage(GL_RENDERBUFFER, g.hwcb.stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, (GLsizei) w, (GLsizei) h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, g.hwcb.stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb);
        }
        GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        g.hwTexW = (int) w; g.hwTexH = (int) h;
        LOGI("HW FBO %ux%u status=0x%x GL_VERSION=%s RENDERER=%s", w, h, st, glGetString(GL_VERSION), glGetString(GL_RENDERER));
        {
            std::lock_guard<std::mutex> lk(g.coreMutex);
            if (g.hwcb.context_reset) g.hwcb.context_reset();
        }
        g.hwReady = true;
        g.hwLast = std::chrono::steady_clock::now(); g.hwAcc = 0;
    }

    // Pacing: run the core at its own rate (display may be 120 Hz), at most 2 frames per vsync, never a catch-up burst.
    auto now = std::chrono::steady_clock::now();
    double dt = std::chrono::duration<double>(now - g.hwLast).count();
    g.hwLast = now;
    if (g.running && !g.paused) {
        const double frame = 1.0 / g.fps;
        g.hwAcc = std::min(g.hwAcc + dt, frame * 3);
        size_t fill = g.ring.avail(), target = (size_t) (g.sampleRate * 0.06);
        double adj = fill > target + target / 2 ? 1.005 : (fill < target / 2 ? 0.995 : 1.0);
        for (int n = 0; n < 2 && g.hwAcc >= frame * adj; n++) {
            std::lock_guard<std::mutex> lk(g.coreMutex);
            if (g.paused || !g.loaded) break;
            g.core.run();
            g.frames++;
            if (g.input[0].load(std::memory_order_relaxed)) g.inputFrames++;
            g.hwAcc -= frame * adj;
        }
    } else g.hwAcc = 0;

    // The core shares this context: put back the state our presentation needs.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindSampler(0, 0);
    glActiveTexture(GL_TEXTURE0);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, g.viewW, g.viewH);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    unsigned w = g.hwW, h = g.hwH;
    if (!w || !h || !g.hwTex) return;
    int x, y, vw, vh;
    placeImage((int) w, (int) h, x, y, vw, vh);
    glViewport(x, y, vw, vh);
    glBindTexture(GL_TEXTURE_2D, g.hwTex);
    float u = (float) w / (float) g.hwTexW, v = (float) h / (float) g.hwTexH;
    // bottom-left origin: texture row 0 is the bottom of the picture
    const float quad[] = {-1, -1, 0, 0,  1, -1, u, 0,  -1, 1, 0, v,  1, 1, u, v};
    drawQuad(quad);
    glViewport(0, 0, g.viewW, g.viewH);
}

extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeDrawFrame(JNIEnv *, jobject) {
    g.glAlive = true;
    drainJobs();   // save/load/readback/shutdown requests from other threads
    if (g.hw && g.loaded) { hwFrame(); return; }
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindTexture(GL_TEXTURE_2D, g.tex);
    {
        std::lock_guard<std::mutex> lk(g.videoMutex);
        if (g.newFrame && !g.pending.empty()) {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            if ((int) g.pw != g.texW || (int) g.ph != g.texH) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g.pw, g.ph, 0, GL_RGBA, GL_UNSIGNED_BYTE, g.pending.data());
                g.texW = g.pw;
                g.texH = g.ph;
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, g.pw, g.ph, GL_RGBA, GL_UNSIGNED_BYTE, g.pending.data());
            }
            g.newFrame = false;
        }
    }
    if (!g.texW) return;
    int x, y, w, h;
    placeImage(g.texW, g.texH, x, y, w, h);
    glViewport(x, y, w, h);
    static const float quad[] = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};
    drawQuad(quad);
    glViewport(0, 0, g.viewW, g.viewH);
}

/** GL thread is about to stop (activity paused/finishing): later runOnGl calls must not wait for it. */
extern "C" JNIEXPORT void JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeGlPaused(JNIEnv *, jobject) {
    g.glAlive = false;
    std::lock_guard<std::mutex> lk(g.jobMutex);
    g.jobCv.notify_all();
}

static inline jint toArgb(uint32_t p) { return (jint) ((p & 0xFF00FF00u) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF)); }

// Returns [w, h, pixels...] of the latest frame as 0xAARRGGBB (Android Bitmap order), or null.
extern "C" JNIEXPORT jintArray JNICALL Java_com_nesgames_app_emu_NativeBridge_nativeCopyFrame(JNIEnv *env, jobject) {
    std::vector<jint> out;
    if (g.hw) {
        // read the core's last frame back from our FBO, on the GL thread (bottom-left origin -> flip rows)
        runOnGl([&] {
            unsigned w = g.hwW, h = g.hwH;
            if (!g.hwFbo || !w || !h) return;
            std::vector<uint32_t> px((size_t) w * h);
            glBindFramebuffer(GL_FRAMEBUFFER, g.hwFbo);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glReadPixels(0, 0, (GLsizei) w, (GLsizei) h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            out.resize((size_t) w * h + 2);
            out[0] = (jint) w; out[1] = (jint) h;
            for (unsigned y = 0; y < h; y++)
                for (unsigned x = 0; x < w; x++) out[2 + (size_t) y * w + x] = toArgb(px[(size_t) (h - 1 - y) * w + x] | 0xFF000000u);
        }, 2000);
    } else {
        std::lock_guard<std::mutex> lk(g.videoMutex);
        if (g.pending.empty() || !g.pw || !g.ph) return nullptr;
        size_t n = (size_t) g.pw * g.ph;
        out.resize(n + 2);
        out[0] = (jint) g.pw; out[1] = (jint) g.ph;
        for (size_t i = 0; i < n; i++) out[i + 2] = toArgb(g.pending[i]);
    }
    if (out.empty()) return nullptr;
    jintArray arr = env->NewIntArray((jsize) out.size());
    env->SetIntArrayRegion(arr, 0, (jsize) out.size(), out.data());
    return arr;
}

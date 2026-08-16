#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>

#include <memory>
#include <string>
#include <cstring>
#include <mutex>
#include <vector>
#include <algorithm>
#include <cstdio>
#include <chrono>
#include <cctype>

// Native core headers
#include "Core/Shared/Emulator.h"
#include "Core/Shared/CheatManager.h"
#include "Core/Shared/EmuSettings.h"
#include "Core/Shared/KeyManager.h"
#include "Core/Shared/MessageManager.h"
#include "Core/Shared/NotificationManager.h"
#include "Core/Shared/SaveStateManager.h"
#include "Core/Shared/Interfaces/IMessageManager.h"
#include "Core/Shared/Audio/SoundMixer.h"
#include "Core/Shared/Video/VideoDecoder.h"
#include "Core/Shared/Video/VideoRenderer.h"
#include "Core/NES/NesConsole.h"
#include "Utilities/FolderUtilities.h"
#include "Utilities/VirtualFile.h"

// Android-specific implementations
#include "android_renderer.h"
#include "android_audio.h"
#include "android_key_manager.h"

#define TAG "MesenJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Global emulator state ─────────────────────────────────────────────────────
static std::unique_ptr<Emulator>           g_emu;
static std::unique_ptr<AndroidRenderer>    g_renderer;
static std::unique_ptr<AndroidAudioDevice> g_audio;
static std::unique_ptr<AndroidKeyManager>  g_keyManager;
static std::chrono::steady_clock::time_point g_lastBatteryFlush = std::chrono::steady_clock::now();

static void SendEmulatorShortcut(EmulatorShortcut shortcut, uint32_t param = 0)
{
    if (!g_emu) return;

    ExecuteShortcutParams params {};
    params.Shortcut = shortcut;
    params.Param = param;
    params.ParamPtr = nullptr;
    g_emu->GetNotificationManager()->SendNotification(ConsoleNotificationType::ExecuteShortcut, &params);
}

class AndroidMessageManager final : public IMessageManager
{
public:
    void DisplayMessage(string title, string message) override
    {
        MessageManager::Log("[" + title + "] " + message);
        LOGI("%s: %s", title.c_str(), message.c_str());
    }
};

static std::unique_ptr<AndroidMessageManager> g_messageManager;

static constexpr uint32_t kDefaultNesPalette[64] = {
    0xFF666666, 0xFF002A88, 0xFF1412A7, 0xFF3B00A4,
    0xFF5C007E, 0xFF6E0040, 0xFF6C0600, 0xFF561D00,
    0xFF333500, 0xFF0B4800, 0xFF005200, 0xFF004F08,
    0xFF00404D, 0xFF000000, 0xFF000000, 0xFF000000,
    0xFFADADAD, 0xFF155FD9, 0xFF4240FF, 0xFF7527FE,
    0xFFA01ACC, 0xFFB71E7B, 0xFFB53120, 0xFF994E00,
    0xFF6B6D00, 0xFF388700, 0xFF0C9300, 0xFF008F32,
    0xFF007C8D, 0xFF000000, 0xFF000000, 0xFF000000,
    0xFFFFFEFF, 0xFF64B0FF, 0xFF9290FF, 0xFFC676FF,
    0xFFF36AFF, 0xFFFE6ECC, 0xFFFE8170, 0xFFEA9E22,
    0xFFBCBE00, 0xFF88D800, 0xFF5CE430, 0xFF45E082,
    0xFF48CDDE, 0xFF4F4F4F, 0xFF000000, 0xFF000000,
    0xFFFFFEFF, 0xFFC0DFFF, 0xFFD3D2FF, 0xFFE8C8FF,
    0xFFFBC2FF, 0xFFFEC4EA, 0xFFFECCC5, 0xFFF7D8A5,
    0xFFE4E594, 0xFFCFEF96, 0xFFBDF4AB, 0xFFB3F3CC,
    0xFFB5EBF2, 0xFFB8B8B8, 0xFF000000, 0xFF000000
};

static bool IsSupportedNesRom(VirtualFile& romFile)
{
    string ext = romFile.GetFileExtension();
    vector<string> extensions = NesConsole::GetSupportedExtensions();
    return std::find(extensions.begin(), extensions.end(), ext) != extensions.end();
}

static const char* GetVideoFilterName(VideoFilterType filter)
{
    switch(filter) {
        case VideoFilterType::None: return "Native";
        case VideoFilterType::NtscBlargg: return "NTSC";
        case VideoFilterType::NtscBisqwit: return "NTSC Bisqwit";
        case VideoFilterType::LcdGrid: return "LCD grid";
        case VideoFilterType::xBRZ2x: return "xBRZ 2x";
        case VideoFilterType::xBRZ3x: return "xBRZ 3x";
        case VideoFilterType::xBRZ4x: return "xBRZ 4x";
        case VideoFilterType::xBRZ5x: return "xBRZ 5x";
        case VideoFilterType::xBRZ6x: return "xBRZ 6x";
        case VideoFilterType::HQ2x: return "HQ2x";
        case VideoFilterType::HQ3x: return "HQ3x";
        case VideoFilterType::HQ4x: return "HQ4x";
        case VideoFilterType::Scale2x: return "Scale2x";
        case VideoFilterType::Scale3x: return "Scale3x";
        case VideoFilterType::Scale4x: return "Scale4x";
        case VideoFilterType::_2xSai: return "2xSai";
        case VideoFilterType::Super2xSai: return "Super 2xSai";
        case VideoFilterType::SuperEagle: return "SuperEagle";
        case VideoFilterType::Prescale2x: return "Prescale 2x";
        case VideoFilterType::Prescale3x: return "Prescale 3x";
        case VideoFilterType::Prescale4x: return "Prescale 4x";
        case VideoFilterType::Prescale6x: return "Prescale 6x";
        case VideoFilterType::Prescale8x: return "Prescale 8x";
        case VideoFilterType::Prescale10x: return "Prescale 10x";
    }
    return "Native";
}

static bool UsesLinearFinalSampling(VideoFilterType filter)
{
    switch(filter) {
        case VideoFilterType::NtscBlargg:
        case VideoFilterType::NtscBisqwit:
        case VideoFilterType::xBRZ2x:
        case VideoFilterType::xBRZ3x:
        case VideoFilterType::xBRZ4x:
        case VideoFilterType::xBRZ5x:
        case VideoFilterType::xBRZ6x:
        case VideoFilterType::HQ2x:
        case VideoFilterType::HQ3x:
        case VideoFilterType::HQ4x:
        case VideoFilterType::_2xSai:
        case VideoFilterType::Super2xSai:
        case VideoFilterType::SuperEagle:
            return true;
        default:
            return false;
    }
}

static bool IsValidSaveStateSlot(int slot)
{
    return slot >= 1 && slot <= 10;
}

static bool IsValidAspectRatio(int aspectRatio)
{
    switch ((VideoAspectRatio)aspectRatio) {
        case VideoAspectRatio::NoStretching:
        case VideoAspectRatio::Standard:
        case VideoAspectRatio::Widescreen:
            return true;
        default:
            return false;
    }
}

static const char* GetAspectRatioName(VideoAspectRatio aspectRatio)
{
    switch (aspectRatio) {
        case VideoAspectRatio::NoStretching: return "Native";
        case VideoAspectRatio::Standard: return "4:3";
        case VideoAspectRatio::Widescreen: return "16:9 Widescreen";
        default: return "Native";
    }
}

static std::string GetSaveStatePath(int slot)
{
    if (!g_emu || !IsValidSaveStateSlot(slot)) {
        return "";
    }

    std::string romFile = g_emu->GetRomInfo().RomFile.GetFileName();
    std::string filename = FolderUtilities::GetFilename(romFile, false) + "_" +
        std::to_string(slot) + ".mss";
    return FolderUtilities::CombinePath(FolderUtilities::GetSaveStateFolder(), filename);
}

static void EnsureRuntimeFolders()
{
    FolderUtilities::GetSaveFolder();
    FolderUtilities::GetSaveStateFolder();
    FolderUtilities::GetHdPackFolder();
    FolderUtilities::GetFirmwareFolder();
    FolderUtilities::GetScreenshotFolder();
    FolderUtilities::GetRecentGamesFolder();
}

static void FlushBatterySave(const char* reason)
{
    if (!g_emu || !g_emu->IsRunning()) return;

    auto emuLock = g_emu->AcquireLock(false);
    std::shared_ptr<IConsole> console = g_emu->GetConsole();
    if (console) {
        console->SaveBattery();
        LOGI("Battery save flushed (%s)", reason);
        MessageManager::Log(std::string("[Android] Battery save flushed (") + reason + ")");
    }
}

static void FlushBatterySaveIfDue()
{
    if (!g_emu || !g_emu->IsRunning()) return;

    auto now = std::chrono::steady_clock::now();
    if (now - g_lastBatteryFlush >= std::chrono::seconds(5)) {
        g_lastBatteryFlush = now;
        FlushBatterySave("auto");
    }
}

// ── OpenGL state (created on the GL thread) ───────────────────────────────────
static GLuint g_program      = 0;
static GLuint g_vao          = 0;
static GLuint g_vbo          = 0;
static GLuint g_texture      = 0;
static int    g_uTexLoc      = -1;
static uint32_t g_texWidth   = 0;
static uint32_t g_texHeight  = 0;
static bool   g_useLinearSampling = false;
static bool   g_samplerDirty = true;

static std::vector<uint32_t> g_pixelScratch; // scratch buffer for GetFrameIfReady

// ── Shader sources ────────────────────────────────────────────────────────────
static const char* kVertSrc = R"(#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUV;
out vec2 vUV;
void main() {
    vUV = aUV;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

static const char* kFragSrc = R"(#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    // Native core outputs ARGB u32 (0xAARRGGBB). On little-endian memory: bytes [BB][GG][RR][AA].
    // GL_RGBA/GL_UNSIGNED_BYTE reads those bytes as (r=BB, g=GG, b=RR, a=AA).
    // Correct output RGB = (RR, GG, BB) = (c.b, c.g, c.r).
    vec4 c = texture(uTex, vUV);
    fragColor = vec4(c.b, c.g, c.r, 1.0);
}
)";

// ── GL helpers ────────────────────────────────────────────────────────────────
static GLuint CompileShader(GLenum type, const char* src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetShaderInfoLog(s, sizeof(buf), nullptr, buf);
        LOGE("Shader compile error: %s", buf);
    }
    return s;
}

static GLuint CreateProgram()
{
    GLuint vs = CompileShader(GL_VERTEX_SHADER,   kVertSrc);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, kFragSrc);
    GLuint p  = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetProgramInfoLog(p, sizeof(buf), nullptr, buf);
        LOGE("Program link error: %s", buf);
    }
    return p;
}

// ── JNI functions ─────────────────────────────────────────────────────────────
extern "C" {

// Called once on startup from EmulatorActivity.onCreate()
JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_initialize(JNIEnv* env, jobject /*thiz*/,
                                              jstring homeFolder)
{
    if (g_emu) return; // already initialized

    const char* home = env->GetStringUTFChars(homeFolder, nullptr);
    FolderUtilities::SetHomeFolder(home);
    env->ReleaseStringUTFChars(homeFolder, home);
    EnsureRuntimeFolders();

    g_messageManager = std::make_unique<AndroidMessageManager>();
    MessageManager::RegisterMessageManager(g_messageManager.get());

    g_emu = std::make_unique<Emulator>();
    g_emu->Initialize(false);

    // NES controller setup: port 1, key codes matching NesKeyCode namespace
    NesConfig& nes = g_emu->GetSettings()->GetNesConfig();
    nes.Port1.Type = ControllerType::NesController;
    auto& m = nes.Port1.Keys.Mapping1;
    m.A      = NesKeyCode::A;
    m.B      = NesKeyCode::B;
    m.Select = NesKeyCode::Select;
    m.Start  = NesKeyCode::Start;
    m.Up     = NesKeyCode::Up;
    m.Down   = NesKeyCode::Down;
    m.Left   = NesKeyCode::Left;
    m.Right  = NesKeyCode::Right;
    m.TurboA = NesKeyCode::TurboA;
    m.TurboB = NesKeyCode::TurboB;
    nes.Port1.Keys.TurboSpeed = 2;

    // HD packs enabled by default
    nes.EnableHdPacks = true;

    // Anti-flicker: lift the 8-sprite-per-scanline limit, but let the PPU put it
    // back on the scanlines where removing it would break the game's own effects.
    nes.RemoveSpriteLimit = true;
    nes.AdaptiveSpriteLimit = true;
    nes.RamPowerOnState = RamState::AllZeros;
    nes.IsFullColorPalette = false;
    std::fill(std::begin(nes.UserPalette), std::end(nes.UserPalette), 0);
    std::copy(std::begin(kDefaultNesPalette), std::end(kDefaultNesPalette), std::begin(nes.UserPalette));
    std::fill(std::begin(nes.ChannelVolumes), std::end(nes.ChannelVolumes), 100);

    // Start at native resolution; Android settings can enable upscalers.
    VideoConfig& vid = g_emu->GetSettings()->GetVideoConfig();
    vid.VideoFilter = VideoFilterType::None;
    vid.AspectRatio = VideoAspectRatio::NoStretching;
    g_useLinearSampling = UsesLinearFinalSampling(vid.VideoFilter);
    g_samplerDirty = true;

    g_audio      = std::make_unique<AndroidAudioDevice>();
    g_keyManager = std::make_unique<AndroidKeyManager>();

    KeyManager::SetSettings(g_emu->GetSettings());
    KeyManager::RegisterKeyManager(g_keyManager.get());

    g_emu->GetSoundMixer()->RegisterAudioDevice(g_audio.get());

    g_renderer = std::make_unique<AndroidRenderer>(g_emu.get());

    LOGI("Emulator initialized. Home: %s", FolderUtilities::GetHomeFolder().c_str());
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_release(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_emu) return;
    g_renderer.reset();
    g_emu->Stop(true);
    g_emu->Release();
    if (g_messageManager) {
        MessageManager::UnregisterMessageManager(g_messageManager.get());
    }
    g_messageManager.reset();
    g_audio.reset();
    g_keyManager.reset();
    g_emu.reset();
}

JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_neshd_NativeLib_loadRom(JNIEnv* env, jobject /*thiz*/, jstring romPath)
{
    if (!g_emu) {
        MessageManager::Log("[Error] Emulator is not initialized");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(romPath, nullptr);
    std::string pathStr = path ? path : "";
    VirtualFile romFile(pathStr);
    if (!IsSupportedNesRom(romFile)) {
        MessageManager::Log("[Error] Android build only supports NES-family ROMs: " + pathStr);
        env->ReleaseStringUTFChars(romPath, path);
        LOGI("LoadRom(%s) -> unsupported extension", pathStr.c_str());
        return JNI_FALSE;
    }

    bool ok = g_emu->LoadRom(romFile, VirtualFile());
    g_lastBatteryFlush = std::chrono::steady_clock::now();
    env->ReleaseStringUTFChars(romPath, path);
    LOGI("LoadRom(%s) -> %s", pathStr.c_str(), ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_stopRom(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_emu) g_emu->Stop(false);
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_flushBatterySave(JNIEnv* /*env*/, jobject /*thiz*/)
{
    FlushBatterySave("lifecycle");
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_pause(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_emu) g_emu->Pause();
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_resume(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_emu) g_emu->Resume();
}

JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_neshd_NativeLib_isRunning(JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (g_emu && g_emu->IsRunning()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_neshd_NativeLib_getRomCheatHash(JNIEnv* env, jobject /*thiz*/)
{
    std::string hash = g_emu ? g_emu->GetHash(HashType::Sha1Cheat) : "";
    return env->NewStringUTF(hash.c_str());
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_setCheats(JNIEnv* env, jobject /*thiz*/, jobjectArray codes)
{
    if (!g_emu || !codes) return;

    jsize length = env->GetArrayLength(codes);
    std::vector<CheatCode> cheats;
    cheats.reserve((size_t)length);

    for (jsize i = 0; i < length; i++) {
        auto codeString = (jstring)env->GetObjectArrayElement(codes, i);
        if (!codeString) continue;

        const char* rawCode = env->GetStringUTFChars(codeString, nullptr);
        if (rawCode) {
            std::string code(rawCode);
            env->ReleaseStringUTFChars(codeString, rawCode);

            code.erase(std::remove_if(code.begin(), code.end(), [](unsigned char ch) {
                return std::isspace(ch) != 0;
            }), code.end());

            CheatCode cheat = {};
            if (!code.empty() && code.size() < sizeof(cheat.Code)) {
                cheat.Type = code.find(':') != std::string::npos
                    ? CheatType::NesCustom
                    : CheatType::NesGameGenie;
                snprintf(cheat.Code, sizeof(cheat.Code), "%s", code.c_str());
                cheats.push_back(cheat);
            }
        }

        env->DeleteLocalRef(codeString);
    }

    if (cheats.empty()) {
        g_emu->GetCheatManager()->ClearCheats(false);
    } else {
        g_emu->GetCheatManager()->SetCheats(cheats);
    }
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_clearCheats(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_emu) {
        g_emu->GetCheatManager()->ClearCheats(false);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_neshd_NativeLib_saveState(JNIEnv* /*env*/, jobject /*thiz*/, jint slot)
{
    if (!g_emu || !g_emu->IsRunning() || !IsValidSaveStateSlot(slot)) {
        return JNI_FALSE;
    }

    std::string path = GetSaveStatePath(slot);
    bool ok = g_emu->GetSaveStateManager()->SaveState(path, false);
    if (ok) {
        MessageManager::DisplayMessage("SaveStates", "SaveStateSaved", std::to_string(slot));
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_neshd_NativeLib_loadState(JNIEnv* /*env*/, jobject /*thiz*/, jint slot)
{
    if (!g_emu || !g_emu->IsRunning() || !IsValidSaveStateSlot(slot)) {
        return JNI_FALSE;
    }

    bool ok = g_emu->GetSaveStateManager()->LoadState(GetSaveStatePath(slot), false);
    if (ok) {
        MessageManager::DisplayMessage("SaveStates", "SaveStateLoaded", std::to_string(slot));
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_neshd_NativeLib_getSaveStatePath(JNIEnv* env, jobject /*thiz*/, jint slot)
{
    std::string path = GetSaveStatePath(slot);
    return env->NewStringUTF(path.c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_com_izzy2lost_neshd_NativeLib_getSaveStatePreview(JNIEnv* env, jobject /*thiz*/, jint slot)
{
    if (!g_emu || !g_emu->IsRunning() || !IsValidSaveStateSlot(slot)) {
        return nullptr;
    }

    std::string path = GetSaveStatePath(slot);
    constexpr size_t MaxPreviewSize = 512 * 478 * 4;
    std::vector<uint8_t> preview(MaxPreviewSize, 0);
    int32_t size = g_emu->GetSaveStateManager()->GetSaveStatePreview(path, preview.data());
    if (size <= 0 || (size_t)size > preview.size()) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(size);
    if (!result) {
        return nullptr;
    }

    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<jbyte*>(preview.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_switchFdsDiskSide(JNIEnv* /*env*/, jobject /*thiz*/)
{
    SendEmulatorShortcut(EmulatorShortcut::FdsSwitchDiskSide);
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_insertNextFdsDisk(JNIEnv* /*env*/, jobject /*thiz*/)
{
    SendEmulatorShortcut(EmulatorShortcut::FdsInsertNextDisk);
}

// Button press/release – buttonId matches NesKeyCode values
JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_setButtonState(JNIEnv* /*env*/, jobject /*thiz*/,
                                                  jint buttonId, jboolean pressed)
{
    if (g_keyManager)
        g_keyManager->SetKeyState((uint16_t)buttonId, pressed == JNI_TRUE);
}

// Video filter selection (maps to VideoFilterType enum)
JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_setVideoFilter(JNIEnv* /*env*/, jobject /*thiz*/,
                                                  jint filterType)
{
    if (!g_emu) return;
    VideoConfig& vid = g_emu->GetSettings()->GetVideoConfig();
    if (filterType < (jint)VideoFilterType::None ||
        filterType > (jint)VideoFilterType::Prescale10x) {
        filterType = (jint)VideoFilterType::None;
    }

    vid.VideoFilter = (VideoFilterType)filterType;
    g_useLinearSampling = UsesLinearFinalSampling(vid.VideoFilter);
    g_samplerDirty = true;
    g_emu->GetVideoDecoder()->ForceFilterUpdate();
    LOGI("Video filter set: %s (%d)", GetVideoFilterName(vid.VideoFilter), filterType);
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_setHdPacksEnabled(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jboolean enabled)
{
    if (!g_emu) return;
    NesConfig& nes = g_emu->GetSettings()->GetNesConfig();
    nes.EnableHdPacks = (enabled == JNI_TRUE);
}

// Anti-flicker – removes the 8-sprite-per-scanline limit (adaptive, so the limit
// is restored on scanlines where dropping it would cause graphical glitches)
JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_setAntiFlicker(JNIEnv* /*env*/, jobject /*thiz*/,
                                                  jboolean enabled)
{
    if (!g_emu) return;
    NesConfig& nes = g_emu->GetSettings()->GetNesConfig();
    nes.RemoveSpriteLimit = (enabled == JNI_TRUE);
    nes.AdaptiveSpriteLimit = (enabled == JNI_TRUE);
    LOGI("Anti-flicker set: %s", enabled == JNI_TRUE ? "on" : "off");
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_setAspectRatio(JNIEnv* /*env*/, jobject /*thiz*/,
                                                  jint aspectRatio)
{
    if (!g_emu) return;
    if (!IsValidAspectRatio(aspectRatio)) {
        aspectRatio = (jint)VideoAspectRatio::NoStretching;
    }

    VideoConfig& vid = g_emu->GetSettings()->GetVideoConfig();
    vid.AspectRatio = (VideoAspectRatio)aspectRatio;
    LOGI("Aspect ratio set: %s (%d)", GetAspectRatioName(vid.AspectRatio), aspectRatio);
}

// ── OpenGL init (called on GL thread) ─────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_glInit(JNIEnv* /*env*/, jobject /*thiz*/)
{
    // Full-screen quad (NDC)
    static const float kVerts[] = {
        // pos        uv
        -1.f, -1.f,  0.f, 1.f,
         1.f, -1.f,  1.f, 1.f,
        -1.f,  1.f,  0.f, 0.f,
         1.f,  1.f,  1.f, 0.f,
    };

    g_program = CreateProgram();
    g_uTexLoc = glGetUniformLocation(g_program, "uTex");

    glGenVertexArrays(1, &g_vao);
    glBindVertexArray(g_vao);

    glGenBuffers(1, &g_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kVerts), kVerts, GL_STATIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          (void*)(2 * sizeof(float)));

    glBindVertexArray(0);

    glGenTextures(1, &g_texture);
    glBindTexture(GL_TEXTURE_2D, g_texture);
    GLenum finalFilter = g_useLinearSampling ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, finalFilter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, finalFilter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_samplerDirty = false;

    // Allocate initial texture (256x240 NES native)
    g_texWidth  = 256;
    g_texHeight = 240;
    g_pixelScratch.resize(g_texWidth * g_texHeight, 0);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)g_texWidth, (GLsizei)g_texHeight,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, g_pixelScratch.data());

    glBindTexture(GL_TEXTURE_2D, 0);
    LOGI("GL initialized");
}

// ── OpenGL draw (called on GL thread each vsync) ──────────────────────────────
JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_glDrawFrame(JNIEnv* /*env*/, jobject /*thiz*/,
                                               jint viewW, jint viewH)
{
    if (viewW <= 0 || viewH <= 0) return;

    glViewport(0, 0, viewW, viewH);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (!g_renderer || !g_program) return;
    FlushBatterySaveIfDue();

    uint32_t outW = 0, outH = 0;
    glBindTexture(GL_TEXTURE_2D, g_texture);
    if (g_samplerDirty) {
        GLenum finalFilter = g_useLinearSampling ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, finalFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, finalFilter);
        g_samplerDirty = false;
    }
    if (g_renderer->GetFrameIfReady(g_pixelScratch, outW, outH)) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        if (outW != g_texWidth || outH != g_texHeight) {
            g_texWidth  = outW;
            g_texHeight = outH;
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                         (GLsizei)g_texWidth, (GLsizei)g_texHeight,
                         0, GL_RGBA, GL_UNSIGNED_BYTE, g_pixelScratch.data());
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            (GLsizei)g_texWidth, (GLsizei)g_texHeight,
                            GL_RGBA, GL_UNSIGNED_BYTE, g_pixelScratch.data());
        }
    }

    // Letterbox / aspect-ratio correction
    float nesAR = (float)g_texWidth / (float)g_texHeight;
    if (g_emu) {
        VideoConfig& vid = g_emu->GetSettings()->GetVideoConfig();
        switch (vid.AspectRatio) {
            case VideoAspectRatio::Standard:
                nesAR = 4.0f / 3.0f;
                break;
            case VideoAspectRatio::Widescreen:
                nesAR = 16.0f / 9.0f;
                break;
            case VideoAspectRatio::NoStretching:
            default:
                break;
        }
    }

    float viewAR = (float)viewW / (float)viewH;
    int vpW = viewW;
    int vpH = viewH;

    if (viewAR > nesAR) {
        vpW = (int)(viewH * nesAR);
    } else {
        vpH = (int)(viewW / nesAR);
    }

    int vpX = (viewW - vpW) / 2;
    int vpY = (viewH - vpH) / 2;
    if (viewH > viewW && vpH < viewH) {
        int portraitLift = std::min((int)(viewH * 0.075f), vpY / 3);
        vpY += portraitLift;
    }

    glViewport(vpX, vpY, vpW, vpH);

    glUseProgram(g_program);
    glUniform1i(g_uTexLoc, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_texture);
    glBindVertexArray(g_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_neshd_NativeLib_glDestroy(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_vao)     { glDeleteVertexArrays(1, &g_vao);  g_vao = 0; }
    if (g_vbo)     { glDeleteBuffers(1, &g_vbo);       g_vbo = 0; }
    if (g_texture) { glDeleteTextures(1, &g_texture);  g_texture = 0; }
    if (g_program) { glDeleteProgram(g_program);       g_program = 0; }
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_neshd_NativeLib_getLog(JNIEnv* env, jobject /*thiz*/)
{
    std::string log = MessageManager::GetLog();
    return env->NewStringUTF(log.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_neshd_NativeLib_getRenderedFrameSize(JNIEnv* env, jobject /*thiz*/)
{
    if (!g_renderer) {
        return env->NewStringUTF("0x0");
    }

    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%ux%u", g_renderer->GetWidth(), g_renderer->GetHeight());
    return env->NewStringUTF(buffer);
}

} // extern "C"

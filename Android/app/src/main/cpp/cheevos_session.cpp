#include "cheevos_session.h"

#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>

#include "Core/Shared/Emulator.h"
#include "Core/Shared/NotificationManager.h"
#include "Core/Shared/Interfaces/IConsole.h"
#include "Core/Shared/Interfaces/INotificationListener.h"
#include "Core/Shared/SettingTypes.h"
#include "Core/NES/NesConsole.h"
#include "Core/NES/NesMemoryManager.h"
#include "Utilities/VirtualFile.h"

#include "rc_consoles.h"
#include "rc_hash.h"

#define TAG "NesHdCheevos"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace neshd::cheevos {
namespace {

// ── HTTP through Java ─────────────────────────────────────────────────────────

JavaVM* g_vm = nullptr;
jclass g_httpClass = nullptr;       // global ref to CheevosHttp
jmethodID g_perform = nullptr;

// A native thread is attached on its first request and detached when it exits.
struct AttachedThread
{
    JNIEnv* env = nullptr;
    bool ours = false;
    ~AttachedThread() { if(ours && g_vm) g_vm->DetachCurrentThread(); }
};

JNIEnv* ThreadEnv()
{
    thread_local AttachedThread a;
    if(a.env) return a.env;
    if(!g_vm) return nullptr;
    if(g_vm->GetEnv(reinterpret_cast<void**>(&a.env), JNI_VERSION_1_6) == JNI_OK) return a.env;
    JavaVMAttachArgs args { JNI_VERSION_1_6, "NESHD cheevos", nullptr };
    if(g_vm->AttachCurrentThread(&a.env, &args) != JNI_OK) {
        a.env = nullptr;
        return nullptr;
    }
    a.ours = true;
    return a.env;
}

class JniBackend final : public Backend
{
public:
    // CheevosHttp.perform returns the status as four big-endian bytes, then the
    // body; status 0 carries the transport's error message as the body.
    Response perform(const Request& req, const char* userAgent) override
    {
        Response res;
        JNIEnv* env = ThreadEnv();
        if(!env || !g_perform) {
            res.error = "no Java transport";
            return res;
        }
        jstring url = env->NewStringUTF(req.url.c_str());
        jstring body = env->NewStringUTF(req.body.c_str());
        jstring type = env->NewStringUTF(req.content_type.c_str());
        jstring ua = env->NewStringUTF(userAgent ? userAgent : "");
        auto out = static_cast<jbyteArray>(env->CallStaticObjectMethod(g_httpClass, g_perform, url, body, type, ua));
        if(env->ExceptionCheck()) {
            env->ExceptionClear();
            out = nullptr;
        }
        env->DeleteLocalRef(url);
        env->DeleteLocalRef(body);
        env->DeleteLocalRef(type);
        env->DeleteLocalRef(ua);
        if(!out) {
            res.error = "the request failed";
            return res;
        }
        const jsize n = env->GetArrayLength(out);
        std::string bytes((size_t)n, '\0');
        if(n > 0) env->GetByteArrayRegion(out, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
        env->DeleteLocalRef(out);
        if(bytes.size() < 4) {
            res.error = "the request failed";
            return res;
        }
        const auto b = reinterpret_cast<const unsigned char*>(bytes.data());
        res.status = (int)((b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]);
        if(res.status == 0) {
            res.error = bytes.substr(4);
            LOGW("no answer from %s: %s", req.url.c_str(), res.error.c_str());
        } else {
            res.body = bytes.substr(4);
        }
        return res;
    }
};

// ── NES memory ────────────────────────────────────────────────────────────────

// Set by the frame listener on the emulation thread just before each frame is
// evaluated, which is the only time rcheevos reads memory (see Client::start).
NesMemoryManager* g_frameMemory = nullptr;

// RetroAchievements' NES addresses are the CPU's: RAM (and mirrors), registers,
// cartridge RAM and ROM, $0000-$FFFF. DebugRead goes through the mapper's current
// banking without side effects, and applies cheats, as the game would see them.
uint32_t ReadNesMemory(uint32_t address, uint8_t* buffer, uint32_t n)
{
    NesMemoryManager* mem = g_frameMemory;
    for(uint32_t i = 0; i < n; i++) {
        const uint32_t a = address + i;
        if(!mem || a > 0xFFFF) {
            std::memset(buffer + i, 0, n - i);
            return i;
        }
        buffer[i] = mem->DebugRead((uint16_t)a);
    }
    return n;
}

class FrameListener final : public INotificationListener
{
public:
    FrameListener(Emulator* emu, Client* client) : _emu(emu), _client(client) {}

    void ProcessNotification(ConsoleNotificationType type, void* /*parameter*/) override
    {
        std::lock_guard<std::mutex> lk(_lock);
        if(!_client) return;
        switch(type) {
            case ConsoleNotificationType::PpuFrameDone: {
                IConsole* console = _emu->GetConsoleUnsafe();
                g_frameMemory = console && console->GetConsoleType() == ConsoleType::Nes
                    ? static_cast<NesConsole*>(console)->GetMemoryManager()
                    : nullptr;
                _client->frame();
                break;
            }
            case ConsoleNotificationType::GameReset:
                _client->reset();
                break;
            default:
                break;
        }
    }

    // After this returns no notification touches the client again.
    void Detach()
    {
        std::lock_guard<std::mutex> lk(_lock);
        _client = nullptr;
        g_frameMemory = nullptr;
    }

private:
    Emulator* _emu;
    Client* _client;
    std::mutex _lock;
};

struct Session
{
    std::unique_ptr<Client> client;
    std::shared_ptr<FrameListener> listener;
};

std::mutex g_sessionLock;
std::unique_ptr<Session> g_session;

bool ReadWholeFile(const std::string& path, std::vector<uint8_t>& out)
{
    out.clear();
    FILE* f = std::fopen(path.c_str(), "rb");
    if(!f) return false;
    uint8_t buf[16 * 1024];
    size_t n;
    while((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.insert(out.end(), buf, buf + n);
    std::fclose(f);
    return true;
}

bool WriteWholeFile(const std::string& path, const std::vector<uint8_t>& data)
{
    const std::string tmp = path + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "wb");
    if(!f) return false;
    const bool ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    std::fclose(f);
    if(!ok || std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        return false;
    }
    return true;
}

const char* StateName(State state)
{
    switch(state) {
        case State::Off: return "off";
        case State::SignedOut: return "signed_out";
        case State::SigningIn: return "signing_in";
        case State::SignedIn: return "signed_in";
        case State::LoadingGame: return "loading";
        case State::Playing: return "playing";
        case State::NoSet: return "no_set";
        case State::EmptySet: return "empty_set";
    }
    return "off";
}

constexpr char Sep = '\x1f';

} // namespace

void InitJni(JavaVM* vm, JNIEnv* env)
{
    g_vm = vm;
    jclass c = env->FindClass("com/izzy2lost/neshd/CheevosHttp");
    if(!c) {
        env->ExceptionClear();
        LOGW("no CheevosHttp class");
        return;
    }
    g_httpClass = static_cast<jclass>(env->NewGlobalRef(c));
    env->DeleteLocalRef(c);
    g_perform = env->GetStaticMethodID(g_httpClass, "perform",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)[B");
    if(!g_perform) env->ExceptionClear();
}

std::unique_ptr<Backend> MakeJniBackend()
{
    return std::make_unique<JniBackend>();
}

void BeginSession(Emulator* emu, const std::string& user, const std::string& token)
{
    EndSession();
    if(!emu || user.empty() || token.empty()) return;

    // Hash the file as loaded; rcheevos skips an iNES or fwNES (FDS) header itself.
    std::vector<uint8_t> rom;
    VirtualFile romFile = emu->GetRomInfo().RomFile;
    char hash[33] = {};
    if(!romFile.ReadFile(rom) || rom.empty() ||
       !rc_hash_generate_from_buffer(hash, RC_CONSOLE_NINTENDO, rom.data(), rom.size())) {
        LOGW("cannot hash %s", romFile.GetFilePath().c_str());
        return;
    }

    auto session = std::make_unique<Session>();
    session->client = std::make_unique<Client>();
    session->client->start(MakeJniBackend(), &ReadNesMemory);
    session->client->sign_in_with_token(user, token);
    session->client->load_game(hash);
    session->listener = std::make_shared<FrameListener>(emu, session->client.get());
    // Held weakly by Mesen: lives exactly as long as the session.
    emu->GetNotificationManager()->RegisterNotificationListener(session->listener);

    std::lock_guard<std::mutex> lk(g_sessionLock);
    g_session = std::move(session);
}

void EndSession()
{
    std::unique_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lk(g_sessionLock);
        session = std::move(g_session);
    }
    if(!session) return;
    session->listener->Detach();
    // Shutting down waits for the request in flight (an unlock, say) to finish,
    // which can take seconds: not on the caller's (UI) thread.
    std::thread([s = std::move(session)]() mutable { s.reset(); }).detach();
}

void AfterSaveState(const std::string& statePath)
{
    std::lock_guard<std::mutex> lk(g_sessionLock);
    const std::string path = statePath + ".rap";
    std::vector<uint8_t> progress;
    if(g_session && g_session->client->serialize_progress(progress)) {
        WriteWholeFile(path, progress);
    } else {
        std::remove(path.c_str());
    }
}

void AfterLoadState(const std::string& statePath)
{
    std::lock_guard<std::mutex> lk(g_sessionLock);
    if(!g_session) return;
    std::vector<uint8_t> progress;
    if(!ReadWholeFile(statePath + ".rap", progress) || progress.empty() ||
       !g_session->client->deserialize_progress(progress.data(), progress.size())) {
        g_session->client->reset();
    }
}

std::vector<std::string> TakeMessages()
{
    std::vector<std::string> out;
    std::lock_guard<std::mutex> lk(g_sessionLock);
    if(!g_session) return out;
    for(const Message& m : g_session->client->take_messages()) {
        const char* kind = m.kind == Message::Kind::Unlock ? "unlock"
                         : m.kind == Message::Kind::Problem ? "problem" : "info";
        out.push_back(std::string(kind) + Sep + m.text + Sep + m.detail + Sep +
                      std::to_string(m.points) + Sep + m.badge_url);
    }
    return out;
}

std::vector<std::string> ListAchievements()
{
    std::vector<std::string> out;
    std::lock_guard<std::mutex> lk(g_sessionLock);
    if(!g_session) {
        out.push_back("off");
        return out;
    }
    Client& c = *g_session->client;
    const std::vector<Client::Achievement> list = c.achievements();
    uint32_t unlocked = 0, points = 0, earned = 0;
    for(const Client::Achievement& a : list) {
        points += a.points;
        if(a.unlocked) {
            unlocked++;
            earned += a.points;
        }
    }
    out.push_back(std::string(StateName(c.state())) + Sep + c.game_title() + Sep + c.game_badge_url() + Sep +
                  std::to_string(unlocked) + Sep + std::to_string(list.size()) + Sep +
                  std::to_string(earned) + Sep + std::to_string(points));
    for(const Client::Achievement& a : list) {
        out.push_back(std::string(a.unlocked ? "1" : "0") + Sep + (a.unsupported ? "1" : "0") + Sep +
                      std::to_string(a.points) + Sep + a.badge_url + Sep + a.badge_locked_url + Sep +
                      a.title + Sep + a.description + Sep + a.progress);
    }
    return out;
}

} // namespace neshd::cheevos

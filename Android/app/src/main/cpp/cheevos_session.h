#pragma once

// RetroAchievements for the running game: ties a cheevos Client to Mesen (frames,
// NES memory, resets, save states) and serves its HTTP through Java (CheevosHttp).

#include <jni.h>
#include <memory>
#include <string>
#include <vector>

#include "cheevos_client.h"

class Emulator;

namespace neshd::cheevos {

// From JNI_OnLoad: caches the VM and the Java transport class (a native worker
// thread's FindClass would not see the app's classes).
void InitJni(JavaVM* vm, JNIEnv* env);
std::unique_ptr<Backend> MakeJniBackend();

// For the game just loaded: signs in, hashes the ROM, loads its achievement set,
// all in the background; the game does not wait.
void BeginSession(Emulator* emu, const std::string& user, const std::string& token);
// After the emulator has stopped. Unlocks still in flight finish on a background thread.
void EndSession();

// Achievement progress beside a save state, so loading one cannot half-trigger anything.
void AfterSaveState(const std::string& statePath);
void AfterLoadState(const std::string& statePath);

// For the UI; fields separated by \x1f.
// Messages to show: kind (unlock/info/problem), title, detail, points, badge URL.
std::vector<std::string> TakeMessages();
// First row: state, game title, game badge, unlocked, total, points earned, points total.
// Then one per achievement: unlocked, unsupported, points, badge, locked badge, title,
// description, progress.
std::vector<std::string> ListAchievements();

} // namespace neshd::cheevos

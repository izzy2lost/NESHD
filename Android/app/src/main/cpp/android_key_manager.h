#pragma once

#include "Core/Shared/Interfaces/IKeyManager.h"
#include <atomic>
#include <array>
#include <string>
#include <vector>

// Key codes assigned to NES buttons (must match what we set in NesConfig)
namespace NesKeyCode {
    static constexpr uint16_t A      = 1;
    static constexpr uint16_t B      = 2;
    static constexpr uint16_t Select = 3;
    static constexpr uint16_t Start  = 4;
    static constexpr uint16_t Up     = 5;
    static constexpr uint16_t Down   = 6;
    static constexpr uint16_t Left   = 7;
    static constexpr uint16_t Right  = 8;
    static constexpr uint16_t TurboA = 9;
    static constexpr uint16_t TurboB = 10;
    static constexpr uint16_t MAX    = 11;
}

class AndroidKeyManager : public IKeyManager
{
public:
    AndroidKeyManager() { for (auto& k : _keyStates) k.store(false, std::memory_order_relaxed); }

    void RefreshState()  override {}
    void UpdateDevices() override {}

    bool IsMouseButtonPressed(MouseButton /*button*/) override { return false; }

    bool IsKeyPressed(uint16_t keyCode) override
    {
        if (keyCode < (uint16_t)_keyStates.size())
            return _keyStates[keyCode].load(std::memory_order_relaxed);
        return false;
    }

    std::vector<uint16_t> GetPressedKeys() override
    {
        std::vector<uint16_t> pressed;
        for (uint16_t i = 0; i < (uint16_t)_keyStates.size(); ++i) {
            if (_keyStates[i].load(std::memory_order_relaxed))
                pressed.push_back(i);
        }
        return pressed;
    }

    std::string GetKeyName(uint16_t keyCode) override
    {
        switch (keyCode) {
            case NesKeyCode::A:      return "A";
            case NesKeyCode::B:      return "B";
            case NesKeyCode::Select: return "Select";
            case NesKeyCode::Start:  return "Start";
            case NesKeyCode::Up:     return "Up";
            case NesKeyCode::Down:   return "Down";
            case NesKeyCode::Left:   return "Left";
            case NesKeyCode::Right:  return "Right";
            case NesKeyCode::TurboA: return "TurboA";
            case NesKeyCode::TurboB: return "TurboB";
            default:                 return "";
        }
    }

    uint16_t GetKeyCode(std::string keyName) override
    {
        if (keyName == "A")      return NesKeyCode::A;
        if (keyName == "B")      return NesKeyCode::B;
        if (keyName == "Select") return NesKeyCode::Select;
        if (keyName == "Start")  return NesKeyCode::Start;
        if (keyName == "Up")     return NesKeyCode::Up;
        if (keyName == "Down")   return NesKeyCode::Down;
        if (keyName == "Left")   return NesKeyCode::Left;
        if (keyName == "Right")  return NesKeyCode::Right;
        if (keyName == "TurboA") return NesKeyCode::TurboA;
        if (keyName == "TurboB") return NesKeyCode::TurboB;
        return 0;
    }

    bool SetKeyState(uint16_t scanCode, bool state) override
    {
        if (scanCode < (uint16_t)_keyStates.size()) {
            _keyStates[scanCode].store(state, std::memory_order_relaxed);
            return true;
        }
        return false;
    }

    void ResetKeyState() override
    {
        for (auto& k : _keyStates) k.store(false, std::memory_order_relaxed);
    }

    void SetDisabled(bool /*disabled*/) override {}

private:
    std::array<std::atomic<bool>, 256> _keyStates;
};

#pragma once

#include "Core/Shared/Interfaces/IRenderingDevice.h"
#include "Core/Shared/RenderedFrame.h"
#include <atomic>
#include <mutex>
#include <vector>
#include <cstdint>

class Emulator;

// Captures rendered NES frames into a CPU-side pixel buffer.
// The GLSurfaceView on the Java side polls GetFrameIfReady() each vsync.
class AndroidRenderer : public IRenderingDevice
{
public:
    explicit AndroidRenderer(Emulator* emu);
    ~AndroidRenderer() override;

    // IRenderingDevice
    void UpdateFrame(RenderedFrame& frame) override;
    void ClearFrame() override;
    void Render(RenderSurfaceInfo& emuHud, RenderSurfaceInfo& scriptHud) override;
    void Reset() override {}
    void SetExclusiveFullscreenMode(bool /*fs*/, void* /*wnd*/) override {}

    // Called from the GL thread - copies latest frame into dst (RGBA 32-bit).
    // Returns true if a new frame was available.
    bool GetFrameIfReady(std::vector<uint32_t>& dst, uint32_t& outWidth, uint32_t& outHeight);

    uint32_t GetWidth()  const { return _frameWidth.load();  }
    uint32_t GetHeight() const { return _frameHeight.load(); }

private:
    Emulator* _emu;

    std::mutex           _lock;
    std::vector<uint32_t> _bufA;  // written by emulator thread
    std::vector<uint32_t> _bufB;  // read by GL thread
    std::atomic<bool>    _dirty  { false };
    std::atomic<uint32_t> _frameWidth  { 256 };
    std::atomic<uint32_t> _frameHeight { 240 };
};

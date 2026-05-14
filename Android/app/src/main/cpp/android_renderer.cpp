#include "android_renderer.h"
#include "Core/Shared/Emulator.h"
#include "Core/Shared/Video/VideoRenderer.h"
#include <cstring>
#include <algorithm>

AndroidRenderer::AndroidRenderer(Emulator* emu) : _emu(emu)
{
    _bufA.resize(256 * 240, 0);
    _bufB.resize(256 * 240, 0);
    _emu->GetVideoRenderer()->RegisterRenderingDevice(this);
}

AndroidRenderer::~AndroidRenderer()
{
    _emu->GetVideoRenderer()->UnregisterRenderingDevice(this);
}

void AndroidRenderer::UpdateFrame(RenderedFrame& frame)
{
    std::lock_guard<std::mutex> lg(_lock);

    uint32_t w = frame.Width;
    uint32_t h = frame.Height;
    size_t   sz = (size_t)w * h;

    if (_bufA.size() != sz) _bufA.resize(sz);

    memcpy(_bufA.data(), frame.FrameBuffer, sz * sizeof(uint32_t));
    _frameWidth.store(w);
    _frameHeight.store(h);
    _dirty.store(true, std::memory_order_release);
}

void AndroidRenderer::ClearFrame()
{
    std::lock_guard<std::mutex> lg(_lock);
    std::fill(_bufA.begin(), _bufA.end(), 0u);
    _dirty.store(true, std::memory_order_release);
}

void AndroidRenderer::Render(RenderSurfaceInfo& /*emuHud*/, RenderSurfaceInfo& /*scriptHud*/)
{
    // The render thread calls us but we use the GL thread to present.
    // Nothing to do here – GL thread polls GetFrameIfReady().
}

bool AndroidRenderer::GetFrameIfReady(std::vector<uint32_t>& dst, uint32_t& outWidth, uint32_t& outHeight)
{
    if (!_dirty.load(std::memory_order_acquire))
        return false;

    std::lock_guard<std::mutex> lg(_lock);
    if (!_dirty.load(std::memory_order_relaxed))
        return false;

    outWidth  = _frameWidth.load();
    outHeight = _frameHeight.load();
    size_t sz = (size_t)outWidth * outHeight;

    if (_bufB.size() != sz) _bufB.resize(sz);
    if (dst.size() < sz) dst.resize(sz);
    std::swap(_bufA, _bufB);
    _dirty.store(false, std::memory_order_release);

    memcpy(dst.data(), _bufB.data(), sz * sizeof(uint32_t));
    return true;
}

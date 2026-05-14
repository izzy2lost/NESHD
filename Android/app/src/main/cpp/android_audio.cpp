#include "android_audio.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <cmath>

#define TAG "MesenAudio"

AndroidAudioDevice::AndroidAudioDevice()
{
    _ring.resize(kRingSize * 2, 0); // stereo worst-case
}

AndroidAudioDevice::~AndroidAudioDevice()
{
    CloseStream();
}

void AndroidAudioDevice::OpenStream(uint32_t sampleRate, bool isStereo)
{
    if (_stream) {
        if (_sampleRate == sampleRate && (_channelCount == 2) == isStereo)
            return;
        CloseStream();
    }

    _sampleRate   = sampleRate;
    _channelCount = isStereo ? 2 : 1;

    // Resize ring to hold ~200ms of audio
    size_t ringSamples = (sampleRate * 200 / 1000) * _channelCount * 2;
    _ring.assign(ringSamples, 0);
    _writePos.store(0);
    _readPos.store(0);

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "createStreamBuilder failed: %s",
                            AAudio_convertResultToText(res));
        return;
    }

    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, _channelCount);
    AAudioStreamBuilder_setSampleRate(builder, (int32_t)sampleRate);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(builder, AndroidAudioDevice::AudioCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder,
        [](AAudioStream*, void* userData, aaudio_result_t err) {
            auto* self = static_cast<AndroidAudioDevice*>(userData);
            __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio error: %s",
                                AAudio_convertResultToText(err));
            // Reopen stream on disconnect
            self->CloseStream();
            self->OpenStream(self->_sampleRate, self->_channelCount == 2);
        }, this);

    res = AAudioStreamBuilder_openStream(builder, &_stream);
    AAudioStreamBuilder_delete(builder);

    if (res != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "openStream failed: %s",
                            AAudio_convertResultToText(res));
        _stream = nullptr;
        return;
    }

    const int32_t framesPerBurst = AAudioStream_getFramesPerBurst(_stream);
    const int32_t capacityFrames = AAudioStream_getBufferCapacityInFrames(_stream);
    if (framesPerBurst > 0 && capacityFrames > 0) {
        const int32_t latencyFrames = std::max(framesPerBurst * 4, (int32_t)(sampleRate * 80 / 1000));
        const int32_t targetFrames = std::min(capacityFrames, latencyFrames);
        AAudioStream_setBufferSizeInFrames(_stream, targetFrames);
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "AAudio stream opened: rate=%u channels=%d burst=%d buffer=%d/%d",
                            sampleRate, _channelCount, framesPerBurst, targetFrames, capacityFrames);
    }

    res = AAudioStream_requestStart(_stream);
    if (res != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "requestStart failed: %s",
                            AAudio_convertResultToText(res));
    }
}

void AndroidAudioDevice::CloseStream()
{
    if (_stream) {
        AAudioStream_requestStop(_stream);
        AAudioStream_close(_stream);
        _stream = nullptr;
    }
}

aaudio_data_callback_result_t AndroidAudioDevice::AudioCallback(
    AAudioStream* /*stream*/, void* userData, void* audioData, int32_t numFrames)
{
    auto* self = static_cast<AndroidAudioDevice*>(userData);
    return self->FillBuffer(static_cast<int16_t*>(audioData), numFrames);
}

aaudio_data_callback_result_t AndroidAudioDevice::FillBuffer(int16_t* dst, int32_t numFrames)
{
    if (_paused.load()) {
        memset(dst, 0, numFrames * _channelCount * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    int32_t samplesNeeded = numFrames * _channelCount;
    size_t  ringSize      = _ring.size();
    size_t  wp            = _writePos.load(std::memory_order_acquire);
    size_t  rp            = _readPos.load(std::memory_order_relaxed);
    size_t  available     = (wp - rp + ringSize) % ringSize;

    int32_t toCopy = (int32_t)std::min((size_t)samplesNeeded, available);

    for (int32_t i = 0; i < toCopy; ++i) {
        dst[i] = _ring[(rp + i) % ringSize];
    }

    if (toCopy < samplesNeeded) {
        memset(dst + toCopy, 0, (samplesNeeded - toCopy) * sizeof(int16_t));
        ++_underruns;
    }

    _readPos.store((rp + toCopy) % ringSize, std::memory_order_release);
    UpdateLatency(available - toCopy);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AndroidAudioDevice::PlayBuffer(int16_t* soundBuffer, uint32_t bufferSize,
                                    uint32_t sampleRate, bool isStereo)
{
    if (!_stream) {
        OpenStream(sampleRate, isStereo);
    }

    size_t ringSize = _ring.size();
    size_t wp       = _writePos.load(std::memory_order_relaxed);
    size_t rp       = _readPos.load(std::memory_order_acquire);

    // Drop oldest samples if ring is nearly full to avoid blocking
    size_t used    = (wp - rp + ringSize) % ringSize;
    size_t freeSpace = ringSize - used - 1;

    uint32_t sampleCount = bufferSize * (isStereo ? 2 : 1);
    uint32_t toCopy = std::min((uint32_t)freeSpace, sampleCount);
    for (uint32_t i = 0; i < toCopy; ++i) {
        _ring[(wp + i) % ringSize] = soundBuffer[i];
    }
    _writePos.store((wp + toCopy) % ringSize, std::memory_order_release);
    UpdateLatency(used + toCopy);
}

void AndroidAudioDevice::Stop()
{
    CloseStream();
}

void AndroidAudioDevice::Pause()
{
    _paused.store(true);
}

void AndroidAudioDevice::ProcessEndOfFrame()
{
    _paused.store(false);
}

AudioStatistics AndroidAudioDevice::GetStatistics()
{
    AudioStatistics stats;
    stats.AverageLatency           = _avgLatencyUs.load(std::memory_order_relaxed) / 1000.0;
    stats.BufferUnderrunEventCount = _underruns.load(std::memory_order_relaxed);
    stats.BufferSize               = (uint32_t)_ring.size();
    return stats;
}

void AndroidAudioDevice::UpdateLatency(size_t queuedSamples)
{
    if (_sampleRate == 0 || _channelCount <= 0) {
        _avgLatencyUs.store(0, std::memory_order_relaxed);
        return;
    }

    double queuedFrames = (double)queuedSamples / (double)_channelCount;
    uint32_t latencyUs = (uint32_t)std::round(queuedFrames * 1000000.0 / (double)_sampleRate);
    _avgLatencyUs.store(latencyUs, std::memory_order_relaxed);
}

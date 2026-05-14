#pragma once

#include "Core/Shared/Interfaces/IAudioDevice.h"
#include <aaudio/AAudio.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstring>

class AndroidAudioDevice : public IAudioDevice
{
public:
    AndroidAudioDevice();
    ~AndroidAudioDevice() override;

    void PlayBuffer(int16_t* soundBuffer, uint32_t bufferSize, uint32_t sampleRate, bool isStereo) override;
    void Stop() override;
    void Pause() override;
    void ProcessEndOfFrame() override;
    std::string GetAvailableDevices() override { return ""; }
    void SetAudioDevice(std::string deviceName) override {}
    AudioStatistics GetStatistics() override;

private:
    static aaudio_data_callback_result_t AudioCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    aaudio_data_callback_result_t FillBuffer(int16_t* dst, int32_t numFrames);
    void OpenStream(uint32_t sampleRate, bool isStereo);
    void CloseStream();
    void UpdateLatency(size_t queuedSamples);

    AAudioStream*           _stream         = nullptr;
    uint32_t                _sampleRate     = 44100;
    int32_t                 _channelCount   = 2;
    std::atomic<bool>       _paused         { false };

    // Ring buffer
    static constexpr size_t kRingSize = 16384; // samples (per channel)
    std::vector<int16_t>    _ring;
    std::atomic<size_t>     _writePos       { 0 };
    std::atomic<size_t>     _readPos        { 0 };

    std::atomic<uint32_t>   _underruns      { 0 };
    std::atomic<uint32_t>   _avgLatencyUs   { 0 };
};

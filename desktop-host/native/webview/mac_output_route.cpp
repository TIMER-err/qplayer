#include <CoreAudio/CoreAudio.h>
#include <atomic>
#include <jni.h>

namespace {
constexpr AudioObjectPropertyAddress defaultOutput = {
    kAudioHardwarePropertyDefaultOutputDevice,
    kAudioObjectPropertyScopeGlobal,
    kAudioObjectPropertyElementMain
};

// The HAL callback never enters Java or manipulates OpenAL. Keep the listener
// alive between tracks and while paused so that quick round trips are observed.
struct OutputMonitor {
    std::atomic<jlong> generation{0};
    OSStatus status;

    static OSStatus changed(AudioObjectID, UInt32 count, const AudioObjectPropertyAddress *properties,
                            void *context) {
        for (UInt32 i = 0; i < count; ++i) {
            if (properties[i].mSelector == defaultOutput.mSelector) {
                static_cast<OutputMonitor *>(context)->generation.fetch_add(1, std::memory_order_relaxed);
                break;
            }
        }
        return noErr;
    }

    OutputMonitor() : status(AudioObjectAddPropertyListener(kAudioObjectSystemObject, &defaultOutput,
                                                           changed, this)) {}
    ~OutputMonitor() {
        if (status == noErr)
            AudioObjectRemovePropertyListener(kAudioObjectSystemObject, &defaultOutput, changed, this);
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_t1m3_qplayer_desktop_audio_MacOutputRoute_defaultOutputGeneration(JNIEnv *, jclass) {
    static OutputMonitor monitor;
    if (monitor.status != noErr) return monitor.status > 0 ? -static_cast<jlong>(monitor.status) : monitor.status;
    return monitor.generation.load(std::memory_order_relaxed);
}

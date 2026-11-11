# WebRTC echo control (AECM)

The mobile echo canceller of the open-source WebRTC audio processing code, with the few files it needs (the delay estimator, a ring
buffer and the signal-processing helpers), **unmodified**, from the `webrtc-audio-processing` 0.3.1 release of the PulseAudio project
(https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/, a packaged copy of Google's WebRTC code).

- `webrtc/modules/audio_processing/aecm`, `webrtc/modules/audio_processing/utility`: the canceller and its delay estimator
- `webrtc/common_audio`: the ring buffer and `signal_processing`, the fixed-point helpers
- `webrtc/typedefs.h`, `webrtc/system_wrappers/include`: the headers they include

Licence: BSD 3-clause (`COPYING`), with Google's patent grant (`PATENTS`); the authors are in `AUTHORS`. Only the C files are compiled
(see `../../CMakeLists.txt`); the NEON and MIPS variants are left out on purpose: the canceller runs on 10 ms chunks and needs a few
million operations a second.

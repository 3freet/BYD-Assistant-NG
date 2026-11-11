// The JNI layer of the live broadcast's echo canceller: WebRTC's mobile echo control (AECM) behind four calls.
//
// One canceller works on one microphone stream and the stream that is played into the speaker, in chunks of 10 ms at 16 kHz (160 samples):
// every chunk, first the chunk that is being played as the reference (nativeFarend), then the microphone chunk (nativeProcess), which comes
// back without what the speaker's sound added to it. All four calls are made from one thread at a time (the Kotlin side takes care of that).

#include <jni.h>
#include <stdint.h>
#include <stddef.h>

#include "webrtc/modules/audio_processing/aecm/include/echo_control_mobile.h"

// The most samples one call takes: 30 ms at 16 kHz (the canceller itself takes 10 ms at a time; this only bounds the stack buffers).
#define MAX_SAMPLES 480

JNIEXPORT jlong JNICALL
Java_com_bydassistantng_audio_EchoNative_nativeCreate(JNIEnv* env, jclass clazz, jint sampleRate, jint echoMode, jboolean comfortNoise) {
  void* aecm = WebRtcAecm_Create();
  if (aecm == NULL) return 0;
  if (WebRtcAecm_Init(aecm, sampleRate) != 0) {
    WebRtcAecm_Free(aecm);
    return 0;
  }
  AecmConfig config;
  config.cngMode = comfortNoise ? AecmTrue : AecmFalse;
  config.echoMode = (int16_t)echoMode;
  if (WebRtcAecm_set_config(aecm, config) != 0) {
    WebRtcAecm_Free(aecm);
    return 0;
  }
  return (jlong)(intptr_t)aecm;
}

JNIEXPORT jint JNICALL
Java_com_bydassistantng_audio_EchoNative_nativeFarend(JNIEnv* env, jclass clazz, jlong handle, jshortArray samples, jint count) {
  if (handle == 0 || count <= 0 || count > MAX_SAMPLES) return -1;
  if ((*env)->GetArrayLength(env, samples) < count) return -1;
  int16_t buffer[MAX_SAMPLES];
  (*env)->GetShortArrayRegion(env, samples, 0, count, buffer);
  return (jint)WebRtcAecm_BufferFarend((void*)(intptr_t)handle, buffer, (size_t)count);
}

JNIEXPORT jint JNICALL
Java_com_bydassistantng_audio_EchoNative_nativeProcess(JNIEnv* env, jclass clazz, jlong handle, jshortArray near, jshortArray out, jint count, jint delayMs) {
  if (handle == 0 || count <= 0 || count > MAX_SAMPLES) return -1;
  if ((*env)->GetArrayLength(env, near) < count || (*env)->GetArrayLength(env, out) < count) return -1;
  int16_t in[MAX_SAMPLES];
  int16_t cleaned[MAX_SAMPLES];
  (*env)->GetShortArrayRegion(env, near, 0, count, in);
  jint result = (jint)WebRtcAecm_Process((void*)(intptr_t)handle, in, NULL, cleaned, (size_t)count, (int16_t)delayMs);
  if (result == 0) (*env)->SetShortArrayRegion(env, out, 0, count, cleaned);
  return result;
}

JNIEXPORT void JNICALL
Java_com_bydassistantng_audio_EchoNative_nativeFree(JNIEnv* env, jclass clazz, jlong handle) {
  if (handle != 0) WebRtcAecm_Free((void*)(intptr_t)handle);
}

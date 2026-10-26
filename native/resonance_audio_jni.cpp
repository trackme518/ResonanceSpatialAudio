// JNI bridge between the Resonance Audio C++ API (vraudio::ResonanceAudioApi)
// and the Processing library's Java classes (com.google.resonanceaudio).
//
// Every Java-side ResonanceAudio instance owns one native instance, addressed
// through a jlong handle.

#include <jni.h>

#include <cstdio>
#include <string>
#include <vector>

#include "api/resonance_audio_api.h"
#include "platforms/common/room_properties.h"
#include "platforms/common/room_effects_utils.h"

using vraudio::CreateResonanceAudioApi;
using vraudio::DistanceRolloffModel;
using vraudio::ReflectionProperties;
using vraudio::RenderingMode;
using vraudio::ResonanceAudioApi;
using vraudio::ReverbProperties;
using vraudio::RoomProperties;

namespace {

inline ResonanceAudioApi* Api(jlong handle) {
  return reinterpret_cast<ResonanceAudioApi*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeCreate(JNIEnv*, jclass,
                                                      jint num_channels,
                                                      jint frames_per_buffer,
                                                      jint sample_rate_hz) {
  ResonanceAudioApi* api = CreateResonanceAudioApi(
      static_cast<size_t>(num_channels),
      static_cast<size_t>(frames_per_buffer), sample_rate_hz);
  return reinterpret_cast<jlong>(api);
}

JNIEXPORT void JNICALL Java_com_google_resonanceaudio_NativeApi_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
  delete Api(handle);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetHeadPosition(JNIEnv*, jclass,
                                                               jlong handle,
                                                               jfloat x,
                                                               jfloat y,
                                                               jfloat z) {
  Api(handle)->SetHeadPosition(x, y, z);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetHeadRotation(
    JNIEnv*, jclass, jlong handle, jfloat x, jfloat y, jfloat z, jfloat w) {
  Api(handle)->SetHeadRotation(x, y, z, w);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetMasterVolume(JNIEnv*, jclass,
                                                               jlong handle,
                                                               jfloat volume) {
  Api(handle)->SetMasterVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetStereoSpeakerMode(
    JNIEnv*, jclass, jlong handle, jboolean enabled) {
  Api(handle)->SetStereoSpeakerMode(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeCreateSoundObjectSource(
    JNIEnv*, jclass, jlong handle, jint rendering_mode) {
  return Api(handle)->CreateSoundObjectSource(
      static_cast<RenderingMode>(rendering_mode));
}

JNIEXPORT jint JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeCreateAmbisonicSource(
    JNIEnv*, jclass, jlong handle, jint num_channels) {
  return Api(handle)->CreateAmbisonicSource(static_cast<size_t>(num_channels));
}

JNIEXPORT jint JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeCreateStereoSource(JNIEnv*,
                                                                  jclass,
                                                                  jlong handle,
                                                                  jint num_channels) {
  return Api(handle)->CreateStereoSource(static_cast<size_t>(num_channels));
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeDestroySource(JNIEnv*, jclass,
                                                             jlong handle,
                                                             jint source_id) {
  Api(handle)->DestroySource(source_id);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSourceBuffer(JNIEnv* env,
                                                               jclass,
                                                               jlong handle,
                                                               jint source_id,
                                                               jfloatArray buffer,
                                                               jint num_channels) {
  const jsize num_samples = env->GetArrayLength(buffer);
  jfloat* samples = env->GetFloatArrayElements(buffer, nullptr);
  Api(handle)->SetInterleavedBuffer(
      source_id, samples, static_cast<size_t>(num_channels),
      static_cast<size_t>(num_samples) / static_cast<size_t>(num_channels));
  env->ReleaseFloatArrayElements(buffer, samples, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeFillOutputBuffer(
    JNIEnv* env, jclass, jlong handle, jfloatArray out, jint num_channels) {
  const jsize num_samples = env->GetArrayLength(out);
  jfloat* samples = env->GetFloatArrayElements(out, nullptr);
  const bool ok = Api(handle)->FillInterleavedOutputBuffer(
      static_cast<size_t>(num_channels),
      static_cast<size_t>(num_samples) / static_cast<size_t>(num_channels),
      samples);
  env->ReleaseFloatArrayElements(out, samples, 0);
  return ok ? JNI_TRUE : JNI_FALSE;
}

// Renders binaural stereo (decoded) and ambisonic B-format (undecoded) output
// from a single graph pass. Either array may be null to skip that output.
JNIEXPORT jboolean JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeFillStereoAndAmbisonicBuffers(
    JNIEnv* env, jclass, jlong handle, jfloatArray ambisonic_out,
    jfloatArray stereo_out, jint ambisonic_channels, jint stereo_channels) {
  jfloat* ambi_samples = ambisonic_out != nullptr
                             ? env->GetFloatArrayElements(ambisonic_out,
                                                          nullptr)
                             : nullptr;
  jfloat* stereo_samples = stereo_out != nullptr
                               ? env->GetFloatArrayElements(stereo_out,
                                                            nullptr)
                               : nullptr;
  jsize num_frames = 0;
  if (stereo_samples != nullptr) {
    num_frames = static_cast<jsize>(env->GetArrayLength(stereo_out)) /
                 stereo_channels;
  } else if (ambi_samples != nullptr) {
    num_frames = static_cast<jsize>(env->GetArrayLength(ambisonic_out)) /
                 ambisonic_channels;
  }
  const bool ok = Api(handle)->FillStereoAndAmbisonicOutputBuffers(
      static_cast<size_t>(num_frames), stereo_samples, ambi_samples,
      static_cast<size_t>(ambisonic_channels));
  if (ambi_samples != nullptr) {
    env->ReleaseFloatArrayElements(ambisonic_out, ambi_samples, 0);
  }
  if (stereo_samples != nullptr) {
    env->ReleaseFloatArrayElements(stereo_out, stereo_samples, 0);
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetAmbisonicRotationCompensated(
    JNIEnv*, jclass, jlong handle, jboolean enable) {
  Api(handle)->SetAmbisonicListenerRotationCompensated(enable == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSourcePosition(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat x, jfloat y,
    jfloat z) {
  Api(handle)->SetSourcePosition(source_id, x, y, z);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSourceRotation(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat x, jfloat y,
    jfloat z, jfloat w) {
  Api(handle)->SetSourceRotation(source_id, x, y, z, w);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSourceVolume(JNIEnv*, jclass,
                                                               jlong handle,
                                                               jint source_id,
                                                               jfloat volume) {
  Api(handle)->SetSourceVolume(source_id, volume);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSourceDistanceModel(
    JNIEnv*, jclass, jlong handle, jint source_id, jint rolloff_model,
    jfloat min_distance, jfloat max_distance) {
  Api(handle)->SetSourceDistanceModel(
      source_id, static_cast<DistanceRolloffModel>(rolloff_model),
      min_distance, max_distance);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSourceDistanceAttenuation(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat attenuation) {
  Api(handle)->SetSourceDistanceAttenuation(source_id, attenuation);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSourceRoomEffectsGain(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat gain) {
  Api(handle)->SetSourceRoomEffectsGain(source_id, gain);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSoundObjectDirectivity(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat alpha, jfloat order) {
  Api(handle)->SetSoundObjectDirectivity(source_id, alpha, order);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSoundObjectListenerDirectivity(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat alpha,
    jfloat order) {
  Api(handle)->SetSoundObjectListenerDirectivity(source_id, alpha, order);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSoundObjectSpread(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat spread_deg) {
  Api(handle)->SetSoundObjectSpread(source_id, spread_deg);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSoundObjectOcclusionIntensity(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat intensity) {
  Api(handle)->SetSoundObjectOcclusionIntensity(source_id, intensity);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetSoundObjectNearFieldEffectGain(
    JNIEnv*, jclass, jlong handle, jint source_id, jfloat gain) {
  Api(handle)->SetSoundObjectNearFieldEffectGain(source_id, gain);
}

JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeEnableRoomEffects(JNIEnv*, jclass,
                                                                 jlong handle,
                                                                 jboolean enable) {
  Api(handle)->EnableRoomEffects(enable == JNI_TRUE);
}

// Sets the acoustic shoebox room model (mirrors the Android SDK's
// ResonanceAudio.setRoomModel()). All geometry uses the right-handed
// coordinate system of Resonance Audio. |materials| holds six material
// indices (see the MaterialName enum in room_properties.h) ordered
// left, right, bottom, top, front, back.
JNIEXPORT void JNICALL
Java_com_google_resonanceaudio_NativeApi_nativeSetRoomModel(
    JNIEnv* env, jclass, jlong handle, jfloatArray position,
    jfloatArray rotation, jfloatArray dimensions, jintArray materials,
    jfloat reflection_scalar, jfloat reverb_gain, jfloat reverb_time,
    jfloat reverb_brightness, jboolean enable) {
  RoomProperties room_properties;

  env->GetFloatArrayRegion(position, 0, 3, room_properties.position);
  env->GetFloatArrayRegion(rotation, 0, 4, room_properties.rotation);
  env->GetFloatArrayRegion(dimensions, 0, 3, room_properties.dimensions);

  jint mats[vraudio::kNumRoomSurfaces];
  env->GetIntArrayRegion(materials, 0, vraudio::kNumRoomSurfaces, mats);
  for (size_t i = 0; i < vraudio::kNumRoomSurfaces; ++i) {
    room_properties.material_names[i] =
        static_cast<vraudio::MaterialName>(mats[i]);
  }

  room_properties.reflection_scalar = reflection_scalar;
  room_properties.reverb_gain = reverb_gain;
  room_properties.reverb_time = reverb_time;
  room_properties.reverb_brightness = reverb_brightness;

  const ReflectionProperties reflection_properties =
      vraudio::ComputeReflectionProperties(room_properties);
  const ReverbProperties reverb_properties =
      vraudio::ComputeReverbProperties(room_properties);

  ResonanceAudioApi* api = Api(handle);
  api->SetReflectionProperties(reflection_properties);
  api->SetReverbProperties(reverb_properties);
  api->EnableRoomEffects(enable == JNI_TRUE);
}

}  // extern "C"

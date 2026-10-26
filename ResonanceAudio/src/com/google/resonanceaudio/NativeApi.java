package com.google.resonanceaudio;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Declares the native (JNI) methods implemented in resonanceaudio_jni.dll and
 * takes care of loading the native library. Not part of the public API.
 */
final class NativeApi {

  static {
    boolean loaded = false;
    try {
      System.loadLibrary("resonanceaudio_jni");
      loaded = true;
    } catch (UnsatisfiedLinkError e) {
      // Fall back: extract the DLL bundled inside the library jar.
      String resource = "/native/windows-amd64/resonanceaudio_jni.dll";
      try (InputStream in = NativeApi.class.getResourceAsStream(resource)) {
        if (in != null) {
          File tmp = File.createTempFile("resonanceaudio_jni", ".dll");
          tmp.deleteOnExit();
          try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
              out.write(buf, 0, n);
            }
          }
          System.load(tmp.getAbsolutePath());
          loaded = true;
        }
      } catch (Exception ex) {
        throw new UnsatisfiedLinkError(
            "Could not extract bundled resonanceaudio_jni.dll: " + ex);
      }
      if (!loaded) {
        throw new UnsatisfiedLinkError(
            "Could not load resonanceaudio_jni.dll (native Resonance Audio "
                + "renderer). Currently only Windows x64 is supported.");
      }
    }
  }

  private NativeApi() {
  }

  static native long nativeCreate(int numChannels, int framesPerBuffer,
      int sampleRateHz);

  static native void nativeDestroy(long handle);

  static native void nativeSetHeadPosition(long handle, float x, float y,
      float z);

  static native void nativeSetHeadRotation(long handle, float x, float y,
      float z, float w);

  static native void nativeSetMasterVolume(long handle, float volume);

  static native void nativeSetStereoSpeakerMode(long handle, boolean enabled);

  static native int nativeCreateSoundObjectSource(long handle,
      int renderingMode);

  static native int nativeCreateAmbisonicSource(long handle, int numChannels);

  static native int nativeCreateStereoSource(long handle, int numChannels);

  static native void nativeDestroySource(long handle, int sourceId);

  static native void nativeSetSourceBuffer(long handle, int sourceId,
      float[] interleavedBuffer, int numChannels);

  static native boolean nativeFillOutputBuffer(long handle, float[] out,
      int numChannels);

  static native boolean nativeFillStereoAndAmbisonicBuffers(long handle,
      float[] ambisonicOut, float[] stereoOut, int ambisonicChannels,
      int stereoChannels);

  static native void nativeSetAmbisonicRotationCompensated(long handle,
      boolean enable);

  static native void nativeSetSourcePosition(long handle, int sourceId,
      float x, float y, float z);

  static native void nativeSetSourceRotation(long handle, int sourceId,
      float x, float y, float z, float w);

  static native void nativeSetSourceVolume(long handle, int sourceId,
      float volume);

  static native void nativeSetSourceDistanceModel(long handle, int sourceId,
      int rolloffModel, float minDistance, float maxDistance);

  static native void nativeSetSourceDistanceAttenuation(long handle,
      int sourceId, float attenuation);

  static native void nativeSetSourceRoomEffectsGain(long handle, int sourceId,
      float gain);

  static native void nativeSetSoundObjectDirectivity(long handle, int sourceId,
      float alpha, float order);

  static native void nativeSetSoundObjectListenerDirectivity(long handle,
      int sourceId, float alpha, float order);

  static native void nativeSetSoundObjectSpread(long handle, int sourceId,
      float spreadDeg);

  static native void nativeSetSoundObjectOcclusionIntensity(long handle,
      int sourceId, float intensity);

  static native void nativeSetSoundObjectNearFieldEffectGain(long handle,
      int sourceId, float gain);

  static native void nativeEnableRoomEffects(long handle, boolean enable);

  static native void nativeSetRoomModel(long handle, float[] position,
      float[] rotation, float[] dimensions, int[] materials,
      float reflectionScalar, float reverbGain, float reverbTime,
      float reverbBrightness, boolean enable);
}

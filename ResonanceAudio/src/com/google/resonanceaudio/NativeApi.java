package com.google.resonanceaudio;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Declares the native (JNI) methods implemented in the Resonance Audio native
 * renderer and takes care of loading the correct platform library. Not part of
 * the public API.
 */
final class NativeApi {

  private static final String LIBRARY_NAME = "resonanceaudio_jni";
  private static final String[] SUPPORTED_PLATFORMS = {
      "windows-amd64",
      "windows-arm64",
      "macos-x86_64",
      "macos-aarch64",
      "linux-amd64",
      "linux-aarch64"
  };

  static {
    boolean loaded = false;
    try {
      // Processing puts library/<platform>-<arch> on java.library.path.
      System.loadLibrary(LIBRARY_NAME);
      loaded = true;
    } catch (UnsatisfiedLinkError e) {
      String platform = nativePlatform();
      String libraryFile = nativeLibraryFileName(platform);
      String resource = "/native/" + platform + "/" + libraryFile;
      try (InputStream in = NativeApi.class.getResourceAsStream(resource)) {
        if (in != null) {
          File tmp = File.createTempFile(LIBRARY_NAME, librarySuffix(platform));
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
        throw new UnsatisfiedLinkError("Could not extract bundled "
            + libraryFile + " for " + platform + ": " + ex);
      }
      if (!loaded) {
        throw new UnsatisfiedLinkError("Could not load " + libraryFile
            + " (native Resonance Audio renderer) for " + platform
            + ". Supported platforms: " + String.join(", ", SUPPORTED_PLATFORMS));
      }
    }
  }

  private static String nativePlatform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    if (os.contains("win")) {
      return isX64(arch) ? "windows-amd64"
          : isArm64(arch) ? "windows-arm64" : "windows-" + arch;
    }
    if (os.contains("mac") || os.contains("darwin")) {
      return isX64(arch) ? "macos-x86_64"
          : isArm64(arch) ? "macos-aarch64" : "macos-" + arch;
    }
    if (os.contains("linux")) {
      return isX64(arch) ? "linux-amd64"
          : isArm64(arch) ? "linux-aarch64" : "linux-" + arch;
    }
    return os.replaceAll("[^a-z0-9]+", "-") + "-" + arch;
  }

  private static boolean isX64(String arch) {
    return arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
  }

  private static boolean isArm64(String arch) {
    return arch.equals("aarch64") || arch.equals("arm64");
  }

  private static String nativeLibraryFileName(String platform) {
    if (platform.startsWith("windows-")) {
      return LIBRARY_NAME + ".dll";
    }
    if (platform.startsWith("macos-")) {
      return "lib" + LIBRARY_NAME + ".dylib";
    }
    return "lib" + LIBRARY_NAME + ".so";
  }

  private static String librarySuffix(String platform) {
    if (platform.startsWith("windows-")) {
      return ".dll";
    }
    if (platform.startsWith("macos-")) {
      return ".dylib";
    }
    return ".so";
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

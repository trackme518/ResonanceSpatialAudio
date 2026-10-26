package com.google.resonanceaudio;

/**
 * A virtual sound source (sound object, ambisonic soundfield or stereo
 * passthrough) inside a {@link ResonanceAudio} renderer. Obtained via
 * {@link ResonanceAudio#addSoundObjectSource(RenderingMode)} and friends.
 *
 * <p>Port of the Android SDK's SoundSource class (cone parameters are not
 * available in the desktop C++ API and are therefore omitted).</p>
 */
public class SoundSource implements AutoCloseable {

  private final ResonanceAudio parent;
  private final int id;
  private boolean closed = false;

  SoundSource(ResonanceAudio parent, int id) {
    this.parent = parent;
    this.id = id;
  }

  /** The native source id (mirrors the Android SDK). */
  public int getId() {
    return id;
  }

  /**
   * Feeds one block of interleaved float audio into this source. The buffer
   * must contain exactly
   * {@code parent.getFramesPerBuffer() * numInputChannels} samples.
   *
   * @param interleaved  interleaved float audio in [-1, 1]
   * @param numInputChannels 1 (mono) or 2 (stereo); for ambisonic sources
   *                     the number of ambisonic channels, e.g. 16
   */
  public void send(float[] interleaved, int numInputChannels) {
    if (parent.isDisposed()) {
      return;
    }
    NativeApi.nativeSetSourceBuffer(parent.getHandle(), id, interleaved,
        numInputChannels);
  }

  /** Sets the source position in world space (meters). */
  public void setPosition(float x, float y, float z) {
    NativeApi.nativeSetSourcePosition(parent.getHandle(), id, x, y, z);
  }

  /** Sets the source position in world space, array form {x, y, z}. */
  public void setPosition(float[] xyz) {
    setPosition(xyz[0], xyz[1], xyz[2]);
  }

  /** Sets the source orientation (quaternion x, y, z, w). */
  public void setOrientation(float x, float y, float z, float w) {
    NativeApi.nativeSetSourceRotation(parent.getHandle(), id, x, y, z, w);
  }

  /** Linear source volume, 1.0 = unity. */
  public void setVolume(float volume) {
    NativeApi.nativeSetSourceVolume(parent.getHandle(), id, volume);
  }

  /** Distance rolloff model with minimum and maximum distance in meters. */
  public void setDistanceRolloff(DistanceRolloffModel model, float minDistance,
      float maxDistance) {
    NativeApi.nativeSetSourceDistanceModel(parent.getHandle(), id,
        model.ordinal(), minDistance, maxDistance);
  }

  /**
   * Explicit distance attenuation factor in [0, 1]. Only takes effect if the
   * rolloff model is {@link DistanceRolloffModel#NONE}.
   */
  public void setDistanceAttenuation(float attenuation) {
    NativeApi.nativeSetSourceDistanceAttenuation(parent.getHandle(), id,
        attenuation);
  }

  /**
   * Source radiation pattern (directivity).
   *
   * @param alpha balance between figure-of-eight and circular pattern [0, 1]
   *              (0.5 = cardioid)
   * @param order width control, higher = narrower, >= 1
   */
  public void setDirectivity(float alpha, float order) {
    NativeApi.nativeSetSoundObjectDirectivity(parent.getHandle(), id, alpha,
        order);
  }

  /** Listener pickup pattern with respect to this source. */
  public void setListenerDirectivity(float alpha, float order) {
    NativeApi.nativeSetSoundObjectListenerDirectivity(parent.getHandle(), id,
        alpha, order);
  }

  /** Simulated source size / spread in degrees, [0, 360]. */
  public void setSpread(float spreadDegrees) {
    NativeApi.nativeSetSoundObjectSpread(parent.getHandle(), id,
        spreadDegrees);
  }

  /** Occlusion intensity (low-pass filtering), >= 0. */
  public void setOcclusion(float intensity) {
    NativeApi.nativeSetSoundObjectOcclusionIntensity(parent.getHandle(), id,
        intensity);
  }

  /** Near-field effect (proximity) gain, [0, 9]. */
  public void setNearFieldEffectGain(float gain) {
    NativeApi.nativeSetSoundObjectNearFieldEffectGain(parent.getHandle(), id,
        gain);
  }

  /** Room effects (reflections + reverb) contribution for this source. */
  public void setRoomEffectsGain(float gain) {
    NativeApi.nativeSetSourceRoomEffectsGain(parent.getHandle(), id, gain);
  }

  /** Removes this source from its renderer. */
  @Override
  public void close() {
    if (!closed) {
      if (!parent.isDisposed()) {
        parent.removeSoundSource(this);
      }
      closed = true;
    }
  }
}

package com.google.resonanceaudio;

/**
 * Java port of the Google Resonance Audio SDK (originally written for Android)
 * for use in desktop Processing sketches. It wraps the Resonance Audio C++
 * renderer through JNI.
 *
 * <p>Unlike the Android SDK (which pulls audio from an internal audio thread),
 * this port uses a push/pull buffer model suited to Processing's audio
 * callbacks: feed each {@link SoundSource} with a block of interleaved float
 * audio via {@link SoundSource#send(float[], int)}, then render one block of
 * spatialized output via {@link #fillOutputBuffer(float[])}.</p>
 *
 * <p>Coordinate system (same as the Resonance Audio SDK): right-handed, Y-up,
 * with the listener looking along the negative Z axis by default. Units are
 * meters.</p>
 *
 * <pre>
 * ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
 * options.numFramesPerBuffer = 512;
 * options.sampleRate = 48000;
 * ResonanceAudio ra = new ResonanceAudio(options);  // 2-ch binaural output
 * SoundSource src = ra.addSoundObjectSource(RenderingMode.BINAURAL_HIGH_QUALITY);
 * src.setPosition(1, 0, -2);
 * src.send(interleavedMonoBlock, 1);
 * float[] out = new float[512 * ra.getOutputChannels()];
 * ra.fillOutputBuffer(out);
 * </pre>
 */
public class ResonanceAudio {

  /** Number of output channels for binaural (headphone) rendering. */
  public static final int OUTPUT_BINAURAL = 2;

  /**
   * Number of output channels for third order ambisonics B-format
   * (ACN ordering, SN3D normalization, 16 channels). No decoder is applied;
   * the raw ambisonic soundfield is written to the output buffer.
   *
   * <p>Note: this is a source-level extension of the public ResonanceAudioApi
   * (see PATCH (HRTF_resonance) markers in the native sources): the API now
   * exposes the intermediate ambisonic buffer of the renderer, which the
   * stock Android/desktop API does not (it only documents Ambisonics as an
   * internal representation for binaural rendering). B-format output is
   * available from every renderer instance, in parallel with the decoded
   * binaural stereo output (see {@link #fillOutputBuffers(float[], float[])}).</p>
   */
  public static final int OUTPUT_AMBISONIC_3RD_ORDER = 16;

  /** First order ambisonics B-format output (4 channels, ACN/SN3D). */
  public static final int OUTPUT_AMBISONIC_1ST_ORDER = 4;

  /** Second order ambisonics B-format output (9 channels, ACN/SN3D). */
  public static final int OUTPUT_AMBISONIC_2ND_ORDER = 9;

  /** Ambisonic B-format channel count (third order, ACN/SN3D). */
  public static final int AMBISONIC_CHANNELS = 16;

  /**
   * Output mode flag for {@link InitOptions#numOutputChannels}: decode the
   * renderer's world-space B-format output to loudspeakers with a
   * {@link SpeakerDecoder} (precomputed matrix from an IEM AllRADecoder /
   * MatrixDecoder JSON export). The number of output channels is then the
   * decoder's loudspeaker count; create the instance with
   * {@link #ResonanceAudio(InitOptions, SpeakerDecoder)}.
   */
  public static final int OUTPUT_DECODER = 1;

  /** Initialization parameters, mirrors the Android SDK's InitOptions. */
  public static class InitOptions {
    /** Number of frames per processing block (32..1024, typically 512). */
    public int numFramesPerBuffer = 512;
    /** System sample rate in Hz (must match the audio output device). */
    public int sampleRate = 48000;
    /**
     * Primary output mode: {@link #OUTPUT_BINAURAL} (2) for headphone
     * rendering, {@link #OUTPUT_AMBISONIC_3RD_ORDER} (16) for raw third
     * order ambisonic B-format output, or {@link #OUTPUT_DECODER} (1) to
     * decode the B-format output to loudspeakers via a {@link
     * SpeakerDecoder} (use the two-argument constructor). This value
     * determines which output a plain {@link #fillOutputBuffer(float[])}
     * call and the JSyn {@link ResonanceAudioEngine} use; note that every
     * instance can produce <em>both</em> binaural and B-format output via
     * {@link #fillOutputBuffers(float[], float[])}.
     */
    public int numOutputChannels = OUTPUT_BINAURAL;
  }

  private long handle;
  private final int framesPerBuffer;
  private final int sampleRate;
  private final int outputChannels;
  private final int ambisonicChannels;
  private final SpeakerDecoder decoder;
  private final float[] ambisonicScratch;

  /** Listener orientation as quaternion (x, y, z, w). */
  private float orX = 0, orY = 0, orZ = 0, orW = 1;

  public ResonanceAudio(InitOptions options) {
    this(options, null);
  }

  private static boolean isValidAmbisonicChannels(int ch) {
    int side = (int) Math.round(Math.sqrt(ch));
    return side >= 2 && side <= 4 && side * side == ch;
  }

  /**
   * Creates a renderer whose {@link #fillOutputBuffer(float[])} output is
   * decoded to loudspeakers: the internal renderer runs in ambisonic
   * world-space B-format mode (order matches the decoder's input channel
   * count: 4/9/16 channels = 1st/2nd/3rd order) and every rendered block is
   * immediately multiplied with the decoder matrix, so the effective output
   * has {@code decoder.getNumChannels()} channels. B-format and binaural
   * outputs remain available via {@link #fillAmbisonicBuffer(float[])} and
   * {@link #fillBinauralBuffer(float[])}.
   *
   * @param options  init options with
   *            {@code numOutputChannels = OUTPUT_DECODER}
   * @param decoder  precomputed decoding matrix
   */
  public ResonanceAudio(InitOptions options, SpeakerDecoder decoder) {
    int ch = options.numOutputChannels;
    boolean plainValid = ch == OUTPUT_BINAURAL || isValidAmbisonicChannels(ch);
    if (!plainValid && ch != OUTPUT_DECODER) {
      throw new IllegalArgumentException(
          "numOutputChannels must be OUTPUT_BINAURAL (2), "
              + "OUTPUT_AMBISONIC_1ST/2ND/3RD_ORDER (4/9/16) or "
              + "OUTPUT_DECODER (1)");
    }
    if (decoder == null && ch == OUTPUT_DECODER) {
      throw new IllegalArgumentException(
          "OUTPUT_DECODER requires the ResonanceAudio(options, decoder) "
              + "constructor");
    }
    this.decoder = decoder;
    int nativeChannels;
    if (decoder != null) {
      nativeChannels = decoder.getNumInputChannels();
    } else if (ch == OUTPUT_BINAURAL) {
      nativeChannels = OUTPUT_BINAURAL;
    } else {
      nativeChannels = ch;
    }
    // Instances created in binaural mode still expose 3rd order B-format
    // via fillAmbisonicBuffer()/fillOutputBuffers().
    this.ambisonicChannels =
        nativeChannels == OUTPUT_BINAURAL ? OUTPUT_AMBISONIC_3RD_ORDER
                                          : nativeChannels;
    this.framesPerBuffer = options.numFramesPerBuffer;
    this.sampleRate = options.sampleRate;
    this.outputChannels =
        decoder != null ? decoder.getNumChannels() : nativeChannels;
    this.ambisonicScratch = decoder != null
        ? new float[framesPerBuffer * nativeChannels] : null;
    this.handle = NativeApi.nativeCreate(nativeChannels, framesPerBuffer,
        sampleRate);
    if (handle == 0) {
      throw new IllegalStateException("Could not create Resonance Audio "
          + "renderer instance (invalid options?)");
    }
  }

  public int getFramesPerBuffer() {
    return framesPerBuffer;
  }

  public int getSampleRate() {
    return sampleRate;
  }

  /**
   * 2 for binaural output, 16 for third order ambisonics B-format, or the
   * loudspeaker count of the {@link SpeakerDecoder} in decoder mode.
   */
  public int getOutputChannels() {
    return outputChannels;
  }

  /** The speaker decoder of this instance, or null if none. */
  public SpeakerDecoder getDecoder() {
    return decoder;
  }

  /**
   * Number of ambisonic B-format channels this instance exposes via
   * {@link #fillAmbisonicBuffer(float[])} and
   * {@link #fillOutputBuffers(float[], float[])}: 4, 9 or 16.
   */
  public int getAmbisonicChannels() {
    return ambisonicChannels;
  }

  // ---------------------------------------------------------------- sources

  /**
   * Adds a new sound object source (a mono or stereo audio stream that is
   * spatialized at a virtual 3D position).
   */
  public SoundSource addSoundObjectSource(RenderingMode mode) {
    int id = NativeApi.nativeCreateSoundObjectSource(handle, mode.ordinal());
    if (id < 0) {
      throw new IllegalStateException("Could not create sound object source");
    }
    return new SoundSource(this, id);
  }

  /**
   * Adds a new ambisonic soundfield input source. The input is rotated
   * according to the listener's head rotation and (in ambisonic output mode)
   * mixed into the ambisonic output.
   *
   * @param numChannels (order + 1)^2, e.g. 4 (1st), 9 (2nd) or 16 (3rd) order
   */
  public SoundSource addAmbisonicSource(int numChannels) {
    int id = NativeApi.nativeCreateAmbisonicSource(handle, numChannels);
    if (id < 0) {
      throw new IllegalStateException("Could not create ambisonic source");
    }
    return new SoundSource(this, id);
  }

  /**
   * Adds a non-spatialized mono/stereo source that is passed directly to the
   * (stereo) output. Only available with binaural output mode.
   */
  public SoundSource addStereoSource(int numChannels) {
    int id = NativeApi.nativeCreateStereoSource(handle, numChannels);
    if (id < 0) {
      throw new IllegalStateException("Could not create stereo source");
    }
    return new SoundSource(this, id);
  }

  /** Removes a previously added source. */
  public void removeSoundSource(SoundSource source) {
    NativeApi.nativeDestroySource(handle, source.getId());
  }

  // --------------------------------------------------------------- listener

  /**
   * Sets the listener's head position and orientation. This is the main
   * per-frame update call (analogous to Android's ResonanceAudio.update()).
   *
   * @param lx    listener head position X (world space, meters)
   * @param ly    listener head position Y
   * @param lz    listener head position Z
   * @param fx    forward (view) direction X
   * @param fy    forward direction Y
   * @param fz    forward direction Z
   * @param ux    up direction X
   * @param uy    up direction Y
   * @param uz    up direction Z
   */
  public void update(float lx, float ly, float lz, float fx, float fy,
      float fz, float ux, float uy, float uz) {
    NativeApi.nativeSetHeadPosition(handle, lx, ly, lz);
    quaternionFromForwardUp(fx, fy, fz, ux, uy, uz);
    NativeApi.nativeSetHeadRotation(handle, orX, orY, orZ, orW);
  }

  /** Sets only the listener's head position. */
  public void setListenerPosition(float x, float y, float z) {
    NativeApi.nativeSetHeadPosition(handle, x, y, z);
  }

  /** Sets only the listener's head orientation (quaternion x, y, z, w). */
  public void setListenerOrientation(float x, float y, float z, float w) {
    orX = x;
    orY = y;
    orZ = z;
    orW = w;
    NativeApi.nativeSetHeadRotation(handle, x, y, z, w);
  }

  /** Master output volume, 1.0 = unity. */
  public void setMasterVolume(float volume) {
    NativeApi.nativeSetMasterVolume(handle, volume);
  }

  /**
   * Enables the stereo speaker mode (disables HRTF filtering, uses cheaper
   * stereo panning). Only meaningful in binaural output mode.
   */
  public void setStereoSpeakerMode(boolean enabled) {
    NativeApi.nativeSetStereoSpeakerMode(handle, enabled);
  }

  // ------------------------------------------------------------ room model

  /**
   * Configures and enables the shoebox room model (early reflections + late
   * reverberation), mirroring the Android SDK's setRoomModel().
   *
   * @param surfaces  six surface materials, ordered
   *                  {left, right, bottom, top, front, back}
   * @param position  room center position {x, y, z} in world space
   * @param rotation  room rotation quaternion {x, y, z, w}
   * @param dimensions room size {width, height, depth} in meters
   * @param reflectionScalar scales the early reflections gain, [0, 1]
   * @param reverbGain scales the late reverberation gain, [0, inf)
   * @param reverbTime scales the late reverberation time, (0, inf)
   * @param reverbBrightness late reverberation brightness, [-1, 1]
   */
  public void setRoomModel(Surface[] surfaces, float[] position,
      float[] rotation, float[] dimensions, float reflectionScalar,
      float reverbGain, float reverbTime, float reverbBrightness) {
    if (surfaces.length != 6 || position.length != 3 || rotation.length != 4
        || dimensions.length != 3) {
      throw new IllegalArgumentException("Invalid room model arguments");
    }
    int[] materials = new int[6];
    for (int i = 0; i < 6; i++) {
      materials[i] = surfaces[i].value;
    }
    NativeApi.nativeSetRoomModel(handle, position, rotation, dimensions,
        materials, reflectionScalar, reverbGain, reverbTime, reverbBrightness,
        true);
  }

  /** Convenience overload using one uniform material for all six surfaces. */
  public void setRoomModel(Surface material, float[] position,
      float[] rotation, float[] dimensions, float reflectionScalar,
      float reverbGain, float reverbTime, float reverbBrightness) {
    setRoomModel(new Surface[] { material, material, material, material,
        material, material }, position, rotation, dimensions, reflectionScalar,
        reverbGain, reverbTime, reverbBrightness);
  }

  /** Turns the room effects on/off without changing the room model. */
  public void setRoomEffectsEnabled(boolean enable) {
    NativeApi.nativeEnableRoomEffects(handle, enable);
  }

  // --------------------------------------------------------------- rendering

  /**
   * Renders one block of spatialized audio using the primary output mode
   * selected in {@link InitOptions#numOutputChannels} -- binaural stereo,
   * raw B-format, or (in decoder mode) the world-space B-format immediately
   * decoded to loudspeakers via the {@link SpeakerDecoder}. Call this once
   * per audio block, after all sources have been fed via
   * {@link SoundSource#send(float[], int)}. Each call advances the rendering
   * graph by one block.
   *
   * @param interleavedOut interleaved float buffer of size
   *            {@code getFramesPerBuffer() * getOutputChannels()}
   * @return true if a valid output buffer was rendered
   */
  public boolean fillOutputBuffer(float[] interleavedOut) {
    if (decoder == null) {
      return NativeApi.nativeFillOutputBuffer(handle, interleavedOut,
          outputChannels);
    }
    if (interleavedOut.length
        != framesPerBuffer * decoder.getNumChannels()) {
      throw new IllegalArgumentException("Output buffer must hold "
          + framesPerBuffer + " frames x " + decoder.getNumChannels()
          + " decoder channels");
    }
    if (!NativeApi.nativeFillOutputBuffer(handle, ambisonicScratch,
        ambisonicChannels)) {
      return false;
    }
    decoder.decodeInterleaved(ambisonicScratch, interleavedOut);
    return true;
  }

  /**
   * Renders one block and writes the decoded binaural stereo output (2
   * channels, HRTF-filtered, ready for headphones).
   */
  public boolean fillBinauralBuffer(float[] interleavedOut) {
    return NativeApi.nativeFillOutputBuffer(handle, interleavedOut,
        OUTPUT_BINAURAL);
  }

  /**
   * Renders one block and writes the undecoded ambisonic B-format soundfield
   * (ACN order, SN3D normalization) directly from the renderer's intermediate
   * ambisonic buffer -- no binaural decoder is applied, so the result can be
   * fed to an external ambisonic decoder for loudspeaker playback. The number
   * of channels is {@link #getAmbisonicChannels()} (4/9/16 = 1st/2nd/3rd
   * order): the renderer always sums the full third-order soundfield and the
   * leading (order + 1)^2 components are written, which is numerically
   * identical to a native encoding at the requested order. Unless disabled
   * via {@link #setAmbisonicListenerRotationCompensated(boolean)}, the
   * soundfield is expressed in world space (i.e. before rotation for the
   * listener's head orientation).
   */
  public boolean fillAmbisonicBuffer(float[] interleavedOut) {
    if (interleavedOut.length != framesPerBuffer * ambisonicChannels) {
      throw new IllegalArgumentException("Buffer must hold " + framesPerBuffer
          + " frames x " + ambisonicChannels + " ambisonic channels");
    }
    return NativeApi.nativeFillOutputBuffer(handle, interleavedOut,
        ambisonicChannels);
  }

  /**
   * Renders one block and writes BOTH outputs from a single graph pass: the
   * decoded binaural stereo signal and the undecoded ambisonic B-format
   * soundfield. Either argument may be null to skip that output. This is the
   * only way to obtain both outputs for the same audio block without running
   * the (expensive) rendering graph twice.
   *
   * @param ambisonicOut interleaved buffer of size
   *            {@code getFramesPerBuffer() * getAmbisonicChannels()}, or null
   * @param stereoOut  interleaved 2-channel buffer of size
   *            {@code getFramesPerBuffer() * 2}, or null
   */
  public boolean fillOutputBuffers(float[] ambisonicOut, float[] stereoOut) {
    return NativeApi.nativeFillStereoAndAmbisonicBuffers(handle, ambisonicOut,
        stereoOut, ambisonicChannels, OUTPUT_BINAURAL);
  }

  /**
   * Enables/disables listener rotation compensation on the ambisonic
   * B-format output (enabled by default).
   *
   * <p>When enabled (default), the ambisonic output is independent of the
   * listener's head orientation: the soundfield is fixed in world/room space
   * ("pre-rotation" B-format), which is what a fixed loudspeaker-array
   * decoder expects. When disabled, the ambisonic output is listener-relative
   * (rotated by the inverse head orientation like the internal binaural
   * pipeline).</p>
   *
   * <p>Note that the listener's head <em>position</em> is always applied --
   * only the orientation compensation is toggled by this switch. Room
   * effects (reflections/reverb) are mixed into the binaural stereo bus only
   * and are not part of the ambisonic output. Has no effect on the binaural
   * output.</p>
   */
  public void setAmbisonicListenerRotationCompensated(boolean enable) {
    NativeApi.nativeSetAmbisonicRotationCompensated(handle, enable);
  }

  long getHandle() {
    return handle;
  }

  /** True once dispose() has released the native renderer. */
  public boolean isDisposed() {
    return handle == 0;
  }

  /** Releases all native resources. */
  public void dispose() {
    if (handle != 0) {
      NativeApi.nativeDestroy(handle);
      handle = 0;
    }
  }

  // ------------------------------------------------------------------ math

  /**
   * Converts forward and up vectors into a head rotation quaternion
   * (port of the Android SDK's internal look-at quaternion computation).
   * Resonance Audio's world space is right-handed with the listener's local
   * axes X = right, Y = up, -Z = forward.
   */
  private void quaternionFromForwardUp(float fx, float fy, float fz,
      float ux, float uy, float uz) {
    // Normalize forward.
    float flen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
    if (flen < 1e-6f) {
      fx = 0; fy = 0; fz = -1; flen = 1;
    }
    fx /= flen; fy /= flen; fz /= flen;

    // right = normalize(cross(forward, up))
    float rx = fy * uz - fz * uy;
    float ry = fz * ux - fx * uz;
    float rz = fx * uy - fy * ux;
    float rlen = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
    if (rlen < 1e-6f) { // up parallel to forward, pick arbitrary right
      rx = 1; ry = 0; rz = 0; rlen = 1;
    }
    rx /= rlen; ry /= rlen; rz /= rlen;

    // true up = cross(right, forward)
    float tx = ry * fz - rz * fy;
    float ty = rz * fx - rx * fz;
    float tz = rx * fy - ry * fx;

    // back = -forward
    float bx = -fx, by = -fy, bz = -fz;

    // Rotation matrix columns: X = right, Y = true up, Z = back.
    // m[row][col]
    float m00 = rx, m01 = bx, m02 = tx;
    float m10 = ry, m11 = by, m12 = ty;
    float m20 = rz, m21 = bz, m22 = tz;

    float trace = m00 + m11 + m22;
    float qw, qx, qy, qz;
    if (trace > 0) {
      float s = (float) Math.sqrt(trace + 1.0f) * 2;
      qw = 0.25f * s;
      qx = (m21 - m12) / s;
      qy = (m02 - m20) / s;
      qz = (m10 - m01) / s;
    } else if (m00 > m11 && m00 > m22) {
      float s = (float) Math.sqrt(1.0f + m00 - m11 - m22) * 2;
      qw = (m21 - m12) / s;
      qx = 0.25f * s;
      qy = (m01 + m10) / s;
      qz = (m02 + m20) / s;
    } else if (m11 > m22) {
      float s = (float) Math.sqrt(1.0f + m11 - m00 - m22) * 2;
      qw = (m02 - m20) / s;
      qx = (m01 + m10) / s;
      qy = 0.25f * s;
      qz = (m12 + m21) / s;
    } else {
      float s = (float) Math.sqrt(1.0f + m22 - m00 - m11) * 2;
      qw = (m10 - m01) / s;
      qx = (m02 + m20) / s;
      qy = (m12 + m21) / s;
      qz = 0.25f * s;
    }
    orX = qx; orY = qy; orZ = qz; orW = qw;
  }
}

package com.google.resonanceaudio;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Speaker decoder built from a precomputed decoding matrix, as exported by
 * the IEM Plug-in Suite (AllRADecoder / MatrixDecoder) or compatible tools:
 * a JSON file whose <code>Decoder.Matrix</code> is a
 * <code>numSpeakers x numInputChannels</code> float matrix (1st/2nd/3rd
 * order = 4/9/16 input channels) that maps Ambisonics B-format (ACN ordering,
 * SN3D normalization, Condon-Shortley phase omitted -- exactly the format
 * produced by {@link ResonanceAudio#fillAmbisonicBuffer(float[])} in
 * world-space mode) to loudspeaker feeds. Weights (e.g. maxRE) are expected
 * to be already baked into the matrix.
 *
 * <p>Optional IEM fields supported in the {@code Decoder} object:</p>
 * <ul>
 *   <li>{@code Routing}: 1-based physical output channel per matrix row
 *       (defaults to {@code 1..numSpeakers}).</li>
 *   <li>{@code SubwooferChannel}: 1-based physical output channel for a
 *       discrete subwoofer (bass-managed via a 4th-order Linkwitz-Riley
 *       crossover when used with {@link ResonanceAudio}). Must not collide
 *       with any routed loudspeaker channel.</li>
 *   <li>{@code SubwooferCrossoverHz}: optional default crossover frequency in
 *       Hz (default 80 Hz).</li>
 *   <li>{@code SubwooferGainDb}: optional default subwoofer trim in dB
 *       (default 0 dB).</li>
 * </ul>
 *
 * <p>Example:</p>
 * <pre>
 * SpeakerDecoder decoder = new SpeakerDecoder(dataPath("decoder_default.json"));
 * ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
 * options.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
 * ResonanceAudio ra = new ResonanceAudio(options, decoder);
 * float[] speakers = new float[ra.getFramesPerBuffer() * ra.getOutputChannels()];
 * ra.fillOutputBuffer(speakers); // 16-ch B-format decoded to speakers
 * </pre>
 */
public class SpeakerDecoder {

  /** Default bass-management crossover frequency in Hz (matches IEM SimpleDecoder). */
  public static final float DEFAULT_SUBWOOFER_CROSSOVER_HZ = 80.0f;

  /** Default subwoofer gain trim in dB. */
  public static final float DEFAULT_SUBWOOFER_GAIN_DB = 0.0f;

  private final String name;
  private final int numInputChannels;
  private final int order;
  private final int numChannels;       // matrix rows (loudspeaker count)
  private final int numOutputChannels; // physical output channels required
  private final float[] matrix;        // row-major [numChannels][numInputChannels]
  private final int[] routing;         // 1-based device channel per row, or null
  private final int[] effectiveRouting; // 0-based device channel per row
  private final boolean identityRouting;
  private final int subwooferChannel;  // 1-based device channel, or -1 if none
  private final float subwooferBaseGain;
  private final float subwooferCrossoverHz;
  private final float subwooferGainDb;
  private final float[] azimuths;   // deg, real speakers only (may be empty)
  private final float[] elevations; // deg, real speakers only (may be empty)

  /** Loads and validates a decoder JSON file (IEM export format). */
  public SpeakerDecoder(String jsonPath) throws IOException {
    this(jsonPath, null);
  }

  /**
   * Loads and validates a decoder JSON file and overrides its discrete
   * subwoofer channel.
   *
   * @param jsonPath         path to the IEM decoder JSON file
   * @param subwooferChannel 1-based physical output channel for the discrete
   *                         subwoofer, or {@code <= 0} (e.g. {@code -1}) to
   *                         disable subwoofer output
   */
  public SpeakerDecoder(String jsonPath, int subwooferChannel)
      throws IOException {
    this(jsonPath, Integer.valueOf(subwooferChannel));
  }

  @SuppressWarnings("unchecked")
  private SpeakerDecoder(String jsonPath, Integer subwooferOverride)
      throws IOException {
    String text = new String(Files.readAllBytes(Paths.get(jsonPath)),
        Charset.forName("UTF-8"));
    Object root = MiniJson.parse(text);
    if (!(root instanceof Map)) {
      throw new IllegalArgumentException("Not a JSON object: " + jsonPath);
    }
    Map<String, Object> top = (Map<String, Object>) root;
    name = stringOrDefault(top.get("Name"), "decoder");
    Object decoderObj = top.get("Decoder");
    if (!(decoderObj instanceof Map)) {
      throw new IllegalArgumentException(
          "JSON has no 'Decoder' object: " + jsonPath);
    }
    Map<String, Object> decoder = (Map<String, Object>) decoderObj;

    Object inChans = decoder.get("NumberOfInputChannels");
    if (!(inChans instanceof Double)) {
      throw new IllegalArgumentException(
          "Decoder has no 'NumberOfInputChannels'");
    }
    numInputChannels = ((Double) inChans).intValue();
    int side = (int) Math.round(Math.sqrt(numInputChannels));
    if (side < 2 || side > 4 || side * side != numInputChannels) {
      throw new IllegalArgumentException("Decoder expects "
          + numInputChannels + " input channels, but only 1st/2nd/3rd order "
          + "ambisonics (4, 9 or 16 channels) are supported.");
    }
    order = side - 1;

    String norm = stringOrDefault(decoder.get("Normalization"), "SN3D");
    if (!norm.equals("SN3D")) {
      throw new IllegalArgumentException(
          "Decoder normalization is '" + norm
              + "', but the renderer outputs SN3D (AmbiX).");
    }
    String ordering = stringOrDefault(decoder.get("Ordering"), "ACN");
    if (!ordering.equals("ACN")) {
      throw new IllegalArgumentException(
          "Decoder channel ordering is '" + ordering
              + "', but the renderer outputs ACN (AmbiX).");
    }
    Object csPhase = decoder.get("CondonShortleyPhase");
    if (csPhase instanceof Boolean && (Boolean) csPhase) {
      throw new IllegalArgumentException(
          "Decoder uses the Condon-Shortley phase, the renderer does not.");
    }

    Object matrixObj = decoder.get("Matrix");
    if (!(matrixObj instanceof List)) {
      throw new IllegalArgumentException("Decoder has no 'Matrix'");
    }
    List<Object> rows = (List<Object>) matrixObj;
    numChannels = rows.size();
    if (numChannels == 0) {
      throw new IllegalArgumentException("Decoder 'Matrix' has no rows");
    }
    matrix = new float[numChannels * numInputChannels];
    for (int r = 0; r < numChannels; r++) {
      Object rowObj = rows.get(r);
      if (!(rowObj instanceof List)) {
        throw new IllegalArgumentException("Matrix row " + r + " is not an array");
      }
      List<Object> row = (List<Object>) rowObj;
      if (row.size() != numInputChannels) {
        throw new IllegalArgumentException("Matrix row " + r + " has "
            + row.size() + " entries, expected " + numInputChannels);
      }
      for (int c = 0; c < numInputChannels; c++) {
        matrix[r * numInputChannels + c] =
            ((Double) row.get(c)).floatValue();
      }
    }

    Object routingObj = decoder.get("Routing");
    if (routingObj != null) {
      if (!(routingObj instanceof List)) {
        throw new IllegalArgumentException("Decoder 'Routing' must be an array");
      }
      List<Object> routingList = (List<Object>) routingObj;
      if (!routingList.isEmpty()) {
        if (routingList.size() != numChannels) {
          throw new IllegalArgumentException("Decoder 'Routing' has "
              + routingList.size() + " entries, expected " + numChannels
              + " (one per matrix row)");
        }
        routing = new int[numChannels];
        for (int i = 0; i < numChannels; i++) {
          int ch = parseIntValue(routingList.get(i), "Routing[" + i + "]");
          if (ch < 1) {
            throw new IllegalArgumentException(
                "Decoder 'Routing' channel must be >= 1, got " + ch
                    + " at index " + i);
          }
          routing[i] = ch;
        }
      } else {
        routing = null;
      }
    } else {
      routing = null;
    }

    effectiveRouting = new int[numChannels];
    for (int r = 0; r < numChannels; r++) {
      effectiveRouting[r] = routing != null ? (routing[r] - 1) : r;
    }

    int parsedSub = subwooferOverride != null
        ? normalizeSubwooferChannel(subwooferOverride.intValue())
        : parseSubwooferChannel(decoder.get("SubwooferChannel"));
    validateSubwooferCollision(parsedSub, effectiveRouting);
    subwooferChannel = parsedSub;

    subwooferCrossoverHz = parsePositiveFloatOrDefault(
        decoder.get("SubwooferCrossoverHz"), "SubwooferCrossoverHz",
        DEFAULT_SUBWOOFER_CROSSOVER_HZ);
    subwooferGainDb = parseFiniteFloatOrDefault(
        decoder.get("SubwooferGainDb"), "SubwooferGainDb",
        DEFAULT_SUBWOOFER_GAIN_DB);

    numOutputChannels = computeNumOutputChannels(numChannels, effectiveRouting,
        subwooferChannel);
    identityRouting = isIdentityRouting(numChannels, numOutputChannels,
        effectiveRouting, subwooferChannel);
    subwooferBaseGain = computeSubwooferBaseGain(matrix, numChannels,
        numInputChannels);

    // Optional loudspeaker geometry (used only by visualizations).
    float[] az = new float[0];
    float[] el = new float[0];
    Object layoutObj = top.get("LoudspeakerLayout");
    if (layoutObj instanceof Map) {
      Object speakersObj = ((Map<String, Object>) layoutObj).get("Loudspeakers");
      if (speakersObj instanceof List) {
        List<Object> speakers = (List<Object>) speakersObj;
        float[] azTmp = new float[speakers.size()];
        float[] elTmp = new float[speakers.size()];
        int n = 0;
        for (Object sp : speakers) {
          if (sp instanceof Map) {
            Map<String, Object> spm = (Map<String, Object>) sp;
            Object imaginary = spm.get("IsImaginary");
            if (!(imaginary instanceof Boolean) || !(Boolean) imaginary) {
              azTmp[n] = toFloat(spm.get("Azimuth"));
              elTmp[n] = toFloat(spm.get("Elevation"));
              n++;
            }
          }
        }
        az = new float[n];
        el = new float[n];
        System.arraycopy(azTmp, 0, az, 0, n);
        System.arraycopy(elTmp, 0, el, 0, n);
      }
    }
    azimuths = az;
    elevations = el;
  }

  private SpeakerDecoder(SpeakerDecoder src, int newSubwooferChannel) {
    int normalized = normalizeSubwooferChannel(newSubwooferChannel);
    validateSubwooferCollision(normalized, src.effectiveRouting);
    this.name = src.name;
    this.numInputChannels = src.numInputChannels;
    this.order = src.order;
    this.numChannels = src.numChannels;
    this.matrix = src.matrix;
    this.routing = src.routing;
    this.effectiveRouting = src.effectiveRouting;
    this.subwooferChannel = normalized;
    this.numOutputChannels = computeNumOutputChannels(this.numChannels,
        this.effectiveRouting, this.subwooferChannel);
    this.identityRouting = isIdentityRouting(this.numChannels,
        this.numOutputChannels, this.effectiveRouting, this.subwooferChannel);
    this.subwooferBaseGain = src.subwooferBaseGain;
    this.subwooferCrossoverHz = src.subwooferCrossoverHz;
    this.subwooferGainDb = src.subwooferGainDb;
    this.azimuths = src.azimuths;
    this.elevations = src.elevations;
  }

  private static int parseIntValue(Object o, String field) {
    if (!(o instanceof Double)) {
      throw new IllegalArgumentException(
          "Decoder '" + field + "' must be an integer");
    }
    double d = ((Double) o).doubleValue();
    if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
      throw new IllegalArgumentException(
          "Decoder '" + field + "' must be an integer, got " + d);
    }
    return (int) d;
  }

  private static int parseSubwooferChannel(Object o) {
    if (o == null) {
      return -1;
    }
    return normalizeSubwooferChannel(parseIntValue(o, "SubwooferChannel"));
  }

  private static int normalizeSubwooferChannel(int ch) {
    if (ch == -1 || ch == 0) {
      return -1;
    }
    if (ch < -1) {
      throw new IllegalArgumentException(
          "SubwooferChannel must be >= 1 (or -1/0 to disable), got " + ch);
    }
    return ch;
  }

  private static float parsePositiveFloatOrDefault(Object o, String field,
      float fallback) {
    if (o == null) {
      return fallback;
    }
    float v = parseFiniteFloatOrDefault(o, field, fallback);
    if (v <= 0f) {
      throw new IllegalArgumentException(
          "Decoder '" + field + "' must be positive, got " + v);
    }
    return v;
  }

  private static float parseFiniteFloatOrDefault(Object o, String field,
      float fallback) {
    if (o == null) {
      return fallback;
    }
    if (!(o instanceof Double)) {
      throw new IllegalArgumentException(
          "Decoder '" + field + "' must be a number");
    }
    double d = ((Double) o).doubleValue();
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException(
          "Decoder '" + field + "' must be finite");
    }
    return (float) d;
  }

  private static void validateSubwooferCollision(int subChannel,
      int[] effRouting) {
    if (subChannel <= 0) {
      return;
    }
    int subZeroBased = subChannel - 1;
    for (int r = 0; r < effRouting.length; r++) {
      if (effRouting[r] == subZeroBased) {
        throw new IllegalArgumentException("SubwooferChannel " + subChannel
            + " collides with routed loudspeaker channel " + subChannel
            + " (matrix row " + r + "). Update 'Routing' or choose a free "
            + "output channel for the subwoofer.");
      }
    }
  }

  private static int computeNumOutputChannels(int rows, int[] effRouting,
      int subChannel) {
    int maxCh = rows;
    for (int r = 0; r < effRouting.length; r++) {
      int ch = effRouting[r] + 1;
      if (ch > maxCh) {
        maxCh = ch;
      }
    }
    if (subChannel > maxCh) {
      maxCh = subChannel;
    }
    return maxCh;
  }

  private static boolean isIdentityRouting(int rows, int outChans,
      int[] effRouting, int subChannel) {
    if (subChannel > 0 || outChans != rows) {
      return false;
    }
    for (int r = 0; r < effRouting.length; r++) {
      if (effRouting[r] != r) {
        return false;
      }
    }
    return true;
  }

  /**
   * Computes the automatic energy-matching gain from SN3D omni channel W to a
   * single discrete subwoofer. For a unit-amplitude plane wave averaged over
   * all directions on the sphere, SN3D spherical harmonics have mean square
   * {@code 1 / (2 * n + 1)}, so the average total energy radiated by all
   * decoded loudspeakers is {@code sum_{r,c} M[r][c]^2 / (2 * n(c) + 1)}.
   * Matching the single subwoofer's energy to that average keeps low- and
   * high-frequency loudness consistent regardless of matrix scaling or order.
   */
  private static float computeSubwooferBaseGain(float[] mat, int rows,
      int cols) {
    double energySum = 0.0;
    for (int r = 0; r < rows; r++) {
      int mOff = r * cols;
      for (int c = 0; c < cols; c++) {
        int n = (int) Math.floor(Math.sqrt(c));
        double w = mat[mOff + c];
        energySum += (w * w) / (2 * n + 1);
      }
    }
    return (float) Math.sqrt(energySum);
  }

  private static String stringOrDefault(Object o, String fallback) {
    return (o instanceof String) ? (String) o : fallback;
  }

  private static float toFloat(Object o) {
    return (o instanceof Double) ? ((Double) o).floatValue() : 0f;
  }

  /**
   * Returns a copy of this decoder configured with the given 1-based discrete
   * subwoofer channel ({@code <= 0} disables subwoofer output). Validates that
   * the subwoofer channel does not collide with any routed loudspeaker.
   */
  public SpeakerDecoder withSubwooferChannel(int subwooferChannel) {
    return new SpeakerDecoder(this, subwooferChannel);
  }

  /** Human readable decoder name from the JSON file. */
  public String getName() {
    return name;
  }

  /** Number of loudspeaker feeds (matrix rows, excluding subwoofer). */
  public int getNumChannels() {
    return numChannels;
  }

  /**
   * Number of physical output channels required by this decoder, accounting
   * for {@code Routing} indices and any configured {@code SubwooferChannel}.
   * Equals {@link #getNumChannels()} when no subwoofer is configured and
   * routing is 1..N.
   */
  public int getNumOutputChannels() {
    return numOutputChannels;
  }

  /** Ambisonic input channels this decoder requires (4, 9 or 16). */
  public int getNumInputChannels() {
    return numInputChannels;
  }

  /** Ambisonic order this decoder expects (1, 2 or 3). */
  public int getOrder() {
    return order;
  }

  /**
   * Optional per-row device channel (1-based) from the JSON 'Routing' field,
   * or null if absent.
   */
  public int[] getRouting() {
    return routing == null ? null : routing.clone();
  }

  /**
   * Effective per-row physical device channel (1-based). Returns the JSON
   * 'Routing' values if present, or {@code [1, 2, ..., getNumChannels()]}.
   */
  public int[] getEffectiveRouting() {
    int[] out = new int[numChannels];
    for (int r = 0; r < numChannels; r++) {
      out[r] = effectiveRouting[r] + 1;
    }
    return out;
  }

  /** True if a discrete subwoofer output channel is configured. */
  public boolean hasSubwoofer() {
    return subwooferChannel > 0;
  }

  /**
   * 1-based physical output channel for the discrete subwoofer, or {@code -1}
   * if none is configured.
   */
  public int getSubwooferChannel() {
    return subwooferChannel;
  }

  /**
   * Automatic energy-matched linear gain applied to the W (omni) channel for
   * the discrete subwoofer feed before any user trim in dB.
   */
  public float getSubwooferBaseGain() {
    return subwooferBaseGain;
  }

  /** Default crossover frequency in Hz (from JSON if present, else 80 Hz). */
  public float getSubwooferCrossoverFrequency() {
    return subwooferCrossoverHz;
  }

  /** Default subwoofer gain trim in dB (from JSON if present, else 0 dB). */
  public float getSubwooferGainDb() {
    return subwooferGainDb;
  }

  /** Azimuths in degrees of the real loudspeakers (may be empty). */
  public float[] getSpeakerAzimuths() {
    return azimuths.clone();
  }

  /** Elevations in degrees of the real loudspeakers (may be empty). */
  public float[] getSpeakerElevations() {
    return elevations.clone();
  }

  /**
   * Decodes one interleaved block of B-format frames to raw matrix-row
   * speaker frames (ignoring {@code Routing} and subwoofer):
   * {@code out[s * numChannels + n] = sum_c matrix[n][c] * in[s * numInputChannels + c]}.
   *
   * @param in  interleaved B-format block (ACN/SN3D, 4/9/16 channels)
   * @param out interleaved output block, at least
   *            {@code (in.length / numInputChannels) * numChannels} long
   */
  public void decodeInterleaved(float[] in, float[] out) {
    int numFrames = in.length / numInputChannels;
    for (int s = 0; s < numFrames; s++) {
      int inOff = s * numInputChannels;
      int outOff = s * numChannels;
      for (int n = 0; n < numChannels; n++) {
        int mOff = n * numInputChannels;
        float acc = 0f;
        for (int c = 0; c < numInputChannels; c++) {
          acc += matrix[mOff + c] * in[inOff + c];
        }
        out[outOff + n] = acc;
      }
    }
  }

  /**
   * Decodes one interleaved block of B-format frames to device-channel-ordered
   * output frames ({@link #getNumOutputChannels()} channels), honoring
   * {@code Routing} and leaving any unrouted channels silent.
   */
  public void decodeRoutedInterleaved(float[] in, float[] out) {
    decodeRoutedInterleaved(in, out, null);
  }

  /**
   * Decodes one interleaved block of B-format frames to device-channel-ordered
   * output frames ({@link #getNumOutputChannels()} channels), honoring
   * {@code Routing}, zeroing unused channels, and optionally writing a mono
   * subwoofer signal to {@code subwooferChannel - 1}.
   *
   * @param in            interleaved B-format block (ACN/SN3D)
   * @param out           interleaved device-channel output block of length at
   *                      least {@code numFrames * getNumOutputChannels()}
   * @param subwooferMono optional mono subwoofer signal of length at least
   *                      {@code numFrames}, or {@code null}
   */
  public void decodeRoutedInterleaved(float[] in, float[] out,
      float[] subwooferMono) {
    int numFrames = in.length / numInputChannels;
    int totalOut = numFrames * numOutputChannels;
    if (out.length < totalOut) {
      throw new IllegalArgumentException("Output buffer too small: need "
          + totalOut + " samples (" + numFrames + " frames x "
          + numOutputChannels + " channels), got " + out.length);
    }
    if (identityRouting && subwooferMono == null) {
      decodeInterleaved(in, out);
      return;
    }
    Arrays.fill(out, 0, totalOut, 0f);
    int subIdx = subwooferChannel - 1;
    boolean writeSub = subwooferChannel > 0 && subwooferMono != null;
    if (writeSub && subwooferMono.length < numFrames) {
      throw new IllegalArgumentException("Subwoofer buffer too small: need "
          + numFrames + " frames, got " + subwooferMono.length);
    }
    for (int s = 0; s < numFrames; s++) {
      int inOff = s * numInputChannels;
      int outOff = s * numOutputChannels;
      for (int n = 0; n < numChannels; n++) {
        int mOff = n * numInputChannels;
        float acc = 0f;
        for (int c = 0; c < numInputChannels; c++) {
          acc += matrix[mOff + c] * in[inOff + c];
        }
        out[outOff + effectiveRouting[n]] += acc;
      }
      if (writeSub) {
        out[outOff + subIdx] = subwooferMono[s];
      }
    }
  }
}

package com.google.resonanceaudio;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
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
 * <p>Example:</p>
 * <pre>
 * SpeakerDecoder decoder = new SpeakerDecoder(dataPath("decoder_default.json"));
 * ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
 * options.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
 * ResonanceAudio ra = new ResonanceAudio(options, decoder);
 * float[] speakers = new float[ra.getFramesPerBuffer() * decoder.getNumChannels()];
 * ra.fillOutputBuffer(speakers); // 16-ch B-format decoded to speakers
 * </pre>
 */
public class SpeakerDecoder {

  private final String name;
  private final int numInputChannels;
  private final int numChannels;
  private final float[] matrix; // row-major [numChannels][numInputChannels]
  private final int[] routing;  // 1-based device channel per matrix row, or null
  private final float[] azimuths;   // deg, real speakers only (may be empty)
  private final float[] elevations; // deg, real speakers only (may be empty)

  /** Loads and validates a decoder JSON file (IEM export format). */
  @SuppressWarnings("unchecked")
  public SpeakerDecoder(String jsonPath) throws IOException {
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
    if (routingObj instanceof List && !((List<?>) routingObj).isEmpty()) {
      List<Object> routingList = (List<Object>) routingObj;
      routing = new int[routingList.size()];
      for (int i = 0; i < routing.length; i++) {
        routing[i] = ((Double) routingList.get(i)).intValue();
      }
    } else {
      routing = null;
    }

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

  private static String stringOrDefault(Object o, String fallback) {
    return (o instanceof String) ? (String) o : fallback;
  }

  private static float toFloat(Object o) {
    return (o instanceof Double) ? ((Double) o).floatValue() : 0f;
  }

  /** Human readable decoder name from the JSON file. */
  public String getName() {
    return name;
  }

  /** Number of loudspeaker output channels (matrix rows). */
  public int getNumChannels() {
    return numChannels;
  }

  /** Ambisonic input channels this decoder requires (16). */
  public int getNumInputChannels() {
    return numInputChannels;
  }

  /**
   * Optional per-row device channel (1-based) from the JSON 'Routing' field,
   * or null if absent.
   */
  public int[] getRouting() {
    return routing == null ? null : routing.clone();
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
   * Decodes one interleaved block of B-format frames to interleaved speaker
   * frames: out[s * numChannels + n] = sum_c matrix[n][c] *
   * in[s * numInputChannels + c].
   *
   * @param in  interleaved 16-channel B-format block (ACN/SN3D)
   * @param out interleaved output block, at least
   *     (in.length / 16) * numChannels long
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
}

package com.google.resonanceaudio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Small WAV helper for the standalone (non Sound library) examples. Loads any
 * format that Java Sound can decode to 16-bit PCM (WAV/PCM/AIFF...) and can
 * write interleaved float audio back to 16-bit PCM WAV files.
 */
public final class WavUtil {

  /** Decoded audio data: interleaved float samples in [-1, 1]. */
  public static class WavData {
    public float[] samples;
    public int channels;
    public int sampleRate;
  }

  private WavUtil() {
  }

  /**
   * Loads a WAV (or other Java-Sound-decodable) file into an interleaved
   * float array.
   */
  public static WavData load(String path) throws Exception {
    File file = new File(path);
    if (!file.isAbsolute()) {
      // Fall back to the sketch's data folder convention.
      File dataFile = new File("data", path);
      if (dataFile.exists()) {
        file = dataFile;
      }
    }
    try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
      AudioFormat base = in.getFormat();
      AudioFormat pcm = new AudioFormat(
          AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16,
          base.getChannels(), base.getChannels() * 2, base.getSampleRate(),
          false);
      try (AudioInputStream pcmStream =
               AudioSystem.getAudioInputStream(pcm, in)) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = pcmStream.read(buf)) > 0) {
          bytes.write(buf, 0, n);
        }
        byte[] raw = bytes.toByteArray();
        WavData data = new WavData();
        data.channels = pcm.getChannels();
        data.sampleRate = (int) pcm.getSampleRate();
        int numSamples = raw.length / 2;
        data.samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
          int lo = raw[2 * i] & 0xFF;
          int hi = raw[2 * i + 1];
          short s = (short) ((hi << 8) | lo);
          data.samples[i] = s / 32768.0f;
        }
        return data;
      }
    }
  }

  /** Writes an interleaved float array to a 16-bit PCM WAV file. */
  public static void write(String path, float[] interleaved, int channels,
      int sampleRate) throws IOException {
    byte[] raw = new byte[interleaved.length * 2];
    for (int i = 0; i < interleaved.length; i++) {
      float v = interleaved[i];
      if (v > 1.0f) {
        v = 1.0f;
      } else if (v < -1.0f) {
        v = -1.0f;
      }
      short s = (short) (v * 32767.0f);
      raw[2 * i] = (byte) (s & 0xFF);
      raw[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
    }
    AudioFormat format = new AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels,
        channels * 2, sampleRate, false);
    try (AudioInputStream stream = new AudioInputStream(
        new java.io.ByteArrayInputStream(raw), format,
        raw.length / format.getFrameSize())) {
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, new File(path));
    }
  }
}

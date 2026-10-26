package com.google.resonanceaudio;

/**
 * Simple playhead/transport around a decoded {@link WavUtil.WavData} buffer,
 * used by the standalone (non Sound library) examples. Every audio block,
 * call {@link #sendTo(SoundSource)} to feed the next
 * {@code ResonanceAudio.getFramesPerBuffer()} frames of audio into a
 * {@link SoundSource} (silence when stopped).
 */
public class FilePlayer {

  private final float[] data;
  private final int channels;
  private final int sampleRate;

  private int positionFrames = 0;
  private boolean playing = false;
  private boolean loop = true;
  private float volume = 1.0f;

  public FilePlayer(WavUtil.WavData wav) {
    this.data = wav.samples;
    this.channels = wav.channels;
    this.sampleRate = wav.sampleRate;
  }

  public int getChannels() {
    return channels;
  }

  public int getSampleRate() {
    return sampleRate;
  }

  public void play() {
    playing = true;
  }

  public void stop() {
    playing = false;
  }

  public void pause() {
    playing = false;
  }

  public boolean isPlaying() {
    return playing;
  }

  public void setLooping(boolean loop) {
    this.loop = loop;
  }

  /** Restart playback from the beginning. */
  public void replay() {
    positionFrames = 0;
    playing = true;
  }

  /** Sets the playhead position in seconds. */
  public void cue(float seconds) {
    positionFrames = (int) (seconds * sampleRate);
    clampPosition();
  }

  /** Current playhead position in seconds. */
  public float position() {
    return positionFrames / (float) sampleRate;
  }

  /** Duration of the audio data in seconds. */
  public float duration() {
    return (data.length / channels) / (float) sampleRate;
  }

  public void setVolume(float volume) {
    this.volume = volume;
  }

  /**
   * Feeds one block of {@code frames} frames into the given sound source.
   * Call once per render block, before
   * {@link ResonanceAudio#fillOutputBuffer(float[])}.
   */
  public void sendTo(SoundSource source, int frames) {
    float[] block = new float[frames * channels];
    if (playing) {
      for (int f = 0; f < frames; f++) {
        int src = positionFrames + f;
        if (src >= data.length / channels) {
          if (loop) {
            src = src % (data.length / channels);
          } else {
            src = -1;
          }
        }
        for (int c = 0; c < channels; c++) {
          if (src >= 0) {
            block[f * channels + c] =
                (src + 1) * channels <= data.length
                    ? volume * data[src * channels + c] : 0f;
          }
        }
      }
      positionFrames += frames;
      if (!loop && positionFrames >= data.length / channels) {
        playing = false;
      }
    }
    source.send(block, channels);
  }

  private void clampPosition() {
    int total = data.length / channels;
    if (positionFrames < 0) {
      positionFrames = 0;
    }
    if (positionFrames >= total) {
      positionFrames = loop ? positionFrames % total : total - 1;
    }
  }
}

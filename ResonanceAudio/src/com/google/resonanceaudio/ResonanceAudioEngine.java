package com.google.resonanceaudio;

import com.jsyn.engine.SynthesisEngine;
import com.jsyn.ports.UnitInputPort;
import com.jsyn.ports.UnitOutputPort;
import com.jsyn.unitgen.ChannelOut;
import com.jsyn.unitgen.UnitGenerator;

import processing.core.PApplet;
import processing.sound.MultiChannel;
import processing.sound.Sound;

/**
 * JSyn unit that drives one {@link ResonanceAudio} renderer from within the
 * Processing Sound library's synthesis engine. It receives one mono input port
 * per sound source (fed by {@link ResonanceSoundFile} tap units), assembles
 * the blocks into Resonance Audio buffers, renders the spatialized output and
 * writes it to the Sound library's multi-channel {@link ChannelOut}s.
 *
 * <p>Because JSyn pulls the graph depth-first from the output channels, all
 * source tap units have already filled their blocks when this unit's
 * {@link #generate(int, int)} runs, guaranteeing correct processing order.</p>
 *
 * <p>Example (with the Processing Sound library):</p>
 * <pre>
 * ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
 * options.sampleRate = Sound.sampleRate();
 * options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_3RD_ORDER;
 * ResonanceAudio ra = new ResonanceAudio(options);
 * ResonanceAudioEngine engine = new ResonanceAudioEngine(this, ra, 4);
 * ResonanceSoundFile file = new ResonanceSoundFile(this, "sound.wav", engine);
 * file.loop();
 * </pre>
 */
public class ResonanceAudioEngine extends UnitGenerator {

  /** Maximum number of spatialized sources per engine. */
  public static final int MAX_SOURCES = 8;

  private final ResonanceAudio resonance;
  private final int numSources;
  private final int numChannels;
  private final int nativeFrames;

  private final UnitInputPort[] sourceInputs;
  private final UnitOutputPort[] channelOutputs;
  private final ResonanceSoundTap[] taps = new ResonanceSoundTap[MAX_SOURCES];

  // A hidden, always-silent source that guarantees the renderer's graph has a
  // connected input, so fillOutputBuffer() never fails when the user has not
  // (yet) added any sound files.
  private final SoundSource silentSource;

  // Accumulation of JSyn quanta into Resonance-Audio-sized blocks.
  private final float[][] accum;
  private int accumFrames = 0;

  // FIFO of rendered (interleaved) output samples.
  private final float[] renderBlock;
  private final float[] fifo;
  private int fifoWrite = 0;
  private int fifoRead = 0;

  /**
   * @param parent     the PApplet (used for cleanup and error reporting)
   * @param resonance  an initialized ResonanceAudio renderer
   * @param numSources maximum number of simultaneous sound sources (1..8)
   */
  public ResonanceAudioEngine(PApplet parent, ResonanceAudio resonance,
      int numSources) {
    this.resonance = resonance;
    this.numSources = Math.max(1, Math.min(MAX_SOURCES, numSources));
    this.numChannels = resonance.getOutputChannels();
    this.nativeFrames = resonance.getFramesPerBuffer();

    sourceInputs = new UnitInputPort[this.numSources];
    for (int i = 0; i < this.numSources; i++) {
      sourceInputs[i] = new UnitInputPort("source_" + i);
      addPort(sourceInputs[i]);
    }
    channelOutputs = new UnitOutputPort[numChannels];
    for (int i = 0; i < numChannels; i++) {
      channelOutputs[i] = new UnitOutputPort("out_" + i);
      addPort(channelOutputs[i]);
    }

    accum = new float[this.numSources][nativeFrames];
    renderBlock = new float[nativeFrames * numChannels];
    fifo = new float[nativeFrames * numChannels * 4];

    silentSource = resonance.addSoundObjectSource(
        RenderingMode.BINAURAL_HIGH_QUALITY);

    SynthesisEngine synth = Sound.getSynthesisEngine();
    synth.add(this);

    ChannelOut[] outs = MultiChannel.outputs();
    if (outs.length < numChannels) {
      parent.die("The current audio output device only provides "
          + outs.length + " channels, but " + numChannels
          + " are needed. Select a suitable device with Sound.outputDevice().");
    }
    for (int i = 0; i < numChannels; i++) {
      channelOutputs[i].connect(outs[i].input);
    }

    parent.registerMethod("dispose", this);
  }

  /** Number of output channels (2 binaural, 16 third order ambisonics). */
  public int getNumOutputChannels() {
    return numChannels;
  }

  /** The wrapped ResonanceAudio renderer. */
  public ResonanceAudio getResonanceAudio() {
    return resonance;
  }

  /** Allocates a free input slot, or -1 if none is left. */
  synchronized int allocateSlot() {
    for (int i = 0; i < numSources; i++) {
      if (taps[i] == null) {
        return i;
      }
    }
    return -1;
  }

  synchronized void connectTap(ResonanceSoundTap tap, int slot) {
    taps[slot] = tap;
    tap.getOutputPort().connect(sourceInputs[slot]);
  }

  synchronized void releaseSlot(int slot) {
    if (slot >= 0 && slot < numSources) {
      taps[slot] = null;
    }
  }

  @Override
  public void generate(int start, int end) {
    int count = end - start;

    // 1. Accumulate each source's JSyn quantum (the tap units have already
    //    generated, JSyn pulls depth-first from the ChannelOuts).
    if (accumFrames + count <= nativeFrames) {
      for (int s = 0; s < numSources; s++) {
        double[] block = sourceInputs[s].getValues();
        float[] dest = accum[s];
        for (int i = 0; i < count; i++) {
          dest[accumFrames + i] = (float) block[start + i];
        }
      }
    }
    accumFrames += count;

    // 2. On a full Resonance block, feed all sources and render.
    if (accumFrames >= nativeFrames) {
      accumFrames = 0;
      silentSource.send(new float[nativeFrames], 1);
      for (int s = 0; s < numSources; s++) {
        if (taps[s] != null) {
          taps[s].sendBlock(accum[s]);
        }
      }
      if (resonance.fillOutputBuffer(renderBlock)) {
        for (int i = 0; i < renderBlock.length; i++) {
          fifo[fifoWrite++ % fifo.length] = renderBlock[i];
        }
      }
    }

    // 3. Drain the FIFO into the output ports (pad with silence on under-run).
    int needed = count * numChannels;
    while (fifoWrite - fifoRead < needed) {
      fifo[fifoWrite++ % fifo.length] = 0f;
    }
    for (int c = 0; c < numChannels; c++) {
      double[] out = channelOutputs[c].getValues();
      for (int i = 0; i < count; i++) {
        out[start + i] = fifo[(fifoRead + i * numChannels + c) % fifo.length];
      }
    }
    fifoRead += needed;
  }

  /** Cleanup, called automatically when the sketch closes. */
  public void dispose() {
    try {
      Sound.getSynthesisEngine().remove(this);
    } catch (Exception ignored) {
    }
    resonance.dispose();
  }
}

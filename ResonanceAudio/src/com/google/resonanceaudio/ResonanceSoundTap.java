package com.google.resonanceaudio;

import com.jsyn.ports.UnitInputPort;
import com.jsyn.ports.UnitOutputPort;
import com.jsyn.unitgen.UnitGenerator;

/**
 * Internal JSyn unit that taps the (stereo) output of a Sound library circuit,
 * downmixes it to mono (or takes the left/panned channel for mono sources)
 * and passes it on to the {@link ResonanceAudioEngine} input slot. The
 * accumulated mono block is finally handed to the wrapped {@link SoundSource}
 * by {@link #sendBlock(float[])}.
 */
class ResonanceSoundTap extends UnitGenerator {

  final UnitInputPort in0 = new UnitInputPort("in0");
  final UnitInputPort in1 = new UnitInputPort("in1");
  private final UnitOutputPort output = new UnitOutputPort("output");
  private final boolean mono;

  private SoundSource source;
  private int slot = -1;
  private boolean active = false;

  ResonanceSoundTap(boolean mono) {
    this.mono = mono;
    addPort(in0);
    addPort(in1);
    addPort(output);
  }

  void configure(SoundSource source, int slot) {
    this.source = source;
    this.slot = slot;
    this.active = true;
  }

  void deactivate() {
    this.active = false;
  }

  boolean isActive() {
    return active;
  }

  UnitOutputPort getOutputPort() {
    return output;
  }

  /** Sends one accumulated (mono) block to the wrapped native source. */
  void sendBlock(float[] monoBlock) {
    if (source != null) {
      source.send(monoBlock, 1);
    }
  }

  @Override
  public void generate(int start, int end) {
    double[] a = in0.getValues();
    double[] out = output.getValues();
    if (mono) {
      for (int i = start; i < end; i++) {
        out[i] = a[i];
      }
    } else {
      double[] b = in1.getValues();
      for (int i = start; i < end; i++) {
        out[i] = 0.5 * (a[i] + b[i]);
      }
    }
  }
}

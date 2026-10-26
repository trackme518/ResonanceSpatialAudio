# Resonance Spatial Audio for Processing

Resonance Spatial Audio is an independent spatial audio library for Processing:
HRTF binaural rendering, 1st/2nd/3rd order Ambisonics, and
Ambisonics-to-loudspeaker decoding, with room reflections either enabled or
disabled per scene.

It combines the Resonance Audio DSP core with a Processing-specific Java/JNI
layer and adds multi-channel output, Ambisonics output and decoding,
configurable room reflections and reverb, and Processing Sound library
integration. It ships standalone and Sound-library-based examples.

## Features

- HRTF-based binaural rendering for headphones
- 1st, 2nd, and 3rd order Ambisonics B-format output (ACN/SN3D)
- Speaker-array decoding from precomputed matrices
- Optional room reflections and reverb (on/off)
- Distance, directivity, occlusion, and near-field effects
- Processing Sound library integration
- Low-latency ASIO output examples for Focusrite audio interfaces
- Windows, macOS (Intel and Apple Silicon), and Linux (x64 and arm64)

## Installation

Download `ResonanceAudio.zip` from the
[releases page](https://github.com/trackme518/ResonanceSpatialAudio/releases),
then unzip it into your Processing sketchbook `libraries` folder:

```
Documents/Processing/libraries/ResonanceAudio/
```

The Sound-library examples additionally require the Processing Sound library to
be installed in the same `libraries` folder.

## Examples

- `ResonanceBinauralSoundLib` / `ResonanceBinauralStandalone`
- `ResonanceAmbisonicsSoundLib` / `ResonanceAmbisonicsStandalone`
- `ResonanceDecoderSoundLib`
- `ResonanceDeviceSelectorSoundLib`
- `SoundAsioHeadphones`
- `SoundAsioSpeakers`

The ASIO examples require a Windows ASIO driver and the Sound library build with
JAsioHost support.

## Building the native renderer

The native JNI library is built from `native/` with CMake:

```bash
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --config Release --parallel
```

Set `JAVA_HOME` to a JDK 17 installation before configuring so that CMake can
find the JNI headers. The GitHub Actions workflow builds and packages the
library for all supported platforms.

## License

Copyright (C) 2026 L. Leischner.

This project is licensed under the **GNU General Public License v3.0**; see
[LICENSE](LICENSE).

The bundled Google Resonance Audio source is licensed under the Apache License
2.0; see [third_party_resonance/LICENSE](third_party_resonance/LICENSE).

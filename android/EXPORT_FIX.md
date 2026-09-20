# Cover Art Video Maker 1.2.0 — Android export repair

The Android exporter now produces H.264 video at 25 frames per second with AAC-LC, 44,100 Hz stereo audio in an MP4 container. The package ID, existing artwork/icon, and landscape/square/portrait output presets are unchanged.

## What changed

- Removed the manual MediaCodec/MediaMuxer pipeline that overwrote video timestamps and fed arbitrary decoded PCM into a fixed 44.1 kHz stereo encoder.
- Media3 Transformer supplies real video presentation times. No duplicate timestamps are manufactured during draining.
- Non-compatible input is converted to 16-bit PCM, mixed from mono to stereo when needed, and actually resampled to 44.1 kHz at unchanged speed and pitch. AAC encoding requests 320 kbps. This is high-quality lossy AAC, not mathematically lossless audio.
- Already compatible AAC-LC, 44.1 kHz stereo input is transmuxed without another audio encode when the source is recognized as compatible.
- The audio sequence runs to its real end of stream. The repeating still image ends with the audio. Rounded duration metadata is not used to discard audio packets.
- Each output is checked for its audio format, constant video display cadence, increasing audio timestamps, and matching audio/video lengths before publication in MediaStore.
- Incomplete exports remain in cache and are removed on failure. Gallery entries stay pending until the complete file has been copied.
- All rendering is local; the application has no Internet permission. Screen sleep is prevented while the activity is rendering. Leaving/destroying the activity cancels its active export.

This repair does not alter volume or deliberately add a fade, remove silence, normalize, time-stretch, or trim the song. It cannot restore missing content from an already damaged export. Use the original complete audio file.

## Input and output scope

Supported channel conversions in this release are mono and stereo. Surround/multichannel processing is not implemented and will fail rather than silently misinterpret samples. Audio format availability depends on the Android decoder and Media3 extractor. Codec/resolution fallback is disabled: an unsupported device preset reports an error instead of silently changing the requested format.

Output presets: 1920 x 1080, 1080 x 1080, and 1080 x 1920. Portrait MP4 storage may use a rotation matrix as determined by the device encoder; displayed dimensions are verified by the regression script.

## Automated regression checks

The GitHub Android workflow builds the application and instrumentation APKs, then invokes the actual exporter on an Android 35 emulator. It generates tests for:

1. 44.1 kHz stereo, 16-bit PCM, landscape.
2. 48 kHz stereo, 16-bit PCM, landscape.
3. 48 kHz mono, 16-bit PCM, portrait.
4. 48 kHz stereo, 24-bit PCM, square.
5. A 61.24-second track crossing the image-loop boundary.
6. Compatible AAC input, copied to another video with bit-identical encoded audio.

`tests/verify_exports.py` analyzes all seven resulting MP4s using FFprobe/FFmpeg. It checks codec/profile/pixel format, displayed dimensions, 25 fps timestamps, audio sample rate/channel count, full duration, test-tone pitch, an ending marker tone, decoding warnings, and AAC packet identity. The APK artifact is published only after these checks succeed; inspect the workflow result rather than assuming a commit means the tests passed.

## Signing and installation

The pre-existing project used GitHub-hosted ephemeral debug signing, not a retained release key. This workflow still builds a debug APK and records the public certificate fingerprint. It does not claim to match an earlier installed APK's signature. An installation rejected for a signing mismatch requires the prior signing key or a reinstall; do not delete user media to address a signature error. Private signing keys must not be committed to this public repository or saved in public Actions caches.

## Phone / TikTok acceptance check

Install the verified APK, select an original artwork and original complete song, create a fresh video, and test a private TikTok upload with original audio and no added sound. Emulator regression tests do not replace verification on the user's phone or guarantee behavior of a third-party uploader.

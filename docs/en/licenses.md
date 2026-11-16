# Licenses & Distribution

RetroMobileBench's original code and original content are licensed under the **Apache License 2.0** (see root [LICENSE](../../LICENSE)). Third-party components under `third_party/` and CC BY 3.0 / CC BY 4.0 media and 3D scene assets remain governed by their respective upstream licenses and attribution terms.

RetroMobileBench is distributed directly as an APK. Pinned upstream revisions and source locations are documented in [Development & Build - Third-Party Dependencies](development.md#third-party-dependencies-7-components); complete license texts are preserved in-tree and presented inside the app's **Open-Source Licenses** screen.

## Third-Party Components & Asset Licenses

| Component / Asset | License | Usage & Attribution Notes |
|---|---|---|
| CoreMark 1.0 | Apache-2.0 + EEMBC Trademark | Preserves original license & trademark notices; custom Android porting layer following standard timing and dual-seed validation |
| SciMark 2.0 C | NIST Public Domain | Attributes NIST origin; adds allocation failure checks and thread-safe init while keeping math kernels unchanged |
| tinymembench C kernels | MIT License | Preserves copyright & license notices; uses C copy and fill memory kernels only |
| STREAM Triad Variant | STREAM License | Preserves original license notice and marks results as a shared 24 MiB working-set STREAM variant |
| SQLite 3.53.1 | Public Domain | Compiled into the native benchmark library as a dedicated transaction workload, separate from app DB |
| FFmpeg 4.4.6 | LGPL-2.1-or-later (GPL/nonfree disabled) | Built as standalone shared libraries with matching corresponding source archive on distribution |
| GFXBench Engine Code | BSD-3-Clause (© 2005–2025 Kishonti Ltd) | Preserves original copyright and license notices |
| GFXBench Scene Assets | CC BY 4.0 | Preserves source attribution, license link, and subset/shader modification notes |
| GFXBench Bundled Deps | Respective OSS Licenses | Preserves notices for compiled zlib, libpng, TinyXML/TinyXML2, libetc1, and Xiph Ogg/Theora |
| GNU Static C++ Runtime | GPLv3 + GCC Runtime Library Exception | Preserves NDK `COPYING3` and `COPYING.RUNTIME` under the runtime library exception |
| Web Benchmark & Corpus | Original Synthetic Content + Sintel Derivatives | Custom portrait feed HTML/JS and 480p/720p/1080p test images; no external web frameworks |
| Sintel Video & Image Derivatives | CC BY 3.0 (© Blender Foundation) | Preserves source attribution, CC BY 3.0 link, and trim/re-encode notes |

### Attributions & Trademarks

- **CoreMark**:
  > CoreMark® is a registered trademark of EEMBC.
- **STREAM**: Results include `Based on a variant of the STREAM benchmark code.`
- **GFXBench Scene Assets**: Derived from [Kishonti's open-source repository](https://github.com/Kishonti-Opensource/gfxbench) (© 2005–2025 Kishonti Ltd, licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)). RetroMobileBench packages only the ETC1 texture subset, removes reference quality screenshots, and patches `ui.fs` precision fallback. Results are labeled as derived from GFXBench open-source scenes.
- **Sintel Media Assets**: Video clips and JPEG test images are derived from the Blender Foundation's *Sintel* trailer (licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/), see [official sharing terms](https://durian.blender.org/sharing/)). Clips are trimmed, scaled, re-encoded, and stripped of audio; PNG alpha test images are generated via FFmpeg's `testsrc2` filter.

During builds, Gradle's `generateBenchmarkNotices` task aggregates upstream license texts, toolchain runtime notices, media manifests, and both license docs alongside `licenses.txt`, `licenses_en.txt`, and `xiph-notices.txt` for in-app viewing.

## FFmpeg Build & LGPL Source Delivery

Software video decoding uses FFmpeg 4.4.6 (`third_party/ffmpeg`). `scripts/build-ffmpeg.sh` enables only H.264, H.263, MPEG-4 Part 2, VP8, and VP9 decoders with required parsers, demuxers, and local file protocol, explicitly disabling encoders, network protocols, filters, `--enable-gpl`, and `--enable-nonfree`. Outputs `libavutil.so`, `libavcodec.so`, and `libavformat.so` are packaged as standalone shared libraries.

### Generating the Corresponding Source Archive

To comply with LGPL-2.1 when distributing APKs, provide the matching corresponding source archive (FFmpeg sources, build scripts, JNI layer, and Gradle configuration) generated via:

```powershell
.\gradlew.bat :app:correspondingSource --console=plain
```

The archive is written to `app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v1.zip`. Full [LGPL-2.1 text](../../third_party/ffmpeg/COPYING.LGPLv2.1) is included in-tree.

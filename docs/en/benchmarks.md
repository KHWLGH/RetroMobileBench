# Benchmarks & Results

RetroMobileBench executes a fixed benchmark suite (`fixed-suite-v1`) serially across **12 stages** in the order shown below. The app runs fixed workloads automatically without per-stage toggles, thread selectors, resolution overrides, repetition sliders, or software/hardware decoder switches. On the report overview page, it provides a single-screen **Key Metrics Summary**, thermal & battery curves, and full data export (in-app reports, CSV tables, and TXT exports follow the selected UI language). For build and asset preparation, see [Development & Build](development.md); for validation entry points and device test records, see [Validation & Limits](validation.md).

## Suite Overview (12 Stages)

The first 6 standard stages run 3 consecutive rounds (1-second pause between rounds, reporting the valid median), while Video and the 5 GPU stages run once each with 1-second inter-stage pauses.

| # | Stage | Explicit Workload & Primary Metrics | Rounds |
|---|---|---|---|
| 1 | CPU Integer (CoreMark®) | Single-core + Multi-core (N threads); 2000-byte stack workspace, dual-seed CRC validation, >=10 s formal timing per segment; outputs **Single / Multi `iterations/s`** | 3 |
| 2 | CPU Float (SciMark 2.0) | Single-core + Multi-core (N threads); small dataset (FFT 1024, SOR 100×100, Monte Carlo 16384, Sparse N=1000/nz=5000, LU 100×100), >=1000 ms per kernel; outputs **Single / Multi Composite `MFLOPS`** (arithmetic mean) and per-kernel breakdowns | 3 |
| 3 | Memory Bandwidth & Latency | **8 MiB Copy / Fill** (16 MiB total buffers) + **4 MiB Random Pointer-Chase Latency** (1M nodes) + **24 MiB Shared Triad** (3×8 MiB `double` arrays, 1T + N threads); >=1500 ms per subtest; outputs `MB/s` and `ns` | 3 |
| 4 | Storage I/O | App private directory **32 MiB test file** (4–32 MiB adaptive when space is tight, >=16 MiB reserve) · **256 KiB Sequential R/W × 128 blocks** · **4 KiB Random R/W × 256 ops**; `O_SYNC` writes, `O_DIRECT` reads preferred; outputs sequential `MB/s` and random `IOPS` | 3 |
| 5 | SQLite Transactions | SQLite 3.53.1; **300 individual transactions each for INSERT / UPDATE / DELETE (900 `COMMIT`s total)** · **1 KiB** payload/row · **4 KiB** pages · `DELETE` journal + `FULL` sync; outputs **Composite Avg `TPS`** and Insert/Update/Delete `TPS` | 3 |
| 6 | Web Feed & Large Images | Offline system WebView in an **isolated portrait fullscreen window**; 3 portrait feed tasks (**640 feed cards**, **2000 dynamic list items**, **480 long-text blocks**) + **20× 480p/720p/1080p JPEG/PNG full-size Canvas decodes with pixel verification**; outputs **`total_ms`**, **`feed_ms`**, and **`image_ms`** (lower is better) | 3 |
| 7 | Video Decode & Playback | **7 fixed clips** (240p H.263, 360p MPEG-4, 480p/720p/1080p H.264, 720p VP8/VP9) tested sequentially across FFmpeg scalar software decode, MediaCodec system decode, and real-time Surface playback | 1 |
| 8 | GPU Egypt HD (Offscreen) | OpenGL ES 2.0, ETC1 textures, fixed 1920×1080 FBO, 113 s duration; outputs `FPS` and `frames` | 1 |
| 9 | GPU Egypt HD (Onscreen) | OpenGL ES 2.0, ETC1 textures, native window resolution, 113 s duration; outputs `FPS` and `frames` | 1 |
| 10 | GPU T-Rex (Offscreen) | OpenGL ES 2.0, ETC1 textures, fixed 1920×1080 FBO, 56 s duration; outputs `FPS` and `frames` | 1 |
| 11 | GPU T-Rex (Onscreen) | OpenGL ES 2.0, ETC1 textures, native window resolution, 56 s duration; outputs `FPS` and `frames` | 1 |
| 12 | GPU Texture Fill Rate | OpenGL ES 2.0, ETC1 textures, fixed 1920×1080 FBO quad-layer multi-texture blend, 60 s duration; outputs `MTexels/s` | 1 |

## Thread Scheduling & Timing Validation

### Thread Policy & Timing Rules

- **Worker Threads (N)**: CoreMark, SciMark 2.0, and Memory Triad test both single-thread and N-thread workloads (single-core devices automatically omit redundant parallel runs). N is resolved from sysfs CPU present/directory/online topology and runtime counts, falling back to configured processors only when topology files are absent, capped at 64 workers.
- **Timing Windows**: Native code measures exclusively with `CLOCK_MONOTONIC`. Buffer warmup, test vector checks, and output verification execute outside the timed region.
- **System Scheduling**: No root access, CPU core pinning, or governor overrides are used; the kernel handles hotplugging, scheduling, and DVFS naturally. Pre-run and post-run core online states and frequencies are captured as environment snapshots.

### Correctness Verification

- **CPU Integer (CoreMark®)**: Uses upstream core sources with a custom Android porting layer, enforcing dual-seed (validation + performance) CRC checks and at least 10 seconds of formal runtime per segment.
- **CPU Float (SciMark 2.0)**: Executes FFT (1024), SOR (100×100), Monte Carlo (16384), Sparse Matrix (N=1000, nz=5000), and LU (100×100) in single-core and multi-core (per-thread scratch buffers) modes, verifying residuals and integration bounds outside timing and computing the 5-kernel arithmetic mean composite MFLOPS.

## Stage Specifications

### Memory, Storage & SQLite Workloads

1. **Memory Bandwidth & Latency (`memory-tiny-v1`)**:
   - **8 MiB Copy & Fill**: Allocates two 8 MiB buffers (16 MiB total). C `aligned_block_copy` counts 8 MiB read + 8 MiB write = 16 MiB/iter for effective copy bandwidth, while `aligned_block_fill` counts 8 MiB write/iter.
   - **4 MiB Random Pointer-Chase Latency**: Constructs a single-cycle pseudo-random pointer chain across all **1,048,576 nodes (1M nodes)** in a contiguous 4 MiB region, traversing 4096 dependent hops per batch to capture true DRAM latency beyond L2/TLB (`ns`).
   - **24 MiB Shared Triad Bandwidth**: Uses STREAM's `a[i] = b[i] + 3.0 * c[i]` kernel across three 8 MiB `double` arrays (1,048,576 elements each, **24 MiB** total working set). Single-thread and multi-thread runs share the exact 24 MiB set, partitioned on 64-byte cache-line boundaries with >=1500 ms runtime per subtest.

2. **Storage I/O (`storage-file-v1`)**:
   - **32 MiB Test File**: Creates a **32 MiB** file in internal cache (adapting within 4–32 MiB when free space is constrained while preserving >=16 MiB free).
   - **Sequential R/W (256 KiB × 128 blocks)**: Sequentially writes the full file in 256 KiB aligned blocks with `O_SYNC`/`fsync`, then reads all blocks back sequentially and verifies payloads (`MB/s`).
   - **Random R/W (4 KiB × 256 ops)**: Performs 256 deterministic pseudo-random 4 KiB overwrites (`O_SYNC`) and 256 verified 4 KiB reads (`IOPS`). Reads prefer `O_DIRECT` to bypass page cache and fall back cleanly to `cached` when unsupported by the kernel.

3. **SQLite Transactions (`sqlite-tps-v1`)**:
   - **900 Individual Synced Transactions**: Uses bundled SQLite 3.53.1 on a dedicated test database to execute **300 individual `INSERT` transactions**, **300 individual `UPDATE` transactions**, and **300 individual `DELETE` transactions** (1 row per `COMMIT`, **900 `COMMIT`s total**).
   - **Row Payload & Durability**: Each row carries a **1024 B (1 KiB)** deterministic text payload plus integer verification columns; page size is **4096 B (4 KiB)** with a 2000 KiB cache limit, `journal_mode = DELETE`, and `synchronous = FULL`. Row counts and contents are verified between phases.
   - **Metrics**: Reports the arithmetic mean **`composite_tps`** alongside `insert_tps`, `update_tps`, and `delete_tps`.

### Web Portrait Feed & HD Image Decode (`web-daily-v1`)

The Web stage runs an **isolated portrait fullscreen** system WebView in the `:web` worker process to model typical vertical feed scrolling, interactive lists, long-text reflow, and high-resolution image decoding on legacy devices. All assets are bundled offline (build recipe: [`web-assets.gradle.kts`](../../app/web-assets.gradle.kts); locked hashes: [`web-corpus-lock.json`](../../app/web-corpus-lock.json)).

Each round warms up, validates, and resets 1 unit per subtest before executing **20 formal work units** per subtest (80 units/round across 3 median rounds):

| Subtest | Per-Unit Operation | Workload per Round (20 Units) |
|---|---|---|
| Feed Cards (`page`) | Clears container and builds 32 portrait single-column feed cards (thumbnail, title, body, action bar), computes styles, and forces layout measurement | **640 feed cards** / 5120 DOM nodes |
| Dynamic List (`todo`) | Renders 100 interactive items, toggles the first 50, applies all/active/completed/all filters with forced reflows, and clears | **2000 list items**, 1000 toggles, 80 filter reflows |
| Long Text (`text`) | Renders 24 paragraphs (256 chars each), replaces 48 phrases, highlights 240 keywords, collapses/expands 12 blocks, and restores | **480 text blocks**, 960 replacements, 4800 highlights |
| HD Image Decode (`image`) | Cycles through 6 bundled 480p (`854×480`), 720p (`1280×720`), and 1080p (`1920×1080`) JPEG & PNG images, rasterizes full-resolution via `<canvas>` `drawImage`, verifies left/center/right `getImageData` pixels, and immediately releases canvas memory | **20× full-resolution 480p/720p/1080p image decodes & rasterizations** |

- **Reported Metrics**:
  - `total_ms`: **Web Total Time** (`feed_ms + image_ms`, lower is better)
  - `feed_ms`: **Portrait Feed Time** (`page_ms + todo_ms + text_ms`)
  - `image_ms`: **HD Image Decode Time** (20 units of 480p/720p/1080p image decoding)
  - Plus individual breakdowns `page_ms`, `todo_ms`, and `text_ms`.
- **Viewport & Timeout Guards**: Enforces `width=device-width` portrait single-column layout (`12 CSS px` font, `18 CSS px` line height, `100%` text zoom) and validates `viewport_height >= viewport_width`. Parent timeouts (15 s startup/warmup, 20 s per subtest, 330 s stage cap) ensure stalled scripts can be cancelled and reaped cleanly.

### Video Decode & Playback (`video-decode-v1`)

Includes 7 fixed video clips (30 fps, 8-bit YUV420, silent). Codec parameters and SHA-256 digests are recorded in [manifest.json](../../app/src/main/assets/media/manifest.json); media attributions are listed in [Licenses & Distribution](licenses.md).

| Clip ID | Codec | Resolution | Duration / Frames |
|---|---|---|---|
| `h263-240p` | H.263 | 352×288 | 1 s / 30 frames (compatibility probe) |
| `mpeg4-360p` | MPEG-4 Part 2 | 640×360 | 5 s / 150 frames |
| `h264-480p` | H.264 Baseline | 854×480 | 5 s / 150 frames |
| `h264-720p` | H.264 Main | 1280×720 | 5 s / 150 frames |
| `vp8-720p` | VP8 | 1280×720 | 5 s / 150 frames |
| `vp9-720p` | VP9 | 1280×720 | 5 s / 150 frames |
| `h264-1080p` | H.264 High (CABAC / B-frames) | 1920×1080 | 5 s / 150 frames |

Each clip runs through three independent paths with per-path status tracking (`completed`, `unsupported`, `failed`, or `timeout`):

1. **Software Decode Throughput**: Bundled scalar FFmpeg 4.4.6 decoder running unthrottled, outputting full YUV420 frames and verifying the first frame's SHA-256 via `libavutil/sha.h`.
2. **System Decode Throughput** (API 16+): Synchronous `MediaCodec` with deterministic decoder selection (single fallback retry on primary failure), verifying dimensions, PTS, EOS, and sampling luma bytes on readable linear layouts.
3. **Real-Time Playback**: Paces frame presentation to `Surface` by PTS timestamps, recording decoded/submitted frames and whether real-time 30 fps was achieved. API 8–15 devices execute this path via `MediaPlayer`.

### GPU Dual Scenes & Texture Fill Rate (OpenGL ES 2.0)

Covers two rendering workloads plus texture fill rate across mobile GPU generations:

- **Egypt HD (Medium Load, 113 s)** and **T-Rex (Heavy Load, 56 s)**: Each runs **1080p Offscreen** (raw GPU throughput) and **Native Window Onscreen** (actual display resolution + VSync).
- **Texture Fill Rate (60 s)**: Fixed 1080p offscreen quad-layer multi-texture blending measuring pixel pipeline and memory bandwidth (`MTexels/s`).
- **Paired Overview Display**: On the Report Overview's **Key Metrics Summary** card, Egypt HD and T-Rex display offscreen and onscreen FPS side by side (`Offscreen xx.x FPS · Onscreen xx.x FPS`).

## Persistence, Telemetry Curves & Export

Reports use a hierarchical JSON schema storing session metadata, build IDs, timestamps, overall status, per-stage raw samples, configuration parameters, native library/ABI tags, and hardware snapshots. Three-round stages compute valid medians only when all 3 rounds succeed under identical parameters and build identities.

- **Thermal & Battery Curves**: Background sampling polls `ACTION_BATTERY_CHANGED` every 2 seconds for battery temperature (`0.1 °C`), battery percentage (`%`), voltage (`mV`), charging state, and active stage name, rendering dual-series curves on both session overview and stage detail pages.
- **Full File Export**: Losslessly exports all stages, raw rounds, device info, and full telemetry time series to `.json` (`retromobilebench-full-v1` + unaltered `raw_report`), `.csv` (UTF-8 BOM), or `.txt`. In-app reports, CSV exports, and TXT exports follow the selected UI language and support public `Downloads`, USB/MTP access, system Share, and Android 4.4+ Storage Access Framework (SAF).

| Status | Meaning |
|---|---|
| `completed` | All workloads in the stage finished and passed verification |
| `partial` | Some sub-paths completed with valid data while others failed or were unsupported |
| `unsupported` | Device or OS lacks required capability |
| `skipped` | Pre-run environment checks (memory/storage) blocked execution with recorded reason |
| `failed` / `timeout` | Execution or validation failed / exceeded stage timeout |
| `cancelled` / `interrupted` | Cancelled by user / interrupted by backgrounding, screen lock, surface loss, or process restart |

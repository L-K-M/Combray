# Web audio and video: research and design

Research date: 27 October 2026. Engine: Servo v0.7.0 from downstream `L-K-M/servo` `combray/0.7` at 6e49369, the pin at the time; upstream main checked at 0a49c63. Sources:

- Servo and Combray source, and the NDK r29 headers.
- Media3 1.11.1 sources and AAR metadata.
- GStreamer 1.28.8 Android binaries, downloaded and linked in a scratch build.
- The live JavaScript of X's logged-out web app and its embeds, fetched on 27 October 2026.

A second pass checked the claims against source and corrected them. Servo paths (`components/`, `python/`) are relative to the Servo checkout (`servo-checkout/servo` in a Combray build); other paths are Combray's. The pin has moved along the same line since. Its two changes (flex layout reuse, SVG path length; [ENGINE_SPIKE.md](ENGINE_SPIKE.md) sections 25 and 26) are not media changes, but the Servo line numbers below were not rechecked against it.

Status: you chose option C, a fullscreen tap-to-play native player, first (28 October 2026). Option B, an inline backend on the device's decoders, is acceptable later. Leaving MSE out is a proposed default, as are the network rules for C, Media3 with OkHttp, and the notice and Play wording, until you confirm them. Phase C1 is implemented in two PRs, pending its device evidence (see [phase C1](#phase-c1-a-fullscreen-player-for-media-combray-cancels)).

## Owner decisions

- **Fullscreen player first** (28 October 2026): option C, a native player that opens fullscreen after a tap. See the [plan](#plan).
- **Device decoders behind Servo's media element** (28 October 2026): acceptable for later inline playback (option B). Servo keeps the page, the element and its controls; only decoding is the platform's. The overlay route, where the platform would also draw the video, was not part of the question.
- **X videos in general** (28 October 2026). The linked post was an example; its public metadata shows two photos and no video.
- **Non-HTML content** (27 October 2026, from [PDF.md](PDF.md#owner-decisions)): "Servo is mandatory" does not apply to it, so a direct link to a media file may use another player.

## Summary

- **Why nothing plays.** Combray's Servo has no media backend for Android, so media elements cannot play. Combray cancels their loads and shows a notice.
- **What a backend fixes, and what it does not.** An inline backend (B) plays ordinary files: `<video src=file.mp4>`, audio files and GIF-style loops. It does not play streaming sites. Servo 0.7 has no Media Source Extensions (MSE), with any backend.
- **What X posts need.** X's player uses MSE when it exists. Without MSE it uses native HLS, but only if `canPlayType` claims HLS. Otherwise it fails. A claim alone is enough to make X hand its playlist to a load Combray can see.
- **Plan.** Phase C1 opens the media loads Combray already cancels in a fullscreen player, after a tap; Media3 is the proposed library. Phase C2 adds an HLS `canPlayType` claim so that X posts reach that player. Option B (inline playback) follows later, starting with a probe.

## What happens today

- **Backend choice.** Servo 0.7 picks its media backend at compile time (`components/servo/servo.rs:117-166`): GStreamer with the `media-gstreamer` feature, the OpenHarmony system player on OHOS, and a dummy backend everywhere else. Combray builds without `media-gstreamer` (`native/Cargo.toml`), so it gets the dummy. So does Servo's own Android build: mach defaults cross builds to the dummy stack (`python/servo/command_base.py:732-752`).
- **Upstream status.** Upstream disabled GStreamer on Android in January 2024 (servo/servo#31086). On 25 October 2026 it removed servoshell's Android media-session code as unusable (servo/servo#49265). Upstream main now compiles the GStreamer backend out on Android and OHOS (`servo.rs:118-121`, `components/servo/Cargo.toml:194-198`).
- **What the dummy does.** It never asks for data, never reports metadata and never produces a frame (`components/media/backends/dummy/lib.rs:85-95`). The element would queue the whole file in memory and stay at `HAVE_NOTHING`.
- **What Combray does about it.** The delegate cancels `<video>`, `<audio>` and `<track>` loads by fetch destination (`native/src/media.rs`, `native/src/delegate.rs:289`) and says "This page's audio or video can't play in Combray." once per page. Some notes still say "Servo 0.6.0" here (`native/src/media.rs:3` among them); the substance holds for 0.7.0.
- **`canPlayType`.** The dummy answers `""` for every type (`dummy/lib.rs:116-118`). A `<source type=...>` child is skipped before any fetch (`htmlmediaelement.rs:1599-1603`). Players that feature-detect see a browser that plays nothing.
- **No MSE or EME, with any backend.** `MediaSource` is commented out at `HTMLMediaElement.webidl:8`. There is no `MediaSource`, `SourceBuffer` or `MediaKeys` interface. Upstream issue servo/servo#22416 is open; draft servo/servo#40423 sits behind `dom_media_source_extensions_enabled`.

### How Servo's media pipeline works

Option B and C2's backend have to fit this.

- **Push model.** Servo fetches the media itself, with Range requests, and pushes the bytes into the player (`Player::push_data`). The backend never gets the URL (`components/media/servo-media/lib.rs:42-50`).
- **Events.** The player drives the element with `NeedData`, `EnoughData`, `SeekData(offset, lock)`, `MetadataUpdated`, `StateChanged`, `PositionChanged` and `VideoFrameUpdated` (`components/media/player/lib.rs:66-89`).
- **Frames (default path).** Tightly packed BGRA8 images (`components/script/dom/html/embedded_content/htmlmediaelement.rs:340-345`).
  - Single-process, as Combray runs, the bytes are shared, not copied (`components/shared/base/generic_channel/shared_memory.rs:93-104`).
  - Per-frame cost: the BGRA conversion in the backend, WebRender's texture upload on Combray's engine thread (where Paint runs), and node damage on the script thread (`htmlmediaelement.rs:3051-3053`). The damage means restyle, layout and display-list work for every frame, competing with page JavaScript.
  - `render()` calls `generate_image_key_blocking` on the first frame and on every size change (`htmlmediaelement.rs:374`, `:422`). It holds the renderer mutex the script thread also takes (`:3043`) and waits for Paint. It must never run on the engine thread.
  - Natural size is set from every frame (`:3043-3047`) as well as from metadata. A frame of a different size changes `videoWidth` and fires `resize`.
- **Frames (GL path).** Experimental and off by default (`media_glvideo_enabled`, `components/config/prefs.rs:570`). Only servoshell on desktop Linux and Windows wires it up. WebRender's `lock()` waits on a script-thread task, and the GPU fence wait is commented out.
- **Audio.** The player outputs audio itself.
- **Backend hooks.** Servo calls `Backend::suspend` and `resume` when a document becomes inactive or active (`components/script/dom/document/document.rs:1094-1115`). They default to no-ops, as does `mute` (`servo-media/lib.rs:72-86`). The OHOS and GStreamer backends implement all three.
- **`ServoMedia::get()` blocks** until a backend is installed (`servo-media/lib.rs:110-112`). Media elements, `canPlayType` and `Document::set_activity` call it.
- **Template.** Servo's OHOS backend (`components/media/backends/ohos/`, 1,942 lines) is the model for an Android backend. It wraps the system player, feeds it through a read-at data source filled by `push_data`, and converts frames to BGRA on the CPU with the `yuv` crate. Android has a matching API at every step within minSdk 30.
- **Trait stability.** The media traits are byte-identical between 0.7.0 and upstream main at 0a49c63.

## What sites need

X's player logic and Vimeo's row come from their live, minified JavaScript, read on 27 October 2026; those bundles change often. X's HLS shape comes from one public playlist, and the MP4 variant count from a secondary sample. The other rows rest on secondary sources. The last three columns say what plays inline with B, B plus native HLS, and B plus MSE.

| Site | How it delivers video | Backend for plain files | Plus native HLS | Plus MSE |
|---|---|---|---|---|
| X posts (x.com) | HLS v6, fMP4 segments, H.264, separate AAC playlist. Without MSE, X sets `video.src` to the playlist only if `canPlayType` claims HLS; otherwise hls.js fails with `MEDIA_NOT_SUPPORTED`. The player has no MP4 fallback. The post data may carry MP4 variants (option E, unverified). | No | Yes | Yes |
| X GIFs | A single MP4 | Yes | Yes | Yes |
| X embeds (platform.twitter.com) | MSE, else native HLS, else the highest-bitrate MP4 variant (at most 950 kbps when the embed caps bitrate) | Yes (MP4) | Yes | Yes |
| YouTube | MSE for adaptive streams. Without MSE, a 360p H.264 MP4 (Ladybird, March 2025 and January 2026). Servo users still report "can't play"; the cause is unknown. | Likely, unverified | Same | HD |
| Vimeo (two public embeds) | No progressive files. DASH/HLS only; native HLS skipped for Android user agents; one video used DRM. | No | No | Non-DRM only |
| Twitch | Probably MSE | No | Unverified | Yes |
| Reddit | DASH/HLS with separate audio. The fallback MP4 is silent. | Silent at best, unverified | Unverified | Likely |
| News players (Video.js, Brightcove) | MSE, else native HLS, else a listed MP4 | If an MP4 is listed | Yes | Yes |
| Plain `<video src>`, podcasts, audio files, direct links | One file | Yes | Yes | Yes |

**One firm rule follows.** Claim HLS in `canPlayType` only while something plays it. A claim without a player makes X embeds and Video.js pick HLS and fail instead of falling back to MP4. C2's claim is backed by the native player; it has to go when inline playback replaces that player (see [phase C2](#phase-c2-x-posts-through-an-hls-claim)).

## X posts

- **What an X post with a video does in Combray today.** From the bundles, not run on a device:
  - X's player finds no `MediaSource` and an empty `canPlayType` for HLS, so it takes the hls.js path.
  - hls.js reports `MEDIA_NOT_SUPPORTED` with `canRetry:false`, before any media load.
  - No media request reaches the delegate, so X shows its own error and Combray's notice does not appear.
- **With an HLS claim** (C2), X sets `video.src` to the playlist. That is a `video` load, which the delegate already cancels and can hand to the native player.
- **Unverified:** whether X post pages render and mount the player in Servo 0.7 at all. Only the logged-out bundle was read; the logged-in one may differ.
- **The linked example.** `cdn.syndication.twimg.com/tweet-result` and `api.fxtwitter.com` both list two photos, no video, no quoted post and no reply-to. Logged-in APIs were not used.

## Options

### A. Servo's GStreamer backend (`media-gstreamer`)

- **What it is.** Servo's own backend. GStreamer's `androidmedia` plugin wraps the device's MediaCodec decoders; the recommended plugin set adds software VP8, VP9, Opus and Vorbis.
- **What plays.** Progressive files inline with sound through OpenSL ES, plus X GIFs and embeds.
- **X posts still fail.** On Android, Servo passes no plugin list (`servo.rs:145-147`, `components/media/backends/gstreamer/lib.rs:361-364`), so plugins are whatever the GStreamer Android build registers. Even with an HLS demuxer, a playlist fed through `servosrc://` (`gstreamer/source.rs:370-371`) has no base URL, so segment URLs cannot be resolved. GStreamer's own HTTP source would bypass Servo's network stack.
- **Effort.** Estimates disagree: 9 to 16 engineer-days by one investigation; 2 to 4 weeks for build, packaging, notices and CI alone by another. A first emulator playback is plausible in 3 to 5 days. Keeping it past the next pin bump adds a downstream patch.
- **APK, per ABI.** Estimated from the measured libraries: at least +15.1 MB (arm64) and +15.8 MB (x86_64) for the recommended set, the stripped `libgstreamer_android.so` stored uncompressed (about a tenth of the roughly 174 MB x86_64 debug APK the Engine workflow installs today; the arm64 APK size is not measured); about +27 to 30 MB with FFmpeg (libraries of 26.5 and 29.5 MB); about 1 to 3 MB more in the engine library (unmeasured).
- **Maintenance.**
  - Upstream main compiles the backend out on Android. After the next pin bump, `media-gstreamer` silently yields the dummy unless L-K-M/servo carries a patch.
  - Servo turns off progressive download on Android because it "breaks playback" (`gstreamer/player.rs:512-513`).
  - A 968 MiB tarball per GStreamer version, cached per ABI in CI.
  - ndk-build has been deprecated since GStreamer 1.26. The 1.28.8 tarball still ships its ndk-build files, and the scratch link with NDK r29 used them for both ABIs. Rust plugins such as dav1d fail to link through ndk-build.
- **Licensing.** LGPL-2.1+: notices, a source offer and the ability to relink. The scratch build produced a separate `libgstreamer_android.so`; whether that meets the relinking duty is unverified. cargo-about does not see the C libraries. FFmpeg would add patent-encumbered decoders.
- **Risks.**
  - **Hardware decoders.** Decoders with colour formats `androidmedia` does not know are GL-output-only. On the phone, Servo's CPU path may fall back to software decoding or fail.
  - **Init hang.** `gstreamer::init().unwrap()` runs on a spawned thread. If it panics, no backend is installed and the first `ServoMedia::get()` blocks its script thread forever.
  - **Init order.** Java must call `GStreamer.init(context)` before Servo starts.
  - **API 30.** GStreamer 1.28 lists a known issue: "Android devices with API level >= 30 cannot play tutorials 4 or 5" (cerbero#529). Combray's minSdk is 30. A fix is unverified.

### B. A Combray backend on Android's media APIs (later)

- **What it is.** A servo-media `Backend` in Combray, modelled on OHOS. It implements `create_player` and `can_play_type`, plus `suspend`, `resume` and `mute` keyed by `ClientContextId`; without `suspend`, audio from pages you navigated away from keeps playing. It delegates Web Audio, WebRTC and streams to `DummyBackend`.
- **How it is installed.** `ServoMedia` is a first-writer-wins `OnceLock` (`servo-media/lib.rs:31`, `99-112`):
  1. Before `ServoBuilder::default()` (`native/src/engine.rs:686`), Combray calls `ServoMedia::init_with_backend(factory)` and then `ServoMedia::get()`, which waits for the instance.
  2. Servo's own dummy init then does nothing (`servo.rs:931-933`; Combray runs single-process).
  3. The factory is an `Fn` that runs on a new thread (`servo-media/lib.rs:103-108`). If it panics, `get()` waits forever, so it catches panics and returns the dummy. Panics on that thread unwind under Combray's panic hook.
  4. To build the backend on the engine thread instead, wrap the build in `panic_policy::recoverable` (the hook aborts on any other engine-thread panic) and pass it through `Mutex<Option<Box<dyn Backend>>>`.
  5. The backend is process-global and outlives Servo re-creation. It holds only application-scoped objects.

  Read from source, not compiled. If it fails, the fallback is a downstream patch of about 20 lines that adds an Android branch to `media_platform`. C2 uses the same installation.
- **Three variants.**
  - **B1, Media3 ExoPlayer 1.11.1 (preferred).** A custom `DataSource` is fed from Servo's pushed bytes. The decoder outputs into an `AImageReader` surface; Rust converts frames to BGRA with `yuv` (0.8.19, already in `native/Cargo.lock`). ExoPlayer plays the audio and keeps A/V sync. The same player can later play HLS from a URL.
  - **B2, `android.media.MediaPlayer` with `MediaDataSource`.** The closest port of OHOS, with no new dependency. Errors are opaque. Its handling of X's HLS v6 fMP4 with separate audio is unverified.
  - **B3, NDK only.** `AMediaExtractor` with a custom source, `AMediaCodec` in ByteBuffer mode, and AAudio. No JVM. ByteBuffer output is the safest choice on the emulator. The A/V clock, playback rate and buffering are written by hand.
- **Frame routes.** Any variant can use any route.
  - **CPU (default).** `ImageReader` (YUV_420_888), `yuv` to BGRA, then `render()` on a dedicated frame thread, never the engine thread or ExoPlayer's Looper.
  - **Zero-copy.** `AImage_getHardwareBuffer` into an EGLImage and an OES texture, through Servo's existing `VideoFrameData::OESTexture`. This is the Android form of the GL path and inherits its problems: off by default, the script-thread wait in `lock()`, no fence wait, and no Android wiring.
  - **Overlay ("hole-punch").** Media3 draws into a `SurfaceView` or `SurfaceControl` at the element's rectangle while Servo paints a transparent hole. Zero-copy, with hardware decoders. It needs element geometry and clipping from Servo (a downstream patch), and has z-order and scroll-sync problems. CSS effects on the video and `drawImage(video)` would not see the frames. Unverified.
- **What plays.** Progressive files inline, with sound, Servo's built-in controls and its fullscreen button. A direct link to a media file plays in Servo's media document. X GIFs and embeds play. YouTube 360p probably plays (unverified). X posts need its phase 2.
- **Effort.** The investigations' estimates for B1 and B2 range from 1.5 to 8 weeks; for B3, from 3 to 14 weeks. The low end assumes a happy-path port of OHOS; the high end includes lifecycle handling, memory caps, a codec table, fixtures and device evidence, which Combray's process requires. Plan 4 to 6 weeks for B1 after the probe. B2 is about the same.
- **APK.** B1 adds about 2.2 MB of compressed dex including Guava, measured with d8 and zip with R8 off as today, HLS and DASH support included; the same in every ABI's APK. `media3-ui` and `media3-session` bring it to about 2.6 MB. The Rust backend's size is unmeasured. B2 and B3 add no library.
- **Licensing.** Media3 and Guava are Apache-2.0, which licensee already allows. `yuv` (BSD-3-Clause or Apache-2.0) and `ndk-sys` (MIT or Apache-2.0) are already accepted in `native/about.toml`. The device's decoders are used, so no patent-encumbered codecs are bundled.
- **Maintenance.** Servo's `Player` and `Backend` traits are internal, so check them at each pin bump; MSE (servo/servo#40423) would likely change them. The JNI and Looper glue needs upkeep. Media3 is pinned in `gradle/libs.versions.toml` and updated through Dependabot. The repo has no Gradle lockfile or dependency verification; adding them is separate work.
- **Risks.**
  - **Frame cost.** At 1080p30, BGRA is about 250 MB/s. Each frame means a conversion, a texture upload on the engine thread and restyle, layout and display-list work on the script thread. None of it is measured on the phone.
  - **`render()` blocking.** See the pipeline section. Run it on its own thread.
  - **Emulator decoding.** Decoding into an `ImageReader` is CTS-tested on real devices only in Android 11 (`@RequiresDevice`). On the API 30 SwiftShader emulator it is unverified.
  - **Frame timing.** Media3 releases frames up to 50 ms early. The release time travels with the buffer, so the `Image` timestamp can schedule display. `maxImages` must cover 50 ms plus conversion time, or the decoder stalls.
  - **Crop.** 1080p decoders often output 1088-row buffers. Honour `getCropRect`, or a green bar shows at the bottom.
  - **SIMD.** CI runs `yuv`'s x86 paths; the phone runs its NEON paths. Conversion needs its own check on the phone.
  - **Seeking.** A seek blocks ExoPlayer's loader until Servo's script thread restarts the fetch. Do not hold the buffer lock while waiting; the OHOS source warns about this.
  - **Backoff.** Servo's `EnoughData` backoff restarts by seeking the element (`htmlmediaelement.rs:3058-3105`). Drive refetches through `SeekData`, as OHOS does.
  - **Memory.** Bytes sit in three buffers: the element's queue, the Rust buffer and ExoPlayer's sample queue. Media3's default allows about 131 MB of video per player. Cap all three.
  - **Decoder instances.** Hardware decoders have instance limits (`getMaxSupportedInstances`). An X timeline autoplays many videos. Cap concurrent players at that limit.
  - **Rejected media.** The delegate decides from the fetch destination before bytes arrive; the backend rejects after. Unsupported media downloads until the backend errors, unless `<source type>` or `canPlayType` filtered it.
- **Rules.** Your 28 October answer covered the device decoding behind Servo's element. Reading it as covering the CPU and zero-copy routes is an inference, since Servo still draws the frames in both. The overlay route also hands display to the platform; ask before choosing it.

### C. A native player outside Servo (chosen first)

- **What it is.** Servo keeps cancelling media loads, but the delegate passes the media URL to the app. A Play action opens a separate fullscreen player with Media3. Picture-in-picture and background audio can come later.
- **What plays.** Direct media links and plain `<video src>`, `<audio src>` and untyped `<source>` media, after a tap. Never inline, never muted autoplay loops, never MSE sites. X posts with the HLS claim of phase C2.
- **Costs.** Pages lose inline video, and media leaves Servo's network stack: no Servo cookies, its own TLS trust store, no Servo HSTS. The [network rules](#network-rules-for-the-native-player) below address what can be addressed.
- **Effort, APK and licensing.** In the [plan](#plan). Apache-2.0.
- **Rules.** Direct media links fit the non-HTML rule. You chose C, including media from HTML pages, on 28 October 2026.

### D. MSE in Servo

- **What it brings.** X posts through hls.js, YouTube in HD, Twitch, non-DRM Vimeo and most news players.
- **Minimum scope.** `MediaSource` and `SourceBuffer`; fMP4 and WebM; at least two SourceBuffers, because X and Vimeo use separate audio; an accurate `isTypeSupported`; `remove` and buffer eviction.
- **Cost.** Months of engine work. Upstream rates it very complex. It also needs a backend from A or B with MSE support. Picking up upstream draft servo/servo#40423 through a pin bump could cut the cost, and would likely change the `Player` and `Backend` traits.
- **Out of scope.** It would be downstream only and never offered upstream. DRM would also need EME and a CDM, which is out of reach.

### E. X posts through MP4 variants, without native HLS (unverified)

- **What it is.** On top of B's phase 1, X post videos play as MP4 inside Servo:
  - X's embed (`platform.twitter.com/embed`) already plays the highest MP4 variant when there is neither MSE nor an HLS claim. Combray could open a post's video in the embed.
  - Or a small Combray page adapter sets an MP4 variant on X's video element. X's web app has code that picks the highest-bitrate `video/mp4` variant, so its data model has them.
- **Gains.** No network stack outside Servo, no `canPlayType` HLS claim, no Servo patch, no taint problem.
- **Unverified.** Whether post videos still carry MP4 variants. A secondary 2026 sample reports two to five per video. Check one video post's `tweet-result`. An adapter would also break whenever X's bundles change.
- **Effort.** Unestimated; likely days if the variants exist.

### Comparison

| | A GStreamer | B1 Media3 backend | C Native player | D MSE | E X via MP4 |
|---|---|---|---|---|---|
| Decision | Not chosen | Acceptable later | Chosen first | Out of scope (proposed) | Possible with B |
| Inline in the page | Yes | Yes | No | Yes, with A or B | Yes, with B |
| Audio | Yes | Yes | Yes | Yes | Yes |
| Plain files, X GIFs, X embeds | Yes | Yes | After a tap | Yes | Needs B |
| X posts | No | After its phase 2 | After a tap, with the C2 claim | Yes | If variants exist |
| Effort | 9 days to 4+ weeks | Probe 1 to 1.5, then 4 to 6 weeks; HLS +2 to 4 | 1 to 4 weeks | Months | Days (guess) |
| APK per ABI | At least +15 MB | About +2.2 MB, HLS included | +2.6 to 3.4 MB | Not estimated | None |
| Licensing | LGPL-2.1+ | Apache-2.0 | Apache-2.0 | Depends on the backend | None |
| Upstream path | Compiled out on Android | Internal traits | None needed | Draft only | None needed |

## Plan

C first, in two phases, then B when you want inline playback. C's Media3 code and network rules carry over to B.

### Phase C1: a fullscreen player for media Combray cancels

**Status: implemented.** PR 1 (the engine's per-page report and the app's media model) and PR 2 (network rules, client, player and Play UI) follow the C1 plan. The device checks (`MediaPlayerTest`, the updated `BrowsingTest` and `AccessibilityTest` checks) pass on the API 30 x86_64 emulator ([ENGINE_SPIKE.md section 29](ENGINE_SPIKE.md#29-media-player-local-checks-28-october-2026)); no run on a phone yet. What was built:

- **Offer.** The coordinator offers only addresses the [network rules](#network-rules-for-the-native-player) accept; a refused address counts as media without one. The playable notice reads "This page’s audio or video opens in a separate player." with **Play** (proposed wording). Play opens the player for one entry, or a sheet of up to 8 for several; More › "Play audio or video" offers the same while the page has media. Play on a notice that outlived its page (another tab shown, or the document replaced) opens nothing and says so (proposed wording).
- **Player.** `PlayerActivity`, a separate activity on `media3-exoplayer`, `media3-ui` and `media3-datasource-okhttp` 1.11.1, with OkHttp 4.12.0 (proposed). The Intent carries only an id for a request kept in the app's memory. The player takes audio focus, pauses when headphones go, is released when the activity stops, and lays out by the tracks it finds: immersive for video, controls always on for audio. Errors show as text with Close.
- **Source.** `mediaSource(request, factory)` builds a progressive source, so a playlist fails as a format error. A refused address fails at once instead of being retried.
- **Not covered.** Typed `<source type=...>` children, media a page fetches by script, captions, cookies, picture-in-picture and background audio.

- **What it covers.** The `audio` and `video` loads the delegate already cancels: `src=` attributes, untyped `<source>` children, and media documents (a direct link to a media file, which Servo shows as a `<video>` or `<audio>` in a synthesized document, `load_media_document` in `servoparser/mod.rs`). Servo keeps reading such a navigation response and drops its body (`process_response_chunk` returns early for a synthesized document; nothing cancels the fetch), so under C1 a direct link is fetched twice: once by Servo, once by the player. Typed `<source type=...>` children never reach the delegate, because the dummy's empty `canPlayType` makes Servo skip them before any fetch.
- **Flow.**
  1. The delegate keeps cancelling `audio`, `video` and `track` loads. For `audio` and `video` it also hands the app the load's URL and the referrer Servo computed for it (`WebResourceRequest::referrer_url`), tied to the document the engine attributes the load to, as today's `media_blocked` event is.
  2. The page's notice offers Play. With several loads on one page it lists them (proposed default). The offer ends when that document is replaced.
  3. Play opens a fullscreen player activity on `androidx.media3:media3-exoplayer` and `media3-ui` 1.11.1, pinned in `gradle/libs.versions.toml`. Nothing plays without that tap. C1 builds a progressive media source explicitly, so a playlist fails with a format error instead of reaching Media3's automatic source selection; C2 adds `media3-exoplayer-hls`.
- **Lifecycle (proposed defaults).** Take audio focus; pause when the player leaves the foreground; release the player on close. No picture-in-picture or background audio in C1.
- **Failure.** A refused URL or a playback error says so in the player. Nothing falls back silently.
- **Tests.**
  - Fixture clips generated with ffmpeg, committed with their commands and SHA-256 hashes. `testing/fixtures/server.py` serves them with `Accept-Ranges` and 206 responses (new work).
  - Device checks: a `<video src>` page and a direct link each offer Play and reach playing; `data:`, `file:` and a redirect to a non-http(s) URL are refused; an http hop from an https page is refused; no media URL reaches logcat. `BrowsingTest.mediaIsCancelledBeforeDownloadingAndThePageIsTold` keeps passing.
  - Whether Media3 decodes and draws on the API 30 SwiftShader emulator is unverified.
- **Records.** Plan [section 7](IMPLEMENTATION_PLAN.md#7-privacy-security-and-platform-behavior) and its status row, the delegate change in [ENGINE_SPIKE.md](ENGINE_SPIKE.md#13-after-m0-engine-changes-for-the-app), `THIRD_PARTY_NOTICES.md` and `CHANGELOG.md`.
- **Effort.** About 1 week minimal; 2 to 4 weeks with a page scan, picture-in-picture and background audio. Estimates.
- **APK.** +2.3 to 2.6 MB of dex, measured with d8 and R8 off, plus 0.3 to 0.8 MB of UI resources. Dex is the same in every ABI's APK. Measured on the built C1 app (28 October 2026, R8 off, without the native engine): the unsigned release APK grew by 6.9 MB and the debug APK by 8.6 MB, nearly all of it dex from Media3, OkHttp, Okio and Guava together ([ENGINE_SPIKE.md](ENGINE_SPIKE.md#29-media-player-local-checks-28-october-2026)).

### Phase C2: X posts through an HLS claim

- **First check.** Open an X post with a video and confirm the page renders and mounts its player in Servo 0.7. C2 is useless if it does not.
- **Where it plugs in.** `mediaSource(request, factory)` in `app/.../player/PlayerActivity.kt` gains an HLS branch, and `gradle/libs.versions.toml` gains `media3-exoplayer-hls` at the Media3 version. The HLS playlist and its segments go through the same client, so every request passes the same per-hop rules.
- **What it does.** Combray installs its own backend before Servo starts, through the installation described under [option B](#b-a-combray-backend-on-androids-media-apis-later). It is Servo's `DummyBackend` except that `canPlayType` answers "maybe" for `application/vnd.apple.mpegurl` and `application/x-mpegurl`, case-insensitively, and `""` for everything else as today. X then sets `video.src` to the playlist; the delegate cancels that load and C1 offers it; Media3's HLS source (`media3-exoplayer-hls`, added here) plays it after a tap. X's in-page player would still show an error state (unverified).
- **Side effects while it is on.** X embeds, Video.js and Brightcove players also choose HLS over MP4. Their playlists reach the native player after a tap, as X posts do.
- **Firm rule.** The shim goes when inline playback lands (B). From then on, claim HLS only if inline HLS plays (B's phase 2b). A false claim beside inline MP4 makes X embeds and Video.js choose HLS and fail instead of playing MP4 inline. Proposed: land B's phases 1 and 2 together, so that X posts move from the native player to inline playback without a gap.
- **Tests.** Host tests for the `canPlayType` parser. A synthetic fMP4 HLS fixture from ffmpeg, edited into X's shape (version 6, where ffmpeg itself writes version 7; fMP4 segments, root-relative segment URIs, a separate audio playlist), on a page that picks HLS through `canPlayType` as X's player does. Then a manual check with X posts on your phone.
- **Effort.** 1 to 2 days on top of C1, plus the risk that the backend installation does not take effect (then the 20-line downstream patch).

### Network rules for the native player

Proposed defaults, pending your confirmation; C1 implements them. Media3 fetches outside Servo through its own OkHttp client (`core/.../media/MediaNetwork.kt`), with the system trust store and without Servo's HSTS list. The rules are in `core/.../media/MediaPolicy.kt`.

- **Where they apply.** The app offers only addresses the URL rules accept. The client checks the first request before connecting, then every hop after connecting (for https, after the TLS handshake) and before sending the request, and each redirect's target before OkHttp follows it. OkHttp follows redirects itself, at most 20. A refusal is final: OkHttp does not retry it on another address, and the player does not retry it either.
- **http(s) only, on every hop.** A redirect to `file:`, `content:`, `data:` or `intent:` is refused.
- **No credentials** in an address or a redirect target.
- **Numeric hosts.** A host that ends in a number but is no dotted quad (`127.1`, `2130706433`, `0177.0.0.1`, `0x7f.1`) is refused: OkHttp would hand it to the system as an address without asking its resolver.
- **Bad ports.** The Fetch standard's bad-port list, as Servo blocks it.
- **Mixed content.** From an https page (a `blob:` page counts as the page that made it), or a page whose referrer is https, every hop must be https.
- **No Servo cookies.** Media behind a sign-in fails in the player. Static cookie headers would leak across redirects and segment hosts; per-request cookie forwarding is about 2 to 3 days of later work. No cache, no authenticator (a 401 is an error), no proxy.
- **Referer and User-Agent set:** the user agent Servo sends (from `engine_ready`), and only the origin of the referrer Servo computed for the load, as `https://example.org/`, on every hop. Servo applies the page's referrer policy first, so a `no-referrer` page sends none; an http hop from an https referrer sends none either. A full page URL never reaches a redirect target or a segment host. The page's referrer policy applies to the first address only. The engine does not report the policy, so the player cannot tell `same-origin` from the default `strict-origin-when-cross-origin`: when a `same-origin` page's own server redirects its media to another host, that host receives the page's origin, where a browser would send none.
- **No platform diagnostics from Media3.** Media3's platform diagnostics are off (`setUsePlatformDiagnostics(false)`): the player reports no playback, format, network or error events to Android's media metrics service. Android's own decoder metrics, kept for every app, are outside Combray's control.
- **Private networks.** A public page cannot send the player to this device or the home network: loopback, private and link-local addresses, `localhost` and its subdomains, home names (`.lan`, `.local` and the other suffixes the address field knows), and single-label names such as `nas`. For media only, `0.0.0.0/8` and `::`, `100.64.0.0/10` (carrier NAT, which Tailscale uses) and NAT64 addresses of local IPv4 addresses count as local too. Names are checked against the address OkHttp actually connected to, so a name that resolves to a local address is refused after the connection (and, for https, the TLS handshake) opens but before any request byte. A page counts as local only when its host is local and its referrer is present and local too: a report can land on the wrong page, so a local page without a referrer is judged as public. A LAN page sent with `Referrer-Policy: no-referrer` therefore cannot play its own media (a known limit, [open question 1](#open-questions)).
- **The page's own host.** The exception covers only the address that a name on the page's own host connects to: a tailnet name (`*.ts.net`) resolving to a Tailscale address, or a split-DNS name resolving to a LAN address. Without it, media on such a page's own server would be refused. It never covers loopback, link-local or unspecified addresses (127.0.0.0/8, `::1`, 169.254.0.0/16, `fe80::/10`, 0.0.0.0/8, `::`, also in IPv4-mapped and NAT64 form), since no tailnet or home name resolves there. A local name or literal in the address itself (`nas.lan`, `192.168.1.10`) is refused from any page judged public, its own host included. Servo already connected there, and a page can already make Servo reach any address, so this gives a page nothing it lacks. What remains is DNS rebinding of the page's own host to a home network or tailnet address.
- **Loads Servo blocked.** The delegate also sees loads that CSP, bad-port or mixed-content rules blocked: interception runs after those checks set a network error (`components/net/fetch/methods.rs:484-547`), and the reported request does not say so. The rules above re-apply the scheme, bad-port and mixed-content checks. A load blocked only by the page's CSP `media-src` cannot be recognised from the URL and can still be offered; a downstream patch that marks blocked loads would close that gap.
- **No URLs in logs.** Combray logs Media3's error code name and a refusal's name only. Media3 logs warnings and errors only, with stack traces off, so it logs only the top exception's message, which for `ExoPlaybackException` carries no URL. Every refused redirect is closed, so OkHttp's leaked-connection warning, which names the host, does not fire. A device check searches logcat for a media URL token.

### Later: option B, inline playback

Why B1 rather than A, when inline playback is wanted:

- Video plays inline with sound and Servo's own controls, so pages keep working as pages.
- A is a poor base: upstream compiles it out on Android, Servo disables progressive download there, and GStreamer 1.28 lists an API 30 issue. It costs at least 15 MB per ABI and LGPL obligations, and it does not play X posts.
- B's frames are not copied in single-process mode, but every frame costs script-thread work. The probe measures that on your phone before phase 1 starts.
- The device's decoders do the work. No new native toolchain is needed. Phase 1 probably needs no Servo patch, and upstream ships the same pattern for OHOS.
- The same Media3 player plays HLS from a URL in phase 2b.

#### Biggest unknown, and the probe that settles it

**The unknown:** whether decoded video reaches the screen through a backend Combray installs itself, on the emulator, and fast enough on your arm64 phone:

1. Does the pre-installed backend take effect over Servo's dummy, and does Servo paint its frames?
2. Does decoder output into an `ImageReader` surface work on the API 30 SwiftShader emulator, or only ByteBuffer output? Media3 and MediaPlayer need the surface.
3. On the phone, at 360p, 720p and 1080p30 with moving content: how many frames are displayed, and what do the engine and script threads spend per frame?
4. Does `AudioTrack`'s playback position advance on the emulator, which runs with `-no-audio`? Media3's clock follows audio.

**Probe (phase 0).** About 5 to 8 working days. A draft PR that is never merged, like the PDF probe ([L-K-M/Combray#142](https://github.com/L-K-M/Combray/pull/142)). Files:

- **`native/Cargo.toml`.** Android-only additions, at versions already in `native/Cargo.lock`. No new packages; only combray-engine's own lock entry changes.
  - Path dependencies from `../servo-checkout/servo/components/`: `servo-media` (`media/servo-media`), `servo-media-dummy` (`media/backends/dummy`) and `servo-base` (`shared/base`, for `GenericCallback`, which libservo does not re-export). `servo-media-player` is reachable as `servo_media::player`.
  - `ndk-sys` 0.6.0+11769913 with the `media` feature, and `yuv` 0.8.19.
- **`native/src/media_probe.rs`** (new).
  - `ProbeBackend` delegates to `DummyBackend`, except `create_player` and `can_play_type`. `can_play_type` parses the MIME type case-insensitively, accepts `codecs=` parameters, and answers `video/mp4` (avc1) and `video/webm` (vp9, vp09) only.
  - `ProbePlayer` sends `NeedData` immediately and buffers pushed bytes until `end_of_stream`; demuxes with `AMediaExtractor` over a custom `AMediaDataSource` and decodes with `AMediaCodec`; converts frames with `yuv`, honouring the crop rectangle, and calls `VideoFrameRenderer::render` on a dedicated frame thread, paced by the frame timestamps; sends `MetadataUpdated`, `StateChanged`, `PositionChanged`, `VideoFrameUpdated` and `EndOfStream`.
  - A switch selects the frame route: an `AImageReader` surface (YUV_420_888) or ByteBuffer output.
  - It logs each stage's time per frame, with no URLs, and the engine and script threads' CPU time from `/proc/self/task` per clip.
  - Video only, no seeking, no audio.
- **`native/src/engine.rs`.** Before `ServoBuilder::default()` (line 686), call `ServoMedia::init_with_backend` with a factory that builds `ProbeBackend`, catches any panic and returns the plain dummy instead, then `ServoMedia::get()`. Report the fallback.
- **`native/src/media.rs`.** On the probe branch only, stop cancelling `video` loads. `audio` and `track` loads stay cancelled.
- **`testing/fixtures/media-probe/`.** Clips generated with ffmpeg, committed with their commands and SHA-256 hashes. H.264 Baseline MP4 (index at the front) and VP9 WebM, 30 fps, no audio:
  - **Colour clips** for the emulator: 640x360, 1280x720 and 1920x1080, two solid colours held 2.5 seconds each.
  - **Crop clip:** 1920x1080 with a marker colour in its bottom rows, to catch 1088-row buffers shown uncropped.
  - **Motion clips** for the phone: 360p, 720p and 1080p, 10 seconds of moving, noisy content with a burned-in binary frame code.
  - **`count_frames.py`:** counts the distinct frame codes in a screen recording.

  Each clip has a page that reports `playing`, `currentTime`, `videoWidth`, `videoHeight`, `ended` and errors in its title and to the fixture server.
- **`testing/fixtures/server.py`.** Range support as in phase C1, request and byte counts, and a report endpoint that saves the pages' and the test's results to an output directory.
- **`scripts/check-app.sh`.** Passes the fixture server an output directory under its results directory, as the PDF probe did.
- **`app/src/androidTest/kotlin/ch/lkmc/combray/MediaProbeTest.kt`.**
  - One test per colour clip and frame route. It waits for `playing`, then `Device.awaitScreen(colour)` for each held colour (it polls about once a second), then `ended`. It posts the timings to the fixture server, which writes them to that output directory.
  - The crop test samples the video's bottom edge and checks `videoHeight` is 1080.
  - One test per motion clip plays it to the end and writes the per-stage and per-thread timings. On the emulator it only checks that the clip ends; on the phone it runs under the screen recording.
  - One more test plays 2 seconds of PCM through `AudioTrack` and records whether the playback head advances.
- **`.github/workflows/engine.yml`.** On the probe branch, the device step runs only `ch.lkmc.combray.MediaProbeTest` on the API 30 emulator, as the PDF probe branch did, and adds a summary to the job page.
- **Your phone.**
  1. `COMBRAY_ABI=arm64 scripts/build-native.sh`
  2. `./gradlew assembleDebug assembleDebugAndroidTest`
  3. `scripts/check-app.sh <phone serial> ch.lkmc.combray.MediaProbeTest`, with an `adb shell screenrecord` capture during the motion clip tests. `count_frames.py` counts the displayed frames.

**Engine CI pass condition (emulator).** For the 360p MP4 and WebM colour clips, through at least one frame route: `playing` fires; `currentTime` passes 2 seconds within 20 seconds; the screen shows each held colour; `ended` fires; the server sent the file once. The crop clip must pass too. There is no timing gate on the emulator.

**Proposed phone gate.** On the 720p30 motion clip, at least 95% of frames are displayed, counted from the frame codes. Engine and script thread time per frame are reported, not gated. 1080p is measured, not gated. The conversion output is checked against a reference frame, since the phone runs NEON code CI never runs.

**Outcomes:**

- **Both routes work on the emulator and 720p passes on the phone.** Go to phase 1 with B1.
- **`ImageReader` output fails on the emulator but works on the phone.** Take B3 (NDK, ByteBuffer) so that CI covers the code that ships. It costs more, because the clock, rate and buffering are hand-written (3 to 14 weeks across two estimates).
- **720p fails on the phone.** Choose one: the zero-copy route in phase 1 (+2 to 4 weeks, with the GL path's script-thread wait); the overlay route (a downstream geometry patch, z-order and scroll-sync work); or a resolution cap. For progressive files a cap needs a Servo patch, because downscaled frames change `videoWidth` and fire `resize`; without one, it only fits adaptive HLS.
- **The pre-installed backend does not take effect.** Add the 20-line `media_platform` branch in L-K-M/servo, through the usual pin process.
- **`AudioTrack` does not advance on the emulator.** CI covers video-only and muted clips; audio becomes a phone check.

**Optional manual check by you.** Not a committed test; it logs no URLs. Check one X video post's `tweet-result` for `video/mp4` variants. This decides between E and native HLS in phase 2.

#### Phases

0. **The probe** (above).
1. **Inline playback of plain files, with sound** (about 4 to 6 weeks):
   - **`native/src/media_backend/` (Rust).** The backend, with `suspend`, `resume` and `mute` per `ClientContextId`, and a player that mirrors state for Servo's synchronous getters. Installation through a panic-safe factory; only application-scoped objects in the backend. A push buffer with `SeekData`-driven refetching. A frame thread that holds frames until their timestamps and calls `render()`. Memory caps across the element queue, the Rust buffer and ExoPlayer.
   - **`engine/src/main/kotlin/ch/lkmc/combray/engine/` (Kotlin).** One `HandlerThread` Looper running ExoPlayer (the Media3 version C1 pinned). A `DataSource` that reads Rust's buffer over JNI. An `ImageReader` surface with enough `maxImages` for early release.
   - **`can_play_type`.** From `MediaCodecList` over JNI, only for codecs the device decodes, case-insensitive, with `codecs=` parsing. C2's HLS claim is removed (firm rule).
   - **Lifecycle.** Proposed defaults, until you answer [open question 2](#open-questions): pause on tab hide and app background (Servo suspends only inactive documents); take audio focus; cap concurrent players at the decoder instance limit and release them on close; never block the script thread in teardown.
   - **Autoplay.** Follow your answer. Servo allows all autoplay today: `is_allowed_to_play` always returns true (`htmlmediaelement.rs:1204-1206`).
   - **`native/src/media.rs` and `delegate.rs`.** Stop cancelling `audio` and `video` loads. Keep `track` cancelled until WebVTT rendering is checked. Show the notice when the backend rejects media; the backend reports that to the app itself. Fall back to C's behaviour if the backend failed to start.
   - **Tests.** Host tests for the buffer, the conversion and the `can_play_type` parser. A `MediaTest` device class: playback; a seek that sends a Range request beyond byte 0 and fires `seeked`; a byte bound for `preload=metadata`; unsupported bytes, which fail with the notice; a server without Range support; audio that stops when its page leaves the session history's active entry. `BrowsingTest.mediaIsCancelledBeforeDownloadingAndThePageIsTold` narrowed to `track` loads.
   - **Records.** `docs/ENGINE_SPIKE.md` evidence, plan section 7 and its status row, `docs/BUILDING.md`, `THIRD_PARTY_NOTICES.md` and `CHANGELOG.md`.
   - **After phase 1.** X GIFs and embeds play inline. Without the HLS claim, X posts no longer reach C's player, which is why the proposal is to land phases 1 and 2 together.
2. **X posts inline.** Run the manual check first.
   - **2a. MP4 route (option E),** if post videos carry MP4 variants. Open the video in X's embed, or add a page adapter ([open question 7](#open-questions)). Days, unverified.
   - **2b. Native HLS** (about 2 to 4 weeks), if E does not work:
     - **A downstream patch in L-K-M/servo.** It gives the player its resource URL, for example through a `Player` method with a default no-op. It also stops the element's own fetch and push of the playlist, never lets `NeedData` follow `EnoughData` without a `SeekData` (or the page sees `seeking` and `seeked`), and marks HLS-mode players as not origin-clean, so `drawImage` plus `getImageData` cannot read cross-origin frames. Size unmeasured, larger than passing a URL.
     - **HLS playback.** Media3's HLS source plays the URL under the same [network rules](#network-rules-for-the-native-player) as C.
     - **`canPlayType`** answers "maybe" for `application/vnd.apple.mpegurl` and `application/x-mpegurl`, case-insensitively, only from this point.
     - **Device check.** C2's synthetic HLS fixture in X's shape, then a manual check with X posts on your phone.
3. **Polish, as you decide.** Fullscreen through `notify_fullscreen_state_changed`; picture-in-picture and background audio through a Media3 session; the zero-copy or overlay route, if the probe or phase 1 shows a need; a Web Audio output sink.

**Time to X posts playing inline:** about 7 to 12 weeks through native HLS, probe included. Through E, phase 1 plus days, if X's data allows it.

## Open questions

1. **Network outside Servo.** Confirm the proposed [network rules](#network-rules-for-the-native-player) for C (and later 2b), or name others: cookies, private networks (including whether a LAN page that withholds its referrer should play its own media), and whether to patch Servo so CSP-blocked loads are marked.
2. **Autoplay and background.** For C: picture-in-picture and background audio in the player, later or never? For B: autoplay with sound, or muted only? Pause hidden tabs and pause in the background? Take audio focus?
3. **Where a later backend lives.** In `native/` with no Servo patch, adapted at each pin bump, as proposed to start? Or as a backend crate in L-K-M/servo, as OHOS does?
4. **APK size.** C adds 6.9 MB to the release APK while R8 is off (measured; estimated at +2.6 to 3.4 MB before). B would reuse the same Media3 libraries; its Rust backend is unmeasured.
5. **The probe run on your arm64 phone,** for B's speed gate, when B starts.
6. **`<track>` loads.** Keep them cancelled until WebVTT is checked? C1 shows no captions.
7. **An X page adapter (B's phase 2a).** Acceptable as site-specific code that breaks when X changes, or embed only?

Decided:

- **Which video.** X videos in general (28 October 2026).
- **Inline or fullscreen.** Fullscreen first (C), inline (B) acceptable later (28 October 2026).
- **Device decoders behind Servo's media element.** Acceptable (28 October 2026). The overlay route was not part of the question.

Proposed, pending your confirmation:

- **MSE.** Out of scope. Neither C nor B needs it.

## Rejected options, with reasons

- **GStreamer first.** At least +15 MB per ABI and LGPL. Upstream compiles it out on Android and disables progressive download there. Phone decoders may need the experimental GL path. It still does not play X posts.
- **A `canPlayType` HLS claim without a player that plays HLS.** X embeds and Video.js would choose HLS and fail instead of using MP4. C2's claim is backed by the native player and goes when inline playback lands.
- **NDK-only as the default for B.** It costs more, because A/V sync, playback rate and buffering are hand-written. It stays the fallback if `ImageReader` output fails on the emulator.
- **MediaPlayer for B.** No new dependency and the closest port of OHOS, but its HLS and error handling are weaker for phase 2 (unverified).
- **Zero-copy first.** Servo marks the GL path experimental. Compositing would wait on the script thread, the GPU fence wait is commented out, and nothing runs that path on Android.
- **Overlay first.** It needs a geometry and clipping patch in Servo, has z-order and scroll-sync problems, and drops CSS effects on video.
- **Native HLS inline before checking for MP4 variants.** It adds a Servo patch and a taint rule that E may make unnecessary.
- **MSE now.** Months of engine work, downstream only.

## Not verified

- **Backend installation.** The pre-installed backend and the panic-safe factory (C2, B) were read from source, not compiled or run.
- **The native player (C).** C1's device checks (`MediaPlayerTest`) have not run yet: Media3 decoding and drawing on the API 30 SwiftShader emulator, audio under `-no-audio`, whether Servo reports `data:` and `file:` media loads to the delegate, whether it follows a link to a `data:` page, and how much of a direct link's body Servo reads as well. Also X's in-page state with the HLS claim, and that X's playlist load reaches the delegate on the current bundles.
- **Emulator and phone behaviour for B.** Decoder output into `ImageReader` on the API 30 emulator; frames displayed, thread time, battery use and heat on the phone; whether `AudioTrack` advances under `-no-audio`; whether `screenrecord` itself drops frames.
- **X.**
  - X's behaviour comes from logged-out bundles fetched on 27 October 2026; nothing was run on a device. The logged-in bundle may differ.
  - Whether X post pages render and mount the player in Servo 0.7.
  - Whether post videos carry MP4 variants, and whether the embed plays in Servo.
  - That Media3 plays X's HLS v6 fMP4 with separate audio. It uses standard Media3 features.
- **Other sites.** YouTube's 360p fallback inside Servo; Twitch, Reddit and Instagram without MSE.
- **Frame routes.** Zero-copy and overlay were not prototyped.
- **GStreamer.** Only the scratch link was done: no Servo build with the feature, no APK, no playback. Also unverified: whether the separate library meets the relinking duty, and the status of cerbero#529.
- **Sizes.** The phase 2b patch, Media3 after R8, the Rust backend. The dex figures were measured once with d8 and not repeated in review.
- **Effort.** All figures are estimates. The investigations disagree by more than five times for option B.
- **Servo's network rules.** That Servo has no private-network rule rests on a search of `components/net`, not on a test.

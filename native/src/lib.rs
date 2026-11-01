//! Combray's Android bridge to Servo (M0 engine spike).
//!
//! ```text
//!  Kotlin (ch.lkmc.combray.engine.NativeEngine)
//!        │  JNI: primitives, strings, Surface          ▲ onEngineEvent(json)
//!        ▼                                             │
//!  jni_api ──Command──▶ engine thread (owns Servo) ─EngineEvent─┘
//!                         ├─ delegate: per-view WebViewDelegate → queue
//!                         ├─ controls: select pickers, script dialogs, file pickers
//!                         ├─ capture: bounded in-page text snapshot
//!                         ├─ animations: Element.animate() for every page
//!                         ├─ page_zoom: the layout zoom of every view
//!                         ├─ find: find in page (bounded in-page search)
//!                         ├─ keyboard: taps that open the keyboard for a focused field
//!                         ├─ pdf: the combray-pdf: scheme serving the PDF viewer's files
//!                         └─ native_window: ANativeWindow ownership
//! ```
//!
//! `activation`, `animations`, `events`, `keyboard`, `media`, `navigation`, `page_zoom`,
//! `panic_policy`, `profile`, `userinfo` and the pure parts of `capture`, `find` and `pdf` build
//! on any host so their tests run without Servo.

pub mod activation;
mod animations;
pub mod capture;
pub mod controls;
pub mod events;
pub mod find;
pub mod keyboard;
mod media;
pub mod navigation;
mod page_zoom;
mod panic_policy;
pub mod pdf;
mod profile;
pub mod reload_pull;
mod userinfo;
mod wakeup;

#[cfg(target_os = "android")]
mod commands;
#[cfg(target_os = "android")]
mod delegate;
#[cfg(target_os = "android")]
mod engine;
#[cfg(target_os = "android")]
mod jni_api;
#[cfg(target_os = "android")]
mod native_window;

// Links jemalloc as the global allocator (see Cargo.toml).
#[cfg(target_os = "android")]
use servo_allocator as _;

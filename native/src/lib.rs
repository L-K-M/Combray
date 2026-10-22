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
//!                         └─ native_window: ANativeWindow ownership
//! ```
//!
//! `animations`, `events`, `media`, `page_zoom`, `panic_policy` and the pure parts of `capture`
//! build on any host so their tests run without Servo.

mod animations;
pub mod capture;
pub mod controls;
pub mod events;
mod media;
mod page_zoom;
mod panic_policy;

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

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
//!                         ├─ find: find in page (bounded in-page search)
//!                         └─ native_window: ANativeWindow ownership
//! ```
//!
//! `events`, `panic_policy` and the pure parts of `capture` and `find` build on any host so their
//! tests run without Servo.

pub mod capture;
pub mod controls;
pub mod events;
pub mod find;
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

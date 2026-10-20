//! Requests from other threads to the engine thread.
//!
//! Servo's handles are `Rc`-based and must stay on the thread that created them, so every
//! caller (JNI threads, Servo's own waker) talks to the engine through this message type.
//! Requests that must finish before the caller returns carry a [`Done`] channel.

use std::sync::atomic::AtomicU64;
use std::sync::mpsc::SyncSender;
use std::time::Duration;

use crate::capture::CaptureKind;
use crate::native_window::NativeWindow;

/// Acknowledgement channel for requests the caller must wait for.
pub type Done = SyncSender<()>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TouchPhase {
    Down,
    Move,
    Up,
    Cancel,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyAction {
    Press,
    Release,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Throttling {
    Normal,
    Throttled,
}

/// Whether the engine may refuse a capture locally when it already knows the expected
/// document was replaced. Tests disable the shortcut to exercise Servo's own guard.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LocalDocumentCheck {
    Enabled,
    SkippedForTest,
}

/// A key from Android's `KeyEvent`: the produced character, if any, and the key code.
#[derive(Clone, Copy, Debug)]
pub struct KeyInput {
    pub unicode: u32,
    pub key_code: i32,
}

pub struct CaptureRequest {
    pub tab: i64,
    pub document: u64,
    pub capture: i64,
    pub kind: CaptureKind,
    pub max_text_bytes: usize,
    pub timeout: Duration,
    pub local_check: LocalDocumentCheck,
}

pub enum Command {
    /// Servo has work to do; spin its event loop.
    Wake,
    /// Queued without waiting: binding (and starting Servo on the first surface) can take
    /// seconds, and Android calls this on its main thread. Outcome: `surface_bound` or `error`.
    SurfaceCreated {
        window: NativeWindow,
        width: u32,
        height: u32,
        /// [`SURFACE_EPOCH`] when the surface was created.
        epoch: u64,
    },
    SurfaceChanged {
        width: u32,
        height: u32,
    },
    /// Must complete before `SurfaceHolder.Callback.surfaceDestroyed` returns.
    SurfaceDestroyed {
        done: Done,
    },
    CreateTab {
        tab: i64,
        url: String,
    },
    SelectTab {
        tab: i64,
    },
    CloseTab {
        tab: i64,
    },
    Navigate {
        tab: i64,
        request: i64,
        url: String,
    },
    GoBack {
        tab: i64,
    },
    GoForward {
        tab: i64,
    },
    Reload {
        tab: i64,
    },
    /// Show no view: the application covers the page area with its own home screen.
    SelectNone,
    /// Write cookies and other profile state now; Android can kill the process at any time.
    PersistProfile,
    SetThrottling {
        tab: i64,
        throttling: Throttling,
    },
    Touch {
        phase: TouchPhase,
        pointer: i32,
        x: f32,
        y: f32,
    },
    Key {
        action: KeyAction,
        key: KeyInput,
    },
    ImeCommit {
        text: String,
    },
    /// Long-press at a surface point on the active view.
    ContextClick {
        x: f32,
        y: f32,
    },
    /// The application's answer to a page control, as JSON (see `controls::ControlResponse`).
    RespondToControl {
        control: u64,
        response: String,
    },
    Capture(CaptureRequest),
    /// Test support: run `script` in the tab's current document without a document guard.
    Evaluate {
        tab: i64,
        request: i64,
        script: String,
    },
    Shutdown {
        done: Done,
    },
}

/// Bounded wait for engine acknowledgements, so a wedged engine cannot hang Android's UI thread.
pub const ACK_TIMEOUT: Duration = Duration::from_secs(5);

/// Incremented by the JNI thread as soon as Android starts destroying the surface, before the
/// engine handles [`Command::SurfaceDestroyed`]. The engine paints only while the epoch it bound
/// is still current, so a late acknowledgement can never lead to rendering into a dead window.
/// Surface calls must come from one thread (Android's `SurfaceHolder.Callback` guarantees it).
pub static SURFACE_EPOCH: AtomicU64 = AtomicU64::new(0);

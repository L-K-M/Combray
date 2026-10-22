//! JNI boundary for `ch.lkmc.combray.engine.NativeEngine`.
//!
//! Thin by design: each export converts Java values into a [`Command`] for the engine thread.
//! No Servo object is reachable from here. Events flow back through one listener method,
//! `EngineListener.onEngineEvent(String json)`, called on the engine thread.
//!
//! Integer codes are shared with `NativeEngine.kt`; keep both sides in sync.

use std::path::PathBuf;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Sender};
use std::thread::ThreadId;
use std::time::Duration;

use android_logger::{Config, FilterBuilder};
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{Global, JClass, JObject, JString, JValueOwned};
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::{Env, EnvUnowned, JavaVM, jni_sig, jni_str};
use log::{LevelFilter, error, info};

use crate::capture::CaptureKind;
use crate::commands::{
    ACK_TIMEOUT, CaptureRequest, Command, KeyAction, KeyInput, LocalDocumentCheck, SURFACE_EPOCH,
    ScrollEdge, Throttling, TouchPhase,
};
use crate::engine::{self, EngineConfig, EventSink};
use crate::events::EngineEvent;
use crate::native_window::NativeWindow;
use crate::panic_policy::{self, PanicAction, ThreadRole};

/// Servo writes its profile while shutting down. Under software emulation a shutdown took
/// 157 s; callers must not block Android's main thread on it.
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(300);

const TOUCH_DOWN: jint = 0;
const TOUCH_MOVE: jint = 1;
const TOUCH_UP: jint = 2;
const TOUCH_CANCEL: jint = 3;

const KEY_PRESS: jint = 0;
const KEY_RELEASE: jint = 1;

/// Servo keeps process-wide state, so one engine per process.
static ENGINE: OnceLock<Sender<Command>> = OnceLock::new();
static ENGINE_THREAD: OnceLock<ThreadId> = OnceLock::new();
/// Set once shutdown is requested: later commands are refused instead of queued forever.
static SHUTTING_DOWN: AtomicBool = AtomicBool::new(false);

fn send(command: Command) -> bool {
    let Some(commands) = ENGINE.get() else {
        error!("engine not started");
        return false;
    };
    if SHUTTING_DOWN.load(Ordering::SeqCst) && !matches!(command, Command::Shutdown { .. }) {
        error!("engine is shutting down; command refused");
        return false;
    }
    if commands.send(command).is_err() {
        error!("engine thread has stopped");
        return false;
    }
    true
}

/// Send a command and wait until the engine thread acknowledges it.
fn send_and_wait(make: impl FnOnce(mpsc::SyncSender<()>) -> Command, timeout: Duration) -> bool {
    if ENGINE_THREAD.get() == Some(&std::thread::current().id()) {
        // Called from inside onEngineEvent: the engine cannot acknowledge while blocked here.
        error!("blocking engine call from the engine thread refused; post it to another thread");
        return false;
    }
    let (done, finished) = mpsc::sync_channel(1);
    if !send(make(done)) {
        return false;
    }
    let acknowledged = finished.recv_timeout(timeout).is_ok();
    if !acknowledged {
        error!("engine did not acknowledge within {timeout:?}");
    }
    acknowledged
}

struct JavaListener {
    vm: JavaVM,
    listener: Global<JObject<'static>>,
}

impl EventSink for JavaListener {
    fn emit(&self, event: EngineEvent) {
        let json = event.to_json();
        let delivered = self
            .vm
            .attach_current_thread(|env| -> jni::errors::Result<()> {
                let text = JValueOwned::from(env.new_string(&json)?);
                env.call_method(
                    self.listener.as_ref(),
                    jni_str!("onEngineEvent"),
                    jni_sig!("(Ljava/lang/String;)V"),
                    &[(&text).into()],
                )?;
                Ok(())
            });
        if let Err(failure) = delivered {
            error!("delivering an engine event failed: {failure}");
        }
    }
}

fn init_logging() {
    // Servo's warnings often name page URLs, so only its errors are kept (some of those can name
    // a URL too; they are needed to diagnose engine failures). Combray's own lines carry no page
    // text or addresses.
    let filter = FilterBuilder::new()
        .filter_level(LevelFilter::Error)
        .filter_module("combray_engine", LevelFilter::Info)
        .build();
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("CombrayEngine")
            .with_filter(filter),
    );
    std::panic::set_hook(Box::new(|info| {
        let thread = std::thread::current();
        error!(
            "panic on thread {}: {info}",
            thread.name().unwrap_or("<unnamed>")
        );

        // An unwinding engine thread would leave a frozen app with no error; crash visibly.
        // Servo flags panics it catches itself (SVG rasterization), as servoshell's hook honours.
        let role = if ENGINE_THREAD.get() == Some(&thread.id()) {
            ThreadRole::Engine
        } else {
            ThreadRole::Other
        };
        if !servo::should_panic_hook_suppress_termination()
            && panic_policy::action(role) == PanicAction::Abort
        {
            error!("engine thread panicked outside a recoverable operation; aborting");
            std::process::abort();
        }
    }));
}

fn java_string(env: &mut Env<'_>, value: JString<'_>) -> jni::errors::Result<String> {
    JString::cast_local(env, value)?.try_to_string(env)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_start<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    profile_dir: JString<'local>,
    density: jfloat,
    listener: JObject<'local>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        init_logging();
        if ENGINE.get().is_some() {
            error!("engine already started in this process");
            return Ok(false);
        }

        let config = EngineConfig {
            profile_dir: PathBuf::from(java_string(env, profile_dir)?),
            density,
        };
        let sink = JavaListener {
            vm: env.get_java_vm()?,
            listener: env.new_global_ref(listener)?,
        };

        // Servo's network stack needs a process-wide TLS crypto provider.
        let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();

        let (commands, receiver) = mpsc::channel();
        let waker = commands.clone();
        let spawned = std::thread::Builder::new()
            .name("combray-engine".into())
            .spawn(move || engine::run(config, receiver, waker, Box::new(sink)));
        let engine_thread = match spawned {
            Ok(handle) => handle.thread().id(),
            Err(failure) => {
                error!("starting the engine thread failed: {failure}");
                return Ok(false);
            },
        };
        // Publish only a running engine, so a failed start can be retried.
        let _ = ENGINE_THREAD.set(engine_thread);
        if ENGINE.set(commands).is_err() {
            error!("engine already started in this process");
            return Ok(false);
        }
        info!("engine thread started");
        Ok(true)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_surfaceCreated<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    surface: JObject<'local>,
    width: jint,
    height: jint,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        let Some(window) = NativeWindow::from_surface(env, &surface) else {
            error!("surface has no native window");
            return Ok(false);
        };
        let epoch = SURFACE_EPOCH.load(Ordering::SeqCst);
        Ok(send(Command::SurfaceCreated {
            window,
            width: width.max(1) as u32,
            height: height.max(1) as u32,
            epoch,
        }))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_surfaceChanged<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    width: jint,
    height: jint,
) {
    send(Command::SurfaceChanged {
        width: width.max(1) as u32,
        height: height.max(1) as u32,
    });
}

/// Stops rendering into the surface and waits for the engine to unbind it, as Android requires.
/// Rendering stops immediately through [`SURFACE_EPOCH`], even if the wait times out.
#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_surfaceDestroyed<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jboolean {
    SURFACE_EPOCH.fetch_add(1, Ordering::SeqCst);
    send_and_wait(|done| Command::SurfaceDestroyed { done }, ACK_TIMEOUT)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_createTab<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    url: JString<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let url = java_string(env, url)?;
        send(Command::CreateTab { tab, url });
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_selectTab<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
) {
    send(Command::SelectTab { tab });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_closeTab<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
) {
    send(Command::CloseTab { tab });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_navigate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    request: jlong,
    url: JString<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let url = java_string(env, url)?;
        send(Command::Navigate { tab, request, url });
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_goBack<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
) {
    send(Command::GoBack { tab });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_goForward<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
) {
    send(Command::GoForward { tab });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_reload<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
) {
    send(Command::Reload { tab });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_scrollToEdge<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    generation: jlong,
    document: jlong,
    edge: jint,
) {
    // Codes shared with engine/PageScroll.kt.
    const TOP: jint = 0;
    const BOTTOM: jint = 1;
    let edge = match edge {
        TOP => ScrollEdge::Top,
        BOTTOM => ScrollEdge::Bottom,
        _ => return,
    };
    let (Ok(generation), Ok(document)) = (u64::try_from(generation), u64::try_from(document))
    else {
        return;
    };
    send(Command::ScrollToEdge {
        view: crate::events::ViewKey { tab, generation },
        document,
        edge,
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_selectNone<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    send(Command::SelectNone);
}

/// Queued; the engine reports `profile_persisted` once cookies and profile state are written.
#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_persistProfile<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    send(Command::PersistProfile);
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_setThrottled<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    throttled: jboolean,
) {
    let throttling = if throttled {
        Throttling::Throttled
    } else {
        Throttling::Normal
    };
    send(Command::SetThrottling { tab, throttling });
}

/// Page zoom of every view, now and for views created later (1.0 is 100%). Values that are not a
/// zoom factor are refused with an `error` event; the engine clamps the rest to Servo's 10%–1000%.
#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_setPageZoom<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    zoom: jfloat,
) {
    send(Command::SetPageZoom { zoom });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_touch<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    phase: jint,
    pointer: jint,
    x: jfloat,
    y: jfloat,
) {
    let phase = match phase {
        TOUCH_DOWN => TouchPhase::Down,
        TOUCH_MOVE => TouchPhase::Move,
        TOUCH_UP => TouchPhase::Up,
        TOUCH_CANCEL => TouchPhase::Cancel,
        _ => {
            error!("unknown touch phase {phase}");
            return;
        },
    };
    send(Command::Touch {
        phase,
        pointer,
        x,
        y,
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_key<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    action: jint,
    key_code: jint,
    unicode: jint,
) {
    let action = match action {
        KEY_PRESS => KeyAction::Press,
        KEY_RELEASE => KeyAction::Release,
        _ => {
            error!("unknown key action {action}");
            return;
        },
    };
    let key = KeyInput {
        unicode: unicode as u32,
        key_code,
    };
    send(Command::Key { action, key });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_imeCommit<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let text = java_string(env, text)?;
        send(Command::ImeCommit { text });
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_contextClick<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    x: jfloat,
    y: jfloat,
) {
    send(Command::ContextClick { x, y });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_respondToControl<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    control: jlong,
    response: JString<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let response = java_string(env, response)?;
        send(Command::RespondToControl {
            // IDs come from `control_shown`, which never emits a negative one.
            control: control as u64,
            response,
        });
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_capture<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    document: jlong,
    capture: jlong,
    max_text_bytes: jint,
    timeout_ms: jint,
    skip_local_check: jboolean,
) {
    let local_check = if skip_local_check {
        LocalDocumentCheck::SkippedForTest
    } else {
        LocalDocumentCheck::Enabled
    };
    send(Command::Capture(CaptureRequest {
        tab,
        document: document as u64,
        capture,
        kind: CaptureKind::PageText,
        max_text_bytes: max_text_bytes.max(0) as usize,
        timeout: Duration::from_millis(timeout_ms.max(0) as u64),
        local_check,
    }));
}

/// Like `capture`, for reader view: the result's text is the article as JSON (`reader.js`).
/// A separate entry point keeps `capture`'s signature, which the M0 harness also binds.
#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_captureArticle<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    document: jlong,
    capture: jlong,
    max_bytes: jint,
    timeout_ms: jint,
) {
    send(Command::Capture(CaptureRequest {
        tab,
        document: document as u64,
        capture,
        kind: CaptureKind::Article,
        max_text_bytes: max_bytes.max(0) as usize,
        timeout: Duration::from_millis(timeout_ms.max(0) as u64),
        local_check: LocalDocumentCheck::Enabled,
    }));
}

/// Find in page in `document` of the view (`tab`, `generation`), only while it is still shown;
/// the engine reports `find_result` for `request`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_find<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    generation: jlong,
    document: jlong,
    request: jlong,
    query: JString<'local>,
    index: jint,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let query = java_string(env, query)?;
        let (Ok(generation), Ok(document)) = (u64::try_from(generation), u64::try_from(document))
        else {
            return Ok(());
        };
        send(Command::Find {
            view: crate::events::ViewKey { tab, generation },
            document,
            request,
            query,
            index,
        });
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_clearFind<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    generation: jlong,
    document: jlong,
) {
    let (Ok(generation), Ok(document)) = (u64::try_from(generation), u64::try_from(document))
    else {
        return;
    };
    send(Command::FindClear {
        view: crate::events::ViewKey { tab, generation },
        document,
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_evaluate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    tab: jlong,
    request: jlong,
    script: JString<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let script = java_string(env, script)?;
        send(Command::Evaluate {
            tab,
            request,
            script,
        });
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Closes every view and shuts Servo down cleanly; the process must not start a new engine.
#[unsafe(no_mangle)]
pub extern "C" fn Java_ch_lkmc_combray_engine_NativeEngine_shutdown<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jboolean {
    // Setting SHUTTING_DOWN without an engine would make a later start() refuse every command.
    if ENGINE.get().is_none() {
        error!("shutdown requested before the engine started");
        return false;
    }
    send_and_wait(
        |done| {
            SHUTTING_DOWN.store(true, Ordering::SeqCst);
            Command::Shutdown { done }
        },
        SHUTDOWN_TIMEOUT,
    )
}

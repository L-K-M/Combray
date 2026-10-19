//! The engine thread: sole owner of Servo, its rendering context and every `WebView`.
//!
//! ```text
//!  JNI threads ──Command──▶ ┌──────────────── engine thread ────────────────┐
//!  Servo waker ──Wake─────▶ │ handle commands → spin Servo → drain delegate │ ──EngineEvent──▶ sink
//!                           │ queue → finish captures → expire → paint      │   (tab, generation,
//!                           └───────────────────────────────────────────────┘    document IDs)
//! ```
//!
//! Logical tabs belong to the application. The engine maps each tab ID to one native view
//! generation; documents are identified by Servo's top-level pipeline ID, which changes on
//! every new document, including same-URL reloads.

use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::Ordering;
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};

use dpi::PhysicalSize;
use euclid::Scale;
use log::{error, info, warn};
use serde_json::{Map, Number, Value};
use servo::CreateNewWebViewRequest;
use servo::{
    CompositionEvent, CompositionState, DevicePoint, EmbedderControl, EmbedderControlId,
    EventLoopWaker, ImeEvent, InputEvent, JSValue, JavaScriptEvaluationError, Key, KeyState,
    KeyboardEvent, NamedKey, Opts, PipelineId, PrefValue, Preferences, RenderingContext,
    SelectElementOption, SelectElementOptionOrOptgroup, Servo, ServoBuilder, SimpleDialog,
    TouchEvent, TouchEventType, TouchId, TouchPointerType, WebView, WebViewBuilder,
    WindowRenderingContext,
};
use url::Url;

use crate::capture::{self, ScriptOutcome};
use crate::commands::{
    CaptureRequest, Command, KeyAction, KeyInput, LocalDocumentCheck, SURFACE_EPOCH, Throttling,
    TouchPhase,
};
use crate::controls::{self, ControlDescription, ControlResponse, SelectOption};
use crate::delegate::{DelegateEvent, DelegateQueue, TabDelegate};
use crate::events::{
    Attribution, CaptureReport, CaptureStatus, EngineEvent, HistoryChange, LoadPhase, ViewKey,
};
use crate::native_window::NativeWindow;
use crate::panic_policy;

/// Receives engine events on the engine thread.
pub trait EventSink {
    fn emit(&self, event: EngineEvent);
}

pub struct EngineConfig {
    /// Servo profile directory (cookies, HSTS, storage).
    pub profile_dir: PathBuf,
    /// Android display density: device pixels per density-independent pixel.
    pub density: f32,
}

/// Longest sleep between event loop iterations when nothing wakes the engine.
const IDLE_TICK: Duration = Duration::from_millis(250);
/// Reports buffered per tab for documents whose commit has not been seen yet.
const MAX_HELD_REPORTS: usize = 64;
/// Minimum interval between `painted` progress events per tab.
const PAINT_REPORT_INTERVAL: Duration = Duration::from_secs(1);
/// Commands handled per loop iteration, so a busy queue cannot postpone Servo, captures or paint.
const MAX_COMMANDS_PER_ITERATION: usize = 64;
/// Nesting limit when converting test evaluation results to JSON.
const MAX_JSON_DEPTH: usize = 64;
/// First tab ID for views that pages open (`window.open`). Applications number their own tabs
/// from small positive values, so the two ranges never meet.
const PAGE_TAB_BASE: i64 = 1 << 52;

pub fn run(
    config: EngineConfig,
    commands: Receiver<Command>,
    wake: Sender<Command>,
    sink: Box<dyn EventSink + Send>,
) {
    let mut engine = Engine::new(config, wake, sink);
    loop {
        let command = match commands.recv_timeout(engine.next_timeout()) {
            Ok(command) => Some(command),
            Err(RecvTimeoutError::Timeout) => None,
            Err(RecvTimeoutError::Disconnected) => break,
        };

        // Handle everything already queued before spinning Servo once.
        let mut flow = Flow::Continue;
        let queued = std::iter::from_fn(|| commands.try_recv().ok());
        for command in command
            .into_iter()
            .chain(queued)
            .take(MAX_COMMANDS_PER_ITERATION)
        {
            flow = engine.handle(command);
            if flow == Flow::Stop {
                break;
            }
        }
        if flow == Flow::Stop {
            break;
        }
        engine.pump();
    }
    info!("engine thread exiting");
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Flow {
    Continue,
    Stop,
}

/// Servo's event loop waker: any thread may ask the engine thread to spin Servo.
#[derive(Clone)]
struct ChannelWaker(Sender<Command>);

impl EventLoopWaker for ChannelWaker {
    fn clone_box(&self) -> Box<dyn EventLoopWaker> {
        Box::new(self.clone())
    }

    fn wake(&self) {
        let _ = self.0.send(Command::Wake);
    }
}

/// Servo and its rendering context exist once the first surface arrives.
struct Runtime {
    servo: Servo,
    rendering_context: Rc<WindowRenderingContext>,
}

enum Surface {
    Unbound,
    Bound {
        _window: NativeWindow,
        /// [`SURFACE_EPOCH`] at creation; a newer epoch means Android is destroying it.
        epoch: u64,
    },
}

impl Surface {
    /// Bound, and Android has not started destroying it.
    fn usable(&self) -> bool {
        match self {
            Surface::Unbound => false,
            Surface::Bound { epoch, .. } => *epoch == SURFACE_EPOCH.load(Ordering::SeqCst),
        }
    }
}

enum HeldReport {
    Load(PipelineId, LoadPhase),
    Title(PipelineId, Option<String>),
}

struct Tab {
    view: ViewKey,
    webview: WebView,
    current_document: Option<PipelineId>,
    current_url: Option<Url>,
    /// Every document this tab reported, so callers can name them by numeric ID.
    documents: HashMap<u64, PipelineId>,
    /// Documents that were current and have been replaced.
    retired: HashSet<PipelineId>,
    /// Reports from documents that have not committed yet (they can overtake the commit).
    held: VecDeque<HeldReport>,
    frames_painted: u64,
    last_paint_report: Option<Instant>,
}

impl Tab {
    fn attribution(&self, document: PipelineId) -> Attribution {
        if self.current_document == Some(document) {
            return Attribution::Current;
        }
        if self.retired.contains(&document) {
            return Attribution::Stale;
        }
        Attribution::HeldUntilCommit
    }

    fn hold(&mut self, report: HeldReport) {
        if self.held.len() == MAX_HELD_REPORTS {
            self.held.pop_front();
        }
        self.held.push_back(report);
    }
}

/// A page control waiting for the application's answer. Dropping it answers with Servo's default.
struct PendingControl {
    view: ViewKey,
    control: EmbedderControl,
}

struct PendingCapture {
    view: ViewKey,
    document: u64,
    started: Instant,
    deadline: Instant,
    max_text_bytes: usize,
}

/// A finished capture script: capture ID, the view and document it was requested for, result.
type CaptureResult = (
    i64,
    ViewKey,
    u64,
    Result<JSValue, JavaScriptEvaluationError>,
);
type CaptureResults = Rc<RefCell<Vec<CaptureResult>>>;
type EvaluationResults =
    Rc<RefCell<Vec<(ViewKey, i64, Result<JSValue, JavaScriptEvaluationError>)>>>;

struct Engine {
    config: EngineConfig,
    wake: Sender<Command>,
    sink: Box<dyn EventSink + Send>,
    runtime: Option<Runtime>,
    surface: Surface,
    size: PhysicalSize<u32>,
    tabs: BTreeMap<i64, Tab>,
    active: Option<i64>,
    next_generation: u64,
    next_page_tab: i64,
    next_sequence: u64,
    needs_paint: bool,
    ime_owner: Option<(ViewKey, EmbedderControlId)>,
    delegate_queue: DelegateQueue,
    capture_results: CaptureResults,
    evaluation_results: EvaluationResults,
    pending_captures: HashMap<i64, PendingCapture>,
    page_controls: HashMap<u64, PendingControl>,
    next_control: u64,
}

impl Engine {
    fn new(config: EngineConfig, wake: Sender<Command>, sink: Box<dyn EventSink + Send>) -> Self {
        Self {
            config,
            wake,
            sink,
            runtime: None,
            surface: Surface::Unbound,
            size: PhysicalSize::new(1, 1),
            tabs: BTreeMap::new(),
            active: None,
            next_generation: 1,
            next_page_tab: PAGE_TAB_BASE,
            next_sequence: 1,
            needs_paint: false,
            ime_owner: None,
            delegate_queue: Default::default(),
            capture_results: Default::default(),
            evaluation_results: Default::default(),
            pending_captures: HashMap::new(),
            page_controls: HashMap::new(),
            next_control: 1,
        }
    }

    fn emit(&self, event: EngineEvent) {
        self.sink.emit(event);
    }

    fn error(&self, message: impl Into<String>) {
        self.refuse(message, None, None);
    }

    /// Report a refused request, identified by tab and request (or capture) ID when known.
    fn refuse(&self, message: impl Into<String>, tab: Option<i64>, request: Option<i64>) {
        let message = message.into();
        warn!("{message}");
        self.emit(EngineEvent::Error {
            message,
            tab,
            request,
        });
    }

    fn next_timeout(&self) -> Duration {
        let now = Instant::now();
        self.pending_captures
            .values()
            .map(|pending| pending.deadline.saturating_duration_since(now))
            .min()
            .map_or(IDLE_TICK, |until_deadline| until_deadline.min(IDLE_TICK))
    }

    fn handle(&mut self, command: Command) -> Flow {
        match command {
            Command::Wake => {},
            Command::SurfaceCreated {
                window,
                width,
                height,
                epoch,
            } => self.surface_created(window, epoch, PhysicalSize::new(width, height)),
            Command::SurfaceChanged { width, height } => {
                self.resize(PhysicalSize::new(width, height))
            },
            Command::SurfaceDestroyed { done } => {
                self.surface_destroyed();
                let _ = done.send(());
            },
            Command::CreateTab { tab, url } => self.create_tab(tab, &url),
            Command::SelectTab { tab } => self.select_tab(tab),
            Command::SelectNone => self.select_none(),
            Command::PersistProfile => self.persist_profile(),
            Command::CloseTab { tab } => self.close_tab(tab),
            Command::Navigate { tab, request, url } => self.navigate(tab, request, &url),
            Command::GoBack { tab } => {
                if let Some(tab) = self.tab(tab) {
                    tab.webview.go_back(1);
                }
            },
            Command::GoForward { tab } => {
                if let Some(tab) = self.tab(tab) {
                    tab.webview.go_forward(1);
                }
            },
            Command::Reload { tab } => {
                if let Some(tab) = self.tab(tab) {
                    tab.webview.reload();
                }
            },
            Command::SetThrottling { tab, throttling } => {
                if let Some(tab) = self.tab(tab) {
                    tab.webview
                        .set_throttled(throttling == Throttling::Throttled);
                }
            },
            Command::Touch {
                phase,
                pointer,
                x,
                y,
            } => self.touch(phase, pointer, x, y),
            Command::Key { action, key } => self.key(action, key),
            Command::ImeCommit { text } => self.ime_commit(text),
            Command::RespondToControl { control, response } => {
                self.respond_to_control(control, &response)
            },
            Command::Capture(request) => self.start_capture(request),
            Command::Evaluate {
                tab,
                request,
                script,
            } => self.evaluate(tab, request, script),
            Command::Shutdown { done } => {
                self.shutdown();
                let _ = done.send(());
                return Flow::Stop;
            },
        }
        Flow::Continue
    }

    /// One iteration of Servo's event loop plus everything it produced.
    fn pump(&mut self) {
        if let Some(runtime) = &self.runtime {
            runtime.servo.spin_event_loop();
        }
        self.drain_delegate_events();
        self.drain_capture_results();
        self.drain_evaluations();
        self.expire_captures();
        self.paint();
    }

    fn tab(&self, tab: i64) -> Option<&Tab> {
        let found = self.tabs.get(&tab);
        if found.is_none() {
            self.refuse(format!("no tab {tab}"), Some(tab), None);
        }
        found
    }

    fn active_webview(&self) -> Option<&WebView> {
        self.active
            .and_then(|tab| self.tabs.get(&tab))
            .map(|tab| &tab.webview)
    }

    // --- Surface lifecycle -------------------------------------------------------------------

    fn surface_created(&mut self, window: NativeWindow, epoch: u64, size: PhysicalSize<u32>) {
        if let Surface::Bound { .. } = self.surface {
            self.error("surface created while another surface is bound; replacing it");
            self.surface_destroyed();
        }

        if self.runtime.is_none() {
            if !self.start_servo(&window, size) {
                return;
            }
        } else if let Some(runtime) = &self.runtime {
            let context = runtime.rendering_context.clone();
            if let Err(error) =
                surface_operation(|| context.set_window(window.window_handle(), size))
            {
                // `set_window` can fail after binding; unbind so a later surface can bind.
                let unbound = surface_operation(|| context.take_window());
                self.error(format!("binding the new surface failed: {error}"));
                if unbound.is_err() {
                    // EGL may still reference the window: keep our reference instead of
                    // releasing it under EGL.
                    std::mem::forget(window);
                }
                return;
            }
        }

        self.surface = Surface::Bound {
            _window: window,
            epoch,
        };
        self.resize(size);
        self.needs_paint = true;
        // Report the first paint on the new surface even if the last report was recent.
        for tab in self.tabs.values_mut() {
            tab.last_paint_report = None;
        }
        self.emit(EngineEvent::SurfaceBound {
            width: size.width,
            height: size.height,
        });
    }

    fn start_servo(&mut self, window: &NativeWindow, size: PhysicalSize<u32>) -> bool {
        let rendering_context = match surface_operation(|| {
            WindowRenderingContext::new(window.display_handle(), window.window_handle(), size)
        }) {
            Ok(context) => Rc::new(context),
            Err(error) => {
                self.error(format!("creating the rendering context failed: {error}"));
                return false;
            },
        };

        if let Err(error) = std::fs::create_dir_all(&self.config.profile_dir) {
            self.error(format!("creating the profile directory failed: {error}"));
        }
        let mut opts = Opts::default();
        opts.config_dir = Some(self.config.profile_dir.clone());
        let mut preferences = Preferences::default();
        preferences.set_value("viewport_meta_enabled", PrefValue::Bool(true));

        let servo = ServoBuilder::default()
            .opts(opts)
            .preferences(preferences)
            .event_loop_waker(Box::new(ChannelWaker(self.wake.clone())))
            .build();

        info!("Servo started at {}x{}", size.width, size.height);
        self.size = size;
        self.runtime = Some(Runtime {
            servo,
            rendering_context,
        });
        self.emit(EngineEvent::EngineReady {
            width: size.width,
            height: size.height,
        });
        true
    }

    fn surface_destroyed(&mut self) {
        let Surface::Bound { .. } = self.surface else {
            return;
        };
        if let Some(runtime) = &self.runtime {
            // Unbind and destroy the EGL surface before Android reclaims the window.
            let context = runtime.rendering_context.clone();
            if let Err(error) = surface_operation(|| context.take_window()) {
                // EGL may still reference the window: stay bound (and unusable, the epoch has
                // moved on) rather than release it; the next surface retries the unbind.
                self.error(format!("unbinding the surface failed: {error}"));
                return;
            }
        }
        // Dropping the bound window releases our ANativeWindow reference after EGL let go.
        self.surface = Surface::Unbound;
        self.emit(EngineEvent::SurfaceUnbound);
    }

    fn resize(&mut self, size: PhysicalSize<u32>) {
        if size.width == 0 || size.height == 0 {
            return;
        }
        self.size = size;
        let Some(runtime) = &self.runtime else {
            return;
        };
        if !self.surface.usable() || runtime.rendering_context.size() == size {
            return;
        }
        // All views share the context; resizing through one resizes it for every view.
        match self.tabs.values().next() {
            Some(tab) => tab.webview.resize(size),
            None => runtime.rendering_context.resize(size),
        }
        self.needs_paint = true;
    }

    // --- Tabs ----------------------------------------------------------------------------------

    fn create_tab(&mut self, tab: i64, url: &str) {
        let Some(runtime) = &self.runtime else {
            self.refuse(
                "cannot create a tab before the first surface starts Servo",
                Some(tab),
                None,
            );
            return;
        };
        if self.tabs.contains_key(&tab) {
            self.refuse(
                format!("tab {tab} already has a native view"),
                Some(tab),
                None,
            );
            return;
        }
        let url = match Url::parse(url) {
            Ok(url) => url,
            Err(error) => {
                self.refuse(
                    format!("invalid URL for tab {tab}: {error}"),
                    Some(tab),
                    None,
                );
                return;
            },
        };

        let view = ViewKey {
            tab,
            generation: self.next_generation,
        };
        self.next_generation += 1;

        let rendering_context: Rc<dyn RenderingContext> = runtime.rendering_context.clone();
        let webview = WebViewBuilder::new(&runtime.servo, rendering_context)
            .url(url)
            .hidpi_scale_factor(Scale::new(self.config.density))
            .delegate(Rc::new(TabDelegate::new(view, self.delegate_queue.clone())))
            .build();

        let webview_id = self.insert_tab(view, webview);
        self.emit(EngineEvent::TabCreated {
            view,
            webview: webview_id,
        });
    }

    /// Registers a new view, hidden until selected so two shown views never overlap.
    fn insert_tab(&mut self, view: ViewKey, webview: WebView) -> String {
        webview.hide();
        let webview_id = format!("{:?}", webview.id());
        self.tabs.insert(
            view.tab,
            Tab {
                view,
                webview,
                current_document: None,
                current_url: None,
                documents: HashMap::new(),
                retired: HashSet::new(),
                held: VecDeque::new(),
                frames_painted: 0,
                last_paint_report: None,
            },
        );
        webview_id
    }

    /// A page asked for a new window. It becomes a hidden view with an engine-assigned tab ID;
    /// the application adopts it (`tab_opened`). Dropping the request refuses the window.
    fn open_page_tab(&mut self, opener: ViewKey, request: CreateNewWebViewRequest) {
        // Only the tab the user sees may open windows: hidden or throttled tabs cannot take
        // over the screen (Servo 0.6.0 reports no user activation to check instead).
        if self.live_tab(opener).is_none() || self.active != Some(opener.tab) {
            return;
        }
        let Some(runtime) = &self.runtime else {
            return;
        };
        let view = ViewKey {
            tab: self.next_page_tab,
            generation: self.next_generation,
        };
        self.next_page_tab += 1;
        self.next_generation += 1;

        let rendering_context: Rc<dyn RenderingContext> = runtime.rendering_context.clone();
        let webview = request
            .builder(rendering_context)
            .hidpi_scale_factor(Scale::new(self.config.density))
            .delegate(Rc::new(TabDelegate::new(view, self.delegate_queue.clone())))
            .build();
        self.insert_tab(view, webview);
        self.emit(EngineEvent::TabOpened { view, opener });
    }

    /// Hides every view; the surface shows no page until a tab is selected again.
    fn select_none(&mut self) {
        for tab in self.tabs.values() {
            tab.webview.hide();
        }
        self.active = None;
        self.needs_paint = true;
    }

    fn persist_profile(&mut self) {
        let Some(runtime) = &self.runtime else {
            return;
        };
        let ok = runtime.servo.site_data_manager().persist_to_disk();
        self.emit(EngineEvent::ProfilePersisted { ok });
    }

    fn select_tab(&mut self, tab: i64) {
        let Some(selected) = self.tabs.get_mut(&tab) else {
            self.error(format!("no tab {tab} to select"));
            return;
        };
        // Report the first paint after selection even if the last report was recent.
        selected.last_paint_report = None;
        let view = selected.view;
        let selected = &self.tabs[&tab];

        for (id, other) in &self.tabs {
            if *id != tab {
                other.webview.hide();
            }
        }
        selected.webview.show();
        selected.webview.focus();

        self.active = Some(tab);
        self.needs_paint = true;
        self.emit(EngineEvent::TabSelected { view });
    }

    fn close_tab(&mut self, tab: i64) {
        let Some(closed) = self.tabs.remove(&tab) else {
            self.error(format!("no tab {tab} to close"));
            return;
        };
        if self.active == Some(tab) {
            // The application picks the successor; until then the surface shows no page.
            self.active = None;
            self.needs_paint = true;
        }
        if self.ime_owner.is_some_and(|(view, _)| view == closed.view) {
            self.ime_owner = None;
            self.emit(EngineEvent::ImeHidden { view: closed.view });
        }
        // Their answers could never reach the page; dropping sends Servo's defaults.
        self.page_controls
            .retain(|_, pending| pending.view != closed.view);
        let view = closed.view;
        // Dropping the last handle closes the WebView in Servo.
        drop(closed);
        self.emit(EngineEvent::TabClosed { view });

        // Captures of the closed view can never be delivered; settle them now. Any result that
        // still arrives is reported as a late result and its text discarded.
        let orphaned: Vec<i64> = self
            .pending_captures
            .iter()
            .filter(|(_, pending)| pending.view == view)
            .map(|(capture, _)| *capture)
            .collect();
        for capture in orphaned {
            let Some(pending) = self.pending_captures.remove(&capture) else {
                continue;
            };
            self.emit(EngineEvent::Capture(CaptureReport {
                capture,
                view,
                document: pending.document,
                status: CaptureStatus::TabClosed,
                elapsed_ms: pending.started.elapsed().as_millis(),
                content: None,
                observed_url: None,
                detail: Some("tab closed before the result arrived".into()),
            }));
        }
    }

    fn navigate(&mut self, tab: i64, request: i64, url: &str) {
        let Some(found) = self.tabs.get(&tab) else {
            self.refuse(
                format!("no tab {tab} to navigate"),
                Some(tab),
                Some(request),
            );
            return;
        };
        let view = found.view;
        match Url::parse(url) {
            Ok(parsed) => {
                found.webview.load(parsed);
                self.emit(EngineEvent::NavigationRequested {
                    view,
                    request,
                    url: url.to_owned(),
                });
            },
            Err(error) => self.refuse(
                format!("invalid URL for tab {tab}: {error}"),
                Some(tab),
                Some(request),
            ),
        }
    }

    // --- Input (always to the active view) ---------------------------------------------------

    fn touch(&mut self, phase: TouchPhase, pointer: i32, x: f32, y: f32) {
        let Some(webview) = self.active_webview() else {
            return;
        };
        let event_type = match phase {
            TouchPhase::Down => TouchEventType::Down,
            TouchPhase::Move => TouchEventType::Move,
            TouchPhase::Up => TouchEventType::Up,
            TouchPhase::Cancel => TouchEventType::Cancel,
        };
        webview.notify_input_event(InputEvent::Touch(TouchEvent::new(
            event_type,
            TouchId(pointer),
            DevicePoint::new(x, y).into(),
            TouchPointerType::Touch,
        )));
    }

    fn key(&mut self, action: KeyAction, key: KeyInput) {
        let Some(webview) = self.active_webview() else {
            return;
        };
        let Some(key) = servo_key(key) else {
            return;
        };
        let state = match action {
            KeyAction::Press => KeyState::Down,
            KeyAction::Release => KeyState::Up,
        };
        webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::from_state_and_key(
            state, key,
        )));
    }

    fn ime_commit(&mut self, text: String) {
        if text.is_empty() {
            return;
        }
        let Some(webview) = self.active_webview() else {
            return;
        };
        // Same sequence as Servo's own Android shell: a composition that ends with the text.
        webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::from_state_and_key(
            KeyState::Down,
            Key::Named(NamedKey::Process),
        )));
        webview.notify_input_event(InputEvent::Ime(ImeEvent::Composition(CompositionEvent {
            state: CompositionState::End,
            data: text,
        })));
        webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::from_state_and_key(
            KeyState::Up,
            Key::Named(NamedKey::Process),
        )));
    }

    // --- Capture -------------------------------------------------------------------------------

    fn start_capture(&mut self, request: CaptureRequest) {
        let started = Instant::now();
        let refuse = |view: ViewKey, status: CaptureStatus, detail: &str| {
            EngineEvent::Capture(CaptureReport {
                capture: request.capture,
                view,
                document: request.document,
                status,
                elapsed_ms: started.elapsed().as_millis(),
                content: None,
                observed_url: None,
                detail: Some(detail.to_owned()),
            })
        };

        let Some(tab) = self.tabs.get(&request.tab) else {
            let unknown = ViewKey {
                tab: request.tab,
                generation: 0,
            };
            self.emit(refuse(unknown, CaptureStatus::NoSuchTab, "no such tab"));
            return;
        };
        let view = tab.view;
        if self.pending_captures.contains_key(&request.capture) {
            self.refuse(
                format!("capture {} is already pending", request.capture),
                Some(request.tab),
                Some(request.capture),
            );
            return;
        }
        let Some(&document) = tab.documents.get(&request.document) else {
            self.emit(refuse(
                view,
                CaptureStatus::UnknownDocument,
                "document never reported by this tab",
            ));
            return;
        };
        if request.local_check == LocalDocumentCheck::Enabled
            && tab.current_document != Some(document)
        {
            self.emit(refuse(
                view,
                CaptureStatus::DocumentNotFound,
                "refused locally: document already replaced",
            ));
            return;
        }

        let results = self.capture_results.clone();
        let (capture_id, expected_document) = (request.capture, request.document);
        tab.webview.evaluate_javascript_in_document(
            document,
            capture::capture_script(request.max_text_bytes),
            move |result| {
                results
                    .borrow_mut()
                    .push((capture_id, view, expected_document, result))
            },
        );
        self.pending_captures.insert(
            capture_id,
            PendingCapture {
                view,
                document: request.document,
                started,
                deadline: started + request.timeout,
                max_text_bytes: request.max_text_bytes,
            },
        );
    }

    fn drain_capture_results(&mut self) {
        let results: Vec<_> = self.capture_results.borrow_mut().drain(..).collect();
        for (capture, view, document, result) in results {
            // Match on view and document too: an ID reused after a timeout must not receive
            // the late result of the earlier request.
            let matches = self
                .pending_captures
                .get(&capture)
                .is_some_and(|pending| pending.view == view && pending.document == document);
            if !matches {
                // Already reported (deadline, closed tab); never deliver its text.
                self.emit(EngineEvent::LateCaptureResult { capture });
                continue;
            }
            let Some(pending) = self.pending_captures.remove(&capture) else {
                continue;
            };
            let report = self.finish_capture(capture, &pending, result);
            self.emit(EngineEvent::Capture(report));
        }
    }

    fn finish_capture(
        &self,
        capture: i64,
        pending: &PendingCapture,
        result: Result<JSValue, JavaScriptEvaluationError>,
    ) -> CaptureReport {
        let mut report = CaptureReport {
            capture,
            view: pending.view,
            document: pending.document,
            status: CaptureStatus::Captured,
            elapsed_ms: pending.started.elapsed().as_millis(),
            content: None,
            observed_url: None,
            detail: None,
        };

        let content = match capture::interpret_result(result, pending.max_text_bytes) {
            ScriptOutcome::Content(content) => content,
            ScriptOutcome::Refused(status, detail) => {
                report.status = status;
                report.detail = Some(detail);
                return report;
            },
        };

        // Re-validate after the asynchronous hop: identity comes from the engine's records,
        // never from the URL the page reported.
        let current = self
            .tabs
            .get(&pending.view.tab)
            .filter(|tab| tab.view == pending.view)
            .map(|tab| tab.current_document.map(u64::from));
        report.status = match current {
            None => CaptureStatus::TabClosed,
            Some(current) if current != Some(pending.document) => CaptureStatus::StaleDocument,
            Some(_) if content.text.is_empty() => CaptureStatus::Empty,
            Some(_) => CaptureStatus::Captured,
        };

        if matches!(
            report.status,
            CaptureStatus::Captured | CaptureStatus::Empty
        ) {
            report.content = Some(content);
        } else {
            report.observed_url = Some(content.url);
        }
        report
    }

    fn expire_captures(&mut self) {
        let now = Instant::now();
        let expired: Vec<i64> = self
            .pending_captures
            .iter()
            .filter(|(_, pending)| pending.deadline <= now)
            .map(|(capture, _)| *capture)
            .collect();
        for capture in expired {
            let Some(pending) = self.pending_captures.remove(&capture) else {
                continue;
            };
            self.emit(EngineEvent::Capture(CaptureReport {
                capture,
                view: pending.view,
                document: pending.document,
                status: CaptureStatus::TimedOut,
                elapsed_ms: pending.started.elapsed().as_millis(),
                content: None,
                observed_url: None,
                detail: Some(
                    "deadline passed; Servo cannot interrupt the script, only the result is abandoned"
                        .into(),
                ),
            }));
        }
    }

    fn evaluate(&mut self, tab: i64, request: i64, script: String) {
        let Some(found) = self.tabs.get(&tab) else {
            self.emit(EngineEvent::Evaluation {
                view: ViewKey { tab, generation: 0 },
                request,
                current_document_at_completion: None,
                result: Err("no such tab".into()),
            });
            return;
        };
        let view = found.view;
        let results = self.evaluation_results.clone();
        found.webview.evaluate_javascript(script, move |result| {
            results.borrow_mut().push((view, request, result))
        });
    }

    fn drain_evaluations(&mut self) {
        let results: Vec<_> = self.evaluation_results.borrow_mut().drain(..).collect();
        for (view, request, result) in results {
            let current_document_at_completion = self
                .tabs
                .get(&view.tab)
                .filter(|tab| tab.view == view)
                .and_then(|tab| tab.current_document)
                .map(u64::from);
            self.emit(EngineEvent::Evaluation {
                view,
                request,
                current_document_at_completion,
                result: result
                    .map(|value| js_to_json(&value, 0))
                    .map_err(|error| format!("{error:?}")),
            });
        }
    }

    // --- Delegate notifications ----------------------------------------------------------------

    fn drain_delegate_events(&mut self) {
        loop {
            // Pop one at a time: emitting must not hold the queue borrow.
            let Some(event) = self.delegate_queue.borrow_mut().pop_front() else {
                break;
            };
            self.handle_delegate_event(event);
        }
    }

    /// The tab for `view`, unless it closed or was recreated since the event was queued.
    fn live_tab(&mut self, view: ViewKey) -> Option<&mut Tab> {
        self.tabs.get_mut(&view.tab).filter(|tab| tab.view == view)
    }

    fn handle_delegate_event(&mut self, event: DelegateEvent) {
        match event {
            DelegateEvent::CreateNew { opener, request } => self.open_page_tab(opener, request),
            DelegateEvent::History {
                view,
                document,
                entries,
                index,
            } => self.history_changed(view, document, entries, index),
            DelegateEvent::LoadStatus {
                view,
                document,
                phase,
            } => self.document_report(view, HeldReport::Load(document, phase)),
            DelegateEvent::Title {
                view,
                document,
                title,
            } => self.document_report(view, HeldReport::Title(document, title)),
            DelegateEvent::UnattributedTitle { view, title } => {
                if self.live_tab(view).is_some() {
                    self.emit(EngineEvent::UnattributedTitle { view, title });
                }
            },
            DelegateEvent::PageNavigationRequested { view, url } => {
                if self.live_tab(view).is_some() {
                    self.emit(EngineEvent::PageNavigationRequested {
                        view,
                        url: url.into(),
                    });
                }
            },
            DelegateEvent::FrameReady { view } => {
                if self.live_tab(view).is_some() && self.active == Some(view.tab) {
                    self.needs_paint = true;
                }
            },
            DelegateEvent::ImeShown {
                view,
                control,
                multiline,
                kind,
            } => {
                if self.live_tab(view).is_some() {
                    self.ime_owner = Some((view, control));
                    self.emit(EngineEvent::ImeShown {
                        view,
                        multiline,
                        kind,
                    });
                }
            },
            DelegateEvent::ClosedByPage { view } => {
                // Same as an application close; the application sees `tab_closed` for a tab it
                // did not close and closes the logical tab.
                if self.live_tab(view).is_some() {
                    self.close_tab(view.tab);
                }
            },
            DelegateEvent::PageControl { view, control } => self.show_page_control(view, control),
            DelegateEvent::ControlHidden { view, control } => {
                if self.ime_owner == Some((view, control)) {
                    self.ime_owner = None;
                    self.emit(EngineEvent::ImeHidden { view });
                }
                let hidden: Vec<u64> = self
                    .page_controls
                    .iter()
                    .filter(|(_, pending)| pending.view == view && pending.control.id() == control)
                    .map(|(id, _)| *id)
                    .collect();
                for id in hidden {
                    self.page_controls.remove(&id);
                    self.emit(EngineEvent::ControlHidden { view, control: id });
                }
            },
            DelegateEvent::DefaultHandled { view, request } => {
                self.emit(EngineEvent::DefaultHandled { view, request })
            },
            DelegateEvent::Crashed { view, reason } => {
                error!("view {view:?} crashed: {reason}");
                self.emit(EngineEvent::Crashed { view, reason });
            },
        }
    }

    fn show_page_control(&mut self, view: ViewKey, control: EmbedderControl) {
        if self.live_tab(view).is_none() {
            return;
        }
        let Some(description) = describe(&control) else {
            return;
        };
        let id = self.next_control;
        self.next_control += 1;
        self.page_controls
            .insert(id, PendingControl { view, control });
        self.emit(EngineEvent::ControlShown {
            view,
            control: id,
            description,
        });
    }

    fn respond_to_control(&mut self, id: u64, response: &str) {
        let Some(pending) = self.page_controls.remove(&id) else {
            // Hidden by the page or its tab closed while the user decided.
            return;
        };
        let response = match controls::parse_response(response) {
            Ok(response) => response,
            Err(error) => {
                self.error(error);
                return;
            },
        };
        match (pending.control, response) {
            (EmbedderControl::SelectElement(mut select), ControlResponse::Select { ids }) => {
                // Only offered, enabled options; a single select keeps at most one.
                let offered: HashSet<usize> = select_options(select.options())
                    .into_iter()
                    .filter(|option| !option.disabled)
                    .map(|option| option.id)
                    .collect();
                let mut chosen: Vec<usize> =
                    ids.into_iter().filter(|id| offered.contains(id)).collect();
                if !select.allow_select_multiple() {
                    chosen.truncate(1);
                }
                select.select(chosen);
                select.submit();
            },
            (
                EmbedderControl::SimpleDialog(SimpleDialog::Prompt(mut prompt)),
                ControlResponse::Text { value },
            ) => {
                prompt.set_current_value(&value);
                prompt.confirm();
            },
            (EmbedderControl::SimpleDialog(dialog), ControlResponse::Accept) => dialog.confirm(),
            (EmbedderControl::SimpleDialog(dialog), ControlResponse::Dismiss) => dialog.dismiss(),
            (EmbedderControl::FilePicker(mut picker), ControlResponse::Files { mut paths }) => {
                if !picker.allow_select_multiple() {
                    paths.truncate(1);
                }
                picker.select(&paths);
                picker.submit();
            },
            (EmbedderControl::FilePicker(picker), ControlResponse::Dismiss) => picker.dismiss(),
            // Dropping a select keeps its previous selection.
            (EmbedderControl::SelectElement(_), ControlResponse::Dismiss) => {},
            (_, response) => self.error(format!(
                "response {response:?} does not fit control {id}; answered with the default"
            )),
        }
    }

    fn history_changed(
        &mut self,
        view: ViewKey,
        document: Option<PipelineId>,
        entries: Vec<Url>,
        index: usize,
    ) {
        let Some(document) = document else {
            // Only possible without the document-identity patch.
            self.error("history change without a document ID; is the Servo patch applied?");
            return;
        };
        let sequence = self.next_sequence;
        self.next_sequence += 1;
        let Some(tab) = self.live_tab(view) else {
            return;
        };
        let url = entries.get(index).cloned();
        let length = entries.len();

        tab.documents.insert(u64::from(document), document);
        let change = if tab.current_document != Some(document) {
            if let Some(previous) = tab.current_document.replace(document) {
                tab.retired.insert(previous);
            }
            // A traversal can make an older document current again.
            tab.retired.remove(&document);
            HistoryChange::NewDocument
        } else if tab.current_url != url {
            HistoryChange::SameDocumentUrl
        } else {
            HistoryChange::ListOnly
        };
        tab.current_url = url.clone();

        // Release reports that overtook this commit; discard those of replaced documents.
        let mut released = Vec::new();
        if change == HistoryChange::NewDocument {
            let held = std::mem::take(&mut tab.held);
            for report in held {
                let reporter = match &report {
                    HeldReport::Load(document, _) | HeldReport::Title(document, _) => *document,
                };
                if reporter == document {
                    released.push(report);
                } else if !tab.retired.contains(&reporter) {
                    tab.held.push_back(report);
                }
            }
        }

        self.emit(EngineEvent::History {
            view,
            change,
            document: u64::from(document),
            sequence,
            url: url.map(String::from).unwrap_or_default(),
            index,
            length,
        });
        for report in released {
            self.emit_document_report(view, report, Attribution::HeldUntilCommit);
        }
    }

    fn document_report(&mut self, view: ViewKey, report: HeldReport) {
        let Some(tab) = self.live_tab(view) else {
            return;
        };
        let document = match &report {
            HeldReport::Load(document, _) | HeldReport::Title(document, _) => *document,
        };
        tab.documents.insert(u64::from(document), document);
        let attribution = tab.attribution(document);
        if attribution == Attribution::HeldUntilCommit {
            tab.hold(report);
            return;
        }
        self.emit_document_report(view, report, attribution);
    }

    fn emit_document_report(&self, view: ViewKey, report: HeldReport, attribution: Attribution) {
        self.emit(match report {
            HeldReport::Load(document, phase) => EngineEvent::LoadStatus {
                view,
                document: u64::from(document),
                phase,
                attribution,
            },
            HeldReport::Title(document, title) => EngineEvent::Title {
                view,
                document: u64::from(document),
                title,
                attribution,
            },
        });
    }

    // --- Painting --------------------------------------------------------------------------------

    fn paint(&mut self) {
        if !self.needs_paint {
            return;
        }
        let Some(runtime) = &self.runtime else {
            return;
        };
        if !self.surface.usable() {
            // Keep the request until a usable surface is bound again.
            return;
        }
        let context = &runtime.rendering_context;
        if let Err(error) = context.make_current() {
            // Keep the request: the next iteration retries.
            warn!("making the GL context current failed: {error:?}");
            return;
        }
        self.needs_paint = false;

        let Some(tab) = self.active.and_then(|tab| self.tabs.get_mut(&tab)) else {
            // No selected view: clear, so a closed page never stays on screen.
            let gl = context.gleam_gl_api();
            context.prepare_for_rendering();
            gl.clear_color(1.0, 1.0, 1.0, 1.0);
            gl.clear(gleam::gl::COLOR_BUFFER_BIT);
            context.present();
            return;
        };

        tab.webview.paint();
        context.present();
        tab.frames_painted += 1;

        let now = Instant::now();
        if tab
            .last_paint_report
            .is_none_or(|last| now.duration_since(last) >= PAINT_REPORT_INTERVAL)
        {
            tab.last_paint_report = Some(now);
            let (view, frames) = (tab.view, tab.frames_painted);
            self.emit(EngineEvent::Painted { view, frames });
        }
    }

    // --- Shutdown --------------------------------------------------------------------------------

    fn shutdown(&mut self) {
        info!("shutting down Servo");
        self.pending_captures.clear();
        self.page_controls.clear();
        self.active = None;
        // Close every view first: each WebView holds a Servo handle.
        self.tabs.clear();
        if let Some(runtime) = self.runtime.take() {
            let Runtime {
                servo,
                rendering_context,
            } = runtime;
            // Dropping the last handle asks the constellation to exit and spins until it has;
            // this is when Servo writes cookies and HSTS state to the profile directory.
            drop(servo);
            if let Surface::Bound { .. } = self.surface {
                if let Err(error) = surface_operation(|| rendering_context.take_window()) {
                    warn!("unbinding the surface during shutdown failed: {error}");
                    // Keep the window reference: EGL may still use it until the process ends.
                    if let Surface::Bound { _window, .. } =
                        std::mem::replace(&mut self.surface, Surface::Unbound)
                    {
                        std::mem::forget(_window);
                    }
                }
            }
            drop(rendering_context);
        }
        self.surface = Surface::Unbound;
        self.emit(EngineEvent::ShutdownComplete);
    }
}

/// Maps an Android key (character plus key code) to a DOM key.
/// What the application needs to present `control`; `None` for controls it does not present.
fn describe(control: &EmbedderControl) -> Option<ControlDescription> {
    let description = match control {
        EmbedderControl::SelectElement(select) => ControlDescription::Select {
            options: select_options(select.options()),
            selected: select.selected_options(),
            multiple: select.allow_select_multiple(),
        },
        EmbedderControl::SimpleDialog(SimpleDialog::Alert(alert)) => ControlDescription::Alert {
            message: alert.message().to_owned(),
        },
        EmbedderControl::SimpleDialog(SimpleDialog::Confirm(confirm)) => {
            ControlDescription::Confirm {
                message: confirm.message().to_owned(),
            }
        },
        EmbedderControl::SimpleDialog(SimpleDialog::Prompt(prompt)) => ControlDescription::Prompt {
            message: prompt.message().to_owned(),
            default: prompt.current_value().to_owned(),
        },
        EmbedderControl::FilePicker(picker) => ControlDescription::Files {
            multiple: picker.allow_select_multiple(),
            extensions: picker
                .filter_patterns()
                .iter()
                .map(|pattern| pattern.0.clone())
                .collect(),
        },
        _ => return None,
    };
    Some(description)
}

/// Flattens `<optgroup>`s: each option remembers its group's label.
fn select_options(entries: &[SelectElementOptionOrOptgroup]) -> Vec<SelectOption> {
    let option = |option: &SelectElementOption, group: Option<&String>| SelectOption {
        id: option.id,
        label: option.label.clone(),
        disabled: option.is_disabled,
        group: group.cloned(),
    };
    let mut options = Vec::new();
    for entry in entries {
        match entry {
            SelectElementOptionOrOptgroup::Option(single) => options.push(option(single, None)),
            SelectElementOptionOrOptgroup::Optgroup {
                label,
                options: grouped,
            } => options.extend(grouped.iter().map(|single| option(single, Some(label)))),
        }
    }
    options
}

fn servo_key(key: KeyInput) -> Option<Key> {
    if let Some(character) = char::from_u32(key.unicode).filter(|c| *c != '\0' && !c.is_control()) {
        return Some(Key::Character(character.to_string()));
    }
    // Values from android.view.KeyEvent.
    let named = match key.key_code {
        19 => NamedKey::ArrowUp,
        20 => NamedKey::ArrowDown,
        21 => NamedKey::ArrowLeft,
        22 => NamedKey::ArrowRight,
        61 => NamedKey::Tab,
        66 => NamedKey::Enter,
        67 => NamedKey::Backspace,
        111 => NamedKey::Escape,
        112 => NamedKey::Delete,
        122 => NamedKey::Home,
        123 => NamedKey::End,
        _ => return None,
    };
    Some(Key::Named(named))
}

/// Runs a rendering-context operation that may panic inside Servo or surfman (they `expect`
/// on some surface errors) and turns errors and panics into a message, so the engine thread
/// survives. The panic itself is logged by the panic hook; other engine-thread panics abort
/// (see `panic_policy`).
fn surface_operation<T, E: std::fmt::Debug>(
    operation: impl FnOnce() -> Result<T, E>,
) -> Result<T, String> {
    match panic_policy::recoverable(operation) {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(error)) => Err(format!("{error:?}")),
        Err(_) => Err("panicked (see log)".into()),
    }
}

fn js_to_json(value: &JSValue, depth: usize) -> Value {
    if depth > MAX_JSON_DEPTH {
        return Value::Null;
    }
    match value {
        JSValue::Undefined | JSValue::Null => Value::Null,
        JSValue::Boolean(value) => Value::Bool(*value),
        JSValue::Number(value) => Number::from_f64(*value).map_or(Value::Null, Value::Number),
        JSValue::String(value) => Value::String(value.clone()),
        JSValue::Element(handle)
        | JSValue::ShadowRoot(handle)
        | JSValue::Frame(handle)
        | JSValue::Window(handle) => {
            let mut object = Map::new();
            object.insert(
                capture::js_value_kind(value).into(),
                Value::String(handle.clone()),
            );
            Value::Object(object)
        },
        JSValue::Array(values) => Value::Array(
            values
                .iter()
                .map(|value| js_to_json(value, depth + 1))
                .collect(),
        ),
        JSValue::Object(fields) => Value::Object(
            fields
                .iter()
                .map(|(name, value)| (name.clone(), js_to_json(value, depth + 1)))
                .collect(),
        ),
    }
}

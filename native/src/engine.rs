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
use servo::AllowOrDenyRequest;
use servo::CreateNewWebViewRequest;
use servo::{
    AuthenticationRequest, CompositionEvent, CompositionState, DevicePoint, EmbedderControl,
    EmbedderControlId, EventLoopWaker, ImeEvent, InputEvent, JSValue, JavaScriptEvaluationError,
    Key, KeyState, KeyboardEvent, MouseButton, MouseButtonAction, MouseButtonEvent, NamedKey,
    NavigationRequest, Opts, PipelineId, PrefValue, Preferences, RenderingContext,
    SelectElementOption, SelectElementOptionOrOptgroup, Servo, ServoBuilder, SimpleDialog, Theme,
    TouchEvent, TouchEventType, TouchId, TouchPointerType, UserContentManager, UserScript, WebView,
    WebViewBuilder, WebViewPoint, WindowRenderingContext,
};
use url::Url;

use crate::activation::{OpeningTap, TapRecognizer};
use crate::animations;
use crate::capture::{self, CaptureKind, ScriptOutcome, Visibility};
use crate::commands::{
    Appearance, CaptureRequest, Command, KeyAction, KeyInput, LocalDocumentCheck, SURFACE_EPOCH,
    ScrollEdge, Throttling, TouchPhase,
};
use crate::controls::{self, ControlDescription, ControlResponse, SelectOption};
use crate::controls::{LeavePageAnswer, LeavePageDecision, PageActivation, TabVisibility};
use crate::delegate::{DelegateEvent, DelegateQueue, TabDelegate};
use crate::events::{
    Attribution, CaptureReport, CaptureStatus, EngineEvent, FindStatus, HistoryChange, LoadPhase,
    ViewKey,
};
use crate::find::{self, FindAction};
use crate::native_window::NativeWindow;
use crate::navigation::{self, PageNavigation, Shown};
use crate::page_zoom;
use crate::panic_policy;
use crate::profile;

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
/// How long a tap or key press lets the page hand one link to another app (as Chromium's
/// transient user activation).
const USER_ACTIVATION_WINDOW: Duration = Duration::from_secs(5);
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
    /// Scripts every view's documents run before their own (see `animations.js`).
    user_content: Rc<UserContentManager>,
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
    /// Documents already reported in `media_blocked`, so each is reported once.
    media_blocked: HashSet<PipelineId>,
    frames_painted: u64,
    last_paint_report: Option<Instant>,
    /// The document the user last touched or typed into. A page may ask to confirm leaving only
    /// while this is still its current document (browsers' sticky activation).
    interacted_document: Option<PipelineId>,
    /// The view whose page opened this one (`target=_blank`, `window.open`); None for the
    /// application's tabs.
    opener: Option<ViewKey>,
    /// Whether a document other than the initial `about:blank` committed: a window opened just
    /// for an app link has none and closes (`navigation::refusal`).
    shown: Shown,
    /// The tap that let the opener open this window, for the window's first navigation.
    opening_tap: OpeningTap,
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
    control: ControlHandle,
}

/// Servo's handle for a control: an embedder control, or a sign-in or leave-page request, which
/// Servo models separately. Dropping a sign-in request loads the server's 401 (or the proxy's
/// 407) response; dropping a leave-page request lets the page go.
enum ControlHandle {
    Embedder(EmbedderControl),
    SignIn(AuthenticationRequest),
    /// `beforeunload`: the page's script thread waits for the answer, so it is let go at
    /// `deadline` if the user has not answered.
    LeavePage {
        request: AllowOrDenyRequest,
        deadline: Instant,
    },
}

impl ControlHandle {
    /// None for sign-ins and leave-page questions: Servo never hides them, so they end when
    /// answered, when their view closes, or (leave-page) at their deadline.
    fn embedder_id(&self) -> Option<EmbedderControlId> {
        match self {
            ControlHandle::Embedder(control) => Some(control.id()),
            ControlHandle::SignIn(_) | ControlHandle::LeavePage { .. } => None,
        }
    }

    /// When an unanswered control is let go; only leave-page questions have one.
    fn deadline(&self) -> Option<Instant> {
        match self {
            ControlHandle::LeavePage { deadline, .. } => Some(*deadline),
            ControlHandle::Embedder(_) | ControlHandle::SignIn(_) => None,
        }
    }
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
/// A finished find script: the view, request ID and document it ran in, and its result.
type FinishedFind = (
    ViewKey,
    i64,
    u64,
    Result<JSValue, JavaScriptEvaluationError>,
);
type FindResults = Rc<RefCell<Vec<FinishedFind>>>;

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
    find_results: FindResults,
    pending_captures: HashMap<i64, PendingCapture>,
    page_controls: HashMap<u64, PendingControl>,
    next_control: u64,
    /// Page zoom of every view (`SetPageZoom`); kept here so views created later start at it.
    page_zoom: f32,
    /// The user's last tap or key press: in which tab, on which of its documents, and when.
    last_activation: Option<(i64, Option<PipelineId>, Instant)>,
    taps: TapRecognizer,
    /// Given to every view, so pages match the app's light or dark theme.
    appearance: Appearance,
    /// Fingers on the page now, and when the last touch event came: captures of the page on
    /// screen wait while the user scrolls or taps.
    touches: capture::Touches,
    /// Page-text captures of the page on screen waiting for a quiet moment, with when each
    /// first had to wait.
    deferred_captures: Vec<(CaptureRequest, Instant)>,
}

impl Engine {
    fn new(config: EngineConfig, wake: Sender<Command>, sink: Box<dyn EventSink + Send>) -> Self {
        // Touches arrive in the view's device pixels.
        let taps = TapRecognizer::new(config.density);
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
            find_results: Default::default(),
            pending_captures: HashMap::new(),
            page_controls: HashMap::new(),
            next_control: 1,
            page_zoom: page_zoom::DEFAULT_ZOOM,
            last_activation: None,
            taps,
            appearance: Appearance::Light,
            touches: Default::default(),
            deferred_captures: Vec::new(),
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
        let controls = self
            .page_controls
            .values()
            .filter_map(|pending| pending.control.deadline());
        // A capture waiting for the user to stop touching the page wakes the engine itself.
        let deferred = self
            .deferred_captures
            .iter()
            .map(|(_, since)| now.saturating_duration_since(*since))
            .max()
            .map(|longest| {
                capture::next_deferred_attempt(
                    self.touches.touching(now),
                    self.touches.since_last(now),
                    longest,
                )
            });
        self.pending_captures
            .values()
            .map(|pending| pending.deadline)
            .chain(controls)
            .map(|deadline| deadline.saturating_duration_since(now))
            .chain(deferred)
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
            Command::SetAppearance { appearance } => self.set_appearance(appearance),
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
            Command::ScrollToEdge {
                view,
                document,
                edge,
            } => self.scroll_to_edge(view, document, edge),
            Command::SetThrottling { tab, throttling } => {
                if let Some(tab) = self.tab(tab) {
                    tab.webview
                        .set_throttled(throttling == Throttling::Throttled);
                }
            },
            Command::SetPageZoom { zoom } => self.set_page_zoom(zoom),
            Command::Touch {
                phase,
                pointer,
                x,
                y,
            } => self.touch(phase, pointer, x, y),
            Command::Key { action, key } => self.key(action, key),
            Command::ImeCommit { text } => self.ime_commit(text),
            Command::ContextClick { x, y } => self.context_click(x, y),
            Command::RespondToControl { control, response } => {
                self.respond_to_control(control, &response)
            },
            Command::Capture(request) => self.start_capture(request),
            Command::Find {
                view,
                document,
                request,
                query,
                index,
            } => self.find(view, document, request, &query, index),
            Command::FindClear { view, document } => self.clear_find(view, document),
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
        self.start_deferred_captures();
        self.drain_capture_results();
        self.drain_evaluations();
        self.drain_find_results();
        self.expire_captures();
        self.expire_leave_page_questions();
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

    fn scroll_to_edge(&self, view: ViewKey, document_id: u64, edge: ScrollEdge) {
        if self.active != Some(view.tab) {
            return;
        }
        let Some(tab) = self.tabs.get(&view.tab).filter(|tab| tab.view == view) else {
            return;
        };
        let Some(&document) = tab.documents.get(&document_id) else {
            return;
        };
        if tab.current_document != Some(document) {
            return;
        }
        // Servo checks again at execution: a queued tap cannot jump the next document.
        tab.webview
            .evaluate_javascript_in_document(document, edge.script(), |result| {
                if result.is_err() {
                    warn!("scroll-to-edge failed");
                }
            });
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
        // Before Servo reads them back: saved sign-ins last one run (see `profile`).
        match profile::forget_saved_sign_ins(&self.config.profile_dir) {
            Ok(0) => {},
            Ok(_) => info!("deleted the sign-ins saved by an earlier run"),
            // Not an engine failure: the browser starts anyway, and an error while starting would
            // read as one (#65).
            Err(error) => warn!(
                "deleting saved sign-ins failed, so a proxy sign-in saved by an earlier run may be sent again: {error}"
            ),
        }
        let mut opts = Opts::default();
        opts.config_dir = Some(self.config.profile_dir.clone());
        let mut preferences = Preferences::default();
        preferences.set_value("viewport_meta_enabled", PrefValue::Bool(true));
        // Off by default in Servo 0.6.0 (servoshell lists it as experimental), but sites built
        // on Next.js call it on start-up and show an error page without it.
        preferences.set_value("dom_intersection_observer_enabled", PrefValue::Bool(true));

        let servo = ServoBuilder::default()
            .opts(opts)
            .preferences(preferences)
            .event_loop_waker(Box::new(ChannelWaker(self.wake.clone())))
            .build();

        let user_content = Rc::new(UserContentManager::new(&servo));
        user_content.add_script(Rc::new(UserScript::from(animations::page_script())));

        info!("Servo started at {}x{}", size.width, size.height);
        self.size = size;
        self.runtime = Some(Runtime {
            servo,
            rendering_context,
            user_content,
        });
        self.emit(EngineEvent::EngineReady {
            width: size.width,
            height: size.height,
        });
        true
    }

    fn surface_destroyed(&mut self) {
        // The gesture on this surface ends without its up events.
        self.touches.forget_pointers();
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
            .user_content_manager(runtime.user_content.clone())
            .delegate(Rc::new(TabDelegate::new(view, self.delegate_queue.clone())))
            .build();

        let webview_id = self.insert_tab(view, webview, None, OpeningTap::default());
        self.emit(EngineEvent::TabCreated {
            view,
            webview: webview_id,
        });
    }

    /// Registers a new view, hidden until selected so two shown views never overlap, at the
    /// page zoom every view uses. Servo builds a view at 100%, so its first document may lay out
    /// once more when the zoom arrives (as a resize).
    fn insert_tab(
        &mut self,
        view: ViewKey,
        webview: WebView,
        opener: Option<ViewKey>,
        opening_tap: OpeningTap,
    ) -> String {
        webview.hide();
        webview.set_page_zoom(self.page_zoom);
        // Servo starts every view light; documents it loads later inherit the view's theme.
        webview.notify_theme_change(servo_theme(self.appearance));
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
                media_blocked: HashSet::new(),
                frames_painted: 0,
                last_paint_report: None,
                interacted_document: None,
                opener,
                shown: Shown::InitialBlank,
                opening_tap,
            },
        );
        webview_id
    }

    /// A page asked for a new window. It becomes a hidden view with an engine-assigned tab ID;
    /// the application adopts it (`tab_opened`). Dropping the request refuses the window.
    fn open_page_tab(&mut self, opener: ViewKey, request: CreateNewWebViewRequest) {
        // Only the tab the user sees may open windows: hidden or throttled tabs cannot take
        // over the screen.
        if self.live_tab(opener).is_none() || self.active != Some(opener.tab) {
            return;
        }
        // And only right after the user's own tap or key press there, on the page it shows now:
        // Servo 0.6.0 opens every window.open() and target=_blank window, with or without one,
        // and each new tab could open the next. One tap opens at most one window.
        let Some(tapped_at) = self.take_document_activation(opener.tab) else {
            info!("window without the user's tap in the visible tab: refused");
            self.emit(EngineEvent::PopupBlocked { view: opener });
            return;
        };
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
            .user_content_manager(runtime.user_content.clone())
            .delegate(Rc::new(TabDelegate::new(view, self.delegate_queue.clone())))
            .build();
        // The window's first navigation is still the tap's: a target=_blank app link opens its app.
        self.insert_tab(view, webview, Some(opener), OpeningTap::at(tapped_at));
        self.emit(EngineEvent::TabOpened { view, opener });
    }

    /// Hides every view; the surface shows no page until a tab is selected again.
    fn select_none(&mut self) {
        for tab in self.tabs.values() {
            tab.webview.hide();
        }
        self.active = None;
        self.release_leave_page_questions_off_screen();
        self.touches.forget_pointers();
        self.needs_paint = true;
    }

    /// Applies `requested` to every view and keeps it for views created later. Servo lays each
    /// page out again and keeps a view's zoom across its navigations.
    fn set_page_zoom(&mut self, requested: f32) {
        let Some(zoom) = page_zoom::accepted(requested) else {
            self.error(format!("page zoom {requested} is not a zoom factor"));
            return;
        };
        self.page_zoom = zoom;
        for tab in self.tabs.values() {
            tab.webview.set_page_zoom(zoom);
        }
        // The shown page lays out again; paint it, as the other visual changes here do.
        self.needs_paint = true;
    }

    fn set_appearance(&mut self, appearance: Appearance) {
        self.appearance = appearance;
        for tab in self.tabs.values() {
            tab.webview.notify_theme_change(servo_theme(appearance));
        }
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
        self.release_leave_page_questions_off_screen();
        self.touches.forget_pointers();
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
            self.touches.forget_pointers();
            self.needs_paint = true;
        }
        if self.ime_owner.is_some_and(|(view, _)| view == closed.view) {
            self.ime_owner = None;
            self.emit(EngineEvent::ImeHidden { view: closed.view });
        }
        // Their answers could never reach the page; dropping sends Servo's defaults.
        self.hide_controls(|pending| pending.view == closed.view);
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
        // A scroll ends with Up too, so it counts, where browsers cancel the pointer once
        // scrolling starts: asking a little more often is the safe side for unsaved text.
        if phase == TouchPhase::Up {
            self.note_interaction();
        }
        // Followed even without a view to deliver to, so no gesture is left half seen.
        let tapped = match phase {
            TouchPhase::Down => {
                self.taps.down(pointer, x, y);
                false
            },
            TouchPhase::Move => {
                self.taps.moved(pointer, x, y);
                false
            },
            TouchPhase::Up => self.taps.up(pointer, x, y),
            TouchPhase::Cancel => {
                self.taps.cancel(pointer);
                false
            },
        };
        let now = Instant::now();
        match phase {
            TouchPhase::Down => self.touches.press(pointer, now),
            TouchPhase::Move => self.touches.moved(now),
            TouchPhase::Up => self.touches.lift(pointer, now),
            TouchPhase::Cancel => self.touches.cancel(now),
        }
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
        // Only a tap: the end of a scroll, fling or pinch must not let the page open an app.
        if tapped {
            self.note_activation();
        }
    }

    fn note_activation(&mut self) {
        let activation = self.active.map(|tab| {
            let document = self.tabs.get(&tab).and_then(|found| found.current_document);
            (tab, document, Instant::now())
        });
        self.last_activation = activation;
    }

    /// When the user tapped or pressed a key, if that was moments ago in one of `tabs` and one of
    /// them is still the visible tab. Using it up means one tap hands at most one link to another
    /// app.
    fn take_activation(&mut self, tabs: &[i64]) -> Option<Instant> {
        let (activated, _, at) = self.last_activation?;
        let recent = self.shows_one_of(tabs)
            && tabs.contains(&activated)
            && at.elapsed() <= USER_ACTIVATION_WINDOW;
        if !recent {
            return None;
        }
        self.last_activation = None;
        Some(at)
    }

    /// Like `take_activation`, for a page opening a window: the tap must also have been on the
    /// document `tab` shows now, as browsers tie activation to the document. Otherwise a tapped
    /// link's destination could open a pop-up as it loads.
    fn take_document_activation(&mut self, tab: i64) -> Option<Instant> {
        let (_, document, _) = self.last_activation?;
        let current = self.tabs.get(&tab).and_then(|found| found.current_document);
        if document.is_none() || document != current {
            return None;
        }
        self.take_activation(&[tab])
    }

    fn shows_one_of(&self, tabs: &[i64]) -> bool {
        self.active.is_some_and(|shown| tabs.contains(&shown))
    }

    fn key(&mut self, action: KeyAction, key: KeyInput) {
        if action == KeyAction::Press {
            self.note_interaction();
        }
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
        if action == KeyAction::Press {
            self.note_activation();
        }
    }

    /// A long-press: a secondary-button click, which makes Servo fire `contextmenu` and, unless
    /// the page cancels it, offer a context menu.
    fn context_click(&mut self, x: f32, y: f32) {
        let Some(webview) = self.active_webview() else {
            return;
        };
        let point: WebViewPoint = DevicePoint::new(x, y).into();
        for action in [MouseButtonAction::Down, MouseButtonAction::Up] {
            webview.notify_input_event(InputEvent::MouseButton(MouseButtonEvent::new(
                action,
                MouseButton::Secondary,
                point,
            )));
        }
    }

    /// The user touched or typed into the selected page: its current document may now ask to
    /// confirm leaving.
    fn note_interaction(&mut self) {
        if let Some(tab) = self.active.and_then(|tab| self.tabs.get_mut(&tab)) {
            tab.interacted_document = tab.current_document;
        }
    }

    fn ime_commit(&mut self, text: String) {
        if text.is_empty() {
            return;
        }
        // Typing: the page sees a key press (`Process`) with the text.
        self.note_interaction();
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

    /// Captures of page text for the page on screen wait while the user touches it: the
    /// script holds the page's script thread, so the page would stop responding under the
    /// finger. Other captures (hidden tabs, reader view) start at once.
    fn start_capture(&mut self, request: CaptureRequest) {
        if self.waits_for_quiet(&request, Duration::ZERO) {
            self.deferred_captures.push((request, Instant::now()));
            return;
        }
        self.run_capture(request);
    }

    fn waits_for_quiet(&self, request: &CaptureRequest, waited: Duration) -> bool {
        let now = Instant::now();
        request.kind == CaptureKind::PageText
            && self.active == Some(request.tab)
            && capture::defer_while_touched(
                self.touches.touching(now),
                self.touches.since_last(now),
                waited,
            )
    }

    fn start_deferred_captures(&mut self) {
        if self.deferred_captures.is_empty() {
            return;
        }
        let deferred = std::mem::take(&mut self.deferred_captures);
        for (request, since) in deferred {
            if self.waits_for_quiet(&request, since.elapsed()) {
                self.deferred_captures.push((request, since));
            } else {
                self.run_capture(request);
            }
        }
    }

    fn run_capture(&mut self, request: CaptureRequest) {
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

        let visibility = if self.active == Some(request.tab) {
            Visibility::Shown
        } else {
            Visibility::Hidden
        };
        let time_budget = capture::time_budget(visibility, request.timeout);
        let results = self.capture_results.clone();
        let (capture_id, expected_document) = (request.capture, request.document);
        tab.webview.evaluate_javascript_in_document(
            document,
            capture::script(request.kind, request.max_text_bytes, time_budget),
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

    // --- Find in page -------------------------------------------------------------------------

    /// Runs the find script in `document_id`, the document the app saw, and only while the view
    /// still shows it: a document that committed before the app heard of it is neither selected
    /// nor scrolled. Servo checks again when the script runs; the result names that document.
    fn find(&mut self, view: ViewKey, document_id: u64, request: i64, query: &str, index: i32) {
        let Some(found) = self.tabs.get(&view.tab).filter(|tab| tab.view == view) else {
            self.emit(EngineEvent::FindResult {
                view,
                document: None,
                request,
                status: FindStatus::TabClosed,
            });
            return;
        };
        let Some(document) = found
            .documents
            .get(&document_id)
            .copied()
            .filter(|&document| found.current_document == Some(document))
        else {
            self.emit(EngineEvent::FindResult {
                view,
                document: Some(document_id),
                request,
                status: FindStatus::StaleDocument,
            });
            return;
        };

        let results = self.find_results.clone();
        let ran_in = u64::from(document);
        found.webview.evaluate_javascript_in_document(
            document,
            find::script(query, FindAction::Find { index }),
            move |result| results.borrow_mut().push((view, request, ran_in, result)),
        );
    }

    /// Removes find's selection from `document_id` if the view still shows it; nothing to report.
    /// A replaced document took its selection with it.
    fn clear_find(&mut self, view: ViewKey, document_id: u64) {
        let Some(found) = self.tabs.get(&view.tab).filter(|tab| tab.view == view) else {
            return;
        };
        let Some(document) = found
            .documents
            .get(&document_id)
            .copied()
            .filter(|&document| found.current_document == Some(document))
        else {
            return;
        };
        found.webview.evaluate_javascript_in_document(
            document,
            find::script("", FindAction::Clear),
            |_| {},
        );
    }

    fn drain_find_results(&mut self) {
        let results: Vec<_> = self.find_results.borrow_mut().drain(..).collect();
        for (view, request, document, result) in results {
            // Re-check after the asynchronous hop, like captures: a replaced document's matches
            // must not reach the page now shown.
            let current = self
                .tabs
                .get(&view.tab)
                .filter(|tab| tab.view == view)
                .map(|tab| tab.current_document.map(u64::from));
            let status = match current {
                None => FindStatus::TabClosed,
                Some(current) if current != Some(document) => FindStatus::StaleDocument,
                Some(_) => match find::interpret_result(result) {
                    Ok(counts) => FindStatus::Found(counts),
                    Err(reason) => {
                        warn!("find request {request} failed: {reason}");
                        FindStatus::Failed
                    },
                },
            };
            self.emit(EngineEvent::FindResult {
                view,
                document: Some(document),
                request,
                status,
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
            DelegateEvent::PageNavigation { view, request } => self.page_navigation(view, request),
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
            DelegateEvent::PageControl { view, control } => {
                self.show_page_control(view, ControlHandle::Embedder(control))
            },
            DelegateEvent::SignIn { view, request } => {
                self.show_page_control(view, ControlHandle::SignIn(request))
            },
            DelegateEvent::LeavePage { view, request } => self.ask_to_leave_page(view, request),
            DelegateEvent::ControlHidden { view, control } => {
                if self.ime_owner == Some((view, control)) {
                    self.ime_owner = None;
                    self.emit(EngineEvent::ImeHidden { view });
                }
                self.hide_controls(|pending| {
                    pending.view == view && pending.control.embedder_id() == Some(control)
                });
            },
            DelegateEvent::DefaultHandled { view, request } => {
                self.emit(EngineEvent::DefaultHandled { view, request })
            },
            DelegateEvent::MediaBlocked { view } => self.media_blocked(view),
            DelegateEvent::Crashed { view, reason } => {
                error!("view {view:?} crashed: {reason}");
                self.emit(EngineEvent::Crashed { view, reason });
            },
        }
    }

    /// Reports cancelled media once per document, so a page with many media elements, or one
    /// that keeps retrying, yields one notice.
    ///
    /// A load names its view but not its document, so it is attributed to the tab's committed
    /// document. A load that overtook its document's commit would count for the previous one;
    /// Servo announces a document before parsing it and media loads start during parsing, so that
    /// is unlikely. Before the tab's first commit there is no document to name: nothing is
    /// reported (the load is still cancelled).
    fn media_blocked(&mut self, view: ViewKey) {
        let Some(tab) = self.live_tab(view) else {
            return;
        };
        let Some(document) = tab.current_document else {
            return;
        };
        if !tab.media_blocked.insert(document) {
            return;
        }
        self.emit(EngineEvent::MediaBlocked {
            view,
            document: u64::from(document),
        });
    }

    /// Web addresses load. A link for another app goes to the application only right after the
    /// user's own tap or key press in the visible tab, so a page or an embedded ad cannot open
    /// apps by itself; the page stays on screen either way. Anything else (`file:` ...) is
    /// refused. A window a page opened just for such a link closes instead of staying blank.
    fn page_navigation(&mut self, view: ViewKey, request: NavigationRequest) {
        let url = request.url.clone();
        // Used up by the window's first navigation, whatever it is.
        let opened_by_tap = self
            .live_tab(view)
            .is_some_and(|tab| tab.opening_tap.take(Instant::now(), USER_ACTIVATION_WINDOW));
        match navigation::classify(url.scheme()) {
            PageNavigation::Allow => {
                request.allow();
                if self.live_tab(view).is_some() {
                    self.emit(EngineEvent::PageNavigationRequested {
                        view,
                        url: url.into(),
                    });
                }
            },
            PageNavigation::Deny => {
                request.deny();
                // No address: it is the page's. The scheme says what it tried to open.
                info!("{} navigation refused", url.scheme());
                let Some(tab) = self.live_tab(view) else {
                    return;
                };
                if navigation::refusal(view, tab.opener, tab.shown).close {
                    self.close_tab(view.tab);
                }
            },
            PageNavigation::External => {
                request.deny();
                let bounded = url.as_str().chars().count() <= controls::MAX_URL_CHARS;
                let Some(tab) = self.live_tab(view) else {
                    return;
                };
                let refusal = navigation::refusal(view, tab.opener, tab.shown);
                // Only a link that can be handed over may use up the user's tap.
                let deliverable = bounded && self.live_tab(refusal.owner).is_some();
                // Before closing the window, which may be the visible tab: the user tapped in
                // the opener, or in the window itself. The tap that opened the window is spent
                // in the opener, so the window's first navigation brings it along.
                let owners = [refusal.owner.tab, view.tab];
                let tapped = deliverable
                    && ((opened_by_tap && self.shows_one_of(&owners))
                        || self.take_activation(&owners).is_some());
                if refusal.close {
                    // Reported like a page closing its own window, and before the link: the
                    // application drops the tab and shows the opener again, which then takes it.
                    self.close_tab(view.tab);
                }
                if !deliverable {
                    return;
                }
                if !tapped {
                    // No address: it is the page's. The scheme says what kind of app it wanted.
                    info!(
                        "{} link without the user's tap in the visible tab: refused",
                        url.scheme()
                    );
                    return;
                }
                self.emit(EngineEvent::ExternalLink {
                    view: refusal.owner,
                    url: url.into(),
                });
            },
        }
    }

    fn show_page_control(&mut self, view: ViewKey, control: ControlHandle) {
        if self.live_tab(view).is_none() {
            return;
        }
        let description = match &control {
            ControlHandle::Embedder(control) => describe(control),
            ControlHandle::SignIn(request) => Some(ControlDescription::SignIn {
                origin: request.url().origin().ascii_serialization(),
                proxy: request.for_proxy(),
            }),
            ControlHandle::LeavePage { .. } => Some(ControlDescription::LeavePage),
        };
        let Some(description) = description else {
            warn!("answering an unsupported page control with Servo's default");
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

    /// A page's `beforeunload` asks to confirm leaving it, and its script thread waits for the
    /// answer. Servo 0.6.0 asks for page-initiated navigations, reloads and `window.close()`
    /// only; the request names the view, not which of its documents (an iframe's) asks.
    fn ask_to_leave_page(&mut self, view: ViewKey, request: AllowOrDenyRequest) {
        let Some(tab) = self.live_tab(view) else {
            // Dropping the request lets the page go.
            return;
        };
        let activation = PageActivation::of(tab.interacted_document, tab.current_document);
        let visibility = if self.active == Some(view.tab) {
            TabVisibility::Selected
        } else {
            TabVisibility::Hidden
        };
        if controls::leave_page_decision(visibility, activation) == LeavePageDecision::Leave {
            request.allow();
            return;
        }

        let deadline = Instant::now() + controls::LEAVE_PAGE_TIMEOUT;
        self.show_page_control(view, ControlHandle::LeavePage { request, deadline });
    }

    /// An unanswered leave-page question keeps its page: its script thread cannot wait longer,
    /// and staying lets it go on as well as leaving does.
    fn expire_leave_page_questions(&mut self) {
        let now = Instant::now();
        self.keep_pages_of_questions(|pending| {
            pending
                .control
                .deadline()
                .is_some_and(|deadline| deadline <= now)
        });
    }

    /// Only the tab on screen can ask the user: a question from a tab that left it keeps its page.
    fn release_leave_page_questions_off_screen(&mut self) {
        let active = self.active;
        self.keep_pages_of_questions(|pending| Some(pending.view.tab) != active);
    }

    /// Answers the matching leave-page questions with Stay and tells the app they are gone. A
    /// navigation the user did not confirm can be repeated; unsaved text cannot be brought back.
    fn keep_pages_of_questions(&mut self, matches: impl Fn(&PendingControl) -> bool) {
        let settled: Vec<u64> = self
            .page_controls
            .iter()
            .filter(|(_, pending)| {
                matches!(pending.control, ControlHandle::LeavePage { .. }) && matches(pending)
            })
            .map(|(id, _)| *id)
            .collect();
        for id in settled {
            let Some(pending) = self.page_controls.remove(&id) else {
                continue;
            };
            if let ControlHandle::LeavePage { request, .. } = pending.control {
                request.deny();
            }
            self.emit(EngineEvent::ControlHidden {
                view: pending.view,
                control: id,
            });
        }
    }

    /// Drops the matching controls (Servo answers them with its defaults) and tells the app.
    fn hide_controls(&mut self, matches: impl Fn(&PendingControl) -> bool) {
        let hidden: Vec<u64> = self
            .page_controls
            .iter()
            .filter(|(_, pending)| matches(pending))
            .map(|(id, _)| *id)
            .collect();
        for id in hidden {
            if let Some(pending) = self.page_controls.remove(&id) {
                self.emit(EngineEvent::ControlHidden {
                    view: pending.view,
                    control: id,
                });
            }
        }
    }

    fn respond_to_control(&mut self, id: u64, response: &str) {
        let Some(pending) = self.page_controls.remove(&id) else {
            // Hidden by the page or its tab closed while the user decided.
            return;
        };
        let response = match controls::parse_response(response) {
            Ok(response) => response,
            Err(error) => {
                if let ControlHandle::LeavePage { request, .. } = pending.control {
                    // Stay: a navigation can be repeated, unsaved text cannot be brought back.
                    request.deny();
                    self.error(format!("{error}; leave-page question {id} kept its page"));
                    return;
                }
                self.error(format!(
                    "{error}; control {id} answered with Servo's default"
                ));
                return;
            },
        };
        let control = match pending.control {
            ControlHandle::Embedder(control) => control,
            ControlHandle::SignIn(request) => {
                match response {
                    ControlResponse::Credentials { username, password } => {
                        request.authenticate(username, password)
                    },
                    // Dropped: the navigation shows the 401 (or the proxy's 407) response.
                    ControlResponse::Dismiss => {},
                    response => self.error(format!(
                        "response {response:?} does not fit sign-in {id}; the sign-in was cancelled"
                    )),
                }
                return;
            },
            ControlHandle::LeavePage { request, .. } => {
                match controls::leave_page_answer(&response) {
                    Some(LeavePageAnswer::Leave) => {
                        request.allow();
                        // The answer stands in for the tap that started the navigation, which
                        // may be older than the activation window by now: a link for another
                        // app still goes through (#80).
                        if self.active == Some(pending.view.tab) {
                            self.note_activation();
                        }
                    },
                    Some(LeavePageAnswer::Stay) => request.deny(),
                    None => {
                        request.deny();
                        self.error(format!(
                            "response {response:?} does not fit leave-page question {id}; the page was kept"
                        ));
                    },
                }
                return;
            },
        };
        match (control, response) {
            (EmbedderControl::SelectElement(mut select), ControlResponse::Select { ids }) => {
                // Only offered, enabled options, each once; a single select keeps at most one.
                let mut offered: HashSet<usize> = select_options(select.options())
                    .into_iter()
                    .filter(|option| !option.disabled)
                    .map(|option| option.id)
                    .collect();
                let mut chosen: Vec<usize> =
                    ids.into_iter().filter(|id| offered.remove(id)).collect();
                if !select.allow_select_multiple() {
                    chosen.truncate(1);
                    // A single select has no empty answer: keep the previous selection.
                    if chosen.is_empty() {
                        return;
                    }
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
            // The application performed the action itself.
            (EmbedderControl::ContextMenu(menu), ControlResponse::Dismiss) => menu.dismiss(),
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
            tab.shown = tab.shown.after_commit(url.as_ref().map_or("", Url::as_str));
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

        if change == HistoryChange::NewDocument {
            // Another document replaced the one asking (a load the application started): its
            // question is moot.
            self.hide_controls(|pending| {
                pending.view == view && matches!(pending.control, ControlHandle::LeavePage { .. })
            });
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
                user_content,
            } = runtime;
            // The user content manager holds a Servo handle of its own.
            drop(user_content);
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
        // Only links and images: the bottom bar has back, forward and reload, and editing
        // actions need a clipboard bridge the app does not have.
        EmbedderControl::ContextMenu(menu) => {
            let info = menu.element_info();
            return controls::link_menu(
                info.link_url.as_ref().map(Url::as_str),
                info.image_url.as_ref().map(Url::as_str),
            );
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

fn servo_theme(appearance: Appearance) -> Theme {
    match appearance {
        Appearance::Light => Theme::Light,
        Appearance::Dark => Theme::Dark,
    }
}

/// Maps an Android key (character plus key code) to a DOM key.
/// Mirrored by `EngineKeys.kt` in the engine module: change both together.
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

//! Events the engine reports to the embedding application.
//!
//! Every event that concerns a tab carries a [`ViewKey`]: the logical tab ID chosen by the
//! application plus the engine generation of the native view. Document-related events also carry
//! the document ID (Servo's pipeline ID for the top-level document). Events cross the JNI
//! boundary as one JSON object each, so the Kotlin side never sees a native handle.

use serde_json::{Value, json};

use crate::controls::ControlDescription;

/// One native incarnation of a logical tab.
///
/// Recreating a view for the same tab yields a new generation, so late callbacks from the old
/// view can be told apart and ignored.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct ViewKey {
    pub tab: i64,
    pub generation: u64,
}

impl ViewKey {
    fn fields(&self) -> Value {
        json!({ "tab": self.tab, "generation": self.generation })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LoadPhase {
    Started,
    HeadParsed,
    Complete,
}

impl LoadPhase {
    fn name(self) -> &'static str {
        match self {
            LoadPhase::Started => "started",
            LoadPhase::HeadParsed => "head_parsed",
            LoadPhase::Complete => "complete",
        }
    }
}

/// How the engine attributed a document-scoped report (title, load status).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Attribution {
    /// The reporting document is the tab's current document.
    Current,
    /// The report arrived before the document's commit and was held until the commit.
    HeldUntilCommit,
    /// The reporting document was replaced; the report must not change tab state.
    Stale,
}

impl Attribution {
    fn name(self) -> &'static str {
        match self {
            Attribution::Current => "current",
            Attribution::HeldUntilCommit => "held_until_commit",
            Attribution::Stale => "stale",
        }
    }
}

/// How a history notification relates to the tab's previous state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HistoryChange {
    /// A different top-level document became current (navigation, reload, traversal).
    NewDocument,
    /// Same document, different URL (fragment navigation, `pushState`).
    SameDocumentUrl,
    /// Same document and URL; only the session history list changed.
    ListOnly,
}

impl HistoryChange {
    fn name(self) -> &'static str {
        match self {
            HistoryChange::NewDocument => "document_committed",
            HistoryChange::SameDocumentUrl => "same_document_navigation",
            HistoryChange::ListOnly => "history_updated",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaptureStatus {
    /// Text was captured from the expected document, which is still current.
    Captured,
    /// The expected document is current but contained no eligible text.
    Empty,
    /// The script ran in the expected document, but another document became current before
    /// the result arrived. The text is discarded.
    StaleDocument,
    /// Servo refused to run the script because the expected document was no longer current.
    DocumentNotFound,
    /// The engine never saw the requested document in this tab.
    UnknownDocument,
    /// The tab, or its native view generation, closed before the result arrived.
    TabClosed,
    /// No result arrived before the deadline. Any later result is ignored.
    TimedOut,
    /// The tab does not exist.
    NoSuchTab,
    /// The script result did not have the expected shape.
    Malformed,
    /// The script threw, failed to compile, or could not be serialized.
    ScriptError,
}

impl CaptureStatus {
    fn name(self) -> &'static str {
        match self {
            CaptureStatus::Captured => "captured",
            CaptureStatus::Empty => "empty",
            CaptureStatus::StaleDocument => "stale_document",
            CaptureStatus::DocumentNotFound => "document_not_found",
            CaptureStatus::UnknownDocument => "unknown_document",
            CaptureStatus::TabClosed => "tab_closed",
            CaptureStatus::TimedOut => "timed_out",
            CaptureStatus::NoSuchTab => "no_such_tab",
            CaptureStatus::Malformed => "malformed",
            CaptureStatus::ScriptError => "script_error",
        }
    }
}

/// Content returned by a successful capture script, after native bounds were enforced.
#[derive(Clone, Debug)]
pub struct CapturedContent {
    pub url: String,
    pub title: String,
    pub text: String,
    pub text_bytes: usize,
    pub sha256: String,
    /// The page script stopped at the byte budget.
    pub truncated_in_page: bool,
    /// Native code had to cut the text again (the page returned more than the budget).
    pub truncated_natively: bool,
    pub nodes_visited: u64,
    pub node_budget_exhausted: bool,
}

#[derive(Clone, Debug)]
pub struct CaptureReport {
    pub capture: i64,
    pub view: ViewKey,
    pub document: u64,
    pub status: CaptureStatus,
    pub elapsed_ms: u128,
    /// Present only for [`CaptureStatus::Captured`] and [`CaptureStatus::Empty`].
    pub content: Option<CapturedContent>,
    /// URL reported by the script for statuses whose text is withheld (corroboration only).
    pub observed_url: Option<String>,
    pub detail: Option<String>,
}

pub enum EngineEvent {
    EngineReady {
        width: u32,
        height: u32,
    },
    SurfaceBound {
        width: u32,
        height: u32,
    },
    SurfaceUnbound,
    TabCreated {
        view: ViewKey,
        webview: String,
    },
    TabSelected {
        view: ViewKey,
    },
    TabClosed {
        view: ViewKey,
    },
    /// A page opened a new window (`target=_blank`, `window.open`): a new hidden view with an
    /// engine-assigned tab ID, which the application adopts as a logical tab.
    TabOpened {
        view: ViewKey,
        opener: ViewKey,
    },
    NavigationRequested {
        view: ViewKey,
        request: i64,
        url: String,
    },
    PageNavigationRequested {
        view: ViewKey,
        url: String,
    },
    History {
        view: ViewKey,
        change: HistoryChange,
        document: u64,
        sequence: u64,
        url: String,
        index: usize,
        length: usize,
    },
    LoadStatus {
        view: ViewKey,
        document: u64,
        phase: LoadPhase,
        attribution: Attribution,
    },
    Title {
        view: ViewKey,
        document: u64,
        title: Option<String>,
        attribution: Attribution,
    },
    /// The title an embedder relying only on upstream's `notify_page_title_changed` would
    /// display. Reported for comparison with [`EngineEvent::Title`].
    UnattributedTitle {
        view: ViewKey,
        title: Option<String>,
    },
    Capture(CaptureReport),
    LateCaptureResult {
        capture: i64,
    },
    Evaluation {
        view: ViewKey,
        request: i64,
        current_document_at_completion: Option<u64>,
        result: Result<Value, String>,
    },
    Painted {
        view: ViewKey,
        frames: u64,
    },
    ImeShown {
        view: ViewKey,
        /// The field's document: Servo's pipeline ID of its frame (the top-level document's ID
        /// unless the field is in a frame).
        document: u64,
        multiline: bool,
        /// Field type: "text", "password", "email", "url", "number", "tel" or "search".
        kind: &'static str,
        /// Whether to open the keyboard. Before the user has used the page, Servo asks for the
        /// field without it (`InputMethodControl::allow_virtual_keyboard`).
        show_keyboard: bool,
    },
    ImeHidden {
        view: ViewKey,
    },
    DefaultHandled {
        view: ViewKey,
        request: &'static str,
    },
    Crashed {
        view: ViewKey,
        reason: String,
    },
    /// A page control waits for an answer; `control` names it in `respondToControl`.
    ControlShown {
        view: ViewKey,
        control: u64,
        description: ControlDescription,
    },
    /// The page withdrew the control, or its tab closed; any answer is ignored.
    ControlHidden {
        view: ViewKey,
        control: u64,
    },
    /// A request was refused or an operation failed. `tab` and `request` identify the
    /// request when there is one (capture ID for captures).
    Error {
        message: String,
        tab: Option<i64>,
        request: Option<i64>,
    },
    ShutdownComplete,
    /// Whether every profile file was written.
    ProfilePersisted {
        ok: bool,
    },
}

impl EngineEvent {
    pub fn to_json(&self) -> String {
        let mut value = match self {
            EngineEvent::EngineReady { width, height } => {
                json!({ "type": "engine_ready", "width": width, "height": height })
            },
            EngineEvent::SurfaceBound { width, height } => {
                json!({ "type": "surface_bound", "width": width, "height": height })
            },
            EngineEvent::SurfaceUnbound => json!({ "type": "surface_unbound" }),
            EngineEvent::TabCreated { view, webview } => {
                with_view(view, json!({ "type": "tab_created", "webview": webview }))
            },
            EngineEvent::TabSelected { view } => with_view(view, json!({ "type": "tab_selected" })),
            EngineEvent::TabClosed { view } => with_view(view, json!({ "type": "tab_closed" })),
            EngineEvent::TabOpened { view, opener } => with_view(
                view,
                json!({
                    "type": "tab_opened",
                    "opener_tab": opener.tab,
                    "opener_generation": opener.generation,
                }),
            ),
            EngineEvent::NavigationRequested { view, request, url } => with_view(
                view,
                json!({ "type": "navigation_requested", "request": request, "url": url }),
            ),
            EngineEvent::PageNavigationRequested { view, url } => with_view(
                view,
                json!({ "type": "page_navigation_requested", "url": url }),
            ),
            EngineEvent::History {
                view,
                change,
                document,
                sequence,
                url,
                index,
                length,
            } => with_view(
                view,
                json!({
                    "type": change.name(),
                    "document": document,
                    "sequence": sequence,
                    "url": url,
                    "index": index,
                    "length": length,
                }),
            ),
            EngineEvent::LoadStatus {
                view,
                document,
                phase,
                attribution,
            } => with_view(
                view,
                json!({
                    "type": "load_status",
                    "document": document,
                    "phase": phase.name(),
                    "attribution": attribution.name(),
                }),
            ),
            EngineEvent::Title {
                view,
                document,
                title,
                attribution,
            } => with_view(
                view,
                json!({
                    "type": "title",
                    "document": document,
                    "title": title,
                    "attribution": attribution.name(),
                }),
            ),
            EngineEvent::UnattributedTitle { view, title } => with_view(
                view,
                json!({ "type": "unattributed_title", "title": title }),
            ),
            EngineEvent::Capture(report) => capture_json(report),
            EngineEvent::LateCaptureResult { capture } => {
                json!({ "type": "late_capture_result", "capture": capture })
            },
            EngineEvent::Evaluation {
                view,
                request,
                current_document_at_completion,
                result,
            } => {
                let (ok, value, error) = match result {
                    Ok(value) => (true, value.clone(), Value::Null),
                    Err(error) => (false, Value::Null, Value::String(error.clone())),
                };
                with_view(
                    view,
                    json!({
                        "type": "evaluation",
                        "request": request,
                        "current_document_at_completion": current_document_at_completion,
                        "ok": ok,
                        "value": value,
                        "error": error,
                    }),
                )
            },
            EngineEvent::Painted { view, frames } => {
                with_view(view, json!({ "type": "painted", "frames": frames }))
            },
            EngineEvent::ImeShown {
                view,
                document,
                multiline,
                kind,
                show_keyboard,
            } => with_view(
                view,
                json!({
                    "type": "ime_shown",
                    "document": document,
                    "multiline": multiline,
                    "kind": kind,
                    "show_keyboard": show_keyboard,
                }),
            ),
            EngineEvent::ImeHidden { view } => with_view(view, json!({ "type": "ime_hidden" })),
            EngineEvent::DefaultHandled { view, request } => with_view(
                view,
                json!({ "type": "default_handled", "request": request }),
            ),
            EngineEvent::Crashed { view, reason } => {
                with_view(view, json!({ "type": "crashed", "reason": reason }))
            },
            EngineEvent::ControlShown {
                view,
                control,
                description,
            } => {
                let mut value = description.to_json();
                value["type"] = json!("control_shown");
                value["control"] = json!(control);
                with_view(view, value)
            },
            EngineEvent::ControlHidden { view, control } => with_view(
                view,
                json!({ "type": "control_hidden", "control": control }),
            ),
            EngineEvent::Error {
                message,
                tab,
                request,
            } => json!({ "type": "error", "message": message, "tab": tab, "request": request }),
            EngineEvent::ShutdownComplete => json!({ "type": "shutdown_complete" }),
            EngineEvent::ProfilePersisted { ok } => {
                json!({ "type": "profile_persisted", "ok": ok })
            },
        };

        if let Value::Object(map) = &mut value {
            map.insert("schema".into(), Value::from(1));
        }
        value.to_string()
    }
}

fn with_view(view: &ViewKey, mut value: Value) -> Value {
    if let (Value::Object(map), Value::Object(view_fields)) = (&mut value, view.fields()) {
        map.extend(view_fields);
    }
    value
}

fn capture_json(report: &CaptureReport) -> Value {
    let content = report.content.as_ref().map(|content| {
        json!({
            "url": content.url,
            "title": content.title,
            "text": content.text,
            "text_bytes": content.text_bytes,
            "sha256": content.sha256,
            "truncated_in_page": content.truncated_in_page,
            "truncated_natively": content.truncated_natively,
            "nodes_visited": content.nodes_visited,
            "node_budget_exhausted": content.node_budget_exhausted,
        })
    });

    with_view(
        &report.view,
        json!({
            "type": "capture",
            "capture": report.capture,
            "document": report.document,
            "status": report.status.name(),
            "elapsed_ms": report.elapsed_ms as u64,
            "content": content,
            "observed_url": report.observed_url,
            "detail": report.detail,
        }),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn control_shown_carries_view_id_and_description() {
        let event = EngineEvent::ControlShown {
            view: ViewKey {
                tab: 3,
                generation: 9,
            },
            control: 7,
            description: ControlDescription::Confirm {
                message: "Leave?".into(),
            },
        };
        let json: Value = serde_json::from_str(&event.to_json()).unwrap();

        assert_eq!(
            json,
            json!({
                "schema": 1,
                "type": "control_shown",
                "control": 7,
                "kind": "confirm",
                "message": "Leave?",
                "tab": 3,
                "generation": 9,
            })
        );
    }

    #[test]
    fn ime_shown_says_whether_to_open_the_keyboard() {
        let event = EngineEvent::ImeShown {
            view: ViewKey {
                tab: 3,
                generation: 9,
            },
            document: 42,
            multiline: false,
            kind: "search",
            show_keyboard: false,
        };
        let json: Value = serde_json::from_str(&event.to_json()).unwrap();

        assert_eq!(
            json,
            json!({
                "schema": 1,
                "type": "ime_shown",
                "document": 42,
                "multiline": false,
                "kind": "search",
                "show_keyboard": false,
                "tab": 3,
                "generation": 9,
            })
        );
    }
}

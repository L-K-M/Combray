//! Servo `WebViewDelegate` for one native view.
//!
//! Servo calls delegates from inside `Servo::spin_event_loop`. Calling back into the same
//! `WebView` from there risks re-entrant `RefCell` borrows, so the delegate only records owned,
//! identified copies of each notification. The engine processes the queue after the spin.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::rc::Rc;

use servo::{
    CreateNewWebViewRequest, EmbedderControl, EmbedderControlId, InputMethodType, LoadStatus,
    NavigationRequest, PermissionRequest, PipelineId, WebView, WebViewDelegate,
};
use url::Url;

use crate::events::{LoadPhase, ViewKey};

pub enum DelegateEvent {
    /// Kept whole: the engine builds the new view after the spin, outside this callback.
    CreateNew {
        opener: ViewKey,
        request: CreateNewWebViewRequest,
    },
    History {
        view: ViewKey,
        document: Option<PipelineId>,
        entries: Vec<Url>,
        index: usize,
    },
    LoadStatus {
        view: ViewKey,
        document: PipelineId,
        phase: LoadPhase,
    },
    Title {
        view: ViewKey,
        document: PipelineId,
        title: Option<String>,
    },
    UnattributedTitle {
        view: ViewKey,
        title: Option<String>,
    },
    PageNavigationRequested {
        view: ViewKey,
        url: Url,
    },
    FrameReady {
        view: ViewKey,
    },
    ImeShown {
        view: ViewKey,
        control: EmbedderControlId,
        multiline: bool,
        kind: &'static str,
    },
    /// A `<select>` picker, script dialog or file picker; kept whole until answered.
    PageControl {
        view: ViewKey,
        control: EmbedderControl,
    },
    /// The page closed its own window (`window.close()`); Servo has discarded the view.
    ClosedByPage {
        view: ViewKey,
    },
    /// Servo withdrew a control it showed (keyboard or page control).
    ControlHidden {
        view: ViewKey,
        control: EmbedderControlId,
    },
    DefaultHandled {
        view: ViewKey,
        request: &'static str,
    },
    Crashed {
        view: ViewKey,
        reason: String,
    },
}

pub type DelegateQueue = Rc<RefCell<VecDeque<DelegateEvent>>>;

pub struct TabDelegate {
    view: ViewKey,
    queue: DelegateQueue,
}

impl TabDelegate {
    pub fn new(view: ViewKey, queue: DelegateQueue) -> Self {
        Self { view, queue }
    }

    fn push(&self, event: DelegateEvent) {
        self.queue.borrow_mut().push_back(event);
    }
}

impl WebViewDelegate for TabDelegate {
    fn notify_history_changed(&self, webview: WebView, entries: Vec<Url>, current: usize) {
        // `active_document_id` is updated before this notification (Combray Servo patch).
        self.push(DelegateEvent::History {
            view: self.view,
            document: webview.active_document_id(),
            entries,
            index: current,
        });
    }

    fn notify_document_load_status_changed(
        &self,
        _webview: WebView,
        document_id: PipelineId,
        status: LoadStatus,
    ) {
        let phase = match status {
            LoadStatus::Started => LoadPhase::Started,
            LoadStatus::HeadParsed => LoadPhase::HeadParsed,
            LoadStatus::Complete => LoadPhase::Complete,
        };
        self.push(DelegateEvent::LoadStatus {
            view: self.view,
            document: document_id,
            phase,
        });
    }

    fn notify_document_title_changed(
        &self,
        _webview: WebView,
        document_id: PipelineId,
        title: Option<String>,
    ) {
        self.push(DelegateEvent::Title {
            view: self.view,
            document: document_id,
            title,
        });
    }

    fn notify_page_title_changed(&self, _webview: WebView, title: Option<String>) {
        self.push(DelegateEvent::UnattributedTitle {
            view: self.view,
            title,
        });
    }

    fn notify_new_frame_ready(&self, _webview: WebView) {
        self.push(DelegateEvent::FrameReady { view: self.view });
    }

    fn notify_crashed(&self, _webview: WebView, reason: String, _backtrace: Option<String>) {
        self.push(DelegateEvent::Crashed {
            view: self.view,
            reason,
        });
    }

    fn request_navigation(&self, _webview: WebView, navigation_request: NavigationRequest) {
        // M0 allows every page-initiated navigation (dropping the request allows it) but reports
        // it, so tests can tell page navigations from embedder requests.
        self.push(DelegateEvent::PageNavigationRequested {
            view: self.view,
            url: navigation_request.url.clone(),
        });
    }

    fn request_create_new(&self, _parent_webview: WebView, request: CreateNewWebViewRequest) {
        // Building the view here would re-enter Servo; the engine does it after the spin.
        self.push(DelegateEvent::CreateNew {
            opener: self.view,
            request,
        });
    }

    fn request_permission(&self, _webview: WebView, request: PermissionRequest) {
        // The plan forbids auto-granting device capabilities; M0 has no permission UI.
        request.deny();
        self.push(DelegateEvent::DefaultHandled {
            view: self.view,
            request: "permission_denied",
        });
    }

    fn show_embedder_control(&self, _webview: WebView, embedder_control: EmbedderControl) {
        let input_method = match embedder_control {
            EmbedderControl::InputMethod(input_method) => input_method,
            control @ (EmbedderControl::SelectElement(_)
            | EmbedderControl::SimpleDialog(_)
            | EmbedderControl::FilePicker(_)
            | EmbedderControl::ContextMenu(_)) => {
                self.push(DelegateEvent::PageControl {
                    view: self.view,
                    control,
                });
                return;
            },
            // Colour pickers get Servo's default response when dropped.
            _ => {
                self.push(DelegateEvent::DefaultHandled {
                    view: self.view,
                    request: "embedder_control",
                });
                return;
            },
        };
        // The field type lets the keyboard behave: no suggestions or learning for passwords.
        let kind = match input_method.input_method_type() {
            InputMethodType::Password => "password",
            InputMethodType::Email => "email",
            InputMethodType::Url => "url",
            InputMethodType::Number => "number",
            InputMethodType::Tel => "tel",
            InputMethodType::Search => "search",
            _ => "text",
        };
        self.push(DelegateEvent::ImeShown {
            view: self.view,
            control: input_method.id(),
            multiline: input_method.multiline(),
            kind,
        });
    }

    fn notify_closed(&self, _webview: WebView) {
        self.push(DelegateEvent::ClosedByPage { view: self.view });
    }

    fn hide_embedder_control(&self, _webview: WebView, control_id: EmbedderControlId) {
        self.push(DelegateEvent::ControlHidden {
            view: self.view,
            control: control_id,
        });
    }
}

//! Page controls Servo asks the embedder to present: `<select>` pickers, script dialogs
//! (`alert`, `confirm`, `prompt`), file pickers, link menus, HTTP sign-in and leave-page
//! questions (`beforeunload`).
//!
//! The engine keeps each control's Servo handle until the application answers or the page hides
//! the control. Only a bounded description crosses JNI; the answer comes back as JSON:
//!
//! ```text
//!  page ─▶ Servo ─show_embedder_control─▶ engine (keeps handle, id 7)
//!                                            │ control_shown {control: 7, kind: "confirm", ...}
//!                                            ▼
//!                                         application dialog
//!                                            │ respondToControl(7, {"type":"dismiss"})
//!  page ◀─ Servo ◀──────── dismiss() ────────┘
//! ```
//!
//! Dropping a handle without an answer makes Servo use its default (dismiss, keep selection).

use std::fmt;
use std::path::PathBuf;
use std::time::Duration;

use serde::Deserialize;
use serde_json::error::Category;
use serde_json::{Value, json};

use crate::userinfo::strip_userinfo;

/// Page-chosen strings are shown in a dialog, not stored: keep them readable and bounded.
pub const MAX_MESSAGE_CHARS: usize = 4_000;
pub const MAX_LABEL_CHARS: usize = 300;
/// A `<select>` with more entries is shown truncated; the rest cannot be picked.
pub const MAX_OPTIONS: usize = 2_000;
pub const MAX_FILE_EXTENSIONS: usize = 50;
/// Longer link addresses are left out of a link menu rather than cut (a cut URL is wrong).
pub const MAX_URL_CHARS: usize = 32_768;
/// How long a leave-page question waits for an answer (proposed default). The asking page's
/// script thread, and every same-site tab sharing it, is blocked until then, so an unanswered
/// question is answered Stay: the page is kept and its script goes on.
pub const LEAVE_PAGE_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SelectOption {
    /// Servo's option ID, used to answer.
    pub id: usize,
    pub label: String,
    pub disabled: bool,
    /// Label of the enclosing `<optgroup>`, if any.
    pub group: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ControlDescription {
    Select {
        options: Vec<SelectOption>,
        selected: Vec<usize>,
        multiple: bool,
    },
    Alert {
        message: String,
    },
    Confirm {
        message: String,
    },
    Prompt {
        message: String,
        default: String,
    },
    /// `extensions` come from the `accept` attribute, without the dot ("png", "pdf").
    Files {
        multiple: bool,
        extensions: Vec<String>,
    },
    /// A long-press on a link or image; the application acts on the addresses itself.
    LinkMenu {
        link: Option<String>,
        image: Option<String>,
    },
    /// HTTP authentication for a navigation. `origin` is the server asking (scheme, host and
    /// port), which may differ from the page that linked to it.
    SignIn {
        origin: String,
        proxy: bool,
    },
    /// The page asks to confirm leaving it (`beforeunload`), usually over unsaved form text.
    /// Servo passes no page message, as browsers no longer show one.
    LeavePage,
}

impl ControlDescription {
    /// Fields added to the `control_shown` event.
    pub fn to_json(&self) -> Value {
        match self {
            ControlDescription::Select {
                options,
                selected,
                multiple,
            } => {
                let sent = &options[..options.len().min(MAX_OPTIONS)];
                // The page chooses the selection too: name only options that are sent.
                let selected: Vec<usize> = selected
                    .iter()
                    .copied()
                    .filter(|id| sent.iter().any(|option| option.id == *id))
                    .collect();
                let options: Vec<Value> = sent
                    .iter()
                    .map(|option| {
                        json!({
                            "id": option.id,
                            "label": bounded(&option.label, MAX_LABEL_CHARS),
                            "disabled": option.disabled,
                            "group": option.group.as_deref().map(|group| bounded(group, MAX_LABEL_CHARS)),
                        })
                    })
                    .collect();
                json!({ "kind": "select", "options": options, "selected": selected, "multiple": multiple })
            },
            ControlDescription::Alert { message } => {
                json!({ "kind": "alert", "message": bounded(message, MAX_MESSAGE_CHARS) })
            },
            ControlDescription::Confirm { message } => {
                json!({ "kind": "confirm", "message": bounded(message, MAX_MESSAGE_CHARS) })
            },
            ControlDescription::Prompt { message, default } => {
                // An unedited field is returned to the page: leave out a default too long to show.
                let default = if default.chars().count() <= MAX_MESSAGE_CHARS {
                    default.as_str()
                } else {
                    ""
                };
                json!({
                    "kind": "prompt",
                    "message": bounded(message, MAX_MESSAGE_CHARS),
                    "default": default,
                })
            },
            ControlDescription::Files {
                multiple,
                extensions,
            } => {
                let extensions: Vec<String> = extensions
                    .iter()
                    .take(MAX_FILE_EXTENSIONS)
                    .map(|extension| bounded(extension, MAX_LABEL_CHARS))
                    .collect();
                json!({ "kind": "files", "multiple": multiple, "extensions": extensions })
            },
            // Links on a page signed in to over HTTP carry its credentials (see `userinfo`).
            ControlDescription::LinkMenu { link, image } => json!({
                "kind": "link_menu",
                "link": link.as_deref().map(strip_userinfo),
                "image": image.as_deref().map(strip_userinfo),
            }),
            ControlDescription::SignIn { origin, proxy } => {
                json!({ "kind": "sign_in", "origin": bounded(origin, MAX_LABEL_CHARS), "proxy": proxy })
            },
            ControlDescription::LeavePage => json!({ "kind": "leave_page" }),
        }
    }
}

/// Whether the tab asking to confirm leaving is the one on screen.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TabVisibility {
    Selected,
    Hidden,
}

/// Whether the user touched or typed into the asking page's current document: browsers' sticky
/// user activation, which Servo 0.6.0 does not check before asking.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PageActivation {
    Interacted,
    Untouched,
}

impl PageActivation {
    /// Activation belongs to one document: the one the user last interacted with must still be
    /// the current one, so a newly committed document starts without it.
    pub fn of<D: PartialEq>(interacted: Option<D>, current: Option<D>) -> Self {
        match (interacted, current) {
            (Some(interacted), Some(current)) if interacted == current => {
                PageActivation::Interacted
            },
            _ => PageActivation::Untouched,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LeavePageDecision {
    /// Show the question; the page waits for the answer.
    Ask,
    /// Let the page go without asking.
    Leave,
}

/// Browsers ask before leaving a page only after the user interacted with it, so a page cannot
/// nag or hold someone who never touched it; a hidden tab cannot take over the screen to ask.
pub fn leave_page_decision(
    visibility: TabVisibility,
    activation: PageActivation,
) -> LeavePageDecision {
    match (visibility, activation) {
        (TabVisibility::Selected, PageActivation::Interacted) => LeavePageDecision::Ask,
        _ => LeavePageDecision::Leave,
    }
}

/// The user's answer to a leave-page question.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LeavePageAnswer {
    Leave,
    Stay,
}

/// Leave answers like OK; Stay like Cancel or Back, since leaving is what loses unsaved text.
/// None for a response that does not fit the question.
pub fn leave_page_answer(response: &ControlResponse) -> Option<LeavePageAnswer> {
    match response {
        ControlResponse::Accept => Some(LeavePageAnswer::Leave),
        ControlResponse::Dismiss => Some(LeavePageAnswer::Stay),
        _ => None,
    }
}

/// A menu for a long-pressed link or image, or None if no address is left to act on. Longer
/// addresses are left out rather than cut: a cut URL is a different, wrong address.
pub fn link_menu(link: Option<&str>, image: Option<&str>) -> Option<ControlDescription> {
    let fits = |url: Option<&str>| {
        url.filter(|url| url.chars().count() <= MAX_URL_CHARS)
            .map(str::to_owned)
    };
    let (link, image) = (fits(link), fits(image));
    if link.is_none() && image.is_none() {
        return None;
    }
    Some(ControlDescription::LinkMenu { link, image })
}

/// The application's answer to a control. Debug output leaves out passwords.
#[derive(PartialEq, Eq, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ControlResponse {
    /// `<select>`: the chosen option IDs.
    Select { ids: Vec<usize> },
    /// OK on `alert` or `confirm`; Leave on a leave-page question.
    Accept,
    /// OK on `prompt` with the entered text.
    Text { value: String },
    /// Files the application copied into its upload directory.
    Files { paths: Vec<PathBuf> },
    /// HTTP sign-in.
    Credentials { username: String, password: String },
    /// Cancel, Back, or no selection: Servo's default response. Stay on a leave-page question,
    /// where Servo's default would leave.
    Dismiss,
}

impl fmt::Debug for ControlResponse {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ControlResponse::Select { ids } => {
                formatter.debug_struct("Select").field("ids", ids).finish()
            },
            ControlResponse::Accept => formatter.write_str("Accept"),
            ControlResponse::Text { value } => formatter
                .debug_struct("Text")
                .field("value", value)
                .finish(),
            ControlResponse::Files { paths } => formatter
                .debug_struct("Files")
                .field("paths", paths)
                .finish(),
            ControlResponse::Credentials { username, .. } => formatter
                .debug_struct("Credentials")
                .field("username", username)
                .field("password", &"<redacted>")
                .finish(),
            ControlResponse::Dismiss => formatter.write_str("Dismiss"),
        }
    }
}

/// Errors name the kind and position of the problem only: serde quotes offending values, and an
/// answer may carry a password.
pub fn parse_response(json: &str) -> Result<ControlResponse, String> {
    serde_json::from_str(json).map_err(|error| match error.classify() {
        // Data errors quote the offending value, which may be a password.
        Category::Data => format!(
            "invalid control response: data error at column {}",
            error.column()
        ),
        // The others name only what the parser expected and where.
        _ => format!("invalid control response: {error}"),
    })
}

/// Keeps at most `max` characters, marking the cut with an ellipsis.
fn bounded(text: &str, max: usize) -> String {
    match text.char_indices().nth(max) {
        None => text.to_owned(),
        Some((cut, _)) => format!("{}…", &text[..cut]),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dialog_messages_are_bounded_on_a_character_boundary() {
        let long = "é".repeat(MAX_MESSAGE_CHARS + 10);
        let json = ControlDescription::Alert { message: long }.to_json();

        let message = json["message"].as_str().unwrap();
        assert_eq!(message.chars().count(), MAX_MESSAGE_CHARS + 1);
        assert!(message.ends_with('…'));
        assert_eq!(json["kind"], "alert");
    }

    #[test]
    fn select_describes_groups_selection_and_caps_options() {
        let options = (0..MAX_OPTIONS + 5)
            .map(|id| SelectOption {
                id,
                label: format!("option {id}"),
                disabled: id == 1,
                group: (id > 0).then(|| "Group".to_owned()),
            })
            .collect();
        let json = ControlDescription::Select {
            options,
            selected: vec![2],
            multiple: false,
        }
        .to_json();

        let options = json["options"].as_array().unwrap();
        assert_eq!(options.len(), MAX_OPTIONS);
        assert_eq!(options[0]["group"], Value::Null);
        assert_eq!(options[1]["disabled"], true);
        assert_eq!(options[1]["group"], "Group");
        assert_eq!(json["selected"], json!([2]));
        assert_eq!(json["multiple"], false);
    }

    #[test]
    fn selection_only_names_options_that_were_sent() {
        let options = (0..MAX_OPTIONS + 5)
            .map(|id| SelectOption {
                id,
                label: "o".into(),
                disabled: false,
                group: None,
            })
            .collect();
        let json = ControlDescription::Select {
            options,
            selected: (0..MAX_OPTIONS + 5).collect(),
            multiple: true,
        }
        .to_json();

        let selected = json["selected"].as_array().unwrap();
        assert_eq!(selected.len(), MAX_OPTIONS);
        assert_eq!(selected.last().unwrap(), MAX_OPTIONS - 1);
    }

    #[test]
    fn an_overlong_prompt_default_is_left_out_rather_than_cut() {
        // Accepting the field unedited returns it to the page: a cut default would be wrong.
        let json = ControlDescription::Prompt {
            message: "Name?".into(),
            default: "x".repeat(MAX_MESSAGE_CHARS + 1),
        }
        .to_json();

        assert_eq!(json["default"], "");
    }

    #[test]
    fn link_menus_leave_out_overlong_addresses() {
        let overlong = format!("https://a.test/{}", "x".repeat(MAX_URL_CHARS));
        let json = link_menu(Some("https://a.test/"), Some(&overlong))
            .unwrap()
            .to_json();

        assert_eq!(json["kind"], "link_menu");
        assert_eq!(json["link"], "https://a.test/");
        assert_eq!(json["image"], Value::Null);
    }

    /// Links on a page signed in to over HTTP inherit the credentials Servo put in its URL.
    #[test]
    fn link_menus_leave_out_sign_in_credentials() {
        let json = link_menu(
            Some("http://ada:lovelace@localhost:8766/next.html"),
            Some("http://ada@localhost:8766/a.png"),
        )
        .unwrap()
        .to_json();

        assert_eq!(json["link"], "http://localhost:8766/next.html");
        assert_eq!(json["image"], "http://localhost:8766/a.png");
    }

    #[test]
    fn a_menu_left_without_addresses_is_not_shown() {
        let overlong = format!("https://a.test/{}", "x".repeat(MAX_URL_CHARS));
        assert_eq!(link_menu(Some(&overlong), None), None);
        assert_eq!(link_menu(None, None), None);

        // The limit itself is allowed.
        let longest = format!("https://a.test/{}", "x".repeat(MAX_URL_CHARS - 15));
        assert_eq!(longest.chars().count(), MAX_URL_CHARS);
        assert!(link_menu(Some(&longest), None).is_some());
    }

    #[test]
    fn responses_parse_by_type() {
        assert_eq!(
            parse_response(r#"{"type":"select","ids":[1,3]}"#),
            Ok(ControlResponse::Select { ids: vec![1, 3] })
        );
        assert_eq!(
            parse_response(r#"{"type":"accept"}"#),
            Ok(ControlResponse::Accept)
        );
        assert_eq!(
            parse_response(r#"{"type":"text","value":"Zoë"}"#),
            Ok(ControlResponse::Text {
                value: "Zoë".into()
            })
        );
        assert_eq!(
            parse_response(r#"{"type":"files","paths":["/data/u/a.png"]}"#),
            Ok(ControlResponse::Files {
                paths: vec![PathBuf::from("/data/u/a.png")]
            })
        );
        assert_eq!(
            parse_response(r#"{"type":"dismiss"}"#),
            Ok(ControlResponse::Dismiss)
        );
    }

    #[test]
    fn sign_in_names_the_origin_asking() {
        let json = ControlDescription::SignIn {
            origin: "https://a.test:8443".into(),
            proxy: false,
        }
        .to_json();

        assert_eq!(
            json,
            json!({ "kind": "sign_in", "origin": "https://a.test:8443", "proxy": false })
        );
    }

    #[test]
    fn credentials_parse_but_never_show_the_password() {
        let response =
            parse_response(r#"{"type":"credentials","username":"ada","password":"hunter2"}"#)
                .unwrap();
        assert_eq!(
            response,
            ControlResponse::Credentials {
                username: "ada".into(),
                password: "hunter2".into()
            }
        );
        assert!(!format!("{response:?}").contains("hunter2"));

        // serde quotes offending values in its messages; ours must not.
        let error =
            parse_response(r#"{"type":"credentials","username":"ada","password":["hunter2"]}"#)
                .unwrap_err();
        assert!(!error.contains("hunter2"), "{error}");
        let error = parse_response(r#"{"type":"select","ids":"hunter2"}"#).unwrap_err();
        assert!(!error.contains("hunter2"), "{error}");
    }

    #[test]
    fn leave_page_questions_carry_no_page_text() {
        assert_eq!(
            ControlDescription::LeavePage.to_json(),
            json!({ "kind": "leave_page" })
        );
    }

    #[test]
    fn only_the_tab_on_screen_asks_and_only_after_the_user_interacted() {
        use LeavePageDecision::{Ask, Leave};
        use PageActivation::{Interacted, Untouched};
        use TabVisibility::{Hidden, Selected};

        assert_eq!(leave_page_decision(Selected, Interacted), Ask);
        assert_eq!(leave_page_decision(Selected, Untouched), Leave);
        assert_eq!(leave_page_decision(Hidden, Interacted), Leave);
        assert_eq!(leave_page_decision(Hidden, Untouched), Leave);
    }

    #[test]
    fn activation_belongs_to_the_document_the_user_interacted_with() {
        assert_eq!(
            PageActivation::of(Some(7), Some(7)),
            PageActivation::Interacted
        );
        // A new document committed since the touch.
        assert_eq!(
            PageActivation::of(Some(7), Some(8)),
            PageActivation::Untouched
        );
        assert_eq!(PageActivation::of(None, Some(8)), PageActivation::Untouched);
        assert_eq!(
            PageActivation::of::<u64>(None, None),
            PageActivation::Untouched
        );
    }

    #[test]
    fn leave_answers_like_ok_and_stay_like_cancel() {
        assert_eq!(
            leave_page_answer(&parse_response(r#"{"type":"accept"}"#).unwrap()),
            Some(LeavePageAnswer::Leave)
        );
        assert_eq!(
            leave_page_answer(&parse_response(r#"{"type":"dismiss"}"#).unwrap()),
            Some(LeavePageAnswer::Stay)
        );
        assert_eq!(
            leave_page_answer(&ControlResponse::Text { value: "x".into() }),
            None
        );
    }

    #[test]
    fn malformed_responses_are_rejected() {
        assert!(parse_response(r#"{"type":"select"}"#).is_err());
        assert!(parse_response(r#"{"type":"launch"}"#).is_err());
        assert!(parse_response("not json").is_err());
    }

    #[test]
    fn syntax_errors_say_what_is_wrong() {
        let error = parse_response("not json").unwrap_err();
        assert!(error.contains("expected"), "{error}");
    }
}

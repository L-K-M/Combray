//! Page controls Servo asks the embedder to present: `<select>` pickers, script dialogs
//! (`alert`, `confirm`, `prompt`) and file pickers.
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

use std::path::PathBuf;

use serde::Deserialize;
use serde_json::{Value, json};

/// Page-chosen strings are shown in a dialog, not stored: keep them readable and bounded.
pub const MAX_MESSAGE_CHARS: usize = 4_000;
pub const MAX_LABEL_CHARS: usize = 300;
/// A `<select>` with more entries is shown truncated; the rest cannot be picked.
pub const MAX_OPTIONS: usize = 2_000;
pub const MAX_FILE_EXTENSIONS: usize = 50;

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
        }
    }
}

/// The application's answer to a control.
#[derive(Debug, PartialEq, Eq, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ControlResponse {
    /// `<select>`: the chosen option IDs.
    Select { ids: Vec<usize> },
    /// OK on `alert` or `confirm`.
    Accept,
    /// OK on `prompt` with the entered text.
    Text { value: String },
    /// Files the application copied into its upload directory.
    Files { paths: Vec<PathBuf> },
    /// Cancel, Back, or no selection: Servo's default response.
    Dismiss,
}

pub fn parse_response(json: &str) -> Result<ControlResponse, String> {
    serde_json::from_str(json).map_err(|error| format!("invalid control response: {error}"))
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
    fn malformed_responses_are_rejected() {
        assert!(parse_response(r#"{"type":"select"}"#).is_err());
        assert!(parse_response(r#"{"type":"launch"}"#).is_err());
        assert!(parse_response("not json").is_err());
    }
}

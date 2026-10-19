//! Bounded rendered-text capture.
//!
//! The script runs inside the page, in the document named by the engine (see
//! `WebView::evaluate_javascript_in_document`), and returns the document URL, title and
//! readable text in one snapshot:
//!
//! ```text
//!   engine thread                     Servo (constellation)            page script thread
//!   capture(doc D) ──────────────────▶ D still active? ── no ──▶ DocumentNotFound
//!                                           │ yes
//!                                           ▼
//!                                     run script in D ───────────▶ walk DOM with budgets
//!   validate shape, enforce byte  ◀───────────────────────────── { url, title, text, ... }
//!   caps natively, re-check that D
//!   is still the tab's document
//! ```
//!
//! The page can redefine any built-in the script uses. That only lets a page misreport its
//! own content; it cannot attach text to another document, because identity comes from the
//! engine, not from the script result. A page can still make the script slow or never finish:
//! Servo v0.6.0 has no interrupt for embedder scripts, so the deadline only abandons the result.

#[cfg(target_os = "android")]
use std::collections::HashMap;

#[cfg(target_os = "android")]
use servo::{JSValue, JavaScriptEvaluationError};
use sha2::{Digest, Sha256};

#[cfg(target_os = "android")]
use crate::events::{CaptureStatus, CapturedContent};

/// Proposed first limits from the implementation plan (section 5).
pub const DEFAULT_MAX_TEXT_BYTES: usize = 256 * 1024;
pub const MAX_TITLE_BYTES: usize = 4 * 1024;
pub const MAX_URL_BYTES: usize = 8 * 1024;
/// Element and text nodes the script may visit before it stops.
pub const MAX_NODES: u32 = 50_000;

const SCRIPT_TEMPLATE: &str = include_str!("capture.js");

/// The capture script with its budgets filled in.
pub fn capture_script(max_text_bytes: usize) -> String {
    SCRIPT_TEMPLATE
        .replace("__MAX_TEXT_BYTES__", &max_text_bytes.to_string())
        .replace("__MAX_NODES__", &MAX_NODES.to_string())
}

#[cfg(target_os = "android")]
/// What the script returned, after shape checks and native bounds.
pub enum ScriptOutcome {
    Content(CapturedContent),
    Refused(CaptureStatus, String),
}

#[cfg(target_os = "android")]
pub fn interpret_result(
    result: Result<JSValue, JavaScriptEvaluationError>,
    max_text_bytes: usize,
) -> ScriptOutcome {
    let fields = match result {
        Ok(JSValue::Object(fields)) => fields,
        Ok(other) => {
            return ScriptOutcome::Refused(
                CaptureStatus::Malformed,
                format!("expected an object, got {}", js_value_kind(&other)),
            );
        },
        Err(JavaScriptEvaluationError::DocumentNotFound) => {
            return ScriptOutcome::Refused(
                CaptureStatus::DocumentNotFound,
                "Servo reported that the document is no longer active".into(),
            );
        },
        Err(error) => {
            return ScriptOutcome::Refused(CaptureStatus::ScriptError, format!("{error:?}"));
        },
    };

    let (Some(url), Some(title), Some(text)) = (
        string_field(&fields, "url"),
        string_field(&fields, "title"),
        string_field(&fields, "text"),
    ) else {
        return ScriptOutcome::Refused(
            CaptureStatus::Malformed,
            "missing url, title or text string".into(),
        );
    };

    let (text, truncated_natively) = truncate_utf8(text, max_text_bytes);
    let (title, _) = truncate_utf8(title, MAX_TITLE_BYTES);
    let (url, _) = truncate_utf8(url, MAX_URL_BYTES);

    ScriptOutcome::Content(CapturedContent {
        text_bytes: text.len(),
        sha256: sha256_hex(text.as_bytes()),
        url,
        title,
        text,
        truncated_in_page: bool_field(&fields, "truncated"),
        truncated_natively,
        nodes_visited: number_field(&fields, "nodes"),
        node_budget_exhausted: bool_field(&fields, "nodeBudgetExhausted"),
    })
}

/// Cut `text` to at most `max_bytes` of UTF-8 without splitting a character.
pub fn truncate_utf8(mut text: String, max_bytes: usize) -> (String, bool) {
    if text.len() <= max_bytes {
        return (text, false);
    }

    let mut cut = max_bytes;
    while !text.is_char_boundary(cut) {
        cut -= 1;
    }
    text.truncate(cut);
    (text, true)
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

#[cfg(target_os = "android")]
fn string_field(fields: &HashMap<String, JSValue>, name: &str) -> Option<String> {
    match fields.get(name) {
        Some(JSValue::String(value)) => Some(value.clone()),
        _ => None,
    }
}

#[cfg(target_os = "android")]
fn bool_field(fields: &HashMap<String, JSValue>, name: &str) -> bool {
    matches!(fields.get(name), Some(JSValue::Boolean(true)))
}

#[cfg(target_os = "android")]
fn number_field(fields: &HashMap<String, JSValue>, name: &str) -> u64 {
    match fields.get(name) {
        Some(JSValue::Number(value)) if value.is_finite() && *value >= 0.0 => *value as u64,
        _ => 0,
    }
}

#[cfg(target_os = "android")]
pub fn js_value_kind(value: &JSValue) -> &'static str {
    match value {
        JSValue::Undefined => "undefined",
        JSValue::Null => "null",
        JSValue::Boolean(_) => "boolean",
        JSValue::Number(_) => "number",
        JSValue::String(_) => "string",
        JSValue::Element(_) => "element",
        JSValue::ShadowRoot(_) => "shadow_root",
        JSValue::Frame(_) => "frame",
        JSValue::Window(_) => "window",
        JSValue::Array(_) => "array",
        JSValue::Object(_) => "object",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncation_keeps_whole_characters() {
        // "é" is 2 bytes, "😀" is 4 bytes.
        let (text, cut) = truncate_utf8("aé😀".to_string(), 4);
        assert_eq!(text, "aé");
        assert!(cut);

        let (text, cut) = truncate_utf8("aé😀".to_string(), 7);
        assert_eq!(text, "aé😀");
        assert!(!cut);
    }

    #[test]
    fn sha256_matches_known_vector() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn script_has_no_unfilled_placeholders() {
        let script = capture_script(DEFAULT_MAX_TEXT_BYTES);
        assert!(!script.contains("__MAX_"));
        assert!(script.contains("262144"));
    }
}

//! Bounded rendered-text capture, and the reader view's article extraction.
//!
//! The script runs inside the page, in the document named by the engine (see
//! `WebView::evaluate_javascript_in_document`), and returns the document URL, title and
//! readable text in one snapshot (for an article, the text is the article as JSON blocks; see
//! `reader.js`):
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
//! Servo v0.7.0 has no interrupt for embedder scripts, so the deadline only abandons the result.

use std::collections::BTreeSet;
#[cfg(target_os = "android")]
use std::collections::HashMap;
use std::time::{Duration, Instant};

#[cfg(target_os = "android")]
use servo::{JSValue, JavaScriptEvaluationError};
use sha2::{Digest, Sha256};

#[cfg(target_os = "android")]
use crate::events::{CaptureStatus, CapturedContent};

/// What a capture reads from the document.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaptureKind {
    /// The rendered text, for Hister.
    PageText,
    /// The main article as structured blocks, for reader view (`reader.js`).
    Article,
}

/// Proposed first limits from the implementation plan (section 5).
pub const DEFAULT_MAX_TEXT_BYTES: usize = 256 * 1024;
pub const MAX_TITLE_BYTES: usize = 4 * 1024;
pub const MAX_URL_BYTES: usize = 8 * 1024;
/// Element and text nodes the script may visit before it stops.
pub const MAX_NODES: u32 = 50_000;
/// How long a capture of the page on screen may hold the page's script thread. The walk stops
/// there and returns the text it has: a page that froze for seconds after loading felt broken.
pub const VISIBLE_SCRIPT_BUDGET: Duration = Duration::from_millis(400);
/// For hidden pages, room left before the result deadline for the result to come back.
pub const DEADLINE_MARGIN: Duration = Duration::from_millis(500);
/// No hidden page holds its script thread longer, whatever the deadline (it can be shared
/// with a page of the same site on screen).
pub const MAX_SCRIPT_BUDGET: Duration = Duration::from_millis(2_500);
/// The page on screen is not captured while touched, nor this long after the last touch.
pub const QUIET_AFTER_TOUCH: Duration = Duration::from_millis(1_500);
/// A capture waits for a quiet moment at most this long, then runs anyway.
pub const MAX_DEFERRAL: Duration = Duration::from_secs(10);

const SCRIPT_TEMPLATE: &str = include_str!("capture.js");
const READER_TEMPLATE: &str = include_str!("reader.js");
/// Recognises Servo's stand-in page for a file it cannot show, and Combray's PDF viewer: both
/// scripts read nothing from either, so the stand-in's "Unknown content type" sentence and the
/// viewer's labels never pass for the file's text. `find.js` uses it too.
pub(crate) const STAND_IN: &str = include_str!("stand_in.js");
/// Mozilla Readability 0.6.0 (Apache-2.0), from the npm package `@mozilla/readability`.
const READABILITY: &str = include_str!("vendor/Readability.js");

/// Whether the page a capture reads is the one on screen.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Visibility {
    Shown,
    Hidden,
}

/// The script for [`CaptureKind`] with its budgets filled in. Reader view, which the user asked
/// for and waits on, reads the whole article; page text for Hister stops after `time_budget`.
pub fn script(kind: CaptureKind, max_text_bytes: usize, time_budget: Duration) -> String {
    match kind {
        CaptureKind::PageText => capture_script(max_text_bytes, time_budget),
        CaptureKind::Article => article_script(max_text_bytes),
    }
}

/// The capture script with its budgets filled in.
pub fn capture_script(max_text_bytes: usize, time_budget: Duration) -> String {
    SCRIPT_TEMPLATE
        .replace("__MAX_TEXT_BYTES__", &max_text_bytes.to_string())
        .replace("__MAX_NODES__", &MAX_NODES.to_string())
        .replace("__MAX_MILLIS__", &time_budget.as_millis().to_string())
        .replacen("__STAND_IN__", STAND_IN, 1)
}

/// How long a capture may run in the page: briefly for the page on screen, which the user may
/// be about to use; for a hidden page up to its result deadline, less a margin. A deadline that
/// leaves no time gives zero: the script then reads nothing and reports a truncated capture.
pub fn time_budget(visibility: Visibility, deadline: Duration) -> Duration {
    match visibility {
        Visibility::Shown => VISIBLE_SCRIPT_BUDGET,
        Visibility::Hidden => deadline
            .saturating_sub(DEADLINE_MARGIN)
            .clamp(Duration::ZERO, MAX_SCRIPT_BUDGET),
    }
}

/// Whether a capture of the page on screen waits: while a finger is down or right after the
/// last touch, the user is scrolling or tapping, and a capture would freeze the page under
/// them. A capture that already waited [`MAX_DEFERRAL`] runs anyway.
pub fn defer_while_touched(
    touching: bool,
    since_last_touch: Option<Duration>,
    waited: Duration,
) -> bool {
    if waited >= MAX_DEFERRAL {
        return false;
    }
    touching || since_last_touch.is_some_and(|since| since < QUIET_AFTER_TOUCH)
}

/// How long until a deferred capture of the page on screen may run: once the quiet period after
/// the last touch ends (a finger still down wakes the engine with its own events), and at the
/// latest when the longest-waiting capture has waited [`MAX_DEFERRAL`]. The engine wakes for it,
/// so deferral does not depend on an idle tick.
pub fn next_deferred_attempt(
    touching: bool,
    since_last_touch: Option<Duration>,
    longest_wait: Duration,
) -> Duration {
    let forced = MAX_DEFERRAL.saturating_sub(longest_wait);
    if touching {
        return forced;
    }
    let quiet = since_last_touch.map_or(Duration::ZERO, |since| {
        QUIET_AFTER_TOUCH.saturating_sub(since)
    });
    quiet.min(forced)
}

/// Fingers on the page on screen, and when the last touch event came.
///
/// The pointer ids down, not a count: a count leaks when an up never comes. The long-press
/// path sends one cancel, for the pointer that started the press, and keeps the rest of the
/// gesture from the engine; with two fingers down a count stayed above zero, and every later
/// capture of the page on screen waited the full [`MAX_DEFERRAL`].
#[derive(Debug, Default)]
pub struct Touches {
    down: BTreeSet<i32>,
    last: Option<Instant>,
}

impl Touches {
    pub fn press(&mut self, pointer: i32, at: Instant) {
        self.down.insert(pointer);
        self.last = Some(at);
    }

    pub fn moved(&mut self, at: Instant) {
        self.last = Some(at);
    }

    pub fn lift(&mut self, pointer: i32, at: Instant) {
        self.down.remove(&pointer);
        self.last = Some(at);
    }

    /// An Android cancel ends the whole gesture, whichever pointer it names.
    pub fn cancel(&mut self, at: Instant) {
        self.down.clear();
        self.last = Some(at);
    }

    /// The surface or the view under the fingers went away: their up events will not come.
    pub fn forget_pointers(&mut self) {
        self.down.clear();
    }

    pub fn since_last(&self, now: Instant) -> Option<Duration> {
        self.last.map(|at| now.saturating_duration_since(at))
    }

    /// Whether a finger is down. One silent for [`MAX_DEFERRAL`] counts as lifted, so a lost up
    /// event cannot hold later captures back.
    pub fn touching(&self, now: Instant) -> bool {
        !self.down.is_empty()
            && self
                .since_last(now)
                .is_some_and(|since| since < MAX_DEFERRAL)
    }
}

/// The reader script: budgets first, then Readability, so its source is never rewritten.
pub fn article_script(max_text_bytes: usize) -> String {
    READER_TEMPLATE
        .replace("__MAX_TEXT_BYTES__", &max_text_bytes.to_string())
        .replace("__MAX_NODES__", &MAX_NODES.to_string())
        .replacen("__STAND_IN__", STAND_IN, 1)
        .replacen("__READABILITY__", READABILITY, 1)
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
        time_budget_exhausted: bool_field(&fields, "timeBudgetExhausted"),
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

    #[test]
    fn a_deferred_capture_wakes_the_engine_when_it_may_run() {
        // Quiet for a second: half a second of the quiet period is left.
        assert_eq!(
            next_deferred_attempt(false, Some(Duration::from_secs(1)), Duration::from_secs(1)),
            QUIET_AFTER_TOUCH - Duration::from_secs(1)
        );
        // Quiet long enough, or never touched: now.
        assert_eq!(
            next_deferred_attempt(false, Some(QUIET_AFTER_TOUCH), Duration::ZERO),
            Duration::ZERO
        );
        assert_eq!(
            next_deferred_attempt(false, None, Duration::ZERO),
            Duration::ZERO
        );
        // A finger down: only the forced run, which no capture waits past.
        assert_eq!(
            next_deferred_attempt(true, Some(Duration::ZERO), Duration::from_secs(4)),
            MAX_DEFERRAL - Duration::from_secs(4)
        );
        assert_eq!(
            next_deferred_attempt(true, Some(Duration::ZERO), MAX_DEFERRAL * 2),
            Duration::ZERO
        );
    }
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
        let script = capture_script(DEFAULT_MAX_TEXT_BYTES, VISIBLE_SCRIPT_BUDGET);
        assert!(!script.contains("__MAX_"));
        assert!(!script.contains("__STAND_IN__"));
        assert!(script.trim_end().ends_with("})(262144, 50000, 400)"));
    }

    #[test]
    fn both_scripts_check_for_the_pdf_viewer_inside_their_function() {
        for (kind, wrapper, skip) in [
            (
                CaptureKind::PageText,
                "(function (maxTextBytes, maxNodes, maxMillis) {",
                "var skipped = isServoStandIn() || isCombrayPdfViewer();",
            ),
            (
                CaptureKind::Article,
                "(function (maxBytes, maxNodes) {",
                "if (isServoStandIn() || isCombrayPdfViewer()) return result;",
            ),
        ] {
            let script = script(kind, DEFAULT_MAX_TEXT_BYTES, VISIBLE_SCRIPT_BUDGET);
            let declared = script.find("function isCombrayPdfViewer()").unwrap();
            // Declared inside the wrapping function, not as a page global, and used after it.
            assert!(script.find(wrapper).unwrap() < declared, "{kind:?}");
            assert!(script[declared..].contains(skip), "{kind:?}");
        }
    }

    #[test]
    fn the_pdf_viewer_check_matches_the_engines_viewer_document() {
        // The engine's viewer document: opaque origin, the PDF's Content-Type, data-pdf-state.
        for needle in [
            "String(window.origin) === \"null\"",
            "=== \"application/pdf\"",
            "hasAttribute(\"data-pdf-state\")",
            // Content-Type keeps its parameters.
            ".split(\";\")[0]",
        ] {
            assert!(STAND_IN.contains(needle), "{needle}");
        }
    }

    #[test]
    fn both_scripts_check_for_servos_stand_in_page_inside_their_function() {
        for (kind, wrapper) in [
            (
                CaptureKind::PageText,
                "(function (maxTextBytes, maxNodes, maxMillis) {",
            ),
            (CaptureKind::Article, "(function (maxBytes, maxNodes) {"),
        ] {
            let script = script(kind, DEFAULT_MAX_TEXT_BYTES, VISIBLE_SCRIPT_BUDGET);
            let declared = script.find("function isServoStandIn()").unwrap();
            // Declared inside the wrapping function, not as a page global.
            assert!(script.find(wrapper).unwrap() < declared, "{kind:?}");
        }
    }

    #[test]
    fn the_page_on_screen_gets_a_short_budget_and_hidden_pages_their_deadline() {
        let deadline = Duration::from_secs(3);
        assert_eq!(
            time_budget(Visibility::Shown, deadline),
            VISIBLE_SCRIPT_BUDGET
        );
        assert_eq!(
            time_budget(Visibility::Hidden, deadline),
            Duration::from_millis(2_500)
        );
        // Never more than the cap, nor past the deadline less its margin.
        assert_eq!(
            time_budget(Visibility::Hidden, Duration::from_secs(60)),
            MAX_SCRIPT_BUDGET
        );
        assert_eq!(
            time_budget(Visibility::Hidden, Duration::from_millis(700)),
            Duration::from_millis(200)
        );
        assert_eq!(
            time_budget(Visibility::Hidden, Duration::from_millis(100)),
            Duration::ZERO
        );
    }

    #[test]
    fn a_zero_budget_is_filled_in_as_zero() {
        let script = capture_script(DEFAULT_MAX_TEXT_BYTES, Duration::ZERO);
        assert!(script.trim_end().ends_with("})(262144, 50000, 0)"));
    }

    #[test]
    fn a_cancel_ends_the_whole_gesture() {
        let at = Instant::now();
        let mut touches = Touches::default();
        touches.press(0, at);
        touches.press(1, at);
        // Lifting one of two fingers leaves the page touched.
        touches.lift(1, at);
        assert!(touches.touching(at));
        touches.press(1, at);
        // The long-press path cancels only the pointer that started the press.
        touches.cancel(at);
        assert!(!touches.touching(at));
        assert_eq!(touches.since_last(at), Some(Duration::ZERO));
    }

    #[test]
    fn fingers_whose_page_went_away_are_forgotten() {
        let at = Instant::now();
        let mut touches = Touches::default();
        touches.press(0, at);
        touches.forget_pointers();
        assert!(!touches.touching(at));
    }

    #[test]
    fn a_finger_silent_for_the_longest_deferral_counts_as_lifted() {
        let at = Instant::now();
        let mut touches = Touches::default();
        touches.press(0, at);
        assert!(touches.touching(at + Duration::from_secs(1)));
        assert!(!touches.touching(at + MAX_DEFERRAL));
        touches.moved(at + MAX_DEFERRAL);
        assert!(touches.touching(at + MAX_DEFERRAL));
    }

    #[test]
    fn a_touched_page_waits_for_a_quiet_moment_but_not_forever() {
        let none = Duration::ZERO;
        assert!(defer_while_touched(true, None, none));
        assert!(defer_while_touched(
            false,
            Some(Duration::from_millis(200)),
            none
        ));
        assert!(!defer_while_touched(false, Some(QUIET_AFTER_TOUCH), none));
        assert!(!defer_while_touched(false, None, none));
        assert!(!defer_while_touched(true, None, MAX_DEFERRAL));
    }

    #[test]
    fn article_script_embeds_readability_inside_its_function() {
        let script = script(
            CaptureKind::Article,
            DEFAULT_MAX_TEXT_BYTES,
            VISIBLE_SCRIPT_BUDGET,
        );
        assert!(!script.contains("__MAX_"));
        assert!(!script.contains("__READABILITY__"));
        assert!(!script.contains("__STAND_IN__"));
        assert!(script.contains("function Readability(doc, options)"));
        // Readability is declared inside the wrapping function, not as a page global.
        let wrapper = script.find("(function (maxBytes, maxNodes) {").unwrap();
        assert!(wrapper < script.find("function Readability(doc, options)").unwrap());
        assert!(script.trim_end().ends_with("})(262144, 50000)"));
    }
}

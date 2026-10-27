//! Find in page.
//!
//! Servo v0.7.0 has no embedder find API and no `window.find`. `find.js` runs in the tab's
//! current document through `WebView::evaluate_javascript_in_document`, like capture: it walks
//! the rendered text with node, match and time budgets, selects one match with a DOM `Range`
//! (Servo paints document selections) and scrolls it into view.
//!
//! The query enters the script as a JSON string literal, so it is never source text. The script
//! runs in the page's realm: a page can observe the query and misreport its own matches, so the
//! result is reduced to bounded integers here and anything else is refused.

#[cfg(target_os = "android")]
use std::collections::HashMap;

#[cfg(target_os = "android")]
use servo::{JSValue, JavaScriptEvaluationError};

use crate::capture::{MAX_NODES, truncate_utf8};

/// Matches counted before the script stops; beyond it the count is a lower bound.
pub const MAX_MATCHES: u32 = 999;
/// Longest the script walks the page before it stops, checked as it goes.
pub const MAX_MILLIS: u32 = 1_000;
/// Longest query, in UTF-8 bytes. The application's limit is lower; this only bounds the script.
pub const MAX_QUERY_BYTES: usize = 1024;

const TEMPLATE: &str = include_str!("find.js");

/// What the script does in the document.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FindAction {
    /// Select match `index`: wrapping around, a negative index counting from the end.
    Find { index: i32 },
    /// Remove the selection find made, giving back the page's earlier one.
    Clear,
}

/// The script searching for `query`, or clearing, with its budgets filled in.
pub fn script(query: &str, action: FindAction) -> String {
    let (name, index) = match action {
        FindAction::Find { index } => ("find", index),
        FindAction::Clear => ("clear", 0),
    };
    let (query, _) = truncate_utf8(query.to_owned(), MAX_QUERY_BYTES);
    // A JSON string is a JavaScript string literal (ES2019 also allows U+2028 and U+2029).
    let literal = serde_json::to_string(&query).unwrap_or_else(|_| "\"\"".into());

    // The query goes in last, so placeholder-like text inside it is never replaced.
    TEMPLATE
        .replacen("__INDEX__", &index.to_string(), 1)
        .replacen("__ACTION__", &format!("\"{name}\""), 1)
        .replacen("__MAX_MATCHES__", &MAX_MATCHES.to_string(), 1)
        .replacen("__MAX_NODES__", &MAX_NODES.to_string(), 1)
        .replacen("__MAX_MILLIS__", &MAX_MILLIS.to_string(), 1)
        .replacen("__QUERY__", &literal, 1)
}

/// Where the matches are, after native bounds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FindCounts {
    /// At most [`MAX_MATCHES`].
    pub count: u32,
    /// The selected match, below `count`; none when nothing matched.
    pub index: Option<u32>,
    /// The script stopped at a budget: `count` is a lower bound.
    pub capped: bool,
}

/// Bounded counts from the script's raw fields, or `None` when they are not what `find.js`
/// returns: whole numbers within the budgets, an index inside the count (-1 for none).
pub fn bounded_counts(count: f64, index: f64, capped: bool) -> Option<FindCounts> {
    let whole = |value: f64| value.is_finite() && value.fract() == 0.0;
    if !whole(count) || !whole(index) || count < 0.0 || count > f64::from(MAX_MATCHES) {
        return None;
    }

    let count = count as u32;
    let index = if count == 0 && index == -1.0 {
        None
    } else if index >= 0.0 && index < f64::from(count) {
        Some(index as u32)
    } else {
        return None;
    };
    Some(FindCounts {
        count,
        index,
        capped,
    })
}

#[cfg(target_os = "android")]
/// The script's result as counts, or why it is not usable (no page text in the reason).
pub fn interpret_result(
    result: Result<JSValue, JavaScriptEvaluationError>,
) -> Result<FindCounts, &'static str> {
    let fields = match result {
        Ok(JSValue::Object(fields)) => fields,
        Ok(_) => return Err("result is not an object"),
        Err(JavaScriptEvaluationError::DocumentNotFound) => return Err("document not found"),
        Err(_) => return Err("script failed"),
    };

    let number = |fields: &HashMap<String, JSValue>, name: &str| match fields.get(name) {
        Some(JSValue::Number(value)) => Some(*value),
        _ => None,
    };
    let (Some(count), Some(index), Some(JSValue::Boolean(capped))) = (
        number(&fields, "count"),
        number(&fields, "index"),
        fields.get("capped"),
    ) else {
        return Err("missing count, index or capped");
    };
    bounded_counts(count, index, *capped).ok_or("counts out of bounds")
}

#[cfg(test)]
mod tests {
    use super::*;

    const PLACEHOLDERS: [&str; 6] = [
        "__QUERY__",
        "__INDEX__",
        "__ACTION__",
        "__MAX_MATCHES__",
        "__MAX_NODES__",
        "__MAX_MILLIS__",
    ];

    #[test]
    fn each_placeholder_appears_once_in_the_call() {
        for placeholder in PLACEHOLDERS {
            assert_eq!(TEMPLATE.matches(placeholder).count(), 1, "{placeholder}");
        }
    }

    #[test]
    fn script_has_its_inputs_and_budgets_filled_in() {
        let script = script("rate limit", FindAction::Find { index: -1 });
        for placeholder in PLACEHOLDERS {
            assert!(!script.contains(placeholder), "{placeholder}");
        }
        assert!(
            script
                .trim_end()
                .ends_with(r#"})("rate limit", -1, "find", 999, 50000, 1000)"#)
        );
    }

    #[test]
    fn the_query_stays_a_string_literal() {
        let query = "a\"); alert(1); (\"\\ __INDEX__ </script>\u{2028}";
        let script = script(query, FindAction::Find { index: 2 });
        let literal = serde_json::to_string(query).unwrap();
        assert!(
            script
                .trim_end()
                .ends_with(&format!(r#"}})({literal}, 2, "find", 999, 50000, 1000)"#))
        );
        // The literal reads back as the exact query.
        let read: String = serde_json::from_str(&literal).unwrap();
        assert_eq!(read, query);
    }

    #[test]
    fn clearing_needs_no_query() {
        assert!(
            script("", FindAction::Clear)
                .trim_end()
                .ends_with(r#"})("", 0, "clear", 999, 50000, 1000)"#)
        );
    }

    #[test]
    fn a_long_query_is_cut_on_a_character_boundary() {
        let query = "é".repeat(MAX_QUERY_BYTES);
        let script = script(&query, FindAction::Find { index: 0 });
        let expected = format!("\"{}\"", "é".repeat(MAX_QUERY_BYTES / 2));
        assert!(script.contains(&format!("}})({expected}, 0,")));
    }

    #[test]
    fn counts_within_bounds_are_kept() {
        assert_eq!(
            bounded_counts(3.0, 2.0, false),
            Some(FindCounts {
                count: 3,
                index: Some(2),
                capped: false
            })
        );
        assert_eq!(
            bounded_counts(0.0, -1.0, true),
            Some(FindCounts {
                count: 0,
                index: None,
                capped: true
            })
        );
        assert_eq!(
            bounded_counts(999.0, 998.0, true).map(|counts| counts.count),
            Some(999)
        );
    }

    #[test]
    fn counts_a_page_could_fake_are_refused() {
        for (count, index) in [
            (3.0, 3.0),
            (3.0, -1.0),
            (0.0, 0.0),
            (1000.0, 0.0),
            (-1.0, -1.0),
            (2.5, 0.0),
            (3.0, 1.5),
            (f64::NAN, 0.0),
            (f64::INFINITY, 0.0),
            (3.0, f64::NAN),
        ] {
            assert_eq!(bounded_counts(count, index, false), None, "{count} {index}");
        }
    }
}

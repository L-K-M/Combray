//! What happens to a navigation a page starts: a link, a form submission, `location = …`.
//!
//! Servo fetches only web schemes. Left alone, a tap on a `mailto:` or `tel:` link replaced the
//! page with Servo's error page, and a page could navigate to `file:` URLs on the device.

use crate::events::ViewKey;
use crate::pdf;

/// The document every new window starts with, before its first navigation.
const INITIAL_BLANK: &str = "about:blank";

/// The decision for one page-initiated navigation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PageNavigation {
    /// Servo loads it.
    Allow,
    /// Another Android app handles it (mail, phone, messages, maps, app store, app links); the
    /// page stays on screen (see `docs/ENGINE_SPIKE.md` for what Servo already stopped in it).
    External,
    /// Refused; the page stays on screen.
    Deny,
}

/// Classifies a navigation by its URL scheme (lowercase, as `url::Url::scheme` returns it).
pub fn classify(scheme: &str) -> PageNavigation {
    match scheme {
        "http" | "https" | "data" | "blob" | "about" | "javascript" => PageNavigation::Allow,
        "mailto" | "tel" | "sms" | "smsto" | "mms" | "geo" | "market" | "intent" => {
            PageNavigation::External
        },
        // The PDF viewer's own files: never a document, whatever token a page guessed.
        pdf::SCHEME => PageNavigation::Deny,
        _ => PageNavigation::Deny,
    }
}

/// What a view has shown so far.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Shown {
    /// Nothing but the initial `about:blank` a new window starts with.
    InitialBlank,
    /// A document of its own.
    Document,
}

impl Shown {
    /// After a document at `url` committed.
    pub fn after_commit(self, url: &str) -> Shown {
        if self == Shown::InitialBlank && url == INITIAL_BLANK {
            return Shown::InitialBlank;
        }
        Shown::Document
    }
}

/// The views a page navigation that does not load concerns.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Refusal {
    /// Whose page the user tapped: a link for another app is reported for this view.
    pub owner: ViewKey,
    /// The navigating view closes: a window a page opened for the link, which would otherwise
    /// stay a blank tab.
    pub close: bool,
}

/// For a navigation in `view` that is denied or handed to another app. `opener` is the view
/// whose page opened `view` (`target=_blank`, `window.open`), None for the application's tabs.
pub fn refusal(view: ViewKey, opener: Option<ViewKey>, shown: Shown) -> Refusal {
    match opener {
        // A `target=_blank` app link or `window.open('tel:...')`: the window would only ever
        // show its initial blank document.
        Some(opener) if shown == Shown::InitialBlank => Refusal {
            owner: opener,
            close: true,
        },
        _ => Refusal {
            owner: view,
            close: false,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn web_schemes_load_in_the_page() {
        for scheme in ["http", "https", "data", "blob", "about", "javascript"] {
            assert_eq!(classify(scheme), PageNavigation::Allow, "{scheme}");
        }
    }

    #[test]
    fn app_schemes_go_to_another_app() {
        for scheme in [
            "mailto", "tel", "sms", "smsto", "mms", "geo", "market", "intent",
        ] {
            assert_eq!(classify(scheme), PageNavigation::External, "{scheme}");
        }
    }

    const VIEW: ViewKey = ViewKey {
        tab: 1 << 52,
        generation: 7,
    };
    const OPENER: ViewKey = ViewKey {
        tab: 3,
        generation: 2,
    };

    #[test]
    fn a_window_shows_a_document_once_one_other_than_its_initial_blank_committed() {
        let blank = Shown::InitialBlank.after_commit("about:blank");
        assert_eq!(blank, Shown::InitialBlank);
        let shown = blank.after_commit("https://a.test/");
        assert_eq!(shown, Shown::Document);
        // Navigating to about:blank later does not make it new again.
        assert_eq!(shown.after_commit("about:blank"), Shown::Document);
    }

    #[test]
    fn a_tab_keeps_its_page_when_a_navigation_does_not_load() {
        for shown in [Shown::InitialBlank, Shown::Document] {
            let expected = Refusal {
                owner: VIEW,
                close: false,
            };
            assert_eq!(refusal(VIEW, None, shown), expected, "{shown:?}");
        }
        // A window that has shown a page of its own is a tab like any other.
        assert_eq!(
            refusal(VIEW, Some(OPENER), Shown::Document),
            Refusal {
                owner: VIEW,
                close: false
            }
        );
    }

    #[test]
    fn a_window_opened_only_for_the_link_closes_and_leaves_it_to_its_opener() {
        assert_eq!(
            refusal(VIEW, Some(OPENER), Shown::InitialBlank),
            Refusal {
                owner: OPENER,
                close: true
            }
        );
    }

    #[test]
    fn device_and_unknown_schemes_are_refused() {
        for scheme in [
            "file", "content", "chrome", "resource", "ftp", "ws", "unknown",
        ] {
            assert_eq!(classify(scheme), PageNavigation::Deny, "{scheme}");
        }
    }

    #[test]
    fn a_page_cannot_open_the_pdf_viewers_files() {
        let url = url::Url::parse("combray-pdf://viewer-0123/pdfium/pdfium.wasm").unwrap();

        assert_eq!(classify(url.scheme()), PageNavigation::Deny);
    }
}

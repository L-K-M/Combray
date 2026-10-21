//! What happens to a navigation a page starts: a link, a form submission, `location = …`.
//!
//! Servo fetches only web schemes. Left alone, a tap on a `mailto:` or `tel:` link replaced the
//! page with Servo's error page, and a page could navigate to `file:` URLs on the device.

/// The decision for one page-initiated navigation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PageNavigation {
    /// Servo loads it.
    Allow,
    /// Another Android app handles it (mail, phone, messages, maps, app store, app links); the
    /// page stays as it is.
    External,
    /// Refused; the page stays as it is.
    Deny,
}

/// Classifies a navigation by its URL scheme (lowercase, as `url::Url::scheme` returns it).
pub fn classify(scheme: &str) -> PageNavigation {
    match scheme {
        "http" | "https" | "data" | "blob" | "about" | "javascript" => PageNavigation::Allow,
        "mailto" | "tel" | "sms" | "smsto" | "mms" | "geo" | "market" | "intent" => {
            PageNavigation::External
        },
        _ => PageNavigation::Deny,
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

    #[test]
    fn device_and_unknown_schemes_are_refused() {
        for scheme in [
            "file", "content", "chrome", "resource", "ftp", "ws", "unknown",
        ] {
            assert_eq!(classify(scheme), PageNavigation::Deny, "{scheme}");
        }
    }
}

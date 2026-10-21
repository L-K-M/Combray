//! Media loads the engine cancels.
//!
//! Combray builds Servo without a media backend (Servo 0.6.0 has none for Android, and the
//! GStreamer one is not built), so Servo uses `servo_media_dummy`, whose player accepts data and
//! never plays it. Servo stops a media fetch only when the player says it has enough data, which
//! the dummy never does, so every `<video>` and `<audio>` resource would download in full and be
//! thrown away; a `<track>`'s captions would have no playing media to show on. A media element
//! also holds back its page's load event until it has data or fails, so by Servo's source such a
//! page never finishes loading. The delegate cancels these loads before they reach the network
//! instead: the element fails, fires `error` and releases the load event, and the page can show
//! its fallback. Script fetches (`fetch()`, `XMLHttpRequest`) have the empty destination and are
//! not affected.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

/// Fetch destinations of `<audio>`, `<video>` and `<track>` loads, as the Fetch standard names
/// them (and Servo's `Destination::as_str` returns them).
const UNPLAYABLE_DESTINATIONS: [&str; 3] = ["audio", "video", "track"];

/// Whether a load with this fetch destination is for media Combray cannot play.
pub(crate) fn is_unplayable_media(destination: &str) -> bool {
    UNPLAYABLE_DESTINATIONS.contains(&destination)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_element_and_text_track_loads_are_unplayable() {
        for destination in ["audio", "video", "track"] {
            assert!(is_unplayable_media(destination), "{destination}");
        }
    }

    #[test]
    fn script_fetches_and_other_loads_are_left_alone() {
        // `fetch()` and XHR use the empty destination, even for a media file.
        for destination in [
            "",
            "document",
            "iframe",
            "image",
            "script",
            "style",
            "font",
            "embed",
            "object",
            "audioworklet",
        ] {
            assert!(!is_unplayable_media(destination), "{destination}");
        }
    }
}

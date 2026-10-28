//! Media loads the engine cancels, and what it reports about them.
//!
//! Combray builds Servo without a media backend (Servo 0.7.0 has none for Android, and the
//! GStreamer one is not built), so Servo uses `servo_media_dummy`, whose player accepts data and
//! never plays it. Servo stops a media fetch only when the player says it has enough data, which
//! the dummy never does, so every `<video>` and `<audio>` resource would download in full and be
//! thrown away; a `<track>`'s captions would have no playing media to show on. A media element
//! also holds back its page's load event until it has data or fails, so by Servo's source such a
//! page never finishes loading. The delegate cancels these loads before they reach the network
//! instead: the element fails, fires `error` and releases the load event, and the page can show
//! its fallback. Script fetches (`fetch()`, `XMLHttpRequest`) have the empty destination and are
//! not affected.
//!
//! The application may offer a cancelled `audio` or `video` load to a player outside Servo, so
//! the engine reports its URL when the URL is one the player could fetch as the page asked
//! ([`reportable_url`]). Reports are per page ([`PageMedia`]): a URL already reported for the
//! page is not reported again, and a page whose media has no reportable URL is reported once. A
//! budget per tab and document ([`MediaBudget`]) bounds the reports of a page that keeps changing
//! its media URL or its address. A load names no document, so the engine credits it to the
//! committed one, except while a new document is about to commit ([`PendingMedia`]).

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::collections::VecDeque;

use log::debug;

/// Longest media URL the engine reports. Signed CDN URLs are typically under 2 KiB.
pub(crate) const MAX_MEDIA_URL_BYTES: usize = 8192;
/// Media URLs remembered per page, most recent first. The most recent rather than the first, so
/// a long-lived page that keeps loading new media keeps having it reported.
const MAX_MEDIA_RECENT: usize = 32;
/// Reports a tab may send at once before the refill rate limits it.
const MEDIA_REPORT_BURST: u32 = 8;
const MEDIA_REPORTS_PER_SECOND: u64 = 1;
const MEDIA_REFILL_INTERVAL_MS: u64 = 1000 / MEDIA_REPORTS_PER_SECOND;
/// How long a document that reported its start may take to commit before loads stop waiting
/// for it. Servo reports the start and asks for the commit at the same moment, so a commit later
/// than this means the document will not commit (another navigation replaced it).
const PENDING_COMMIT_MS: u64 = 5_000;
/// Loads held per tab for a document about to commit.
const MAX_HELD_MEDIA: usize = 16;

/// What a cancelled load was for, from its fetch destination.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MediaKind {
    Audio,
    Video,
    Track,
}

impl MediaKind {
    /// The fetch destination's name, as the Fetch standard (and Servo's `Destination::as_str`)
    /// spells it.
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            MediaKind::Audio => "audio",
            MediaKind::Video => "video",
            MediaKind::Track => "track",
        }
    }
}

/// The kind of media Combray cannot play that a load with this fetch destination is for, if any.
pub(crate) fn cancelled_kind(destination: &str) -> Option<MediaKind> {
    match destination {
        "audio" => Some(MediaKind::Audio),
        "video" => Some(MediaKind::Video),
        "track" => Some(MediaKind::Track),
        _ => None,
    }
}

/// The URL of a cancelled load, if the engine reports it: http(s) only, without sign-in
/// credentials, and at most [`MAX_MEDIA_URL_BYTES`]. A URL with credentials is not stripped,
/// because the player would then fetch something other than what the page asked for, and
/// credentials never leave the engine. Captions are never offered, so a `track` URL is never
/// reported.
///
/// `url` is a serialized URL, so its scheme is lower case and its authority ends at the first
/// `/`, `?` or `#` after `//`; an empty authority is refused all the same.
pub(crate) fn reportable_url(kind: MediaKind, url: &str) -> Option<&str> {
    if kind == MediaKind::Track || url.len() > MAX_MEDIA_URL_BYTES {
        return None;
    }
    let rest = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))?;
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.is_empty() || authority.contains('@') {
        return None;
    }
    Some(url)
}

/// The page a URL names: everything before its fragment. Moving to another fragment stays on
/// the page; the application uses the same rule.
pub(crate) fn page_key(url: &str) -> &str {
    url.split_once('#').map_or(url, |(page, _)| page)
}

/// What to tell the application about one cancelled load.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum MediaReport {
    /// A media URL new to the page.
    Playable(String),
    /// The page's first load without a reportable URL.
    Unplayable,
    Nothing,
}

/// Reports a tab may send, as a token bucket: a burst of [`MEDIA_REPORT_BURST`], then
/// [`MEDIA_REPORTS_PER_SECOND`]. It bounds the events of a page that keeps setting `src` to new
/// URLs, or keeps changing its address to start a new page. The engine keeps it across a
/// document's address changes and restarts it when another document commits. A report over budget
/// is dropped and not sent later: its element has already failed and will not load again.
pub(crate) struct MediaBudget {
    tokens: u32,
    refilled_ms: u64,
}

impl Default for MediaBudget {
    fn default() -> Self {
        Self {
            tokens: MEDIA_REPORT_BURST,
            refilled_ms: 0,
        }
    }
}

impl MediaBudget {
    fn take(&mut self, now_ms: u64) -> bool {
        let earned = now_ms.saturating_sub(self.refilled_ms) / MEDIA_REFILL_INTERVAL_MS;
        if earned > 0 {
            let tokens = u64::from(self.tokens).saturating_add(earned);
            self.tokens = tokens.min(u64::from(MEDIA_REPORT_BURST)) as u32;
            self.refilled_ms += earned * MEDIA_REFILL_INTERVAL_MS;
        }
        if self.tokens == 0 {
            return false;
        }
        self.tokens -= 1;
        true
    }
}

/// The media one page of a tab loaded. The engine starts a new one when another document
/// commits, or when the document moves to another page ([`page_key`]).
#[derive(Default)]
pub(crate) struct PageMedia {
    /// The URLs reported, without their fragment, most recent first.
    recent: VecDeque<String>,
    unplayable_reported: bool,
    /// Reports the budget dropped, for the debug log (never with a URL).
    dropped: u32,
}

impl PageMedia {
    /// Records a cancelled load with its reportable URL, if any, and says what to report.
    pub(crate) fn note(
        &mut self,
        url: Option<&str>,
        budget: &mut MediaBudget,
        now_ms: u64,
    ) -> MediaReport {
        let Some(url) = url else {
            if self.unplayable_reported {
                return MediaReport::Nothing;
            }
            if !self.spend(budget, now_ms) {
                return MediaReport::Nothing;
            }
            self.unplayable_reported = true;
            return MediaReport::Unplayable;
        };

        let key = page_key(url);
        if let Some(position) = self.recent.iter().position(|known| known == key) {
            if let Some(known) = self.recent.remove(position) {
                self.recent.push_front(known);
            }
            return MediaReport::Nothing;
        }
        if !self.spend(budget, now_ms) {
            return MediaReport::Nothing;
        }
        self.recent.push_front(key.to_owned());
        self.recent.truncate(MAX_MEDIA_RECENT);
        MediaReport::Playable(url.to_owned())
    }

    fn spend(&mut self, budget: &mut MediaBudget, now_ms: u64) -> bool {
        if budget.take(now_ms) {
            return true;
        }
        self.dropped = self.dropped.saturating_add(1);
        debug!(
            "media report over budget; {} dropped on this page",
            self.dropped
        );
        false
    }
}

/// A cancelled load the engine has not credited to a document yet.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct UnattributedMedia {
    pub(crate) kind: MediaKind,
    pub(crate) url: Option<String>,
    pub(crate) referrer: Option<String>,
}

/// Loads that arrive while a tab's next document is about to commit. Servo delivers a load
/// (network), a document's start (script) and its commit (constellation) on three channels and
/// takes from them in no fixed order, so a new document's first loads can arrive before its
/// commit and would be credited to the page it replaces. Its start, though, is sent when the
/// document is created, before it parses anything that loads media. Loads arriving between a
/// document's start and its commit are held and given to that document when it commits. A
/// document that reported its start commits even when a traversal commits another document
/// first (Servo sends its activation before its start and does not override a pending change),
/// so another commit leaves the hold in place; loads held for a document that never commits go
/// to the committed one after [`PENDING_COMMIT_MS`]. A late load of the replaced page in that
/// window is credited to the new one.
#[derive(Default)]
pub(crate) struct PendingMedia {
    /// The document that reported its start but has not committed, and when.
    pending: Option<(u64, u64)>,
    held: VecDeque<UnattributedMedia>,
}

impl PendingMedia {
    /// `document`, not committed yet, reported its start. A document started later replaces it:
    /// the loads held for the earlier one are dropped with it.
    pub(crate) fn document_started(&mut self, document: u64, now_ms: u64) {
        if self.pending.is_some_and(|(pending, _)| pending != document) {
            self.held.clear();
        }
        self.pending = Some((document, now_ms));
    }

    /// Holds `media` for the document about to commit, or returns the loads to credit to the
    /// committed document now: `media`, after any held for a document that did not commit.
    pub(crate) fn admit(
        &mut self,
        media: UnattributedMedia,
        now_ms: u64,
    ) -> Vec<UnattributedMedia> {
        if let Some((_, since)) = self.pending {
            if now_ms.saturating_sub(since) <= PENDING_COMMIT_MS {
                if self.held.len() == MAX_HELD_MEDIA {
                    self.held.pop_front();
                }
                self.held.push_back(media);
                return Vec::new();
            }
            self.pending = None;
        }
        let mut admitted: Vec<_> = self.held.drain(..).collect();
        admitted.push(media);
        admitted
    }

    /// `document` committed: the loads held for it, in arrival order. Another document's commit
    /// keeps them for the pending one.
    pub(crate) fn committed(&mut self, document: u64) -> Vec<UnattributedMedia> {
        if self.pending.is_none_or(|(pending, _)| pending != document) {
            return Vec::new();
        }
        self.pending = None;
        std::mem::take(&mut self.held).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn load(name: &str) -> UnattributedMedia {
        UnattributedMedia {
            kind: MediaKind::Video,
            url: Some(format!("https://example.org/{name}.mp4")),
            referrer: Some("https://example.org".to_owned()),
        }
    }

    #[test]
    fn loads_without_a_pending_document_are_credited_now() {
        let mut pending = PendingMedia::default();
        assert_eq!(pending.admit(load("a"), 1_000), vec![load("a")]);
        assert_eq!(pending.committed(7), Vec::new());
    }

    #[test]
    fn loads_that_overtake_a_commit_go_to_the_document_that_commits() {
        let mut pending = PendingMedia::default();
        pending.document_started(7, 1_000);
        assert_eq!(pending.admit(load("a"), 1_010), Vec::new());
        assert_eq!(pending.admit(load("b"), 1_020), Vec::new());
        assert_eq!(pending.committed(7), vec![load("a"), load("b")]);
        // Committed: later loads are the new page's at once.
        assert_eq!(pending.admit(load("c"), 1_030), vec![load("c")]);
    }

    #[test]
    fn loads_held_for_a_document_that_did_not_commit_are_dropped_or_returned() {
        let mut pending = PendingMedia::default();
        pending.document_started(7, 1_000);
        pending.admit(load("a"), 1_010);
        // A traversal commits a cached page first: the load stays held for the pending document.
        assert_eq!(pending.committed(8), Vec::new());
        assert_eq!(pending.committed(7), vec![load("a")]);

        pending.document_started(9, 2_000);
        pending.admit(load("b"), 2_010);
        // Another start replaces the first; its loads go with it.
        pending.document_started(10, 2_020);
        assert_eq!(pending.committed(10), Vec::new());

        // A start that never commits stops holding loads after the window, and gives back
        // what it held to the committed document.
        pending.document_started(11, 3_000);
        assert_eq!(pending.admit(load("c"), 3_100), Vec::new());
        assert_eq!(
            pending.admit(load("d"), 3_000 + PENDING_COMMIT_MS + 1),
            vec![load("c"), load("d")]
        );
        assert_eq!(pending.committed(11), Vec::new());
    }

    #[test]
    fn a_pending_document_holds_a_bounded_number_of_loads() {
        let mut pending = PendingMedia::default();
        pending.document_started(7, 1_000);
        for n in 0..MAX_HELD_MEDIA + 4 {
            pending.admit(load(&n.to_string()), 1_000);
        }
        let held = pending.committed(7);
        assert_eq!(held.len(), MAX_HELD_MEDIA);
        assert_eq!(held.first(), Some(&load("4")));
    }

    #[test]
    fn media_element_and_text_track_loads_are_cancelled() {
        assert_eq!(cancelled_kind("audio"), Some(MediaKind::Audio));
        assert_eq!(cancelled_kind("video"), Some(MediaKind::Video));
        assert_eq!(cancelled_kind("track"), Some(MediaKind::Track));
        for kind in [MediaKind::Audio, MediaKind::Video, MediaKind::Track] {
            assert_eq!(cancelled_kind(kind.as_str()), Some(kind));
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
            "Video",
        ] {
            assert_eq!(cancelled_kind(destination), None, "{destination}");
        }
    }

    #[test]
    fn web_media_urls_are_reported() {
        for url in [
            "http://localhost:8766/media/clip.mp4?k=1",
            "https://cdn.example.org/a.mp4",
            "https://example.org/watch#t=10",
            "https://example.org:8443/a.m3u8",
            "https://example.org/a?next=user@example.org",
        ] {
            assert_eq!(reportable_url(MediaKind::Video, url), Some(url), "{url}");
            assert_eq!(reportable_url(MediaKind::Audio, url), Some(url), "{url}");
        }
    }

    #[test]
    fn other_schemes_credentials_and_captions_are_not_reported() {
        for url in [
            "data:video/mp4;base64,AAAA",
            "blob:https://example.org/7f0c",
            "file:///sdcard/a.mp4",
            "about:blank",
            "filesystem:https://example.org/temporary/a.mp4",
            "ftp://example.org/a.mp4",
            "https://ada:secret@example.org/a.mp4",
            "http://ada@example.org/a.mp4",
            "https://ada@example.org?a",
            "https://",
            "http:///a.mp4",
        ] {
            assert_eq!(reportable_url(MediaKind::Video, url), None, "{url}");
        }
        assert_eq!(
            reportable_url(MediaKind::Track, "https://example.org/captions.vtt"),
            None
        );
    }

    #[test]
    fn a_url_is_reported_up_to_eight_kib() {
        let prefix = "https://example.org/";
        let longest = format!("{prefix}{}", "a".repeat(MAX_MEDIA_URL_BYTES - prefix.len()));
        let too_long = format!("{longest}a");

        assert_eq!(longest.len(), MAX_MEDIA_URL_BYTES);
        assert_eq!(
            reportable_url(MediaKind::Video, &longest),
            Some(longest.as_str())
        );
        assert_eq!(reportable_url(MediaKind::Video, &too_long), None);
    }

    #[test]
    fn the_page_key_leaves_out_the_fragment() {
        assert_eq!(
            page_key("https://example.org/a?b#c#d"),
            "https://example.org/a?b"
        );
        assert_eq!(page_key("https://example.org/a#"), "https://example.org/a");
        assert_eq!(page_key("https://example.org/a"), "https://example.org/a");
    }

    #[test]
    fn a_page_reports_each_media_url_once() {
        let mut page = PageMedia::default();
        let mut budget = MediaBudget::default();
        let url = "https://example.org/a.mp4";

        assert_eq!(
            page.note(Some(url), &mut budget, 0),
            MediaReport::Playable(url.into())
        );
        assert_eq!(page.note(Some(url), &mut budget, 0), MediaReport::Nothing);
        // Another fragment is the same media.
        assert_eq!(
            page.note(Some("https://example.org/a.mp4#t=5"), &mut budget, 0),
            MediaReport::Nothing
        );
        assert_eq!(
            page.note(Some("https://example.org/a.mp4?b"), &mut budget, 0),
            MediaReport::Playable("https://example.org/a.mp4?b".into())
        );
    }

    #[test]
    fn media_without_a_reportable_url_is_reported_once_per_page() {
        let mut page = PageMedia::default();
        let mut budget = MediaBudget::default();
        let track = reportable_url(MediaKind::Track, "https://example.org/captions.vtt");

        assert_eq!(page.note(track, &mut budget, 0), MediaReport::Unplayable);
        assert_eq!(page.note(None, &mut budget, 0), MediaReport::Nothing);
        // A playable URL after it is still reported.
        assert_eq!(
            page.note(Some("https://example.org/a.mp4"), &mut budget, 0),
            MediaReport::Playable("https://example.org/a.mp4".into())
        );
        assert_eq!(
            PageMedia::default().note(None, &mut budget, 0),
            MediaReport::Unplayable
        );
    }

    #[test]
    fn a_page_remembers_its_most_recent_urls() {
        let mut page = PageMedia::default();
        let url = |n: usize| format!("https://example.org/{n}.mp4");
        // Fresh budgets: only the list is under test.
        for n in 0..MAX_MEDIA_RECENT {
            assert!(matches!(
                page.note(Some(&url(n)), &mut MediaBudget::default(), 0),
                MediaReport::Playable(_)
            ));
        }
        // Seen again, the first URL becomes the most recent; the second is now the oldest.
        assert_eq!(
            page.note(Some(&url(0)), &mut MediaBudget::default(), 0),
            MediaReport::Nothing
        );
        let newest = url(MAX_MEDIA_RECENT);
        assert_eq!(
            page.note(Some(&newest), &mut MediaBudget::default(), 0),
            MediaReport::Playable(newest.clone())
        );

        // The oldest was forgotten and counts as new; the refreshed first URL was kept.
        assert_eq!(
            page.note(Some(&url(0)), &mut MediaBudget::default(), 0),
            MediaReport::Nothing
        );
        assert_eq!(
            page.note(Some(&url(1)), &mut MediaBudget::default(), 0),
            MediaReport::Playable(url(1))
        );
    }

    #[test]
    fn the_budget_allows_a_burst_then_one_report_a_second() {
        let mut page = PageMedia::default();
        let mut budget = MediaBudget::default();
        let url = |n: u32| format!("https://example.org/{n}.mp4");
        let start = 5_000;

        for n in 0..MEDIA_REPORT_BURST {
            assert!(matches!(
                page.note(Some(&url(n)), &mut budget, start),
                MediaReport::Playable(_)
            ));
        }
        // Over budget: dropped, and not remembered as reported.
        assert_eq!(
            page.note(Some(&url(100)), &mut budget, start + 999),
            MediaReport::Nothing
        );
        assert_eq!(
            PageMedia::default().note(None, &mut budget, start + 999),
            MediaReport::Nothing
        );

        // A repeat is free: one token has refilled, and it is still there for the next URL.
        assert_eq!(
            page.note(Some(&url(0)), &mut budget, start + 1000),
            MediaReport::Nothing
        );
        assert_eq!(
            page.note(Some(&url(100)), &mut budget, start + 1000),
            MediaReport::Playable(url(100))
        );
        assert_eq!(
            page.note(Some(&url(101)), &mut budget, start + 1500),
            MediaReport::Nothing
        );

        // A long pause refills the burst, and no more.
        for n in 0..MEDIA_REPORT_BURST {
            assert!(matches!(
                page.note(Some(&url(200 + n)), &mut budget, start + 60_000),
                MediaReport::Playable(_)
            ));
        }
        assert_eq!(
            page.note(Some(&url(300)), &mut budget, start + 60_000),
            MediaReport::Nothing
        );
    }
}

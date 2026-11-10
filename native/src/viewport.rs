//! The page's part of the engine surface, and when a frame of it is on screen.
//!
//! The app's bottom controls lie over the bottom of the surface instead of shrinking it: resizing
//! an Android `SurfaceView` from the app's own layout shows its last frame scaled into the new
//! size for a frame, because the platform applies the new geometry with the app's frame but the
//! engine's buffer at the new size only later. The surface keeps its size, the app reports how
//! many rows its controls cover, and pages lay out in the rows above them ([page_size]).
//!
//! When the controls go away, the app keeps them on screen until the engine has painted the rows
//! they covered ([ViewportAck]). Servo's first frames after a resize can come from a WebRender
//! frame built for the smaller page, which leaves the new rows in Servo's clear color, so a grown
//! page counts as shown only once those rows show something else, any whole row among the
//! [ROWS_ABOVE] shown rows just above them is that color too, or enough frames have followed.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

/// What Servo paints where a frame has no content (its `shell_background_color_rgba`), and
/// what the engine paints under the app's controls and while no page is shown. It is the
/// color most pages start from, so a page that has not painted yet does not flash.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ClearColor([u8; 4]);

impl ClearColor {
    /// The canvas of a page painted as authored, unless it says otherwise.
    pub(crate) const LIGHT: Self = Self([255, 255, 255, 255]);

    /// What Servo's adaptive dark mode paints a white canvas as.
    pub(crate) const DARKENED: Self = Self([24, 24, 24, 255]);

    /// Red, green, blue and alpha, each from 0 to 1.
    pub(crate) fn components(self) -> [f32; 4] {
        self.0.map(|component| f32::from(component) / 255.0)
    }
}

/// A size in device pixels.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Size {
    pub width: u32,
    pub height: u32,
}

/// A rectangle in GL window coordinates: `y` counts rows up from the bottom edge.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct GlRect {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

/// The page for a surface whose bottom `inset` rows the app covers. Never empty: Servo needs at
/// least one pixel, and a stale inset can exceed a surface that just shrank.
pub(crate) fn page_size(surface: Size, inset: u32) -> Size {
    Size {
        width: surface.width.max(1),
        height: surface.height.saturating_sub(inset).max(1),
    }
}

/// Where a page of `page` size goes in a surface of `surface` size: at its top.
pub(crate) fn page_target(surface: Size, page: Size) -> GlRect {
    let height = page.height.min(surface.height);
    GlRect {
        x: 0,
        y: to_i32(surface.height - height),
        width: to_i32(page.width.min(surface.width)),
        height: to_i32(height),
    }
}

/// Rows of the page's framebuffer to read back after a frame, in its GL coordinates.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct StripCheck {
    /// The rows the page gained since a frame was last known to show it.
    pub strip: GlRect,
    /// Shown rows just above them, when there are any.
    pub above: Option<GlRect>,
}

/// What the rows of a [StripCheck] held in the frame just rendered.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Strip {
    /// Something other than the clear color: a frame laid out for the new size.
    Painted,
    /// Only the clear color, as is any whole row among the shown rows read above them (up to
    /// [ROWS_ABOVE]): the page is that color there (a white page has such rows between its lines),
    /// so the frame looks right either way.
    MatchesAbove,
    /// Only the clear color, though no row just above is: possibly a frame for the smaller page.
    ClearOnly,
    /// Not read (no page shown, or the read failed); trust the frame count alone.
    Unknown,
}

/// The app's latest viewport request, acknowledged once a frame showing it was presented.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Request {
    pub request: u64,
    pub inset: u32,
}

/// How many shown rows above new rows [ViewportAck::check] reads.
pub(crate) const ROWS_ABOVE: u32 = 32;

/// After this many new WebRender frames following a growth, rows still in the clear color are
/// taken as the page's own (a white area of a dark page), not as a frame for the smaller page.
pub(crate) const FRAMES_BEFORE_TRUSTING_CLEAR_ROWS: u32 = 3;

/// Tracks which rows of the page a presented frame is known to show, and the request waiting
/// for all of them.
#[derive(Debug, Default)]
pub(crate) struct ViewportAck {
    pending: Option<Request>,
    /// Page height now.
    page: Size,
    /// Rows from the top that a presented frame is known to show.
    shown: u32,
    /// WebRender frames that became ready since the page last grew.
    frames_since_growth: u32,
}

impl ViewportAck {
    /// The app asked for a page above `inset` covered rows. Later requests replace it.
    pub(crate) fn requested(&mut self, request: u64, inset: u32) {
        self.pending = Some(Request { request, inset });
    }

    /// A new surface: nothing on it can be a frame for another size.
    pub(crate) fn surface_bound(&mut self, page: Size) {
        self.page = page;
        self.shown = page.height;
        self.frames_since_growth = 0;
    }

    /// The page now has this size. A smaller page shows a crop of rows already shown.
    pub(crate) fn page_resized(&mut self, page: Size) {
        if page.width != self.page.width {
            // Every row lays out anew at another width (a rotation or a fold), so no shown row is
            // left to compare new ones with; reading the whole page back would only cost time.
            self.surface_bound(page);
            return;
        }
        if page.height > self.page.height {
            self.frames_since_growth = 0;
        }
        self.page = page;
        self.shown = self.shown.min(page.height);
    }

    /// WebRender has a new frame for the page shown.
    pub(crate) fn frame_ready(&mut self) {
        self.frames_since_growth = self.frames_since_growth.saturating_add(1);
    }

    /// The rows to read back from the frame about to be presented, if any are unconfirmed.
    pub(crate) fn check(&self) -> Option<StripCheck> {
        if self.shown >= self.page.height {
            return None;
        }
        let gained = self.page.height - self.shown;
        // The framebuffer's bottom rows are the page's last ones.
        let strip = GlRect {
            x: 0,
            y: 0,
            width: to_i32(self.page.width),
            height: to_i32(gained),
        };
        let above = (self.shown > 0).then(|| GlRect {
            x: 0,
            y: to_i32(gained),
            width: to_i32(self.page.width),
            height: to_i32(self.shown.min(ROWS_ABOVE)),
        });
        Some(StripCheck { strip, above })
    }

    /// A frame was presented; `strip` is what [Self::check]'s rows held. Returns the request
    /// to acknowledge once every row of the page is shown.
    pub(crate) fn presented(&mut self, strip: Strip) -> Option<Request> {
        if self.shown < self.page.height {
            let confirmed = match strip {
                Strip::Painted | Strip::MatchesAbove => true,
                Strip::ClearOnly | Strip::Unknown => {
                    self.frames_since_growth >= FRAMES_BEFORE_TRUSTING_CLEAR_ROWS
                },
            };
            if confirmed {
                self.shown = self.page.height;
            }
        }
        if self.shown >= self.page.height {
            self.pending.take()
        } else {
            None
        }
    }
}

/// What a frame's new rows (`strip`) and the shown rows above them (`above`) hold, from RGBA
/// pixels read back in rows of `width` pixels; `None` when there are no such rows or reading
/// them failed.
pub(crate) fn classify(
    strip: Option<&[u8]>,
    above: Option<&[u8]>,
    width: usize,
    clear: ClearColor,
) -> Strip {
    let Some(strip) = strip else {
        return Strip::Unknown;
    };
    if has_content(strip, clear) {
        return Strip::Painted;
    }
    let row = width.max(1) * 4;
    match above {
        Some(above)
            if above
                .chunks_exact(row)
                .any(|line| !has_content(line, clear)) =>
        {
            Strip::MatchesAbove
        },
        _ => Strip::ClearOnly,
    }
}

/// Whether any RGBA pixel differs in color from `clear` (alpha is ignored).
fn has_content(pixels: &[u8], clear: ClearColor) -> bool {
    pixels
        .chunks_exact(4)
        .any(|pixel| pixel[..3] != clear.0[..3])
}

fn to_i32(value: u32) -> i32 {
    i32::try_from(value).unwrap_or(i32::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    const fn size(width: u32, height: u32) -> Size {
        Size { width, height }
    }

    fn shown_page(height: u32) -> ViewportAck {
        let mut ack = ViewportAck::default();
        ack.surface_bound(size(1080, height));
        ack
    }

    #[test]
    fn the_page_is_the_surface_above_the_covered_rows() {
        assert_eq!(page_size(size(1080, 2000), 300), size(1080, 1700));
        assert_eq!(page_size(size(1080, 2000), 0), size(1080, 2000));
    }

    #[test]
    fn a_stale_inset_cannot_empty_the_page() {
        assert_eq!(page_size(size(1080, 200), 300), size(1080, 1));
        assert_eq!(page_size(size(0, 0), 0), size(1, 1));
    }

    #[test]
    fn the_page_goes_at_the_top_of_the_surface_in_gl_coordinates() {
        assert_eq!(
            page_target(size(1080, 2000), size(1080, 1700)),
            GlRect {
                x: 0,
                y: 300,
                width: 1080,
                height: 1700
            }
        );
        assert_eq!(
            page_target(size(1080, 2000), size(1080, 2000)),
            GlRect {
                x: 0,
                y: 0,
                width: 1080,
                height: 2000
            }
        );
    }

    #[test]
    fn a_page_larger_than_a_shrinking_surface_is_clamped_to_it() {
        assert_eq!(
            page_target(size(1080, 1500), size(1080, 1700)),
            GlRect {
                x: 0,
                y: 0,
                width: 1080,
                height: 1500
            }
        );
    }

    #[test]
    fn a_shrunk_page_is_acknowledged_after_the_next_frame() {
        let mut ack = shown_page(2000);
        ack.requested(1, 300);
        ack.page_resized(size(1080, 1700));
        assert_eq!(ack.check(), None);
        assert_eq!(
            ack.presented(Strip::Unknown),
            Some(Request {
                request: 1,
                inset: 300
            })
        );
        assert_eq!(ack.presented(Strip::Unknown), None, "acknowledged once");
    }

    #[test]
    fn a_grown_page_waits_while_its_new_rows_are_only_the_clear_color() {
        let mut ack = shown_page(1700);
        ack.requested(2, 0);
        ack.page_resized(size(1080, 2000));
        let check = ack.check().expect("the new rows need a look");
        assert_eq!(
            check.strip,
            GlRect {
                x: 0,
                y: 0,
                width: 1080,
                height: 300
            }
        );
        assert_eq!(
            check.above,
            Some(GlRect {
                x: 0,
                y: 300,
                width: 1080,
                height: ROWS_ABOVE as i32
            })
        );
        assert_eq!(ack.presented(Strip::ClearOnly), None);
        ack.frame_ready();
        assert_eq!(
            ack.presented(Strip::Painted),
            Some(Request {
                request: 2,
                inset: 0
            })
        );
        assert_eq!(ack.check(), None);
    }

    #[test]
    fn clear_rows_below_clear_rows_need_no_wait() {
        let mut ack = shown_page(1700);
        ack.requested(3, 0);
        ack.page_resized(size(1080, 2000));
        assert_eq!(
            ack.presented(Strip::MatchesAbove),
            Some(Request {
                request: 3,
                inset: 0
            })
        );
    }

    #[test]
    fn enough_frames_make_clear_rows_the_pages_own() {
        let mut ack = shown_page(1700);
        ack.requested(4, 0);
        ack.page_resized(size(1080, 2000));
        for _ in 1..FRAMES_BEFORE_TRUSTING_CLEAR_ROWS {
            ack.frame_ready();
            assert_eq!(ack.presented(Strip::ClearOnly), None);
        }
        ack.frame_ready();
        assert_eq!(
            ack.presented(Strip::ClearOnly),
            Some(Request {
                request: 4,
                inset: 0
            })
        );
    }

    #[test]
    fn growing_again_restarts_the_frame_count() {
        let mut ack = shown_page(1700);
        ack.page_resized(size(1080, 1800));
        for _ in 0..FRAMES_BEFORE_TRUSTING_CLEAR_ROWS {
            ack.frame_ready();
        }
        ack.page_resized(size(1080, 2000));
        ack.requested(5, 0);
        assert_eq!(ack.presented(Strip::ClearOnly), None);
    }

    #[test]
    fn the_latest_request_replaces_an_unacknowledged_one() {
        let mut ack = shown_page(2000);
        ack.requested(6, 300);
        ack.page_resized(size(1080, 1700));
        ack.requested(7, 0);
        ack.page_resized(size(1080, 2000));
        // The page grew back before any frame: those rows were shown before the shrink, but the
        // shrink forgot them, so they are checked like any growth.
        assert!(ack.check().is_some());
        assert_eq!(
            ack.presented(Strip::Painted),
            Some(Request {
                request: 7,
                inset: 0
            })
        );
    }

    #[test]
    fn a_request_that_changes_nothing_is_acknowledged_by_the_next_frame() {
        let mut ack = shown_page(2000);
        ack.requested(8, 0);
        assert_eq!(
            ack.presented(Strip::Unknown),
            Some(Request {
                request: 8,
                inset: 0
            })
        );
    }

    #[test]
    fn a_request_waits_for_unconfirmed_rows_it_did_not_cause() {
        // The keyboard closed (the surface grew), then the controls went away.
        let mut ack = shown_page(1200);
        ack.page_resized(size(1080, 2000));
        ack.requested(9, 0);
        assert_eq!(ack.presented(Strip::ClearOnly), None);
        assert_eq!(
            ack.presented(Strip::Painted),
            Some(Request {
                request: 9,
                inset: 0
            })
        );
    }

    #[test]
    fn a_new_surface_shows_its_whole_page() {
        let mut ack = shown_page(1700);
        ack.page_resized(size(1080, 2000));
        ack.surface_bound(size(1080, 2000));
        assert_eq!(ack.check(), None);
    }

    #[test]
    fn another_width_reads_nothing_back() {
        let mut ack = shown_page(2000);
        ack.requested(10, 0);
        ack.page_resized(size(2000, 1080));
        assert_eq!(ack.check(), None);
        assert_eq!(
            ack.presented(Strip::Unknown),
            Some(Request {
                request: 10,
                inset: 0
            })
        );
    }

    // Rows two pixels wide. CLEAR's second pixel has alpha 0 and still is the clear color.
    const CLEAR: [u8; 8] = [255, 255, 255, 255, 255, 255, 255, 0];
    const DARK: [u8; 8] = [255, 255, 255, 255, 13, 13, 20, 255];

    #[test]
    fn any_other_color_in_the_new_rows_is_content() {
        assert_eq!(
            classify(Some(&DARK), Some(&DARK), 2, ClearColor::LIGHT),
            Strip::Painted
        );
        assert_eq!(
            classify(Some(&DARK), None, 2, ClearColor::LIGHT),
            Strip::Painted
        );
    }

    #[test]
    fn clear_new_rows_under_content_may_be_a_frame_for_the_smaller_page() {
        assert_eq!(
            classify(Some(&CLEAR), Some(&DARK), 2, ClearColor::LIGHT),
            Strip::ClearOnly
        );
        assert_eq!(
            classify(Some(&CLEAR), None, 2, ClearColor::LIGHT),
            Strip::ClearOnly
        );
    }

    #[test]
    fn one_clear_row_above_makes_clear_new_rows_look_right_either_way() {
        let text_then_gap = [DARK, CLEAR].concat();
        assert_eq!(
            classify(Some(&CLEAR), Some(&text_then_gap), 2, ClearColor::LIGHT),
            Strip::MatchesAbove
        );
        let text_only = [DARK, DARK].concat();
        assert_eq!(
            classify(Some(&CLEAR), Some(&text_only), 2, ClearColor::LIGHT),
            Strip::ClearOnly
        );
    }

    #[test]
    fn the_clear_color_in_use_decides_what_is_content() {
        // Rows in the darkened clear color; DARKENED_PAGE's second pixel is light text.
        const DARKENED: [u8; 8] = [24, 24, 24, 255, 24, 24, 24, 255];
        const DARKENED_PAGE: [u8; 8] = [24, 24, 24, 255, 232, 232, 232, 255];
        let clear = ClearColor::DARKENED;
        assert_eq!(
            classify(Some(&DARKENED), Some(&DARKENED_PAGE), 2, clear),
            Strip::ClearOnly
        );
        assert_eq!(
            classify(Some(&DARKENED), Some(&DARKENED), 2, clear),
            Strip::MatchesAbove
        );
        // White is the clear color only while pages are painted as authored.
        assert_eq!(classify(Some(&CLEAR), None, 2, clear), Strip::Painted);
        assert_eq!(
            classify(Some(&DARKENED), None, 2, ClearColor::LIGHT),
            Strip::Painted
        );
    }

    #[test]
    fn unread_rows_are_unknown() {
        assert_eq!(
            classify(None, Some(&DARK), 2, ClearColor::LIGHT),
            Strip::Unknown
        );
    }
}

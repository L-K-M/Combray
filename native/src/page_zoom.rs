//! Page zoom: the layout zoom every view uses (Servo's `WebView::set_page_zoom`).
//!
//! Page zoom scales CSS pixels, so a page lays out again at the new size; one with
//! `width=device-width` gets a narrower viewport and rewraps its text. Combray uses one zoom for
//! every view: the engine applies it to the open views and to each new view right after it is
//! built. Servo keeps a view's page zoom across its navigations.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

/// Servo 0.6.0 clamps page zoom to this range (`MIN_PAGE_ZOOM` and `MAX_PAGE_ZOOM` in
/// `components/shared/paint/viewport_description.rs`).
const MIN_ZOOM: f32 = 0.1;
const MAX_ZOOM: f32 = 10.0;

/// Servo's zoom for a new view: 100%.
pub(crate) const DEFAULT_ZOOM: f32 = 1.0;

/// The zoom to apply for `requested`, clamped as Servo clamps it, or `None` when it is not a
/// zoom factor at all (not finite, zero or negative).
pub(crate) fn accepted(requested: f32) -> Option<f32> {
    if !requested.is_finite() || requested <= 0.0 {
        return None;
    }
    Some(requested.clamp(MIN_ZOOM, MAX_ZOOM))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zoom_factors_in_servos_range_pass_unchanged() {
        assert_eq!(accepted(DEFAULT_ZOOM), Some(1.0));
        assert_eq!(accepted(0.8), Some(0.8));
        assert_eq!(accepted(1.5), Some(1.5));
        assert_eq!(accepted(MIN_ZOOM), Some(MIN_ZOOM));
        assert_eq!(accepted(MAX_ZOOM), Some(MAX_ZOOM));
    }

    #[test]
    fn zoom_factors_outside_servos_range_are_clamped() {
        assert_eq!(accepted(0.01), Some(MIN_ZOOM));
        assert_eq!(accepted(25.0), Some(MAX_ZOOM));
    }

    #[test]
    fn values_that_are_no_zoom_factor_are_refused() {
        for requested in [0.0, -0.0, -1.5, f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
            assert_eq!(accepted(requested), None, "{requested}");
        }
    }
}

//! Opening the keyboard for a field the page focused before the user used the page.
//!
//! Servo asks for an input method only when a field gains focus, and allows the keyboard only
//! once the page has had user activation (`InputMethodControl::allow_virtual_keyboard`). A
//! field focused by script on load therefore has focus but no keyboard, and a tap on it fires
//! no new focus, so Servo never asks again. The engine closes that gap: after a tap, it asks
//! the page whether the tap landed on the focused element, and if so opens the keyboard.
//!
//! These parts are pure, so their tests run on any host.

use std::collections::HashSet;

/// Servo's tap/pan threshold (`TOUCH_PAN_MIN_SCREEN_PX` in `components/paint/touch.rs`) at 100%
/// page zoom: a finger that moves further than this on either axis pans, and Servo sends no
/// click. Servo scales it by the density and the page zoom.
const TAP_SLOP_DP: f32 = 10.0;

#[derive(Clone, Copy, Debug)]
struct Press {
    pointer: i32,
    x: f32,
    y: f32,
}

/// Recognises a tap on the page: one finger that lifts within Servo's pan threshold of where
/// it went down. A second finger (pinch), a pan or a cancelled touch (long-press) is no tap.
pub struct TapTracker {
    /// Device pixels per axis.
    slop: f32,
    /// The fingers on the surface. A gesture that had more than one is no tap until all lift.
    fingers: HashSet<i32>,
    press: Option<Press>,
}

impl TapTracker {
    /// `scale`: device pixels per density-independent pixel, times the page zoom.
    pub fn new(scale: f32) -> Self {
        Self {
            slop: TAP_SLOP_DP * scale,
            fingers: HashSet::new(),
            press: None,
        }
    }

    /// For a new page zoom, which moves Servo's threshold too.
    pub fn set_scale(&mut self, scale: f32) {
        self.slop = TAP_SLOP_DP * scale;
    }

    pub fn down(&mut self, pointer: i32, x: f32, y: f32) {
        // Only a gesture's first finger can tap. Any other makes the gesture multi-touch for
        // good: a finger put down after the second one lifted must not start a press again.
        let first = self.fingers.is_empty();
        self.fingers.insert(pointer);
        self.press = first.then_some(Press { pointer, x, y });
    }

    pub fn moved(&mut self, pointer: i32, x: f32, y: f32) {
        if self
            .press
            .is_some_and(|press| press.pointer == pointer && self.beyond_slop(press, x, y))
        {
            self.press = None;
        }
    }

    /// The point of the tap this lift completes, if it completes one.
    pub fn up(&mut self, pointer: i32, x: f32, y: f32) -> Option<(f32, f32)> {
        self.fingers.remove(&pointer);
        let press = self.press.take()?;
        if press.pointer != pointer || self.beyond_slop(press, x, y) {
            return None;
        }
        Some((x, y))
    }

    /// Android sends no lifts after a cancel: the gesture ends for every finger.
    pub fn cancel(&mut self) {
        self.fingers.clear();
        self.press = None;
    }

    fn beyond_slop(&self, press: Press, x: f32, y: f32) -> bool {
        (x - press.x).abs() > self.slop || (y - press.y).abs() > self.slop
    }
}

/// A script that answers `true` when the point, in CSS pixels of the viewport, is on the
/// page's focused element (or inside it). The page can change what it answers; that grants it
/// nothing, since a page the user has used can focus a field, and open the keyboard, anyway.
/// A field inside a frame counts as hit anywhere on its frame. None for a point that is not
/// finite.
pub fn tap_hits_focus_script(x: f32, y: f32) -> Option<String> {
    if !x.is_finite() || !y.is_finite() {
        return None;
    }
    Some(format!(
        "(function (x, y) {{\
           var active = document.activeElement;\
           if (!active || active === document.body || active === document.documentElement) return false;\
           var hit = document.elementFromPoint(x, y);\
           return !!hit && (hit === active || active.contains(hit));\
         }})({x}, {y})"
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    const DENSITY: f32 = 2.0;

    #[test]
    fn a_lift_near_the_press_is_a_tap_at_the_lift() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        taps.moved(0, 110.0, 190.0);
        assert_eq!(taps.up(0, 119.0, 181.0), Some((119.0, 181.0)));
    }

    #[test]
    fn the_slop_follows_the_page_zoom() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        assert_eq!(taps.up(0, 125.0, 200.0), None);

        // At 150% Servo still clicks after 25 px, so the touch is a tap here too.
        taps.set_scale(DENSITY * 1.5);
        taps.down(0, 100.0, 200.0);
        assert_eq!(taps.up(0, 125.0, 200.0), Some((125.0, 200.0)));
    }

    #[test]
    fn moving_past_servos_pan_threshold_is_no_tap() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        taps.moved(0, 100.0, 221.0);
        taps.moved(0, 100.0, 200.0);
        assert_eq!(taps.up(0, 100.0, 200.0), None);

        // Lifting far from the press without a reported move is no tap either.
        taps.down(0, 100.0, 200.0);
        assert_eq!(taps.up(0, 121.0, 200.0), None);
    }

    #[test]
    fn a_second_finger_makes_the_gesture_no_tap() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        taps.down(1, 300.0, 400.0);
        assert_eq!(taps.up(1, 300.0, 400.0), None);
        assert_eq!(taps.up(0, 100.0, 200.0), None);

        // The next gesture starts fresh.
        taps.down(0, 100.0, 200.0);
        assert_eq!(taps.up(0, 100.0, 200.0), Some((100.0, 200.0)));
    }

    #[test]
    fn a_third_finger_does_not_make_a_pinch_a_tap() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        taps.down(1, 300.0, 400.0);
        taps.down(2, 500.0, 600.0);
        assert_eq!(taps.up(2, 500.0, 600.0), None);
    }

    #[test]
    fn a_second_finger_put_down_again_is_no_tap() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        taps.down(1, 300.0, 400.0);
        assert_eq!(taps.up(1, 300.0, 400.0), None);
        taps.down(1, 300.0, 400.0);
        assert_eq!(taps.up(1, 300.0, 400.0), None);
        assert_eq!(taps.up(0, 100.0, 200.0), None);

        // With every finger up, a plain tap is a tap again.
        taps.down(0, 100.0, 200.0);
        assert_eq!(taps.up(0, 100.0, 200.0), Some((100.0, 200.0)));
    }

    #[test]
    fn a_cancel_ends_the_gesture_for_every_finger() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        taps.down(1, 300.0, 400.0);
        taps.cancel();

        // Android sends no lifts after a cancel; the next press starts a new gesture.
        taps.down(0, 100.0, 200.0);
        assert_eq!(taps.up(0, 100.0, 200.0), Some((100.0, 200.0)));
    }

    #[test]
    fn a_cancelled_touch_is_no_tap() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        taps.cancel();
        assert_eq!(taps.up(0, 100.0, 200.0), None);
    }

    #[test]
    fn each_press_completes_at_most_one_tap() {
        let mut taps = TapTracker::new(DENSITY);
        taps.down(0, 100.0, 200.0);
        assert!(taps.up(0, 100.0, 200.0).is_some());
        assert_eq!(taps.up(0, 100.0, 200.0), None);
    }

    #[test]
    fn the_script_names_the_point_and_refuses_non_finite_ones() {
        let script = tap_hits_focus_script(12.5, 40.0).unwrap();
        assert!(script.ends_with("})(12.5, 40)"), "{script}");
        assert!(script.contains("document.elementFromPoint(x, y)"));
        assert_eq!(tap_hits_focus_script(f32::NAN, 1.0), None);
        assert_eq!(tap_hits_focus_script(1.0, f32::INFINITY), None);
    }
}

//! Opening the keyboard for a field the page focused before the user used the page.
//!
//! Servo asks for an input method only when a field gains focus, and allows the keyboard only
//! once the page has had user activation (`InputMethodControl::allow_virtual_keyboard`). A
//! field focused by script on load therefore has focus but no keyboard, and a tap on it fires
//! no new focus, so Servo never asks again. The engine closes that gap: after a tap, it asks
//! the page whether the tap landed on the focused element, and if so opens the keyboard.
//!
//! These parts are pure, so their tests run on any host.

/// Servo's tap/pan threshold (`TOUCH_PAN_MIN_SCREEN_PX` in `components/paint/touch.rs`): a
/// finger that moves further than this on either axis pans, and Servo sends no click.
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
    press: Option<Press>,
}

impl TapTracker {
    /// `density`: device pixels per density-independent pixel.
    pub fn new(density: f32) -> Self {
        Self {
            slop: TAP_SLOP_DP * density,
            press: None,
        }
    }

    pub fn down(&mut self, pointer: i32, x: f32, y: f32) {
        // A second finger while the first is down makes the gesture multi-touch.
        self.press = match self.press {
            None => Some(Press { pointer, x, y }),
            Some(_) => None,
        };
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
        let press = self.press.take()?;
        if press.pointer != pointer || self.beyond_slop(press, x, y) {
            return None;
        }
        Some((x, y))
    }

    pub fn cancel(&mut self) {
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

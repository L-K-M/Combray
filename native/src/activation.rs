//! Which touches are taps: only a tap (or a key press) is the user's activation that lets a page
//! hand a link to another app. The end of a scroll, a fling or a pinch is not.

use std::collections::HashMap;
use std::time::{Duration, Instant};

/// Movement up to which a touch still counts as a tap, in density-independent pixels, along
/// either axis. Servo 0.6.0 starts scrolling, and then issues no click, past the same distance
/// (`TOUCH_PAN_MIN_SCREEN_PX` in `components/paint/touch.rs`, private there).
pub const TAP_SLOP_DIP: f32 = 10.0;

/// How long a pointer may stay down before it counts as one whose up was lost. Longer than any
/// real hold, so only a lost up is forgotten: otherwise a lost up of a finger whose id does not
/// come back would make every later gesture multi-touch.
pub const STALE_POINTER: Duration = Duration::from_secs(60);

/// Follows one touch gesture at a time, from the first pointer down to the last pointer up.
#[derive(Debug)]
pub struct TapRecognizer {
    /// [`TAP_SLOP_DIP`] in the touch coordinates' device pixels.
    slop: f32,
    /// Where and when each pointer still down went down.
    down: HashMap<i32, (f32, f32, Instant)>,
    /// The gesture had a second pointer or moved too far: it ends without a tap.
    spoiled: bool,
}

impl TapRecognizer {
    /// `scale`: device pixels per density-independent pixel.
    pub fn new(scale: f32) -> Self {
        Self {
            slop: TAP_SLOP_DIP * scale,
            down: HashMap::new(),
            spoiled: false,
        }
    }

    pub fn down(&mut self, pointer: i32, x: f32, y: f32) {
        self.down_at(pointer, x, y, Instant::now());
    }

    pub fn down_at(&mut self, pointer: i32, x: f32, y: f32, at: Instant) {
        // A pointer that is down again never reported its up (Combray keeps a long press's up
        // from the engine): what was held belongs to an ended gesture.
        if self.down.contains_key(&pointer) {
            self.down.clear();
        }
        self.down
            .retain(|_, &mut (_, _, since)| at.saturating_duration_since(since) <= STALE_POINTER);
        // The first pointer starts a gesture; any further one makes it a multi-touch gesture.
        self.spoiled = !self.down.is_empty();
        self.down.insert(pointer, (x, y, at));
    }

    pub fn moved(&mut self, pointer: i32, x: f32, y: f32) {
        if !self.within_slop(pointer, x, y) {
            self.spoiled = true;
        }
    }

    /// Whether this up ends a tap: the gesture's only pointer, never far from where it went down.
    pub fn up(&mut self, pointer: i32, x: f32, y: f32) -> bool {
        if !self.down.contains_key(&pointer) {
            return false;
        }
        if !self.within_slop(pointer, x, y) {
            self.spoiled = true;
        }
        self.down.remove(&pointer);
        !self.spoiled && self.down.is_empty()
    }

    pub fn cancel(&mut self, pointer: i32) {
        self.down.remove(&pointer);
        self.spoiled = true;
    }

    /// False for a pointer that is not down: its gesture cannot be a tap.
    fn within_slop(&self, pointer: i32, x: f32, y: f32) -> bool {
        self.down
            .get(&pointer)
            .is_some_and(|&(start_x, start_y, _)| {
                (x - start_x).abs() <= self.slop && (y - start_y).abs() <= self.slop
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Two device pixels per density-independent pixel: the slop is 20 px.
    const SCALE: f32 = 2.0;

    #[test]
    fn a_touch_that_stays_put_is_a_tap() {
        let mut taps = TapRecognizer::new(SCALE);
        taps.down(0, 100.0, 200.0);
        taps.moved(0, 112.0, 188.0);
        assert!(taps.up(0, 119.0, 181.0));
    }

    #[test]
    fn a_scroll_or_fling_is_not_a_tap() {
        let mut taps = TapRecognizer::new(SCALE);
        taps.down(0, 100.0, 200.0);
        taps.moved(0, 100.0, 260.0);
        // Back near the start does not undo the scroll.
        taps.moved(0, 100.0, 205.0);
        assert!(!taps.up(0, 100.0, 205.0));

        // A fling can end far away without a move event in between.
        taps.down(0, 100.0, 200.0);
        assert!(!taps.up(0, 100.0, 400.0));

        // The next gesture starts afresh.
        taps.down(0, 100.0, 200.0);
        assert!(taps.up(0, 101.0, 199.0));
    }

    #[test]
    fn two_fingers_are_not_a_tap() {
        let mut taps = TapRecognizer::new(SCALE);
        taps.down(0, 100.0, 200.0);
        taps.down(1, 300.0, 400.0);
        assert!(!taps.up(1, 300.0, 400.0));
        assert!(!taps.up(0, 100.0, 200.0));

        // Lifted in the other order.
        taps.down(0, 100.0, 200.0);
        taps.down(1, 300.0, 400.0);
        assert!(!taps.up(0, 100.0, 200.0));
        assert!(!taps.up(1, 300.0, 400.0));
    }

    #[test]
    fn a_finger_down_again_while_another_stays_down_is_not_a_tap() {
        let mut taps = TapRecognizer::new(SCALE);
        taps.down(0, 100.0, 200.0);
        taps.down(1, 300.0, 400.0);
        assert!(!taps.up(1, 300.0, 400.0));
        taps.down(1, 300.0, 400.0);
        assert!(!taps.up(1, 300.0, 400.0));
        assert!(!taps.up(0, 100.0, 200.0));
    }

    #[test]
    fn a_finger_whose_up_never_came_stops_spoiling_taps_after_a_while() {
        let mut taps = TapRecognizer::new(SCALE);
        let start = Instant::now();
        // A second finger whose up was lost: its id may not come back.
        taps.down_at(0, 100.0, 200.0, start);
        taps.down_at(1, 300.0, 400.0, start);
        assert!(!taps.up(0, 100.0, 200.0));

        let later = start + STALE_POINTER + Duration::from_secs(1);
        taps.down_at(0, 100.0, 200.0, later);
        assert!(taps.up(0, 101.0, 199.0));
    }

    #[test]
    fn a_cancelled_touch_is_not_a_tap() {
        let mut taps = TapRecognizer::new(SCALE);
        taps.down(0, 100.0, 200.0);
        taps.cancel(0);
        assert!(!taps.up(0, 100.0, 200.0));
    }

    #[test]
    fn an_up_never_delivered_does_not_block_later_taps() {
        // A long press ends without its up reaching the engine; the next gesture reuses the ID.
        let mut taps = TapRecognizer::new(SCALE);
        taps.down(0, 100.0, 200.0);
        taps.down(0, 150.0, 250.0);
        assert!(taps.up(0, 150.0, 250.0));
    }
}

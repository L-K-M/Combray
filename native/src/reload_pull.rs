//! A single-finger pull, armed only at the compositor's top edge.

use std::collections::HashSet;

const TOUCH_SLOP_DP: f32 = 8.0;
const RELOAD_DISTANCE_DP: f32 = 80.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StartPosition {
    Top,
    Scrollable,
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum PullUpdate {
    Cancelled,
    Pulling(f32),
    Released,
}

struct Drag {
    pointer: i32,
    x: f32,
    y: f32,
}

pub struct ReloadPull {
    pointers: HashSet<i32>,
    drag: Option<Drag>,
    slop: f32,
    distance: f32,
}

impl ReloadPull {
    pub fn new(density: f32) -> Self {
        Self {
            pointers: HashSet::new(),
            drag: None,
            slop: TOUCH_SLOP_DP * density,
            distance: RELOAD_DISTANCE_DP * density,
        }
    }

    pub fn press(&mut self, pointer: i32, x: f32, y: f32, position: StartPosition) -> PullUpdate {
        self.pointers.insert(pointer);
        self.drag = (self.pointers.len() == 1 && position == StartPosition::Top).then_some(Drag {
            pointer,
            x,
            y,
        });
        match self.drag {
            Some(_) => PullUpdate::Pulling(0.0),
            None => PullUpdate::Cancelled,
        }
    }

    pub fn moved(&mut self, pointer: i32, x: f32, y: f32) -> PullUpdate {
        let Some(drag) = &self.drag else {
            return PullUpdate::Cancelled;
        };
        if pointer != drag.pointer {
            return PullUpdate::Cancelled;
        }

        let dx = (x - drag.x).abs();
        let dy = y - drag.y;
        if !dx.is_finite() || !dy.is_finite() || dy < -self.slop || (dx > self.slop && dx >= dy) {
            self.drag = None;
            return PullUpdate::Cancelled;
        }

        PullUpdate::Pulling(((dy - self.slop) / self.distance).clamp(0.0, 1.0))
    }

    pub fn release(&mut self, pointer: i32, x: f32, y: f32, position: StartPosition) -> PullUpdate {
        self.pointers.remove(&pointer);
        let update = self.moved(pointer, x, y);
        self.drag = None;
        if update == PullUpdate::Pulling(1.0) && position == StartPosition::Top {
            return PullUpdate::Released;
        }
        PullUpdate::Cancelled
    }

    pub fn cancel_gesture(&mut self) {
        self.drag = None;
    }

    pub fn reset(&mut self) {
        self.pointers.clear();
        self.drag = None;
    }
}

/// A page may prevent a drag after Android has already delivered its release.
/// Wait for every input answer, even when those answers arrive out of order.
pub struct PullInputGate<I> {
    pending: HashSet<I>,
    released: bool,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum DefaultAction {
    Allowed,
    Prevented,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InputDecision {
    Waiting,
    Cancelled,
    Released,
}

impl<I: Eq + std::hash::Hash> Default for PullInputGate<I> {
    fn default() -> Self {
        Self {
            pending: HashSet::new(),
            released: false,
        }
    }
}

impl<I: Eq + std::hash::Hash> PullInputGate<I> {
    pub fn sent(&mut self, id: I) -> InputDecision {
        const MAX_PENDING_INPUTS: usize = 128;
        self.pending.insert(id);
        if self.pending.len() <= MAX_PENDING_INPUTS {
            return InputDecision::Waiting;
        }
        self.reset();
        InputDecision::Cancelled
    }

    pub fn release(&mut self) {
        self.released = true;
    }

    pub fn handled(&mut self, id: I, action: DefaultAction) -> InputDecision {
        if !self.pending.remove(&id) {
            return InputDecision::Waiting;
        }
        if action == DefaultAction::Prevented {
            self.reset();
            return InputDecision::Cancelled;
        }
        if self.released && self.pending.is_empty() {
            self.reset();
            return InputDecision::Released;
        }
        InputDecision::Waiting
    }

    pub fn reset(&mut self) {
        self.pending.clear();
        self.released = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delayed_prevent_default_wins_over_a_release() {
        let mut gate = PullInputGate::default();
        gate.sent(1);
        gate.sent(2);
        gate.release();
        assert_eq!(
            gate.handled(2, DefaultAction::Allowed),
            InputDecision::Waiting
        );
        assert_eq!(
            gate.handled(1, DefaultAction::Prevented),
            InputDecision::Cancelled
        );
        assert_eq!(
            gate.handled(2, DefaultAction::Allowed),
            InputDecision::Waiting
        );
    }

    #[test]
    fn every_input_must_be_allowed_and_old_answers_do_not_release_a_new_pull() {
        let mut gate = PullInputGate::default();
        gate.sent(1);
        gate.reset();
        gate.sent(2);
        gate.release();
        assert_eq!(
            gate.handled(1, DefaultAction::Allowed),
            InputDecision::Waiting
        );
        assert_eq!(
            gate.handled(2, DefaultAction::Allowed),
            InputDecision::Released
        );
    }

    #[test]
    fn unanswered_input_has_a_bounded_budget() {
        let mut gate = PullInputGate::default();
        let mut cancelled = false;
        for id in 0..1_000 {
            if gate.sent(id) == InputDecision::Cancelled {
                cancelled = true;
                break;
            }
        }
        assert!(cancelled);
    }

    #[test]
    fn a_full_pull_reloads_once_on_release() {
        let mut pull = ReloadPull::new(1.0);
        assert_eq!(
            pull.press(0, 20.0, 10.0, StartPosition::Top),
            PullUpdate::Pulling(0.0)
        );
        assert_eq!(pull.moved(0, 20.0, 58.0), PullUpdate::Pulling(0.5));
        assert_eq!(pull.moved(0, 20.0, 98.0), PullUpdate::Pulling(1.0));
        assert_eq!(
            pull.release(0, 20.0, 98.0, StartPosition::Top),
            PullUpdate::Released
        );
        assert_eq!(
            pull.release(0, 20.0, 98.0, StartPosition::Top),
            PullUpdate::Cancelled
        );
    }

    #[test]
    fn scrolling_to_top_in_the_same_gesture_does_not_arm_a_pull() {
        for start in [StartPosition::Scrollable, StartPosition::Unknown] {
            let mut pull = ReloadPull::new(1.0);
            pull.press(0, 0.0, 0.0, start);
            assert_eq!(
                pull.release(0, 0.0, 200.0, StartPosition::Top),
                PullUpdate::Cancelled
            );
        }
    }

    #[test]
    fn short_pulls_reversal_and_a_changed_edge_cancel() {
        for (end, edge) in [
            (40.0, StartPosition::Top),
            (20.0, StartPosition::Top),
            (100.0, StartPosition::Scrollable),
            (100.0, StartPosition::Unknown),
        ] {
            let mut pull = ReloadPull::new(1.0);
            pull.press(0, 0.0, 0.0, StartPosition::Top);
            pull.moved(0, 0.0, 100.0);
            assert_eq!(pull.release(0, 0.0, end, edge), PullUpdate::Cancelled);
        }
    }

    #[test]
    fn taps_horizontal_drags_and_upward_scrolling_keep_their_meaning() {
        for (x, y) in [(0.0, 0.0), (100.0, 30.0), (0.0, -20.0)] {
            let mut pull = ReloadPull::new(1.0);
            pull.press(0, 0.0, 0.0, StartPosition::Top);
            pull.moved(0, x, y);
            assert_eq!(
                pull.release(0, x, y, StartPosition::Top),
                PullUpdate::Cancelled
            );
        }
    }

    #[test]
    fn multi_touch_cannot_become_a_reload_when_one_finger_lifts() {
        let mut pull = ReloadPull::new(1.0);
        pull.press(0, 0.0, 0.0, StartPosition::Top);
        pull.moved(0, 0.0, 100.0);
        pull.press(1, 50.0, 0.0, StartPosition::Top);
        pull.release(1, 50.0, 0.0, StartPosition::Top);
        assert_eq!(
            pull.release(0, 0.0, 100.0, StartPosition::Top),
            PullUpdate::Cancelled
        );
    }

    #[test]
    fn cancellation_discards_the_gesture_and_density_scales_the_threshold() {
        let mut pull = ReloadPull::new(2.0);
        pull.press(0, 0.0, 0.0, StartPosition::Top);
        assert_eq!(
            pull.release(0, 0.0, 100.0, StartPosition::Top),
            PullUpdate::Cancelled
        );
        pull.press(0, 0.0, 0.0, StartPosition::Top);
        pull.moved(0, 0.0, 200.0);
        pull.reset();
        assert_eq!(
            pull.release(0, 0.0, 200.0, StartPosition::Top),
            PullUpdate::Cancelled
        );
    }
}

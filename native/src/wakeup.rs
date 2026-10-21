//! When the engine thread must run without a message.
//!
//! Work reaches the engine thread as messages on its command channel: the application's
//! commands, and `Wake` from Servo's `EventLoopWaker`, which Servo calls whenever it sends the
//! embedder a message (constellation, network and paint channels) and on every refresh-driver
//! frame while a page animates. Delegate and script-result callbacks run while Servo spins or
//! inside the engine's own calls, and each iteration drains them before it paints (painting
//! calls none). Only two things need a timer: a capture deadline passing, and a paint that failed
//! on a usable surface (`make_current`), which nothing announces when it could succeed. Without
//! either, the thread sleeps until a message arrives instead of waking several times a second.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::time::Duration;

/// How soon a paint that failed on a usable surface is tried again (the cadence of the idle
/// tick it replaces).
pub(crate) const PAINT_RETRY: Duration = Duration::from_millis(250);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PaintRetry {
    /// Nothing to paint, or painting waits for a surface, whose arrival is a command.
    NotNeeded,
    /// A paint failed on a usable surface and must be tried again.
    Pending,
}

/// The longest the engine thread may wait for a message; `None` waits until one arrives.
pub(crate) fn sleep_limit(
    until_capture_deadline: Option<Duration>,
    paint: PaintRetry,
) -> Option<Duration> {
    let retry = match paint {
        PaintRetry::Pending => Some(PAINT_RETRY),
        PaintRetry::NotNeeded => None,
    };
    match (until_capture_deadline, retry) {
        (Some(deadline), Some(retry)) => Some(deadline.min(retry)),
        (deadline, retry) => deadline.or(retry),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn without_timed_work_the_thread_waits_for_a_message() {
        assert_eq!(sleep_limit(None, PaintRetry::NotNeeded), None);
    }

    #[test]
    fn a_capture_deadline_bounds_the_sleep_however_far_it_is() {
        let far = Duration::from_secs(15);
        assert_eq!(sleep_limit(Some(far), PaintRetry::NotNeeded), Some(far));
        assert_eq!(
            sleep_limit(Some(Duration::ZERO), PaintRetry::NotNeeded),
            Some(Duration::ZERO)
        );
    }

    #[test]
    fn a_failed_paint_is_retried_soon() {
        assert_eq!(sleep_limit(None, PaintRetry::Pending), Some(PAINT_RETRY));
    }

    #[test]
    fn the_nearer_of_a_deadline_and_a_paint_retry_wins() {
        let near = Duration::from_millis(10);
        let far = Duration::from_secs(3);
        assert_eq!(sleep_limit(Some(near), PaintRetry::Pending), Some(near));
        assert_eq!(
            sleep_limit(Some(far), PaintRetry::Pending),
            Some(PAINT_RETRY)
        );
    }
}

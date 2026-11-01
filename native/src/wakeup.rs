//! Message-driven sleep with deadlines for work that has no external waker.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::time::Duration;

/// Retry a failed paint at the cadence of the idle tick it replaces.
pub(crate) const PAINT_RETRY: Duration = Duration::from_millis(250);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PaintRetry {
    /// No pending paint, or a surface command must arrive before painting can resume.
    NotNeeded,
    /// Painting failed on a usable surface.
    Pending,
}

/// Longest wait for a command or Servo wakeup; `None` waits until a message arrives.
pub(crate) fn sleep_limit(until_deadline: Option<Duration>, paint: PaintRetry) -> Option<Duration> {
    let retry = match paint {
        PaintRetry::Pending => Some(PAINT_RETRY),
        PaintRetry::NotNeeded => None,
    };
    until_deadline.into_iter().chain(retry).min()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn without_timed_work_the_thread_waits_for_a_message() {
        assert_eq!(sleep_limit(None, PaintRetry::NotNeeded), None);
    }

    #[test]
    fn a_deadline_bounds_sleep_without_periodic_polling() {
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

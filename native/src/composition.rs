//! A composition belongs to one InputConnection and one native editing context. Delayed
//! updates or completion must never replace text in the next field, tab or document.

use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Start,
    Update,
    End,
}

impl Phase {
    pub fn from_android(value: i32) -> Option<Self> {
        match value {
            0 => Some(Self::Start),
            1 => Some(Self::Update),
            2 => Some(Self::End),
            _ => None,
        }
    }
}

/// The process-global next editing context ID. It starts at `1`, never returns to zero and
/// never wraps: once the positive Java `long` range is spent, allocation fails closed.
static NEXT_EDITOR_CONTEXT: AtomicU64 = AtomicU64::new(1);

/// Hands out the native editing context IDs (`ImeShown.context`).
///
/// IDs are process-global: recreating an `EngineBackend` must not restart the numbering, or the
/// application could mistake a new field for one it still remembers.
pub struct EditorContexts;

impl Default for EditorContexts {
    fn default() -> Self {
        Self
    }
}

impl EditorContexts {
    pub fn new() -> Self {
        Self
    }

    /// The next positive Java `long` context ID, or `None` once the process has issued them all.
    pub fn allocate(&self) -> Option<u64> {
        Self::allocate_from(&NEXT_EDITOR_CONTEXT)
    }

    /// Takes the next ID from `counter`, or `None` when it holds no positive Java `long` left.
    fn allocate_from(counter: &AtomicU64) -> Option<u64> {
        let mut current = counter.load(Ordering::Relaxed);
        loop {
            // Zero is not an ID, and above `i64::MAX` the application's Java `long` would be
            // negative. Refuse rather than wrap around to an ID already issued.
            if current == 0 || current > i64::MAX as u64 {
                return None;
            }
            match counter.compare_exchange_weak(
                current,
                current + 1,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return Some(current),
                Err(actual) => current = actual,
            }
        }
    }
}

pub struct CompositionSession<T> {
    active: Option<(u64, T)>,
    /// The greatest Start ID ever accepted, kept after its session ends: a late old Start
    /// must never begin or replace a composition, even once a newer one has ended.
    last_start: u64,
}

impl<T> Default for CompositionSession<T> {
    fn default() -> Self {
        Self {
            active: None,
            last_start: 0,
        }
    }
}

impl<T: Copy + PartialEq> CompositionSession<T> {
    /// Accept a phase only for its original context. A late old completion must not end a
    /// newer composition, even when Android restarted input for the very same field.
    ///
    /// Start IDs are positive and increase: a duplicate or older Start is refused without
    /// ending or replacing the current session, and a Start without its owner reserves nothing.
    pub fn accept(&mut self, id: u64, phase: Phase, owner: Option<T>) -> bool {
        if id == 0 {
            return false;
        }
        if phase == Phase::Start {
            // An ownerless Start is not a session: leave the current one and the ID numbering
            // alone, so a later valid Start with the same ID is not mistaken for a duplicate.
            let Some(owner) = owner else {
                return false;
            };
            if id <= self.last_start {
                return false;
            }
            self.last_start = id;
            self.active = Some((id, owner));
            return true;
        }
        let Some((active_id, active_owner)) = self.active else {
            return false;
        };
        if active_id != id {
            return false;
        }
        let accepted = owner == Some(active_owner);
        if !accepted || phase == Phase::End {
            self.active = None;
        }
        accepted
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn two_fresh_backends_do_not_hand_out_the_same_context_id() {
        let one = EditorContexts::new();
        let two = EditorContexts::new();
        let first = one.allocate();
        let second = two.allocate();
        assert!(first.is_some());
        assert!(second.is_some());
        assert_ne!(first, second);
    }

    #[test]
    fn issued_ids_are_never_zero_and_never_repeat() {
        let contexts = EditorContexts::new();
        let mut seen = HashSet::new();
        for _ in 0..64 {
            let id = contexts
                .allocate()
                .expect("the process has plenty of IDs left");
            assert_ne!(id, 0);
            assert!(seen.insert(id));
        }
    }

    #[test]
    fn the_id_space_fails_closed_at_the_last_positive_java_long() {
        let counter = AtomicU64::new(i64::MAX as u64);
        assert_eq!(
            EditorContexts::allocate_from(&counter),
            Some(i64::MAX as u64)
        );
        assert_eq!(counter.load(Ordering::Relaxed), i64::MAX as u64 + 1);
        assert_eq!(EditorContexts::allocate_from(&counter), None);
        assert_eq!(EditorContexts::allocate_from(&counter), None);
        assert_eq!(counter.load(Ordering::Relaxed), i64::MAX as u64 + 1);
    }

    #[test]
    fn zero_is_never_an_id() {
        let counter = AtomicU64::new(0);
        assert_eq!(EditorContexts::allocate_from(&counter), None);
    }

    #[test]
    fn stale_updates_cannot_enter_a_different_field_tab_or_document() {
        for replacement in [(2, 10, 3), (1, 11, 3), (1, 10, 4)] {
            let mut session = CompositionSession::default();
            assert!(session.accept(1, Phase::Start, Some((1, 10, 3))));
            assert!(!session.accept(1, Phase::Update, Some(replacement)));
            assert!(!session.accept(1, Phase::End, Some((1, 10, 3))));
        }
    }

    #[test]
    fn late_completion_of_an_old_connection_does_not_end_its_replacement() {
        let mut session = CompositionSession::default();
        assert!(session.accept(1, Phase::Start, Some(7)));
        assert!(session.accept(2, Phase::Start, Some(7)));
        assert!(!session.accept(1, Phase::End, Some(7)));
        assert!(session.accept(2, Phase::Update, Some(7)));
        assert!(session.accept(2, Phase::End, Some(7)));
        assert!(!session.accept(2, Phase::End, Some(7)));
    }

    #[test]
    fn duplicate_start_does_not_replace_the_active_session() {
        let mut session = CompositionSession::default();
        assert!(session.accept(1, Phase::Start, Some(7)));
        assert!(!session.accept(1, Phase::Start, Some(8)));
        assert!(session.accept(1, Phase::Update, Some(7)));
        assert!(session.accept(1, Phase::End, Some(7)));
    }

    #[test]
    fn older_start_after_a_newer_session_ended_is_refused() {
        let mut session = CompositionSession::default();
        assert!(session.accept(1, Phase::Start, Some(7)));
        assert!(session.accept(2, Phase::Start, Some(7)));
        assert!(session.accept(2, Phase::End, Some(7)));
        assert!(!session.accept(1, Phase::Start, Some(7)));
        assert!(!session.accept(2, Phase::Start, Some(7)));
        assert!(!session.accept(1, Phase::Update, Some(7)));
        assert!(!session.accept(2, Phase::Update, Some(7)));
    }

    #[test]
    fn older_start_does_not_end_the_newer_session() {
        let mut session = CompositionSession::default();
        assert!(session.accept(1, Phase::Start, Some(7)));
        assert!(session.accept(2, Phase::Start, Some(7)));
        assert!(!session.accept(1, Phase::Start, Some(7)));
        assert!(session.accept(2, Phase::Update, Some(7)));
        assert!(session.accept(2, Phase::End, Some(7)));
    }

    #[test]
    fn a_start_without_an_owner_neither_ends_the_session_nor_reserves_its_id() {
        let mut session = CompositionSession::default();
        assert!(session.accept(1, Phase::Start, Some(7)));
        assert!(!session.accept(2, Phase::Start, None));
        assert!(session.accept(1, Phase::Update, Some(7)));
        assert!(session.accept(2, Phase::Start, Some(7)));
        assert!(session.accept(2, Phase::Update, Some(7)));
    }

    #[test]
    fn missing_context_and_missing_start_are_refused() {
        let mut session = CompositionSession::default();
        assert!(!session.accept(0, Phase::Start, Some(7)));
        assert!(!session.accept(1, Phase::Update, Some(7)));
        assert!(!session.accept(1, Phase::Start, None));
        assert!(session.accept(1, Phase::Start, Some(7)));
        assert!(!session.accept(1, Phase::Update, None));
        assert!(!session.accept(1, Phase::End, Some(7)));
        assert_eq!(Phase::from_android(3), None);
        assert_eq!(Phase::from_android(-1), None);
    }
}

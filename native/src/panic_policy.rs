//! What a panic on the engine thread means.
//!
//! Servo and surfman `expect` on some surface errors; [`recoverable`] runs such an operation so a
//! panic inside it becomes an error and the engine thread survives. Any other panic on the engine
//! thread leaves Servo's state unknown, so the panic hook aborts the process: Android reports a
//! crash and the app restarts with session recovery, instead of keeping a silently dead engine.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::cell::Cell;
use std::panic::AssertUnwindSafe;

thread_local! {
    /// Number of [`recoverable`] calls active on this thread.
    static RECOVERABLE_DEPTH: Cell<u32> = const { Cell::new(0) };
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum ThreadRole {
    Engine,
    Other,
}

#[derive(Debug, PartialEq)]
pub(crate) enum PanicAction {
    /// Let the panic unwind to whoever catches it (Servo catches its own threads' panics).
    Unwind,
    Abort,
}

/// Runs `operation`, turning a panic inside it into `Err`.
pub(crate) fn recoverable<T>(operation: impl FnOnce() -> T) -> std::thread::Result<T> {
    let _scope = RecoverableScope::enter();
    std::panic::catch_unwind(AssertUnwindSafe(operation))
}

/// Decides the fate of a panic. Called by the panic hook, which runs on the panicking thread
/// before unwinding, so an enclosing [`recoverable`] call is still counted.
pub(crate) fn action(role: ThreadRole) -> PanicAction {
    if role == ThreadRole::Other || RECOVERABLE_DEPTH.with(Cell::get) > 0 {
        return PanicAction::Unwind;
    }
    PanicAction::Abort
}

/// Counts a [`recoverable`] call; the count drops again even when the operation panics.
struct RecoverableScope;

impl RecoverableScope {
    fn enter() -> Self {
        RECOVERABLE_DEPTH.with(|depth| depth.set(depth.get() + 1));
        RecoverableScope
    }
}

impl Drop for RecoverableScope {
    fn drop(&mut self) {
        RECOVERABLE_DEPTH.with(|depth| depth.set(depth.get() - 1));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_thread_panic_outside_a_recoverable_operation_aborts() {
        assert_eq!(action(ThreadRole::Engine), PanicAction::Abort);
    }

    #[test]
    fn panics_on_other_threads_unwind() {
        assert_eq!(action(ThreadRole::Other), PanicAction::Unwind);
    }

    #[test]
    fn recoverable_operation_unwinds_and_is_caught() {
        let inside = recoverable(|| action(ThreadRole::Engine));
        assert_eq!(inside.unwrap(), PanicAction::Unwind);

        let caught = recoverable(|| -> () { panic!("surface error") });
        assert!(caught.is_err());

        // Leaving the operation by panicking must not leave the thread marked recoverable.
        assert_eq!(action(ThreadRole::Engine), PanicAction::Abort);
    }

    #[test]
    fn nested_recoverable_operations_restore_the_outer_state() {
        let outer = recoverable(|| {
            let _ = recoverable(|| -> () { panic!("inner") });
            action(ThreadRole::Engine)
        });
        assert_eq!(outer.unwrap(), PanicAction::Unwind);
        assert_eq!(action(ThreadRole::Engine), PanicAction::Abort);
    }
}

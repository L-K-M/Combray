//! What a panic means, on the engine thread and on Servo's own threads.
//!
//! Servo and surfman `expect` on some surface errors; [`recoverable`] runs such an operation so a
//! panic inside it becomes an error and the engine thread survives. Any other panic on the engine
//! thread leaves Servo's state unknown, so the panic hook aborts the process: Android reports a
//! crash and the app restarts with session recovery, instead of keeping a silently dead engine.
//!
//! A panic on one of Servo's threads is reported to Servo before it unwinds. Servo hears of a
//! dead script thread only through such a report: it then crashes that thread's pages, and the
//! app offers to reload them. Unreported, the page stayed on screen but no longer ran script or
//! responded to input, scrolling included.

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
    /// Let the panic unwind to the code that recovers from it: a [`recoverable`] call, or Servo
    /// for the panics it catches itself (`servo::should_panic_hook_suppress_termination`).
    Unwind,
    /// Report the panic to Servo, then let it unwind and end the thread. For a script thread,
    /// Servo crashes that thread's pages.
    ReportAndUnwind,
    Abort,
}

/// Whether Servo catches the panic and carries on (`servo::should_panic_hook_suppress_termination`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum ServoRecovery {
    Recovers,
    DoesNotRecover,
}

/// Runs `operation`, turning a panic inside it into `Err`.
pub(crate) fn recoverable<T>(operation: impl FnOnce() -> T) -> std::thread::Result<T> {
    let _scope = RecoverableScope::enter();
    std::panic::catch_unwind(AssertUnwindSafe(operation))
}

/// Decides the fate of a panic. Called by the panic hook, which runs on the panicking thread
/// before unwinding, so an enclosing [`recoverable`] call is still counted.
pub(crate) fn action(role: ThreadRole, servo: ServoRecovery) -> PanicAction {
    if servo == ServoRecovery::Recovers {
        return PanicAction::Unwind;
    }
    if role == ThreadRole::Other {
        return PanicAction::ReportAndUnwind;
    }
    if RECOVERABLE_DEPTH.with(Cell::get) > 0 {
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

    const UNCAUGHT: ServoRecovery = ServoRecovery::DoesNotRecover;

    #[test]
    fn engine_thread_panic_outside_a_recoverable_operation_aborts() {
        assert_eq!(action(ThreadRole::Engine, UNCAUGHT), PanicAction::Abort);
    }

    #[test]
    fn panics_on_other_threads_are_reported_to_servo_and_unwind() {
        assert_eq!(
            action(ThreadRole::Other, UNCAUGHT),
            PanicAction::ReportAndUnwind
        );
    }

    #[test]
    fn panics_servo_recovers_from_are_neither_reported_nor_fatal() {
        let recovers = ServoRecovery::Recovers;
        assert_eq!(action(ThreadRole::Other, recovers), PanicAction::Unwind);
        assert_eq!(action(ThreadRole::Engine, recovers), PanicAction::Unwind);
    }

    #[test]
    fn recoverable_operation_unwinds_and_is_caught() {
        let inside = recoverable(|| action(ThreadRole::Engine, UNCAUGHT));
        assert_eq!(inside.unwrap(), PanicAction::Unwind);

        let caught = recoverable(|| -> () { panic!("surface error") });
        assert!(caught.is_err());

        // Leaving the operation by panicking must not leave the thread marked recoverable.
        assert_eq!(action(ThreadRole::Engine, UNCAUGHT), PanicAction::Abort);
    }

    #[test]
    fn nested_recoverable_operations_restore_the_outer_state() {
        let outer = recoverable(|| {
            let _ = recoverable(|| -> () { panic!("inner") });
            action(ThreadRole::Engine, UNCAUGHT)
        });
        assert_eq!(outer.unwrap(), PanicAction::Unwind);
        assert_eq!(action(ThreadRole::Engine, UNCAUGHT), PanicAction::Abort);
    }

    #[test]
    fn a_recoverable_operation_on_another_thread_is_still_reported() {
        // `recoverable` is the engine thread's own mechanism; Servo's threads end on a panic.
        let inside = recoverable(|| action(ThreadRole::Other, UNCAUGHT));
        assert_eq!(inside.unwrap(), PanicAction::ReportAndUnwind);
    }
}

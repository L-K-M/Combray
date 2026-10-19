//! Ownership of an Android `ANativeWindow` obtained from a Java `Surface`.
//!
//! `ANativeWindow_fromSurface` acquires a reference that must be released exactly once. The
//! engine keeps the [`NativeWindow`] for as long as Servo renders into it and drops it only
//! after `WindowRenderingContext::take_window` destroyed the EGL surface.

use std::ffi::c_void;
use std::ptr::NonNull;

use jni::Env;
use jni::objects::JObject;
use raw_window_handle::{
    AndroidDisplayHandle, AndroidNdkWindowHandle, DisplayHandle, RawDisplayHandle, RawWindowHandle,
    WindowHandle,
};

#[link(name = "android")]
unsafe extern "C" {
    fn ANativeWindow_fromSurface(
        env: *mut jni::sys::JNIEnv,
        surface: jni::sys::jobject,
    ) -> *mut c_void;
    fn ANativeWindow_release(window: *mut c_void);
}

pub struct NativeWindow(NonNull<c_void>);

// SAFETY: an `ANativeWindow` reference may be used and released from any thread; the engine
// moves it once, from the JNI thread to the engine thread.
unsafe impl Send for NativeWindow {}

impl NativeWindow {
    /// Acquire the native window behind `surface`, or `None` if the surface is invalid.
    pub fn from_surface(env: &mut Env<'_>, surface: &JObject<'_>) -> Option<Self> {
        // SAFETY: `env` and `surface` are live JNI references for the duration of this call.
        let window = unsafe { ANativeWindow_fromSurface(env.get_raw(), surface.as_raw()) };
        NonNull::new(window).map(NativeWindow)
    }

    pub fn display_handle(&self) -> DisplayHandle<'_> {
        let raw = RawDisplayHandle::Android(AndroidDisplayHandle::new());
        // SAFETY: the Android display handle carries no pointer.
        unsafe { DisplayHandle::borrow_raw(raw) }
    }

    pub fn window_handle(&self) -> WindowHandle<'_> {
        let raw = RawWindowHandle::AndroidNdk(AndroidNdkWindowHandle::new(self.0));
        // SAFETY: `self` holds a reference to the window for the lifetime of the borrow.
        unsafe { WindowHandle::borrow_raw(raw) }
    }
}

impl Drop for NativeWindow {
    fn drop(&mut self) {
        // SAFETY: releases the single reference acquired in `from_surface`.
        unsafe { ANativeWindow_release(self.0.as_ptr()) };
    }
}

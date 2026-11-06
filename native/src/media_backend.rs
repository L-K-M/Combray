//! The `ServoMedia` backend Combray installs before Servo would install the dummy.
//!
//! Servo has no Android media backend and falls back to `servo_media_dummy`, whose every
//! `canPlayType` answers `No`. That keeps a capability-driven page from attaching a source:
//! a site that asks `canPlayType` first, or a `<source>` element with a `type`, is skipped
//! before its fetch — the fetch the delegate would cancel and report — so its media never
//! reaches the player. [`ClaimingBackend`] is the dummy with `can_play_type` answered from
//! the types the player outside the engine can fetch and decode
//! ([`crate::media::claimed_support`]), so such pages attach their media and it is offered
//! like an unconditional `src`. The claim covers only what the player plays: claiming more
//! would send pages to formats that fail (docs/VIDEO.md).

use std::sync::{Arc, Mutex};

use servo_base::generic_channel::GenericCallback;
use servo_media::audio::context::{AudioContext, AudioContextOptions};
use servo_media::audio::sink::AudioSinkError;
use servo_media::player::audio::AudioRenderer;
use servo_media::player::context::PlayerGLContext;
use servo_media::player::video::VideoFrameRenderer;
use servo_media::player::{Player, PlayerEvent, StreamType};
use servo_media::streams::capture::MediaTrackConstraintSet;
use servo_media::streams::device_monitor::MediaDeviceMonitor;
use servo_media::streams::registry::MediaStreamId;
use servo_media::streams::{MediaOutput, MediaSocket, MediaStreamType};
use servo_media::webrtc::{WebRtcController, WebRtcSignaller};
use servo_media::{Backend, ClientContextId, ServoMedia, SupportsMediaType};
use servo_media_dummy::DummyBackend;

use crate::media::{self, MediaSupport};

/// Makes [`ClaimingBackend`] the `ServoMedia` backend before Servo's `media_platform::init`
/// runs inside `ServoBuilder::build`, so it must be called first. `ServoMedia` keeps the
/// first backend made and `get` waits until one exists, so once this returns ours is the one
/// installed and Servo's own init changes nothing.
pub(crate) fn install() {
    ServoMedia::init_with_backend(|| {
        // A factory that unwound would leave the lock empty and `get` waiting forever; the
        // dummy answers `No` to everything, which is what this all replaces but never worse.
        std::panic::catch_unwind(|| Box::new(ClaimingBackend(DummyBackend)) as Box<dyn Backend>)
            .unwrap_or_else(|_| Box::new(DummyBackend))
    });
    let _ = ServoMedia::get();
}

/// [`DummyBackend`] with `can_play_type` answered from [`media::claimed_support`]. The trait
/// is a flat `&self` interface, so every other method forwards to the held dummy.
struct ClaimingBackend(DummyBackend);

impl Backend for ClaimingBackend {
    fn create_player(
        &self,
        id: &ClientContextId,
        stream_type: StreamType,
        sender: GenericCallback<PlayerEvent>,
        video_renderer: Option<Arc<Mutex<dyn VideoFrameRenderer>>>,
        audio_renderer: Option<Arc<Mutex<dyn AudioRenderer>>>,
        gl_context: Box<dyn PlayerGLContext>,
    ) -> Arc<Mutex<dyn Player>> {
        self.0.create_player(
            id,
            stream_type,
            sender,
            video_renderer,
            audio_renderer,
            gl_context,
        )
    }

    fn create_audiostream(&self) -> MediaStreamId {
        self.0.create_audiostream()
    }

    fn create_videostream(&self) -> MediaStreamId {
        self.0.create_videostream()
    }

    fn create_stream_output(&self) -> Box<dyn MediaOutput> {
        self.0.create_stream_output()
    }

    fn create_stream_and_socket(
        &self,
        ty: MediaStreamType,
    ) -> (Box<dyn MediaSocket>, MediaStreamId) {
        self.0.create_stream_and_socket(ty)
    }

    fn create_audioinput_stream(&self, set: MediaTrackConstraintSet) -> Option<MediaStreamId> {
        self.0.create_audioinput_stream(set)
    }

    fn create_videoinput_stream(&self, set: MediaTrackConstraintSet) -> Option<MediaStreamId> {
        self.0.create_videoinput_stream(set)
    }

    fn create_audio_context(
        &self,
        id: &ClientContextId,
        options: AudioContextOptions,
    ) -> Result<Arc<Mutex<AudioContext>>, AudioSinkError> {
        self.0.create_audio_context(id, options)
    }

    fn create_webrtc(&self, signaller: Box<dyn WebRtcSignaller>) -> WebRtcController {
        self.0.create_webrtc(signaller)
    }

    fn can_play_type(&self, media_type: &str) -> SupportsMediaType {
        match media::claimed_support(media_type) {
            MediaSupport::No => SupportsMediaType::No,
            MediaSupport::Maybe => SupportsMediaType::Maybe,
            MediaSupport::Probably => SupportsMediaType::Probably,
        }
    }

    fn get_device_monitor(&self) -> Box<dyn MediaDeviceMonitor> {
        self.0.get_device_monitor()
    }
}

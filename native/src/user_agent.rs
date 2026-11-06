//! The `User-Agent` Combray sends: Servo's, with the `Gecko/<version>` token Firefox for Android
//! sends.
//!
//! Servo's Android user agent claims Firefox (`rv:153.0`, `Firefox/153.0`) but writes
//! `Servo/0.7.0` where Firefox writes `Gecko/153.0`. Some site firewalls take a user agent that
//! names Firefox without a `Gecko/` token for a forged one and answer every page with 403. With the
//! token in front of Servo's, the string reads like Firefox for Android's and still names Servo:
//!
//! ```text
//! Servo:   Mozilla/5.0 (Android 10; Mobile; rv:153.0) Servo/0.7.0 Firefox/153.0
//! Combray: Mozilla/5.0 (Android 10; Mobile; rv:153.0) Gecko/153.0 Servo/0.7.0 Firefox/153.0
//! ```
//!
//! Servo sends its `user_agent` preference both as the HTTP header and as `navigator.userAgent`,
//! so the two stay equal.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

const REVISION_PREFIX: &str = "rv:";
const REVISION_END: char = ')';
const SERVO_TOKEN: &str = " Servo/";
const GECKO_TOKEN: &str = "Gecko/";

/// `servo` with `Gecko/<rv>` in front of its `Servo/` token, `<rv>` being its `rv:` version;
/// `servo` itself when it already has a `Gecko/` token. `None` when it has no `rv:<version>)` or
/// no `Servo/` token after it to place the token by, so the caller keeps Servo's string and says
/// so.
pub fn with_gecko_token(servo: &str) -> Option<String> {
    if servo.contains(GECKO_TOKEN) {
        return Some(servo.to_owned());
    }

    let revision_start = servo.find(REVISION_PREFIX)? + REVISION_PREFIX.len();
    let revision_length = servo[revision_start..].find(REVISION_END)?;
    let revision = &servo[revision_start..revision_start + revision_length];
    if !is_version(revision) {
        return None;
    }

    let after_revision = revision_start + revision_length;
    let servo_token = after_revision + servo[after_revision..].find(SERVO_TOKEN)?;
    let (before, after) = servo.split_at(servo_token);
    Some(format!("{before} {GECKO_TOKEN}{revision}{after}"))
}

/// Dot-separated decimal numbers such as `153.0`: what goes into the header verbatim.
fn is_version(text: &str) -> bool {
    !text.is_empty()
        && text
            .split('.')
            .all(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()))
}

#[cfg(test)]
mod tests {
    use super::with_gecko_token;

    /// `UserAgentPlatform::Android` in the pinned Servo's components/config/prefs.rs, for Servo
    /// 0.7.0 (`the_pinned_servo_sends_servo_android` keeps it current).
    const SERVO_ANDROID: &str =
        "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Servo/0.7.0 Firefox/153.0";
    const SERVO_VERSION_TOKEN: &str = "Servo/0.7.0";
    /// What Combray sends for `SERVO_ANDROID`. The site-check harness sends the same agent, and
    /// testing/sitecheck/tests/test_config.py fails when its template differs from this one.
    const COMBRAY_ANDROID: &str =
        "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Gecko/153.0 Servo/0.7.0 Firefox/153.0";

    /// The pinned Servo's preferences source: cargo resolves the Android-only `servo` dependency
    /// from this checkout even on the host, so it is there whenever these tests build.
    const SERVO_PREFERENCES: &str = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../servo-checkout/servo/components/config/prefs.rs"
    ));

    /// A pin whose Android agent changes (a new Firefox revision, say) fails here, so the
    /// constants above, and with them the harness's template, follow it.
    #[test]
    fn the_pinned_servo_sends_servo_android() {
        let (_, branch) = SERVO_PREFERENCES
            .split_once("UserAgentPlatform::Android =>")
            .expect("prefs.rs has no Android branch in to_user_agent_string");
        let template = branch
            .split('"')
            .nth(1)
            .expect("no string literal in the Android branch");
        assert_eq!(
            template,
            SERVO_ANDROID.replace(SERVO_VERSION_TOKEN, "Servo/{SERVO_VERSION}"),
            "update SERVO_ANDROID and COMBRAY_ANDROID, and the site-check harness's \
             USER_AGENT_TEMPLATE, to the pinned Servo's Android agent"
        );
    }

    #[test]
    fn servos_android_agent_gains_firefox_for_androids_gecko_token() {
        assert_eq!(
            with_gecko_token(SERVO_ANDROID).as_deref(),
            Some(COMBRAY_ANDROID)
        );
    }

    #[test]
    fn the_token_follows_the_revision_of_the_agent() {
        assert_eq!(
            with_gecko_token(
                "Mozilla/5.0 (X11; Linux x86_64; rv:160.0) Servo/0.8.0-dev Firefox/160.0"
            )
            .as_deref(),
            Some(
                "Mozilla/5.0 (X11; Linux x86_64; rv:160.0) Gecko/160.0 Servo/0.8.0-dev Firefox/160.0"
            )
        );
    }

    #[test]
    fn an_agent_with_a_gecko_token_is_kept() {
        let firefox = "Mozilla/5.0 (Android 15; Mobile; rv:144.0) Gecko/144.0 Firefox/144.0";
        for agent in [firefox, COMBRAY_ANDROID] {
            assert_eq!(with_gecko_token(agent).as_deref(), Some(agent));
        }
    }

    #[test]
    fn an_agent_of_another_shape_is_left_to_the_caller() {
        for agent in [
            "",
            "Mozilla/5.0 (Android 10; Mobile) Servo/0.7.0 Firefox/153.0",
            "Mozilla/5.0 (Android 10; Mobile; rv:153.0 Servo/0.7.0 Firefox/153.0",
            "Mozilla/5.0 (Android 10; Mobile; rv:) Servo/0.7.0 Firefox/153.0",
            "Mozilla/5.0 (Android 10; Mobile; rv:153.x) Servo/0.7.0 Firefox/153.0",
            "Mozilla/5.0 (Android 10; Mobile; rv:153..0) Servo/0.7.0 Firefox/153.0",
            "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Firefox/153.0",
            "Servo/0.7.0 Mozilla/5.0 (Android 10; Mobile; rv:153.0) Firefox/153.0",
        ] {
            assert_eq!(with_gecko_token(agent), None, "{agent:?}");
        }
    }
}

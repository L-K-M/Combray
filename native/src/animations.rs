//! The script every document runs before its own: Web Animations for pages (see
//! `animations.js`).

const TEMPLATE: &str = include_str!("animations.js");
/// web-animations-js 2.3.2 (Apache-2.0), `web-animations-next-lite.min.js` from the npm package,
/// unmodified: the build with `finished` and `ready` promises and `Animation.effect`. The upstream
/// project is archived. Tarball integrity:
/// sha512-TOMFWtQdxzjWp8qx4DAraTWTsdhxVSiWa6NkPFSaPtZ1diKUxTn4yTix73A1euG1WbSOMMPcY51cnjTIHrGtDA==
const POLYFILL: &str = include_str!("vendor/web-animations-next-lite.min.js");
const PLACEHOLDER: &str = "__WEB_ANIMATIONS__";

/// The page script with the polyfill in place, its source never rewritten.
// Only the Android engine injects it; host builds compile it for the tests.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) fn page_script() -> String {
    TEMPLATE.replacen(PLACEHOLDER, POLYFILL, 1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capture::sha256_hex;

    /// SHA-256 of the file in the 2.3.2 tarball.
    const POLYFILL_SHA256: &str =
        "79f638b4a682bb827746aeb8203abf4492eccc9288ec7c9c0a39bf2e31e7b716";

    #[test]
    fn the_vendored_polyfill_is_the_published_file() {
        assert_eq!(sha256_hex(POLYFILL.as_bytes()), POLYFILL_SHA256);
    }

    #[test]
    fn the_polyfill_is_embedded_once_and_unchanged() {
        let script = page_script();

        assert!(!script.contains(PLACEHOLDER));
        assert_eq!(script.matches(POLYFILL).count(), 1);
        assert_eq!(
            script.len(),
            TEMPLATE.len() - PLACEHOLDER.len() + POLYFILL.len()
        );
    }
}

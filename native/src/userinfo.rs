//! Removes `user:password@` from every URL the bridge reports.
//!
//! After an HTTP sign-in, Servo 0.6.0 does not keep the answer as an authentication entry: it
//! writes the username and password into the request URL (`components/net/http_loader.rs`,
//! HTTP-network-or-cache fetch step 14.3) and fetches again. The response, the committed
//! document and every relative link on that page then carry the credentials, and the
//! application would store, show and share them. Servo keeps its own copy of the URL,
//! so the signed-in page and its links keep working; only what crosses JNI is stripped.
//!
//! Works on serialized URLs, so it builds and is tested on any host (the `url` crate is an
//! Android-only dependency). In a WHATWG-serialized URL the authority follows `scheme://`,
//! ends at the first `/`, `?` or `#`, and its last `@` ends the userinfo (an `@` inside the
//! userinfo is percent-encoded). Strings without that shape are returned unchanged.

use std::borrow::Cow;

/// Characters that end an authority. A serialized URL has no raw `\` in its authority, but
/// URL parsers read one as a path separator in `http(s)` addresses: stopping there keeps a
/// page-made string from being rewritten into a different address.
const AUTHORITY_END: [char; 4] = ['/', '?', '#', '\\'];

/// `url` without its username and password. The scheme, host, port, path, query and fragment
/// are kept exactly.
///
/// ```text
/// http://ada:lovelace@localhost:8766/a?to=b@c  →  http://localhost:8766/a?to=b@c
/// https://ada@[::1]:8443/                      →  https://[::1]:8443/
/// https://example.org/@ada, mailto:ada@a.test  →  unchanged
/// ```
pub(crate) fn strip_userinfo(url: &str) -> Cow<'_, str> {
    let Some((scheme, after_scheme)) = url.split_once(':') else {
        return Cow::Borrowed(url);
    };
    if !is_scheme(scheme) {
        return Cow::Borrowed(url);
    }
    let Some(authority_and_rest) = after_scheme.strip_prefix("//") else {
        return Cow::Borrowed(url);
    };

    let authority_end = authority_and_rest
        .find(AUTHORITY_END)
        .unwrap_or(authority_and_rest.len());
    let Some(at) = authority_and_rest[..authority_end].rfind('@') else {
        return Cow::Borrowed(url);
    };

    let prefix = &url[..url.len() - authority_and_rest.len()];
    Cow::Owned(format!("{prefix}{}", &authority_and_rest[at + 1..]))
}

/// `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` (RFC 3986 section 3.1).
fn is_scheme(scheme: &str) -> bool {
    let mut characters = scheme.chars();
    characters
        .next()
        .is_some_and(|first| first.is_ascii_alphabetic())
        && characters.all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stripped(url: &str) -> String {
        strip_userinfo(url).into_owned()
    }

    #[test]
    fn username_and_password_are_removed() {
        assert_eq!(
            stripped("http://ada:lovelace@localhost:8766/auth.html"),
            "http://localhost:8766/auth.html"
        );
        assert_eq!(
            stripped("https://ada:lovelace@example.org"),
            "https://example.org"
        );
    }

    #[test]
    fn a_username_alone_is_removed() {
        assert_eq!(stripped("https://ada@example.org/"), "https://example.org/");
        assert_eq!(stripped("http://ada:@example.org/"), "http://example.org/");
    }

    #[test]
    fn addresses_without_userinfo_are_returned_as_they_are() {
        for url in [
            "http://localhost:8766/auth.html",
            "https://example.org:8443/a/b?c=d#e",
            "about:blank",
            "",
            "not a url",
        ] {
            assert!(matches!(strip_userinfo(url), Cow::Borrowed(same) if same == url));
        }
    }

    #[test]
    fn ipv6_hosts_and_ports_are_kept() {
        assert_eq!(
            stripped("http://ada:lovelace@[::1]:8080/x"),
            "http://[::1]:8080/x"
        );
        assert_eq!(
            stripped("https://ada@[2001:db8::1]/"),
            "https://[2001:db8::1]/"
        );
        assert_eq!(
            stripped("http://[2001:db8::1]:8080/"),
            "http://[2001:db8::1]:8080/"
        );
        assert_eq!(
            stripped("http://ada:lovelace@192.0.2.1:81/"),
            "http://192.0.2.1:81/"
        );
    }

    #[test]
    fn percent_encoded_userinfo_is_removed_whole() {
        assert_eq!(
            stripped("https://ada%40home:p%3A%40ss%2F@example.org/a"),
            "https://example.org/a"
        );
    }

    #[test]
    fn at_signs_in_the_path_query_or_fragment_are_untouched() {
        let untouched = "https://example.org/@ada?mail=ada@example.org#x@y";
        assert_eq!(stripped(untouched), untouched);
        assert_eq!(
            stripped("https://ada:pw@example.org/@ada?mail=ada@example.org#x@y"),
            untouched
        );
        assert_eq!(
            stripped("https://example.org?to=ada@example.org"),
            "https://example.org?to=ada@example.org"
        );
        assert_eq!(
            stripped("https://example.org#ada@example.org"),
            "https://example.org#ada@example.org"
        );
    }

    #[test]
    fn addresses_without_an_authority_are_untouched() {
        for url in [
            "mailto:ada@example.org",
            "data:text/html,<a href=//ada:pw@example.org>",
            "blob:https://example.org/0b6d-ada@x",
            "1http://ada@example.org/",
            "http:ada@example.org",
        ] {
            assert_eq!(stripped(url), url);
        }
    }

    #[test]
    fn a_backslash_ends_the_authority() {
        let page_made = r"http://example.org\ada@evil.test/";
        assert_eq!(stripped(page_made), page_made);
    }
}

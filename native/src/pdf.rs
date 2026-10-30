//! The `combray-pdf:` scheme: the files of the PDF viewer, served to Servo's fetch.
//!
//! ```text
//!  viewer document or its worker (opaque origin)
//!    fetch combray-pdf://viewer-<token>/pdfium/pdfium.wasm
//!          │
//!          ▼
//!  PdfProtocolHandler::load ── GET? host viewer-<token>? allowlisted path? ──┐
//!          │ yes                                                            │ no
//!          ▼                                                                ▼
//!  inflate the embedded gzip file, Content-Type                    network error
//!          └──────────── both carry the viewer CSP header ─────────────────┘
//! ```
//!
//! The host carries a random token chosen once per process (owner decision, 29 October 2026),
//! so a site cannot detect Combray by probing for these files. HTTPS pages cannot fetch the
//! scheme at all, because it is not secure (mixed content); a plain-HTTP page can, since
//! `is_fetchable` skips CORS, and only the token stops it.
//!
//! Every response carries the viewer's CSP: Servo gives a worker loaded from a non-local
//! scheme the policy in its own script response, not the policy of the document that made it.
//!
//! The files are the viewer's module, style sheet and worker (`pdf/viewer.mjs`, `viewer.css`,
//! `worker.mjs`) and PDFium. The engine loads the module into every top-level PDF response once
//! `engine.rs` sets the viewer preferences; the phone's CJK fonts join later (docs/PDF.md).

use std::fmt;
use std::fs::File;
use std::io::{self, Read};
use std::sync::OnceLock;

use flate2::read::GzDecoder;
use url::Url;

pub const SCHEME: &str = "combray-pdf";
const HOST_PREFIX: &str = "viewer-";

/// 128 bits from the operating system's random number generator.
const TOKEN_BYTES: usize = 16;
const RANDOM_SOURCE: &str = "/dev/urandom";

pub const CSP_HEADER: &str = "content-security-policy";
pub const CONTENT_TYPE_HEADER: &str = "content-type";

/// `pdfium.wasm` and `index.browser.js` of `@embedpdf/pdfium` 2.15.1, compressed with
/// `gzip -9n` by `scripts/vendor-pdfium.py`.
const PDFIUM_WASM_GZ: &[u8] = include_bytes!("pdf/vendor/pdfium.wasm.gz");
const PDFIUM_SCRIPT_GZ: &[u8] = include_bytes!("pdf/vendor/index.browser.js.gz");
/// The viewer: the module the engine's viewer document loads, its style sheet and its worker.
const VIEWER_MODULE: &str = include_str!("pdf/viewer.mjs");
const VIEWER_STYLESHEET: &str = include_str!("pdf/viewer.css");
const VIEWER_WORKER: &str = include_str!("pdf/worker.mjs");

/// The random part of the viewer's host name. Not shown in logs: it is what keeps sites from
/// finding the viewer's files.
#[derive(Clone, PartialEq, Eq)]
pub struct ViewerToken(String);

impl ViewerToken {
    /// This process's token, generated on first use. If the random source cannot be read, the
    /// error stays for the life of the process and the scheme is not registered.
    pub fn for_process() -> Result<&'static ViewerToken, &'static str> {
        static TOKEN: OnceLock<Result<ViewerToken, String>> = OnceLock::new();
        TOKEN
            .get_or_init(|| {
                ViewerToken::generate().map_err(|error| format!("{RANDOM_SOURCE}: {error}"))
            })
            .as_ref()
            .map_err(String::as_str)
    }

    fn generate() -> io::Result<ViewerToken> {
        let mut bytes = [0; TOKEN_BYTES];
        File::open(RANDOM_SOURCE)?.read_exact(&mut bytes)?;
        Ok(ViewerToken::from_bytes(bytes))
    }

    fn from_bytes(bytes: [u8; TOKEN_BYTES]) -> ViewerToken {
        ViewerToken(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
    }

    /// `viewer-<32 lowercase hex digits>`.
    pub fn host(&self) -> String {
        format!("{HOST_PREFIX}{}", self.0)
    }

    /// `combray-pdf://viewer-<token>/`, the only source the viewer's CSP allows.
    pub fn origin(&self) -> String {
        format!("{SCHEME}://{}/", self.host())
    }
}

impl fmt::Debug for ViewerToken {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("ViewerToken(..)")
    }
}

/// The policy of the viewer document and of every file the scheme serves: scripts, the worker,
/// fetches and style sheets only from the viewer's own origin (fetches also from the `blob:`
/// URL holding the PDF), WebAssembly compilation but no `eval`, and nothing else.
pub fn viewer_csp(token: &ViewerToken) -> String {
    let origin = token.origin();
    format!(
        "default-src 'none'; script-src {origin} 'wasm-unsafe-eval'; worker-src {origin}; \
         connect-src {origin} blob:; style-src {origin}; img-src 'none'; font-src 'none'; \
         base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'"
    )
}

/// The allowlist: every file the scheme serves.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ViewerFile {
    Module,
    Stylesheet,
    Worker,
    PdfiumScript,
    PdfiumWasm,
}

impl ViewerFile {
    const ALL: [ViewerFile; 5] = [
        ViewerFile::Module,
        ViewerFile::Stylesheet,
        ViewerFile::Worker,
        ViewerFile::PdfiumScript,
        ViewerFile::PdfiumWasm,
    ];

    fn at(path: &str) -> Option<ViewerFile> {
        ViewerFile::ALL.into_iter().find(|file| file.path() == path)
    }

    fn path(self) -> &'static str {
        match self {
            ViewerFile::Module => "/viewer.mjs",
            ViewerFile::Stylesheet => "/viewer.css",
            ViewerFile::Worker => "/worker.mjs",
            ViewerFile::PdfiumScript => "/pdfium/index.browser.js",
            ViewerFile::PdfiumWasm => "/pdfium/pdfium.wasm",
        }
    }

    fn content_type(self) -> &'static str {
        match self {
            // Module scripts and module workers need a JavaScript MIME type.
            ViewerFile::Module | ViewerFile::Worker | ViewerFile::PdfiumScript => "text/javascript",
            ViewerFile::Stylesheet => "text/css",
            // What WebAssembly.instantiateStreaming requires.
            ViewerFile::PdfiumWasm => "application/wasm",
        }
    }

    /// PDFium is inflated on every request rather than kept: a viewer loads each file once, and
    /// keeping 4.9 MB for the life of the process would cost more than inflating 2.2 MB again.
    fn contents(self) -> io::Result<Vec<u8>> {
        match self {
            ViewerFile::Module => Ok(VIEWER_MODULE.as_bytes().to_vec()),
            ViewerFile::Stylesheet => Ok(VIEWER_STYLESHEET.as_bytes().to_vec()),
            ViewerFile::Worker => Ok(VIEWER_WORKER.as_bytes().to_vec()),
            ViewerFile::PdfiumScript => inflate(PDFIUM_SCRIPT_GZ),
            ViewerFile::PdfiumWasm => inflate(PDFIUM_WASM_GZ),
        }
    }
}

/// The most [inflate] reserves up front: a little above the largest embedded file (pdfium.wasm,
/// 4,646,932 bytes). The gzip trailer is only a hint; `read_to_end` grows the buffer past it.
const MAX_INFLATE_HINT: usize = 6 << 20;

fn inflate(compressed: &[u8]) -> io::Result<Vec<u8>> {
    // A gzip member ends with its uncompressed length modulo 2^32 (RFC 1952). Damaged input can
    // end in any four bytes, so the length only sizes the first allocation, up to a bound.
    let length = compressed
        .last_chunk::<4>()
        .map_or(0, |size| u32::from_le_bytes(*size) as usize);
    let mut contents = Vec::with_capacity(length.min(MAX_INFLATE_HINT));
    GzDecoder::new(compressed).read_to_end(&mut contents)?;
    Ok(contents)
}

/// Why the scheme answered with a network error.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refusal {
    /// Anything but GET.
    Method,
    /// Another host (a guessed or stale token), a port, user info or a query.
    Address,
    /// A path outside the allowlist.
    NotServed,
    /// An embedded file did not inflate.
    Unreadable,
}

/// The scheme's answer to one request, before it becomes a Servo response.
#[derive(Debug)]
pub struct SchemeResponse {
    pub body: Result<Vec<u8>, Refusal>,
    /// Lowercase header names; the CSP is always first.
    pub headers: Vec<(&'static str, String)>,
}

/// The decisions of the `combray-pdf:` handler, without Servo, so host tests cover them.
#[derive(Debug)]
pub struct ViewerScheme {
    host: String,
    csp: String,
}

impl ViewerScheme {
    pub fn new(token: &ViewerToken) -> ViewerScheme {
        ViewerScheme {
            host: token.host(),
            csp: viewer_csp(token),
        }
    }

    pub fn respond(&self, method: &str, url: &Url) -> SchemeResponse {
        let mut headers = vec![(CSP_HEADER, self.csp.clone())];
        let body = self.file(method, url).and_then(|file| {
            let contents = file.contents().map_err(|_| Refusal::Unreadable)?;
            headers.push((CONTENT_TYPE_HEADER, file.content_type().to_owned()));
            Ok(contents)
        });
        SchemeResponse { body, headers }
    }

    fn file(&self, method: &str, url: &Url) -> Result<ViewerFile, Refusal> {
        // Method names are case-sensitive (RFC 9110, section 9.1).
        if method != "GET" {
            return Err(Refusal::Method);
        }

        let exact = url.scheme() == SCHEME
            && url.host_str() == Some(self.host.as_str())
            && url.port().is_none()
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none();
        if !exact {
            return Err(Refusal::Address);
        }

        ViewerFile::at(url.path()).ok_or(Refusal::NotServed)
    }
}

#[cfg(target_os = "android")]
pub use handler::PdfProtocolHandler;

#[cfg(target_os = "android")]
mod handler {
    use std::future::{self, Future};
    use std::pin::Pin;

    use http::{HeaderName, HeaderValue};
    use servo::protocol_handler::{
        DoneChannel, FetchContext, HttpStatus, NetworkError, ProtocolHandler, Request,
        ResourceFetchTiming, Response, ResponseBody,
    };

    use super::{ViewerScheme, ViewerToken};

    /// Registered for [`super::SCHEME`]: maps [`ViewerScheme`]'s answer onto a Servo response,
    /// header for header.
    pub struct PdfProtocolHandler(ViewerScheme);

    impl PdfProtocolHandler {
        pub fn new(token: &ViewerToken) -> PdfProtocolHandler {
            PdfProtocolHandler(ViewerScheme::new(token))
        }
    }

    impl ProtocolHandler for PdfProtocolHandler {
        fn load<'a>(
            &'a self,
            request: &'a mut Request,
            _done_chan: &mut DoneChannel,
            _context: &FetchContext,
        ) -> Pin<Box<dyn Future<Output = Response> + Send + 'a>> {
            let url = request.current_url();
            let answer = self.0.respond(request.method.as_str(), url.as_url());
            let mut response = match answer.body {
                Ok(body) => {
                    let mut response =
                        Response::new(url, ResourceFetchTiming::new(request.timing_type()));
                    response.status = HttpStatus::default();
                    *response.body.lock() = ResponseBody::Done(body);
                    response
                },
                Err(refusal) => Response::network_error(NetworkError::ResourceLoadError(format!(
                    "{}: {refusal:?}",
                    super::SCHEME
                ))),
            };
            for (name, value) in answer.headers {
                let Ok(value) = HeaderValue::try_from(value) else {
                    // The host tests check every value; a file must never load without its CSP.
                    return Box::pin(future::ready(Response::network_error(
                        NetworkError::ResourceLoadError(format!("{}: bad {name}", super::SCHEME)),
                    )));
                };
                response
                    .headers
                    .insert(HeaderName::from_static(name), value);
            }
            Box::pin(future::ready(response))
        }

        /// The viewer's opaque origin can fetch the files only through this (Servo's
        /// `scheme_fetch`); the response is basic, with no CORS check.
        fn is_fetchable(&self) -> bool {
            true
        }

        /// Not secure, so secure pages are refused these files as mixed content.
        fn is_secure(&self) -> bool {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capture::sha256_hex;

    /// SHA-256 of the files in the `@embedpdf/pdfium` 2.15.1 tarball (also pinned in
    /// scripts/vendor-pdfium.py).
    const PDFIUM_WASM_SHA256: &str =
        "5e4cd023c3dad4a895b3571ca573d3fc51bac6de48360d95283134385b954eaa";
    const PDFIUM_SCRIPT_SHA256: &str =
        "2b8b6db33451f2599385697fe5bf634abdfbb093fec497febd7fb60b5aeea8ca";

    const TOKEN: [u8; TOKEN_BYTES] = [
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee,
        0xff,
    ];
    const HOST: &str = "viewer-00112233445566778899aabbccddeeff";
    const CSP: &str = "default-src 'none'; \
        script-src combray-pdf://viewer-00112233445566778899aabbccddeeff/ 'wasm-unsafe-eval'; \
        worker-src combray-pdf://viewer-00112233445566778899aabbccddeeff/; \
        connect-src combray-pdf://viewer-00112233445566778899aabbccddeeff/ blob:; \
        style-src combray-pdf://viewer-00112233445566778899aabbccddeeff/; img-src 'none'; \
        font-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; \
        object-src 'none'";

    fn scheme() -> ViewerScheme {
        ViewerScheme::new(&ViewerToken::from_bytes(TOKEN))
    }

    fn get(url: &str) -> SchemeResponse {
        scheme().respond("GET", &Url::parse(url).unwrap())
    }

    fn header<'a>(response: &'a SchemeResponse, name: &str) -> Vec<&'a str> {
        response
            .headers
            .iter()
            .filter(|(header, _)| *header == name)
            .map(|(_, value)| value.as_str())
            .collect()
    }

    #[test]
    fn the_csp_is_the_viewers_policy_for_its_own_origin() {
        let token = ViewerToken::from_bytes(TOKEN);

        assert_eq!(token.host(), HOST);
        assert_eq!(token.origin(), format!("combray-pdf://{HOST}/"));
        assert_eq!(viewer_csp(&token), CSP);
    }

    #[test]
    fn the_allowlisted_files_are_served_with_their_types() {
        for (path, content_type) in [
            ("/viewer.mjs", "text/javascript"),
            ("/viewer.css", "text/css"),
            ("/worker.mjs", "text/javascript"),
            ("/pdfium/index.browser.js", "text/javascript"),
            ("/pdfium/pdfium.wasm", "application/wasm"),
        ] {
            let response = get(&format!("combray-pdf://{HOST}{path}"));

            assert!(
                response.body.as_ref().is_ok_and(|body| !body.is_empty()),
                "{path}"
            );
            assert_eq!(
                header(&response, CONTENT_TYPE_HEADER),
                [content_type],
                "{path}"
            );
        }
    }

    #[test]
    fn the_served_files_are_the_pinned_package_files() {
        for (path, digest) in [
            ("/pdfium/index.browser.js", PDFIUM_SCRIPT_SHA256),
            ("/pdfium/pdfium.wasm", PDFIUM_WASM_SHA256),
        ] {
            let body = get(&format!("combray-pdf://{HOST}{path}")).body.unwrap();

            assert_eq!(sha256_hex(&body), digest, "{path}");
        }
    }

    #[test]
    fn the_viewer_files_are_served_as_written() {
        for (path, source) in [
            ("/viewer.mjs", VIEWER_MODULE),
            ("/viewer.css", VIEWER_STYLESHEET),
            ("/worker.mjs", VIEWER_WORKER),
        ] {
            let body = get(&format!("combray-pdf://{HOST}{path}")).body.unwrap();

            assert_eq!(body, source.as_bytes(), "{path}");
        }
    }

    #[test]
    fn the_viewer_loads_only_allowlisted_files() {
        // Every scheme path the module and the worker name, relative to them, is served. A
        // renamed file would otherwise only fail on a device.
        let module = ViewerFile::Module.path();
        for (source, relative) in [
            (VIEWER_MODULE, "worker.mjs"),
            (VIEWER_MODULE, "viewer.css"),
            (VIEWER_WORKER, "./pdfium/index.browser.js"),
            (VIEWER_WORKER, "pdfium/pdfium.wasm"),
        ] {
            assert!(source.contains(&format!("\"{relative}\"")), "{relative}");
            let url = Url::parse(&format!("combray-pdf://{HOST}{module}"))
                .unwrap()
                .join(relative)
                .unwrap();
            assert!(get(url.as_str()).body.is_ok(), "{url}");
        }
    }

    #[test]
    fn the_viewer_adds_no_unload_listener() {
        // An unload or beforeunload listener keeps the viewer out of the back-forward cache, and
        // the engine then frees the PDF on leaving (docs/PDF.md).
        for source in [VIEWER_MODULE, VIEWER_WORKER] {
            for event in [
                "\"unload\"",
                "\"beforeunload\"",
                "onunload",
                "onbeforeunload",
            ] {
                assert!(!source.contains(event), "{event}");
            }
            assert!(!source.contains("revokeObjectURL"));
        }
    }

    #[test]
    fn every_file_inflates_to_the_length_its_gzip_trailer_records() {
        for (file, compressed) in [
            (ViewerFile::PdfiumScript, PDFIUM_SCRIPT_GZ),
            (ViewerFile::PdfiumWasm, PDFIUM_WASM_GZ),
        ] {
            let recorded = u32::from_le_bytes(*compressed.last_chunk::<4>().unwrap());

            assert_eq!(
                file.contents().unwrap().len(),
                recorded as usize,
                "{file:?}"
            );
        }
    }

    #[test]
    fn a_damaged_file_does_not_inflate() {
        let mut damaged = PDFIUM_SCRIPT_GZ.to_vec();
        damaged.truncate(damaged.len() / 2);

        assert!(inflate(&damaged).is_err());
        assert!(inflate(b"not gzip").is_err());
    }

    #[test]
    fn a_lying_length_trailer_does_not_size_the_buffer() {
        use std::io::Write;

        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(b"%PDF-1.7").unwrap();
        let mut crafted = encoder.finish().unwrap();
        // Bytes after the gzip member are not read, but they are the input's last four.
        crafted.extend_from_slice(&u32::MAX.to_le_bytes());

        let contents = inflate(&crafted).unwrap();

        assert_eq!(contents, b"%PDF-1.7");
        assert!(contents.capacity() <= MAX_INFLATE_HINT);
    }

    #[test]
    fn the_embedded_files_fit_the_capacity_hint() {
        for compressed in [PDFIUM_SCRIPT_GZ, PDFIUM_WASM_GZ] {
            let recorded = u32::from_le_bytes(*compressed.last_chunk::<4>().unwrap());

            assert!(recorded as usize <= MAX_INFLATE_HINT, "{recorded}");
        }
    }

    #[test]
    fn only_the_allowlist_is_served() {
        for path in [
            "/",
            "/pdfium/",
            "/pdfium/pdfium.wasm.gz",
            "/pdfium/index.js",
            "/pdfium/PDFIUM.WASM",
            "/pdfium/%70dfium.wasm",
            "/pdfium/../pdfium/pdfium.wasm/x",
            "/viewer.js",
            "/VIEWER.MJS",
            "/viewer.mjs/",
            "/pdf/viewer.mjs",
            "/test/viewer.test.mjs",
            "/vendor/pdfium.LICENSE",
            "/pdfium.wasm",
            "/font/manifest.json",
        ] {
            assert_eq!(
                get(&format!("combray-pdf://{HOST}{path}"))
                    .body
                    .unwrap_err(),
                Refusal::NotServed,
                "{path}"
            );
        }
    }

    #[test]
    fn another_token_or_address_is_refused() {
        for url in [
            "combray-pdf://viewer-00112233445566778899aabbccddeefe/pdfium/pdfium.wasm",
            "combray-pdf://VIEWER-00112233445566778899AABBCCDDEEFF/pdfium/pdfium.wasm",
            "combray-pdf://viewer-/pdfium/pdfium.wasm",
            "combray-pdf:///pdfium/pdfium.wasm",
            "combray-pdf:/pdfium/pdfium.wasm",
            "combray-pdf://viewer-00112233445566778899aabbccddeeff.example/pdfium/pdfium.wasm",
            "combray-pdf://viewer-00112233445566778899aabbccddeeff:80/pdfium/pdfium.wasm",
            "combray-pdf://user@viewer-00112233445566778899aabbccddeeff/pdfium/pdfium.wasm",
            "combray-pdf://viewer-00112233445566778899aabbccddeeff/pdfium/pdfium.wasm?v=1",
            "https://viewer-00112233445566778899aabbccddeeff/pdfium/pdfium.wasm",
        ] {
            assert_eq!(get(url).body.unwrap_err(), Refusal::Address, "{url}");
        }
    }

    #[test]
    fn a_fragment_does_not_change_the_file() {
        let response = get(&format!("combray-pdf://{HOST}/pdfium/pdfium.wasm#x"));

        assert!(response.body.is_ok());
    }

    #[test]
    fn only_get_is_answered() {
        let url = Url::parse(&format!("combray-pdf://{HOST}/pdfium/pdfium.wasm")).unwrap();
        for method in ["HEAD", "POST", "PUT", "DELETE", "OPTIONS", "get"] {
            assert_eq!(
                scheme().respond(method, &url).body.unwrap_err(),
                Refusal::Method,
                "{method}"
            );
        }
    }

    #[test]
    fn every_scheme_response_carries_the_viewer_csp() {
        let served = ViewerFile::ALL.map(|file| format!("combray-pdf://{HOST}{}", file.path()));
        let refused = [
            format!("combray-pdf://{HOST}/viewer.js"),
            "combray-pdf://viewer-0/pdfium/pdfium.wasm".to_owned(),
        ];
        let mut responses: Vec<SchemeResponse> =
            served.iter().chain(&refused).map(|url| get(url)).collect();
        responses.push(scheme().respond("POST", &Url::parse(&served[0]).unwrap()));

        for response in &responses {
            assert_eq!(header(response, CSP_HEADER), [CSP], "{:?}", response.body);
            assert_eq!(response.headers[0], (CSP_HEADER, CSP.to_owned()));
        }
        assert_eq!(
            responses.iter().filter(|r| r.body.is_ok()).count(),
            ViewerFile::ALL.len()
        );
    }

    #[test]
    fn a_refusal_names_no_content_type() {
        let response = get(&format!("combray-pdf://{HOST}/viewer.js"));

        assert!(header(&response, CONTENT_TYPE_HEADER).is_empty());
    }

    #[test]
    fn header_values_are_visible_ascii() {
        // What http::HeaderValue accepts without error, so the Android handler never drops one.
        let mut responses: Vec<SchemeResponse> = ViewerFile::ALL
            .map(|file| get(&format!("combray-pdf://{HOST}{}", file.path())))
            .into();
        responses.push(get(&format!("combray-pdf://{HOST}/")));
        for response in responses {
            for (name, value) in &response.headers {
                assert_eq!(*name, name.to_ascii_lowercase());
                assert!(
                    value
                        .bytes()
                        .all(|byte| byte == b' ' || byte.is_ascii_graphic())
                );
            }
        }
    }

    #[test]
    fn the_process_token_is_random_and_stable() {
        let token = ViewerToken::for_process().unwrap();

        assert!(std::ptr::eq(token, ViewerToken::for_process().unwrap()));
        assert_eq!(token.0.len(), 2 * TOKEN_BYTES);
        assert!(
            token
                .0
                .bytes()
                .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
        );
        assert_ne!(ViewerToken::generate().unwrap(), *token);
        assert_eq!(format!("{token:?}"), "ViewerToken(..)");
    }

    #[test]
    fn the_viewer_origin_parses_to_the_token_host() {
        let origin = Url::parse(&ViewerToken::from_bytes(TOKEN).origin()).unwrap();

        assert_eq!(origin.scheme(), SCHEME);
        assert_eq!(origin.host_str(), Some(HOST));
        assert_eq!(origin.path(), "/");
    }
}

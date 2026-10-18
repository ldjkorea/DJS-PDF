# DJS PDF security notes

The bundled PDF.js API remains 3.11.174 for compatibility with the existing
Android file-origin WebView and its IndexedDB data. This is a downstream patched
build, not an assertion that upstream 3.11.174 is safe without these changes.

For [CVE-2024-4367 / GHSA-wgrm-67xf-hhpq](https://github.com/mozilla/pdf.js/security/advisories/GHSA-wgrm-67xf-hhpq):

- The dynamic glyph compiler (`new Function("c", "size", ...)`) has been removed
  from `FontFaceObject.getPathGenerator`; the existing interpreted path is used.
- The main-thread eval capability probe returns false without invoking Function.
- Every document task receives `isEvalSupported: false` and `enableScripting: false`.
- The page's CSP permits neither inline scripts nor `unsafe-eval`, remote scripts,
  remote connections, frames/objects, or forms. Fonts may use local data/blob URLs.
- Tests verify that the removed glyph compiler cannot be reintroduced unnoticed.

The worker and main library retain the same upstream version. A future engine
upgrade must verify API/text-layer/worker compatibility, Android file-origin
loading, CJK/image PDFs, and the security advisories for the selected version.
This patch does not claim to resolve unknown vulnerabilities in all old code.

The Android app keeps `file:///android_asset/index.html` and `DjsPdfDB_v2` so that
installing the update does not switch existing documents to an empty origin.
It restricts resource requests to exact bundled asset names and an exact,
request-ID-scoped incoming PDF route. Universal file URL access and content URL
access are disabled; remote navigation and cleartext are blocked. Local file
access from file URLs is retained for the bundled worker and intercepted XHR;
arbitrary paths are denied by the resource interceptor.

Incoming files are copied to unique temporary paths on a serial executor and
acknowledged by ID. Files over 256 MiB fail with an explicit message. Sharing uses
bounded chunks and verifies both byte count and content hash before granting a
read-only URI under `cache/shared`. The provider checks canonical boundaries and
rejects encoded traversal and writable modes.

An APK update requires an allowlisted GitHub repository URL, HTTPS-only redirects,
a SHA-256 digest, a matching package, the expected newer version, a non-debuggable
application, and the currently installed signer. Android's installer remains the
final installation boundary. Private keys and credentials are never packaged.

Existing v1.2.0 installs use the historical certificate whose SHA-256 is
`63c4d2868987dfc9be77e3321df5925a7051e585e49538e5bdfac0df45a5a9e5`.
Release builds can explicitly preserve that identity with
`-PpreserveInstalledSigner=true`; the resulting app is **not debuggable** even
though the certificate's historical subject is `Android Debug`. The key was not
regenerated or rotated. Changing it without an upgrade plan breaks installation
over the existing app. New distribution/signing policies require a separate
decision that accounts for installed users.

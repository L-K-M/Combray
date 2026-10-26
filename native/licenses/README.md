# Upstream license texts

License texts for components that publish none with their package. `native/bundled-notices.toml` names each file; `scripts/generate-notices.py` shows it in Settings › About instead of an SPDX template. Check the file again when the locked version changes: the generator stops when a `[[crate]]` entry's `version` no longer matches.

| File | Component | Source |
| --- | --- | --- |
| `alloc-stdlib-0.2.4/LICENSE` | alloc-stdlib 0.2.4 | `LICENSE` of dropbox/rust-alloc-no-stdlib at `ae42d22078b98549e987d2f03d12df7b984fde47`, the commit in the crate's `.cargo_vcs_info.json` |
| `nom-language-0.1.0/LICENSE` | nom-language 0.1.0 | `LICENSE` of rust-bakery/nom at `2cec1b3e4c9ccac62c902d60c00de6d1549ccbe1` (`.cargo_vcs_info.json`) |
| `realfft-3.5.0/LICENSE` | realfft 3.5.0 | The SPDX MIT text. Neither the crate nor HEnquist/realfft publishes a license file; `Cargo.toml` says MIT and names HEnquist as the author |
| `taffy-0.14.0/LICENSE` | taffy 0.14.0 | `LICENSE` of DioxusLabs/taffy at `77f385683c1d698c91a23a259f87fdddf26925fb` (`.cargo_vcs_info.json`) |
| `void-1.0.2/LICENSE-MIT` | void 1.0.2 | `LICENSE-MIT` of reem/rust-void, added in `ea5a2526d7a81ff45960525b62a3dbfd34c1703c`; the 1.0.2 tag has no license file |
| `wuff/LICENSE` | wuff 0.2.8, wuff-capi 0.2.0 | `LICENSE` of nicoburns/wuff, identical at `0775526a3555e089d2fce140f755cb225e35cdc7` and `3351f406f2069d45260eb425f768c38c961ece48` (`.cargo_vcs_info.json`) |
| `chromium/LICENSE` | Chromium HSTS preload list | `LICENSE` of chromium/src (main, October 2026); Servo builds its preload list from Chromium's `transport_security_state_static.json` |

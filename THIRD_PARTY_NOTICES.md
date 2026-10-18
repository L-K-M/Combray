# Third-party material

The repository's existing Unlicense applies to Combray's own work subject to the terms in [LICENSE](LICENSE). It does not relicense third-party code, fonts, icons, or dependencies.

No Servo engine or Android dependency is vendored in this bootstrap. Servo source files inspected for planning carry the Mozilla Public License 2.0. When incorporating Servo, preserve applicable notices, identify modified covered files, and provide the required source and license material with distribution. Determine obligations from the exact dependencies and files incorporated, rather than treating this planning note as a complete license inventory.

The design concept's standalone renderer includes visualization support code and loads Lucide and Floating UI from public CDNs. These are development preview dependencies, not an Android dependency selection. [Lucide's license](https://lucide.dev/license) includes ISC terms and MIT terms for Feather-derived icons; [Floating UI](https://github.com/floating-ui/floating-ui/blob/master/LICENSE) uses MIT terms. The HTML does not contain a complete third-party license inventory. Before distributing the prototype or incorporating its runtime into the app, inventory the actual bundled libraries and retain required notices. The Android implementation should use a separately selected, licensed native icon set.

The original app icon was generated with OpenAI's built-in ImageGen tool. Its prompt and provenance are in [media-sources](media-sources/README.md).

M0 must record Servo's exact revision and imported components. Before an APK is shared, add the actual dependency/license inventory, notices, and source-availability instructions for the resulting build. This file is currently a tracking note, not a completed audit.

# Instructions for implementation agents

## Read first

Read [README.md](README.md), then [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), then [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md). These files carry the project context; do not require the original chat to understand the work. The plan separates confirmed requirements, proposed defaults, and unresolved questions. Keep those distinctions when making changes.

## Product boundaries

- The app is **Combray**, a personal **Android** browser. **Servo is mandatory.** Do not replace it with Android WebView, Chromium, GeckoView, a remote renderer, or an external browser to pass a milestone. Report a concrete blocker and preserve the Servo direction.
- Keep routine UI actions at the bottom. Tabs and short-term history share one Recent UI. Karakeep saves, Hister content/history search, and Kagi web search are core requirements.
- Preserve the existing `LICENSE` and `media-sources/icon.png`. Icon artwork is square, opaque, edge-to-edge, with sharp outer corners. Launcher masking is Android's concern; do not bake a squircle into the source artwork.
- Existing HTML is a design demo with sample data, not the browser or evidence of working integrations. Two layout alternatives remain; a proposed default is not a user-confirmed decision.
- Personal use does not mean arbitrary sites, page content, tokens, or service requests are trusted. Never put native integration credentials in page JavaScript, navigation URLs, committed configuration, logs, or screenshots.

## Implementation discipline

1. Start with milestone M0. Prove independent Servo views, lifecycle handling, and correctly attributed text extraction on Android. Record exact revisions, commands, environment, results, and remaining limitations in `docs/ENGINE_SPIKE.md` when it exists.
2. Keep Servo handles on their owner thread. Tag callbacks with tab identity, engine generation, and navigation identity. An equal URL is not proof of an equal document.
3. Separate persistent logical tabs, visits, and native views. Closing, expiring local history, forgetting, and deleting remote content have different semantics.
4. Make integration operations durable and tied to their original payload and destination. Test delayed callbacks, ambiguous network completion, process death, and deletion races, not just happy paths.
5. Pin dependencies and toolchains once verified. Do not invent a Maven Servo artifact, JNI API, build command, or compatible version combination. Keep future Gradle wrapper files and dependency lockfiles in version control.
6. Implement bounded vertical slices. Update the plan's status and record decisions after evidence changes. Do not mark a feature complete from fake engine events or mocked HTTP alone.
7. Run checks appropriate to the change. For this documentation bootstrap: `python3 scripts/check_docs.py` and `git diff --check`. Add native tests/build checks when there is native code. Do not claim device tests that were not run.
8. Prefer small patches and inspect the actual diff. Do not commit, push, publish, or change the user's services unless the active task authorizes it. Do not read or export personal history to validate a fixture-based feature.

## Subagents

Proactively delegate suitable work to available free or cheaper subagents running in other harnesses.

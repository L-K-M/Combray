# Combray design reference

[New-tab design](new-tab.md) specifies the native launchpad approved on
24 October 2026, its persistent + button and above-field history suggestions.

[Open the interactive concept](combray-concepts.html) in a current desktop browser. It uses sample content and simulated states, with no live service requests. The standalone preview may load its support libraries from public CDNs; internet access may be needed. It is a reference for the native implementation, not the app's rendering layer.

The [editable source fragment](combray-concepts.fragment.html) preserves the original concept. The standalone file contains the preview wrapper and supporting runtime so another developer can inspect it outside the original conversation. Edit the source if refining the concept, then regenerate the wrapper or explicitly document a new export method. Do not independently modify both files and let them drift.

## Two retained layouts

- **Recent sheet:** a list of open/recent pages rises from the bottom over the current page.
- **Recent home:** Recent becomes the dedicated home surface, keeping bottom navigation in place.

The user liked the concepts; there is no recorded exclusive final choice. The implementation plan proposes starting with the sheet. Both demonstrate bottom address/search, Save, Recent and More; closing a row leaves it in Recent; reopening returns it to an open state; Search all history opens a Hister-style sample view.

## Visual direction

Quiet, compact native chrome; pine green accents, warm light surfaces, charcoal dark surfaces, restrained typography and clear row hierarchy. Keep the page central and the main actions thumb accessible. Use system typography in chrome. The serif article heading is sample webpage content, not a requirement for Combray UI typography. The source contains concrete light/dark palette values if useful; validate contrast on-device before freezing tokens.

Use the original [icon](../../media-sources/icon.png) and [asset notes](../../media-sources/README.md). The source icon is square and full-bleed. The phone's rounded frame in the mockup is a presentation frame and says nothing about icon geometry.

## Known demo limitations

The demo is deliberately not a state-management specification. It has no Servo, network integration, native lifecycle, durable queue or real history. It reuses a small row model; it does not establish the required separation of logical tabs, committed visits and document identity. Closing the current demo row may leave sample article content underneath; the native browser must select another open tab or internal home immediately. The sample Hister filter searches sample labels rather than indexed page bodies. Save acknowledgments are simulated.

Use the [implementation plan](../IMPLEMENTATION_PLAN.md) when interaction semantics or failure behavior are ambiguous. Keep responsive layout, touch targets, TalkBack, font scaling, keyboard/system insets and real Android Back behavior part of implementation acceptance, even when the static preview cannot demonstrate them.

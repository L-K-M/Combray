# New tab: start, continue, recall

Approved for implementation by the owner on 24 October 2026, including local
suggestions above the typing field and a persistent bottom **+** button.

## Layout

Home is native Compose UI, with a compact square Combray icon and wordmark,
a serif “Where next?” heading, one Continue card and up to six quick links.
The content grows upward from the bottom dock; it scrolls on short screens
and at large text sizes. Warm paper, pine, sage and pine-charcoal use the
existing theme. No page renderer, preview fetch or service request is needed
to show Home.

The Home dock contains a single search/address affordance and Hister,
Karakeep, **+**, Recent and More. On a page the actions are Back, Save,
Share, **+**, Recent and More, with the address on a separate row when
needed. **+** stays beside the field while editing and is available in Find
in page too. New tab is removed from More.

New tab reuses an unused blank tab and focuses Home's existing search field,
with the keyboard ready. Tapping the field or using the launcher search
shortcut edits there too, without an additional sheet (owner correction,
24 October 2026). Close ends typing and keeps Home and its blank tab.
Cold start restores the active logical tab rather than replacing it with Home.

## Continue and quick links

- Continue selects the most recently used eligible other open tab by its
  exact identity. Blank, forgotten, failed and sensitive-address pages are
  excluded. Recovering an evicted renderer reloads its last address; it
  does not promise restored forms or scroll position.
- Pins come first in the user's order; frequent sites fill the remaining
  places. Automatic links keep the existing newest-page destination.
  Multiple pages of one site may be pinned; that site's automatic tile is
  then suppressed.
- Pins store an exact navigable HTTP(S) address and a user label, survive
  history expiry, and can be edited, unpinned or reordered. A seventh pin
  requires an explicit replacement. Credential-bearing addresses are refused.
  Accepted edits finish even if the editor is dismissed or recreated.
- Hide site suppresses automatic links until restored; it does not delete
  visits or remote bookmarks. Edit exposes all actions without requiring
  long-press or dragging, plus switches for Continue and frequent sites.
- Ranking, hiding and site-wide Forget share a `www.`-merged site key.
  Other subdomains stay distinct unless Include subdomains is selected.
  Forget removes matching local visits and pins, cancels pending captures,
  and leaves open tabs on their current page. It does not delete remote data.

Pin and hidden-site records use Room schema 5, migrated without destroying
tabs, visits, integration configuration or queued work. Display switches
use the existing browser preferences. Icons are local monograms.

## Typing and recall

Home's search field is editable in place. While typing, local matches appear
above the anchored field, beside which
**+** stays visible above the keyboard. Only the suggestion area scrolls.
Up to five matches show title/domain highlights; open-tab rows explicitly
switch to that tab, while history rows navigate in the tab being edited.
Empty queries and no matches show no suggestion list. Typing contacts no
server; explicit submission opens the ordinary Kagi results website.

Hister and Karakeep open their existing labeled scopes in Recent. Hister
search needs a query; Karakeep lists saved links after its action is tapped.
Unconfigured scopes explain setup and link to Settings. Recent still opens
on Open tabs. Visible counts obey Show tab count, off by default.

## Validation

Pure-state and Room checks cover pin validation, order, cap, replacement,
hidden sites, migration, preferences, Continue identity and merged-site
forgetting. Device checks are `HomeTest`, `AddressSuggestionsUiTest`,
`AccessibilityTest` and the existing `BrowsingTest`.

Compose previews in `HomeScreen.kt` cover populated, empty, dark, narrow,
large-text, landscape and RTL layouts. Device results and limitations are
recorded in [the engine report](../ENGINE_SPIKE.md); a preview is not device
evidence. Service checks use synthetic fixtures, not personal data.

Focused device regressions also cover short-list input anchoring,
background/resume ordering with no background collection, and dismissal
before a blocked SQLite write can commit.

## Design references

- [Firefox's mobile re-entry design](https://blog.mozilla.org/en/mozilla/news/firefox-brings-you-a-new-homepage/)
  informed Continue.
- [Vivaldi's Android Start Page](https://help.vivaldi.com/android/android-bookmarks/android-start-page/)
  informed curated links alongside automatic sites.
- [Chrome's Android shortcuts](https://support.google.com/chrome/answer/11032183?co=GENIE.Platform%3DAndroid&hl=en)
  informed pin/edit interactions.
- [DuckDuckGo's Favorites guidance](https://duckduckgo.com/duckduckgo-help-pages/sync-and-backup/syncing-favorites)
  informed deliberate destinations and avoiding unsolicited icon fetches.

Competitor research was documentation-based. The earlier HTML concepts
remain references for the two Recent layouts, not this native Home screen.

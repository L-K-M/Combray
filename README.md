# Combray

A personal Android browser powered by **Servo**, with controls at the bottom and one place to return to open tabs and recently visited pages.

<img src="media-sources/icon.png" alt="Combray: an ivory folded C on a square pine-green background" width="160" height="160">

Combray connects to **Karakeep** for quick saves, **Hister** for long-term history and full-text search, and **Kagi** for web search. The initial audience is its owner, for personal use.

## Status

This repository contains the implementation handoff, design reference, icon, and repository checks. **There is no Android application or Servo integration yet.** The next step is the Servo feasibility milestone, M0, in the plan. No Android build, service integration, or target-site compatibility has been demonstrated by this bootstrap.

## Start here

1. Read [AGENTS.md](AGENTS.md) for implementation constraints and working instructions.
2. Read the [implementation plan](docs/IMPLEMENTATION_PLAN.md). It is the authoritative, self-contained handoff: requirements, architecture, data model, integration contracts, milestones, and acceptance checks.
3. Consult [integration evidence](docs/INTEGRATIONS.md) before implementing the HTTP clients.
4. Open the [design reference](docs/design/README.md) and its interactive HTML concept. Both Recent layouts are retained; the final layout has not been selected.
5. Implement M0 and record the exact engine revision and working toolchain before starting the product shell.

## Repository check

Requires Python 3.10 or later; no third-party Python packages:

```sh
python3 scripts/check_docs.py
git diff --check
```

These checks validate repository documentation and assets. The included GitHub Actions workflow runs the documentation check on pushes and pull requests once committed and pushed. Native build and device checks will be added with the first implementation milestone.

## Contents

| Path | Purpose |
| --- | --- |
| `docs/IMPLEMENTATION_PLAN.md` | Complete implementation brief and handoff |
| `docs/INTEGRATIONS.md` | Source-backed API observations and compatibility caveats |
| `docs/design/` | Existing UI concepts, sample interactions, and design guidance |
| `media-sources/` | Original square icon and generation provenance |
| `scripts/check_docs.py` | Dependency-free documentation and asset checks |

## License

The existing project [Unlicense](LICENSE) is preserved. Servo and any other incorporated dependencies keep their own licenses and obligations; see [third-party notices](THIRD_PARTY_NOTICES.md).

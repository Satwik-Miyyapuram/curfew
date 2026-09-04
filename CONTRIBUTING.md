# Contributing

Curfew is AGPL-3.0. By contributing you agree your work ships under that licence.

## Layout

```
crates/curfew-core   pure policy: config, rule engine, lock lattice. No I/O.
crates/curfew-cli    thin CLI over the core, used by hand and by CI
docs/                research, architecture, decisions, roadmap, gaps
```

## Build and test

```bash
cargo test
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
```

All three must pass; CI runs them on Linux and Windows.

### If your checkout is inside OneDrive

OneDrive locks files mid-write, which makes the MSVC linker fail with `LNK1104` or `LNK1201` on
the `.pdb`. Put the build directory somewhere OneDrive does not sync:

```bash
export CARGO_TARGET_DIR="$LOCALAPPDATA/curfew-target"
```

## Rules that are not style preferences

- **The core stays pure.** No clock reads, no filesystem, no platform calls in `curfew-core`.
  `now` is a parameter. This is what lets Android and Windows agree on what a config means, and
  what makes the engine testable at all.
- **A lock can never be shortened.** Any change touching `lock.rs` must keep the property tests in
  `crates/curfew-core/tests/lock.rs` green, and new lock behaviour needs a new property, not just
  an example test.
- **Config changes bump `CONFIG_SCHEMA_VERSION`** and add a golden file. Migrations are
  forward-only; a config we cannot understand is preserved, never rewritten.
- **No telemetry, no accounts, no network calls to anything we run.** See `docs/ARCHITECTURE.md`
  invariant 1.
- **No surveillance features.** No parent/admin role, no monitoring of another person, no covert
  mode. See `docs/GAPS.md` D3. Pull requests adding these will be closed.

## Before opening a pull request

Read `docs/DECISIONS.md`. If your change contradicts a decision there, that is fine — but say so in
the PR and propose the replacement ADR, rather than quietly diverging.

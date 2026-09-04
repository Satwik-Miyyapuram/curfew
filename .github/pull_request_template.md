## What this changes

<!-- One paragraph. Link the issue if there is one. -->

## Checks

- [ ] `cargo test`, `cargo fmt --all --check` and `cargo clippy --all-targets -- -D warnings` pass
- [ ] `curfew-core` is still pure: no clock reads, no I/O, no platform calls
- [ ] No change can shorten an active lock; lock property tests still pass
- [ ] Config shape changes bump `CONFIG_SCHEMA_VERSION` and add a golden file
- [ ] Does not contradict `docs/DECISIONS.md` (or proposes the replacement ADR)

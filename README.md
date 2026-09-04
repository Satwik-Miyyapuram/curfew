# nodis — no distractions

Open-source, account-free distraction blocker for **Android + PC** that **syncs peer-to-peer with no
server**, and that can drive its blocks from **your calendar**.

Think Freedom's cross-device sessions + Cold Turkey's strictness + StayFocusd's rule granularity —
without a subscription, without a cloud account, and with your data never leaving your devices.

> Status: **planning / phase 0**. Nothing to install yet.

## Why

| | Freedom | Cold Turkey | StayFocusd | nodis |
|---|---|---|---|---|
| Android + PC | yes | PC only | browser only | yes |
| Cross-device session sync | yes (their cloud) | no | no | **yes, P2P, no account** |
| Calendar-driven blocking | no | no | no | **yes** |
| Allowances / budgets | limited | yes | yes | yes |
| Hard locks + challenges | yes | yes | yes | yes |
| Open source | no | no | no | **AGPL-3.0** |
| Cost | subscription | paid | free | free |

## Docs

- [docs/RESEARCH.md](docs/RESEARCH.md) — teardown of Freedom, Cold Turkey, StayFocusd and friends;
  Android/Windows enforcement constraints; serverless sync options
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — design invariants, domain model, rule engine, sync
  protocol, enforcement layers, threat model
- [docs/ROADMAP.md](docs/ROADMAP.md) — phased build plan

## Principles

1. No server. No account. No telemetry.
2. A lock is a promise — nothing shortens it except the conditions you chose.
3. Every enforcement mechanism has a fallback; losing a permission weakens blocking, never kills it.
4. Everything the UI can do is expressible in an exportable config file.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).

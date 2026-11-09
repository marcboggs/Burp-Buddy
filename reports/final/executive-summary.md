---
title: "AI Pal security review: executive summary"
engagement_id: fdd380ad-7535-4e17-9a30-ce076d59c762
date: 2026-11-09
status: draft
---

# Executive summary

**Scope:** AI Pal Burp Suite extension, v1.2.1 plus uncommitted Kiro/ACP work (HEAD `e44b24f`, dirty tree). Static review only: no live Burp, no model calls, no third-party targets.

**Bottom line:** no command injection, no hardcoded secrets, no vulnerable dependencies. The risk is architectural: the extension sends hostile target content and the tester's client data into LLM agents and clouds without enough controls.

## Top risks (merged root causes)

| Pri | Risk | Status |
|---|---|---|
| P0 | Agent CLIs (Claude Code, Codex, Kiro) run in `$HOME` with the full environment. MCP, hooks and settings are not disabled although the flags exist. A hostile response body could steer an agent. | Code confirmed. Tool reachability is unverified live. Becomes Critical if lab tests RU-1/RU-2 hit. |
| P0 | No egress governance: all headers (Authorization, Cookie) and bodies go to the provider with no redaction, scope check, consent or preview. Destination label is wrong for 3 providers. | Confirmed. |
| P0 | Documentation and code comments overclaim (for example "all tools removed", "Detect refuses IDE launcher", "all requests go through Burp's HTTP stack"). | Confirmed. |
| P1 | No instruction/data separation in prompts. | Confirmed. |
| P1 | AWS keys stored in plaintext Burp preferences, saved per keystroke. | Confirmed. |
| P1 | Discovered CLI paths are auto-persisted and executed at load. | Confirmed. |
| P1 | Cleartext Ollama accepted. Hung CLI runs can starve the 3-thread pool. | Confirmed in code; hang is plausible, not observed. |

## Results by agent

| Agent | Result |
|---|---|
| secreview | 26 findings (1 High, 9 Medium, 13 Low, 3 Info). Semgrep: 0 results, so all are manual. |
| security-architecture | 13 risks (3 High, 6 Medium, 4 Low). |
| skill-reviewer | CAUTION. No hidden or obfuscated instructions. |
| supply-chain | 11 findings (2 Medium). SBOM produced. Wrapper jar is authentic but stale (Gradle 7.5.x), which corrects an earlier tampering suspicion. Build is not reproducible. |
| threat-model | 53 STRIDE entries (3 High). All architecture Highs traced. |
| compliance | 49 control rows: 4 met, 13 partial, 17 gap, 6 potential gap. PCI-DSS and HIPAA not applicable as frameworks. BApp Store: not ready on the criteria in `docs/`. |

## Caveats

- Everything is a point-in-time static claim. Seven items are unverified live (threat model section 12, RU-1..RU-7).
- The Kiro/ACP rewrite postdates the secreview Kiro delta and has no scan of its own.
- Codex, Kiro and Ollama were not installed, so their runtime behaviour was not tested.
- The compliance report is a technical mapping, not an audit or certification.

## Next steps

1. Apply the P0 quick fixes (about 2 days). See `remediation-priority.md`.
2. Run the lab tests RU-1/RU-2 against the hardened and unhardened flags.
3. Commit the Kiro/ACP work and request a targeted secreview of it.
4. Decide whether you are submitting to the BApp Store (affects the transport work).

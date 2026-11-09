---
title: "AI Pal remediation priority (engineer checklist)"
engagement_id: fdd380ad-7535-4e17-9a30-ce076d59c762
date: 2026-11-09
status: draft
source: reports/compliance/compliance-report.md (section 5)
---

# AI Pal: what to fix, in order

Static review only. Nothing here was exercised live. Effort is a rough range for one developer who knows the code (plus or minus 50 percent). Line numbers are for the working tree at HEAD `e44b24f` plus uncommitted changes; if the files move, find the symbol. `V` = re-read in current source. `V·UL` = code confirmed, behaviour not observed live. Full detail and control mapping: `reports/compliance/compliance-report.md`.

## P0: do first

1. **Isolate the agent CLIs, quick part (CO-01, 1.5 to 2 days, V·UL).**
   - `ClaudeCodeClient.buildCommand` (`:305-327`): add `--disallowedTools "mcp__*"`, `--strict-mcp-config`, `--disable-slash-commands`, `--no-session-persistence`. All four are listed by `claude --help` in 2.1.318.
   - All CLI clients: stop using `user.home` as cwd (`ClaudeCodeClient` `:69,201,238,259`; `CodexClient` `:149,186,291`; `KiroClient` `:117,191,242`). Use an empty private temp directory.
   - Pass the child an allow-listed environment instead of the whole parent environment (`CliEnvironmentUtil.ensureNodePath` only edits PATH).
   - Fix the comment at `ClaudeCodeClient.java:316-321` in the same change. "Removes all tools" is not shown to be true for MCP.
2. **Fix the false claims in the same pull request (CO-11, 0.5 to 1 day, V).** `README.md:171, 302, 306, 325`, `CLAUDE.md:5, 35, 37`. Details in section 6.3 of the report. `README.md:306` ("all requests go through Burp's HTTP stack") and `:325` ("Detect refuses the IDE launcher") are wrong today.
3. **Add an egress gate (CO-02, 6 to 8 days, V).**
   - Redact `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key` and key-named body fields by default (`HttpRequestFormatter.java:35-36,63-64`), with an opt-out setting.
   - Show the real destination per provider and ask for consent once per provider and project. `LLMProvider.java:6-8` says `"localhost"` for three cloud-bound CLIs and `getApiHost()` has no callers.
   - Show an editable preview with a byte count before sending to any non-loopback destination.
   - Cap items and total bytes (`LLMContextMenuProvider.formatItems`), warn on out-of-scope items, and switch the Repeater tab's Request toggle off by default for cloud providers (`LLMSecurityAssistantEditorPanel.java:44`).
4. **Run the Claude lab tests RU-1 and RU-2 (CO-01, about 1 to 2 days, threat model section 12).** They decide whether the High finding becomes Critical (a tool call runs under the shipped flags) or drops. Do it right after item 1, using the hardened flags as the control. Add a startup canary that refuses to enable a provider if any tool event appears (3 days).
5. **Decide the BApp Store path (CO-12, 4 to 8 days if yes, otherwise P3).** The extension uses none of the Montoya AI API although `montoya-api-2025.10` contains it. The live acceptance criterion makes Burp AI the default provider and allows others in addition. CLI providers cannot use Montoya networking. Decide whether you are submitting before spending effort on CO-05.

## P1: next release

6. **Codex and Kiro isolation (CO-01, 2 to 3 days after the lab).** Test `--ignore-user-config` for Codex (RU-3). Kiro: relay-log the ACP traffic and test an isolated HOME (RU-5, RU-6). The ACP rewrite has had no scan.
7. **Prompt hygiene (CO-03, 3 to 4 days, V).** Per-request random fence around traffic (`PromptTemplates.java:233,243,253`), strip the fence and line-initial `User:`/`Assistant:`/`System:` from the data, an "untrusted data" clause in every system prompt, a system prompt on Custom Prompt (`LLMContextMenuProvider.java:174` passes `null`), pin the system message in `ConversationHistory` (`:28-29,58-59`).
8. **AWS credentials (CO-04, 2 to 3 days, V).** Do not persist keys by default (`SettingsManager.java:111-117`); save on focus-lost or an explicit Save, not per keystroke (`LLMSettingsPanel.java:806,819,832`). Honour `AWS_PROFILE` (`AwsCredentialsUtil.java:57`) and show the effective account and source before the first send (`BedrockClient.java:118` has no callers).
9. **CLI discovery and path trust (CO-06, 4 to 5 days, V).**
   - Do not run `--version` on a candidate at panel construction (`LLMSettingsPanel.java:1044-1050,1067,1085`).
   - Do not auto-persist a discovered path (`:642,960,1046-1048`, `detectCliBinary` `:1190-1212`). Ask first.
   - Resolve names without the current directory (`:1253-1279`).
   - Use `File.pathSeparator` and an exact-entry check (`CliEnvironmentUtil.java:27,29`).
   - Validate model strings against `^[A-Za-z0-9][A-Za-z0-9._:/@-]*$` (`KiroClient.java:177-179`, `CodexClient.java:285-286`, `ClaudeCodeClient.java:314-315`).
   - Apply the IDE-launcher guard on Detect too, or fix the README.
10. **Provider transport (CO-05, 3 days).** Cleartext Ollama only to loopback (`OllamaClient.java:238-239`, `LLMSettings.java:246`); one HTTP client for both paths; `setInstanceFollowRedirects(false)` (`:140`). Bedrock: decide between a dedicated client and the documented Burp-Logger exposure (`BedrockClient.java:228,233`; V·UL). Coordinate with item 5.
11. **Process I/O and the thread pool (CO-07, 3 to 4 days).** Drain stderr on its own thread, enforce the timeout with a watchdog that calls `destroyForcibly()` (`ClaudeCodeClient.java:270-273`, `CodexClient.java:111`), bound the queue and consider per-feature pools (`ThreadManager.java:18`), catch `Throwable` (`:33,48`), kill child processes on unload (`Extension.java:54-57`), move `isModelAlreadyPulled` off the click handler (`LLMSettingsPanel.java:321-354,373`), drain stderr in `KiroClient` (`:190`).
12. **Add security regression tests (CO-11, about 3 days).** There is no `src/test`. Start with redaction, fence stripping, argument validation and the canary.

## P2: scheduled

13. **Output handling (CO-08, 1 to 2 days).** Show provider and CLI text in a `JTextArea`, not `JOptionPane` (`LLMSettingsPanel.java:1386-1395`); add a provider/model label to results.
14. **State lifecycle (CO-09, 1.5 to 2 days).** `ChatPanel.clearChat` (`:282-287`) must reset `attachedRequest` and cancel the stream (one-line fix; do it with item 3). Cap and purge `AITaskManager` (`:31`), clear state on unload, log lengths not content (`ClaudeCodeClient.java:153`, `CodexClient.java:106,241`, `OllamaClient.java:203`).
15. **Release integrity (CO-10, 3 to 4 days).** Under one day for the first four: `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true` in `build.gradle.kts`; regenerate the wrapper with `distributionSha256Sum`; `gradle/verification-metadata.xml`; Dependabot `github-actions`. Then signed tags and a published SHA-256. CI with provenance is optional.

## P3: backlog

16. **Housekeeping (CO-13, about 1 day).** Sign `x-amz-date` (`BedrockClient.java:185,189,223`), update gson and montoya-api after checking compatibility, add a `LICENSE` and gson's licence text, add signature and `module-info` excludes to `tasks.jar`.

## Not in this list

No live testing was done, no vendor-cloud assessment, no organisational controls. The report claims no certification or audit-readiness.

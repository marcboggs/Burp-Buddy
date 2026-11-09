---
title: "SecReview addendum: coverage closure for AI Pal Burp extension v1.2.1"
agent: secreview
cross_ref_prefix: SR
engagement_id: fdd380ad-7535-4e17-9a30-ce076d59c762
component: burpsuite-ai-pal-ext
date: 2026-11-09
status: draft
parent_report: reports/secreview/burpsuite-ai-pal-ext/secreview-report.md (not modified)
source_revision: "HEAD e44b24f, working tree read as-is (git status: only reports/ untracked)"
scope: "10 pattern-search-only files + AnalysisResultDialog, ChatPanel, CliEnvironmentUtil (output rendering) + re-verification of SR-SAST-015/016/017"
findings_count: 2
sca_count: 0
severity_counts: {critical: 0, high: 0, medium: 0, low: 2, info: 0}
tools: {semgrep: "not re-run in this pass (prior pass: 0 results); every item below is manual-only", sca: "not in scope for this addendum; no dependency change"}
quality_gate: "draft. Static only, no live Burp/LLM. Items needing runtime behaviour are marked (unverified live)."
---

# SecReview addendum: coverage closure

## 1. Coverage

**Files read in full for this slice: 13 / 13** (all of them end to end, `read_file` with no truncation).

| # | File | Result |
|---|---|---|
| 1 | `ui/AIPalSuiteTab.java` | Full. Clean. |
| 2 | `ui/UIStyle.java` | Full. Clean (borders and padding only). |
| 3 | `ui/tasks/AITask.java` | Full. Retention finding SR-SAST-019. |
| 4 | `ui/tasks/AITaskManager.java` | Full. Retention finding SR-SAST-019. |
| 5 | `ui/tasks/AITaskStatus.java` | Full. Enum only. |
| 6 | `llm/LLMResponse.java` | Full. Clean (see note on `toString`). |
| 7 | `ui/editor/LLMSecurityAssistantHttpRequestEditor.java` | Full. Clean. |
| 8 | `ui/editor/LLMSecurityAssistantHttpResponseEditor.java` | Full. Clean. |
| 9 | `ui/editor/LLMSecurityAssistantHttpRequestEditorProvider.java` | Full. Clean. |
| 10 | `ui/editor/LLMSecurityAssistantHttpResponseEditorProvider.java` | Full. Clean. |
| 11 | `ui/dialogs/AnalysisResultDialog.java` | Full. Plain-text rendering, clean. |
| 12 | `ui/chat/ChatPanel.java` | Full. Finding SR-SAST-018. |
| 13 | `util/CliEnvironmentUtil.java` | Full. Re-confirms SR-SAST-011 (no new issue). |

Supporting reads, needed to trace data flow and verify claims:
- Read in full: `ui/tasks/AITasksTab.java`, `ui/editor/LLMSecurityAssistantEditorPanel.java`, `ui/chat/ConversationHistory.java`, `ui/chat/ChatMessage.java`.
- Read as excerpts only, and **only the cited ranges are verified**: `BedrockClient.java` (150-260), `ClaudeCodeClient.java` (60-192), `OllamaClient.java` (150-185), `LLMSettingsPanel.java` (310-400, 455-480, 628-640, 946-956, 1020-1052).
- Covered by **pattern search only**: `Extension.java` (lines 22-56 via grep), `ThreadManager.java`, `ChatSessionPanel.java` (grep).
- **Not examined**: `LLMSettingsPanel.findOllamaExecutable()` (line 264). Whether it spawns a process is unknown, so the EDT status of `checkOllamaInstalled()` at lines 350, 435 and 498 is *unverified*.

Tools: semgrep was **not re-run**. This is a manual-only pass and no item below is tool-confirmed.

### Output-rendering conclusion (LLM text, links, markdown, HTML)

| Surface | Component | Evidence | Verdict |
|---|---|---|---|
| Analyze / context-menu result | `JTextArea`, `setEditable(false)` | `AnalysisResultDialog.java` (constructor, `resultArea = new JTextArea(result)`); `appendResult`/`setResult` use `append`/`setText` | Plain text. No HTML or link handling. |
| Chat transcript | `JTextPane` (default content type) filled via `StyledDocument.insertString` | `ChatPanel.java` `appendMessage`, streaming `onChunk` | Plain text. No `setContentType`, no `HTMLEditorKit`, no hyperlink listener. |
| Tasks tab (summary, prompt, result, request/response) | four `JTextArea` | `AITasksTab.java:32-35`, `:145`, `:152` | Plain text. |
| Task list rows | `DefaultListCellRenderer.setText(status + " " + t.title())` | `AITasksTab.java:204` | Safe **only because** a status glyph (`⏳ ▶ ✓ !`) always prefixes the text. Swing treats a label as HTML only if the string starts with `<html>`. The title embeds target-controlled text (`HttpRequestFormatter.getRequestSummary`, `LLMSecurityAssistantEditorPanel.java:161`). Removing the prefix would create an HTML-injection sink, so keep it. |
| Error text | `appendMessage("Error", ...)` in the chat pane, `summaryArea` in Tasks | `ChatPanel.java` error handlers, `AITasksTab.java:145` | Plain text. The `JOptionPane` HTML risk stays confined to `LLMSettingsPanel` (SR-SAST-010). |
| Clipboard | `StringSelection(resultArea.getText())` | `AnalysisResultDialog.java` copy handler | User-initiated. Could carry sensitive output, but it is expected behaviour. |

The prior claim "no HTML/markdown/image channel in result views" is **confirmed** against current source for all of the above. Remaining softer points (no finding raised):
- Plain `JTextArea`/`JTextPane` will draw bidi control characters (for example U+202E) from model output, so displayed text can be visually reordered. Cosmetic spoofing only.
- Output length is not capped. A very long model reply grows the Swing document without limit.

### Editor tabs: unsafe handling check

- `LLMSecurityAssistantHttpRequestEditor.getRequest()` returns `current.request()` unmodified, `isModified()` returns `false`, and `selectedData()` returns `null`. The tab **cannot alter** the message the user is editing. No write-back path exists.
- `isEnabledFor` returns true only for `ToolType.REPEATER` (`...RequestEditor.java` `isEnabledFor`; the response editor is identical). It is not offered in Proxy, Intruder or Logger.
- The editor stores the last `HttpRequestResponse` in a `volatile` field (`...RequestEditor.java:12`) and in the panel (`LLMSecurityAssistantEditorPanel.java` `current`). The reference lives as long as the editor instance. This is bounded and not a finding.
- Providers create one panel per editor instance, share the injected clients and task manager, and hold no static state.
- `LLMResponse.toString()` prints the first 100 chars of content. Nothing in the source logs a response object (not checked exhaustively beyond the files in this slice).
- `AIPalSuiteTab.selectSuiteTab()` swallows exceptions and calls `frame.toFront()` / `requestFocus()`. This is focus stealing only, not a security issue. Swing calls are marshalled through `invokeLater`.

---

## 2. Re-verification of inherited claims (current source)

### SR-SAST-017: system prompt evicted by conversation trimming. **CONFIRMED (Info stands)**

- `ui/chat/ConversationHistory.java:9` sets `MAX_HISTORY_SIZE = 100`. Both add paths trim by removing index 0: `:28-29` (`addMessage`) and `:58-59` (`addAssistantMessageSilent`).
- The system message is the first entry. `ChatPanel.java:117` adds it in the constructor before any user message, and `ChatPanel.java:285` re-adds it after Clear. It is never pinned or re-inserted.
- Eviction starts when the 101st entry is added. That is roughly 50 user/assistant exchanges after the system message.
- Consequences once evicted, by backend:
  - Bedrock: no `system` field is sent, because it is only set from a `SYSTEM`-role entry (`BedrockClient.java:139-140`).
  - Ollama: no `"system"` role message (`OllamaClient.java:78,120`).
  - Claude Code and Codex: no `System:` line (`ClaudeCodeClient.java:341`, `CodexClient.java:316`).
- Not affected: one-shot calls (editor tab, context menu) pass `CHAT_SYSTEM` per request through `complete(prompt, systemPrompt)`.
- Added observation: after the first eviction the retained history begins with a `user` message, and after the next eviction with an `assistant` message. Providers such as Anthropic via Bedrock require the first message to be `user`, so alternate turns may be rejected (availability) *(unverified live)*.
- Reach: the thread of the attack is still SR-SAST-003. Eviction only weakens a thin defence.

### SR-SAST-016: SigV4 `x-amz-date` sent but not signed. **CONFIRMED (Info stands)**

- `BedrockClient.java:182-185` (token present): `canonicalHeaders` has `host`, `x-amz-content-sha256`, `x-amz-security-token`. `signedHeaders = "host;x-amz-content-sha256;x-amz-security-token"`.
- `BedrockClient.java:187-189` (no token): `host;x-amz-content-sha256`.
- `BedrockClient.java:223` sends `.withHeader("X-Amz-Date", amzDate)`. It appears in neither canonical header block nor `SignedHeaders`.
- `amzDate` is bound into the string-to-sign (`BedrockClient.java:202-207`). A tampered `X-Amz-Date` therefore fails signature validation, so this is a spec-conformance and availability issue, not a forgery vector. AWS guidance asks that `x-amz-*` headers in the request be signed *(unverified live: whether Bedrock rejects it)*.
- Unchanged: `X-Amz-Security-Token` (`:228`) is signed when present, and the request goes through `api.http().sendRequest` (`:233`), which is the basis of SR-SAST-006.

### SR-SAST-015: UI-thread blocking and process leaks. **PARTIALLY CONFIRMED (Low stands; wording needs correction)**

| Sub-claim in prior report | Current evidence | Verdict |
|---|---|---|
| `ollama list` run synchronously in a click handler | `LLMSettingsPanel.java:228` (button listener) → `pullOllamaModel` `:349` → `isModelAlreadyPulled` `:366`, which spawns at `:316-318`, reads to EOF, then `waitFor()` at `:342` with no timeout. No `SwingWorker` wraps it (the worker starts later, at `:381`). | **Confirmed.** EDT blocking. Also, the early `return true` at `:334` and `:338` skips `waitFor` and abandons the process handle. |
| `where`/`which` with unbounded `waitFor()` during construction and Detect | `findExecutableOnPath` `:1026-1052`, called from `:632` and `:950` inside the panel builders. `waitFor()` at `:1047` has no timeout, but only runs after the read hit EOF, so a hang is unlikely. | **Confirmed, but "EDT" is unproven.** Panels are constructed from `Extension.java:35` during extension load. I did not establish which thread Burp uses, so this may stall extension loading rather than the EDT. Detect button path (`:591`) not traced. |
| `ollama pull` pipes never drained, `waitFor()` unbounded | `:387-390`: `redirectErrorStream(true)`, no reader, `process.waitFor()` with no timeout. Runs in a `SwingWorker` (`:381`), so it is **not** the EDT. | **Confirmed.** It can hang once progress output fills the pipe, and the "Pulling…" button stays disabled *(unverified live)*. The worker thread is blocked, not the EDT. |
| `ollama run` handle discarded after 3 s sleep | `:461-469`: stdin closed, no drain, `Thread.sleep(3000)`, handle dropped. Runs in a `SwingWorker` (`:453`). | **Confirmed but by design.** The command is meant to keep a model loaded (`--keepalive 24h`), and `ollama stop` exists (`:522`). Not a defect on its own. |
| `ClaudeCodeClient` returns on `result` without `waitFor`/`destroy` | `ClaudeCodeClient.java:138-149`: `return;` at `:149` inside the stdout loop. Stdin was already closed (`:76`), stderr is never read on this path (`:159` is unreachable here). The `catch` at `:181-184` destroys only on exceptions. | **Confirmed.** The process normally exits by itself once it has sent `result`. Leak only if the CLI lingers. |
| `OllamaClient` swallows `SocketTimeoutException` and loops; cancel only honoured at loop top | `OllamaClient.java:170-174` (`continue`), cancel check at `:165-168`. `setReadTimeout(120000)` at `:145`. | **Confirmed, with more precision.** Stop is honoured only after a read returns or times out, so latency is up to 120 s. There is no overall deadline. |
| Settings status checks run HTTP calls at load (`:105-160`) | The calls are inside a `SwingWorker` (`:99`). | **Not a UI-blocking issue.** It does contact the configured URL on load. Suggest dropping this bullet from SR-SAST-015. |

---

## 3. New findings

Numbering continues from SR-SAST-017.

### [SR-SAST-018] [LOW] Chat: stale request attachment survives "Clear", and an in-flight stream repopulates the cleared history
- **Type**: A04 Insecure Design, data exposure to a third-party LLM. **CWE-200**, **CWE-459**, CWE-488.
- **File**: `src/main/java/ui/chat/ChatPanel.java:282-287` (`clearChat`), `:30` (`attachedRequest` field), `:153-155` (consumed only on send), `:224` (`history.addAssistantMessageSilent` in `onComplete`), `:277-279` (`stopStreaming`).
- **Endpoint**: n/a (desktop UI). Attachment is set from `ChatSessionPanel.java:63` (`chatPanel.setAttachedRequest(formatted)`).
- **Parameter**: attached HTTP request/response text (headers and bodies, including `Authorization` and `Cookie`).
- **Sink**: `history.addUserMessage(userMessage, attachedRequest)` (`ChatPanel.java:154`) → `ChatMessage.getFullContent()` → the LLM request body.
- **Code**:
  ```java
  private void clearChat() {
      history.clear();                       // :283
      chatDisplay.setText("");
      history.addSystemMessage(...);         // :285
      statusLabel.setText("Chat cleared");   // :286  (attachedRequest and stopRequested/isProcessing untouched)
  }
  ```
- **Details**:
  1. **Hidden stale attachment.** `clearChat` never resets `attachedRequest`. If the tester attaches request A, clicks Clear, then types a message, request A is sent to the provider. The status label "Request attached. Type your message." (`:126`) is overwritten by "Chat cleared" (`:286`), so the only visual cue is gone. The transcript shows `[Request attached]` only after sending (`:307`). This matters for engagement hygiene: a Clear click is a natural "start fresh for a different client" gesture.
  2. **Post-clear resurrection.** Clear does not cancel a running stream and does not set `stopRequested`. When the stream finishes, `onComplete` appends the old reply to the now-cleared history (`:224`). The next prompt then includes the previous conversation's assistant text. The display will also show the tail chunks of that reply, because `onChunk` keeps inserting into `chatDisplay` after the reset.
- **Exploitability**: self-inflicted disclosure, with no remote attacker. Impact is cross-engagement data leaving to Anthropic, OpenAI or AWS, or an Ollama host, against the tester's intent. Reliable and deterministic (attach, Clear, send). Item 2 needs timing.
- **Tool confirmation**: manual-only. *(unverified live; code path traced, UI not run)*
- **DAST payload / test steps** (no HTTP payload; use a local logging stub as the Ollama endpoint):
  1. Send a request containing `Authorization: Bearer CANARY-0018A` to the chat via the context menu.
  2. Click **Clear**. Type `hello` and press Enter.
  3. Pass condition: the stub's request log contains `CANARY-0018A` in the user message.
  4. For item 2, start a long answer, click **Clear** before it finishes, then send `repeat my last answer`. Pass condition: the stub receives the earlier assistant text in `messages`.
- **Repeater**: not applicable.
- **Remediation**:
  ```java
  private void clearChat() {
      stopRequested = true;            // cancel any stream
      attachedRequest = null;          // drop pending attachment
      history.clear();
      ...
  }
  ```
  Ignore callbacks from a stale generation (an `AtomicInteger` epoch captured at send time and compared in `onChunk`/`onComplete`). Keep an "Attached: <summary>" indicator that persists until sent or cleared.

### [SR-SAST-019] [LOW] Full traffic and LLM output are retained in memory with no cap, expiry or purge, and are displayed to any later user of the session
- **Type**: A02 / A04. **CWE-316** (cleartext sensitive data in memory), CWE-770 (unbounded allocation), CWE-212.
- **File**:
  - `ui/tasks/AITaskManager.java:15` (list), `:31` (`tasks.add(0, task)`). There is no remove, clear or cap method anywhere in the class.
  - `ui/tasks/AITask.java:10-13` (final `prompt`, `requestText`, `responseText`, `notesText`) and `:16` (`result`).
  - `ui/tasks/AITasksTab.java:29` (`listModel`) and `:161-168` (renders the retained request/response/notes). The tab has no delete or clear control.
  - `ui/chat/ChatMessage.java:25` (`attachedRequest` field kept for every message) with `ConversationHistory.java:9` (100 messages).
  - Created at `ui/editor/LLMSecurityAssistantEditorPanel.java:162-164`.
- **Sink**: heap, plus the Tasks tab, which re-renders retained data on selection (`AITasksTab.java:161`).
- **Code**:
  ```java
  tasks.add(0, task); // newest first       // AITaskManager.java:31, never removed
  ```
- **Details**:
  - Every Analyze run from the editor tab keeps the full formatted request (headers, cookies, tokens, up to 10 000 chars of body per `HttpRequestFormatter`), optionally the response, the notes and the full LLM answer, for the life of the extension. A failed or cancelled task keeps them too.
  - The Tasks tab shows these to anyone with the Burp window open. There is no per-task delete, no "clear all", and no unload cleanup (`Extension.java:54-56` only shuts down the thread pool).
  - Chat holds up to 100 messages, each with its full attached traffic, and retains them until the user clicks Clear (and see SR-SAST-018 for what survives Clear).
  - Memory only: nothing here is written to disk by the extension. The exposure is long-lived heap contents (heap dumps, crash reports) and shoulder-surfing or screen-sharing during a multi-client session.
  - Growth is unbounded, so a long session or a bulk workflow can exhaust memory. There is also no per-task size cap beyond the formatter truncation.
  - `AITasksTab` and `ChatPanel` register listeners that are never removed (`AITasksTab.java:60`, no `removeListener` call). This has no practical effect while the extension lives.
- **Exploitability**: requires local access to the running Burp or its memory. Low severity, but relevant for consultants who switch clients inside one Burp session.
- **Tool confirmation**: manual-only.
- **DAST payload**: not applicable. To confirm, run Analyze on a request containing `Cookie: session=CANARY-0019`, then switch to a different Burp project or target and open the Tasks tab. Pass condition: the canary is still visible with no way to delete it.
- **Repeater**: not applicable.
- **Remediation**:
  - Add per-task delete and "Clear all" controls.
  - Cap the number of retained tasks (for example 50) and evict the oldest.
  - Avoid storing `requestText`/`responseText` after completion (keep a summary and a redacted copy), or apply the redaction from SR-SAST-004 before retaining.
  - Clear task and chat state in the unloading handler.

---

## 4. Checked, no vulnerability found (this slice)

| Area | Result |
|---|---|
| HTML / markdown / link rendering of LLM text | None. See section 1 table. |
| `AIPalSuiteTab`, `UIStyle`, `AITaskStatus` | No security-relevant logic. |
| Editor tabs modifying traffic | Cannot: `getRequest`/`getResponse` pass through originals, `isModified()` is `false`. |
| `CliEnvironmentUtil` | Re-confirms SR-SAST-011: separator hard-coded as `:` at `:29` (wrong for Windows `;`), substring `contains` at `:27`, user-writable dirs appended at `:21-22` (`home + "/.nvm/current/bin"`, `home + "/.local/bin"`). Nothing new. |
| Secrets in the 13 files | None hardcoded; none logged. |

## 5. Suggested follow-ups

1. Fix SR-SAST-018 (small change in `clearChat`).
2. Correct the SR-SAST-015 wording using the table above (drop the status-check bullet, soften the EDT claim for construction-time calls, note `ollama run` is by design).
3. Trace `findOllamaExecutable()` (`LLMSettingsPanel.java:264`) and the Detect path (`:591`) to settle the EDT question.

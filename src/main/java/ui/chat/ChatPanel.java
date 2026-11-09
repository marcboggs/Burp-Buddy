package ui.chat;

import config.SettingsManager;
import llm.LLMClient;
import llm.LLMClientFactory;
import llm.LLMResponse;
import llm.prompts.PromptTemplates;
import ui.UIStyle;
import util.ThreadManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ChatPanel extends JPanel implements ConversationHistory.ConversationListener {
    private final LLMClientFactory clientFactory;
    private final ThreadManager threadManager;
    private final SettingsManager settingsManager;
    private final ConversationHistory history;

    private final JEditorPane chatDisplay;
    private final HTMLEditorKit htmlKit = new HTMLEditorKit();
    private HTMLDocument chatDoc;
    private ChatTheme theme;
    private int cardCounter = 0;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JLabel statusLabel;

    private String attachedRequest = null;
    private boolean isProcessing = false;
    private volatile boolean stopRequested = false;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final int STREAM_RENDER_DELAY_MS = 80;

    private enum CardKind { USER, ASSISTANT, ERROR }

    // State of the reply currently streaming in (EDT only).
    private String streamElementId;
    private final StringBuilder streamText = new StringBuilder();
    private final Timer streamRenderTimer;

    public ChatPanel(LLMClientFactory clientFactory, ThreadManager threadManager,
                     SettingsManager settingsManager, ConversationHistory history) {
        this.clientFactory = clientFactory;
        this.threadManager = threadManager;
        this.settingsManager = settingsManager;
        this.history = history;

        setLayout(new BorderLayout(5, 5));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Chat display area - HTML message cards. Wrap to the viewport, but when a line
        // can't break (long URL in a code block) scroll sideways instead of clipping it.
        chatDisplay = new JEditorPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                Container parent = getParent();
                return parent == null || getUI().getMinimumSize(this).width <= parent.getWidth();
            }
        };
        chatDisplay.setEditable(false);
        chatDisplay.setEditorKit(htmlKit);
        resetDocument();

        // Coalesce streamed chunks: re-rendering Markdown per token is wasteful.
        streamRenderTimer = new Timer(STREAM_RENDER_DELAY_MS, e -> renderStream(true));
        streamRenderTimer.setRepeats(false);
        JScrollPane chatScroll = new JScrollPane(chatDisplay);
        chatScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        chatScroll.setBorder(UIStyle.createFieldBorder());
        JPanel chatSection = new JPanel(new BorderLayout());
        chatSection.setBorder(UIStyle.createSectionBorder("Conversation"));
        chatSection.add(chatScroll, BorderLayout.CENTER);
        add(chatSection, BorderLayout.CENTER);

        // Input panel
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.setBorder(UIStyle.createSectionBorder("Message input"));

        // Input area
        inputArea = new JTextArea(3, 40);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        UIStyle.applyTextInputPadding(inputArea);
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isShiftDown()) {
                    // Shift+Enter for newline
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    sendMessage();
                }
            }
        });
        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setBorder(UIStyle.createFieldBorder());
        inputPanel.add(inputScroll, BorderLayout.CENTER);

        // Button panel - use GridLayout for vertical stacking
        JPanel buttonPanel = new JPanel(new GridLayout(3, 1, 5, 5));

        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendMessage());
        buttonPanel.add(sendButton);

        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopStreaming());
        buttonPanel.add(stopButton);

        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clearChat());
        buttonPanel.add(clearButton);

        inputPanel.add(buttonPanel, BorderLayout.EAST);

        // Status label
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        inputPanel.add(statusLabel, BorderLayout.SOUTH);

        add(inputPanel, BorderLayout.SOUTH);

        // Add system message
        history.addSystemMessage(PromptTemplates.CHAT_SYSTEM);

        // Register as listener
        history.addListener(this);
    }

    public void setAttachedRequest(String requestContent) {
        this.attachedRequest = requestContent;
        if (requestContent != null) {
            statusLabel.setText("Request attached. Type your message.");
        } else {
            statusLabel.setText("Ready");
        }
    }

    private void sendMessage() {
        if (isProcessing) return;

        String userMessage = inputArea.getText().trim();
        if (userMessage.isEmpty()) return;

        if (!clientFactory.hasValidConfig()) {
            JOptionPane.showMessageDialog(this,
                    "Please configure the LLM provider in the extension settings first.",
                    "Configuration Required",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        isProcessing = true;
        stopRequested = false;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusLabel.setText("Sending to " + settingsManager.getActiveProvider().getDisplayName() + "...");

        // Add user message to history
        if (attachedRequest != null) {
            history.addUserMessage(userMessage, attachedRequest);
            attachedRequest = null;
        } else {
            history.addUserMessage(userMessage);
        }

        inputArea.setText("");

        // Send to LLM
        threadManager.submitAsync(() -> {
            LLMClient client = clientFactory.createClient();

            if (client.supportsStreaming()) {
                // Use streaming for real-time response
                final StringBuilder responseBuilder = new StringBuilder();

                // Open an empty assistant card that chunks render into
                SwingUtilities.invokeLater(() -> {
                    streamText.setLength(0);
                    streamElementId = appendCard(CardKind.ASSISTANT, assistantTitle(), Instant.now(),
                            "<span class=\"muted\">Thinking&hellip;</span>");
                    statusLabel.setText("Streaming response...");
                });

                client.chatStreaming(history.getMessages(), "", new LLMClient.StreamCallback() {
                    @Override
                    public boolean isCancelled() {
                        return stopRequested;
                    }

                    @Override
                    public void onChunk(String chunk) {
                        if (stopRequested) {
                            return; // Skip processing if stop was requested
                        }
                        responseBuilder.append(chunk);
                        SwingUtilities.invokeLater(() -> {
                            streamText.append(chunk);
                            if (!streamRenderTimer.isRunning()) streamRenderTimer.start();
                        });
                    }

                    @Override
                    public void onComplete(int totalTokens) {
                        SwingUtilities.invokeLater(() -> {
                            finishStream();

                            // Add to history (without triggering onMessageAdded display)
                            String fullResponse = responseBuilder.toString();
                            if (!fullResponse.isEmpty()) {
                                history.addAssistantMessageSilent(fullResponse);
                            }

                            isProcessing = false;
                            sendButton.setEnabled(true);
                            stopButton.setEnabled(false);
                            if (stopRequested) {
                                statusLabel.setText("Stopped");
                            } else {
                                statusLabel.setText("Ready (Tokens used: " + totalTokens + ")");
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        SwingUtilities.invokeLater(() -> {
                            finishStream();
                            appendError(error);
                            isProcessing = false;
                            sendButton.setEnabled(true);
                            stopButton.setEnabled(false);
                            statusLabel.setText("Error occurred");
                        });
                    }
                });
            } else {
                // Fallback to non-streaming
                LLMResponse response = client.chat(history.getMessages(), "");

                SwingUtilities.invokeLater(() -> {
                    isProcessing = false;
                    sendButton.setEnabled(true);
                    stopButton.setEnabled(false);

                    if (response.isSuccess()) {
                        history.addAssistantMessage(response.getContent());
                        statusLabel.setText("Ready (Tokens used: " + response.getTokensUsed() + ")");
                    } else {
                        appendError(response.getErrorMessage());
                        statusLabel.setText("Error occurred");
                    }
                });
            }
        }, error -> SwingUtilities.invokeLater(() -> {
            isProcessing = false;
            sendButton.setEnabled(true);
            stopButton.setEnabled(false);
            finishStream();
            appendError(error.getMessage());
            statusLabel.setText("Error occurred");
        }));
    }

    private void stopStreaming() {
        stopRequested = true;
        stopButton.setEnabled(false);
        statusLabel.setText("Stopping...");
    }

    private void clearChat() {
        history.clear();
        finishStream();
        resetDocument();
        history.addSystemMessage(PromptTemplates.CHAT_SYSTEM);
        statusLabel.setText("Chat cleared");
    }

    @Override
    public void onMessageAdded(ChatMessage message) {
        SwingUtilities.invokeLater(() -> {
            if (message.getRole() == ChatMessage.Role.SYSTEM) return;
            boolean user = message.getRole() == ChatMessage.Role.USER;
            String body = MarkdownRenderer.toHtml(message.getContent());
            if (message.hasAttachedRequest()) {
                body += "<div class=\"muted\">[HTTP request attached]</div>";
            }
            appendCard(user ? CardKind.USER : CardKind.ASSISTANT,
                    user ? "You" : assistantTitle(), message.getTimestamp(), body);
        });
    }

    @Override
    public void onHistoryCleared() {
        SwingUtilities.invokeLater(() -> {
            finishStream();
            resetDocument();
        });
    }

    private String assistantTitle() {
        return settingsManager.getActiveProvider().getDisplayName() + " - " + settingsManager.getCurrentModel();
    }

    private void appendError(String error) {
        appendCard(CardKind.ERROR, "Error", Instant.now(),
                MarkdownRenderer.toHtml(error == null ? "Unknown error" : error));
    }

    /** Re-renders the streaming card; {@code typing} adds a trailing ellipsis. */
    private void renderStream(boolean typing) {
        if (streamElementId == null) return;
        Element body = chatDoc.getElement(streamElementId);
        if (body == null) return;
        String html = MarkdownRenderer.toHtml(streamText.toString());
        if (typing) html += "<span class=\"muted\">&hellip;</span>";
        setInnerHtml(body, html.isEmpty() ? "<span class=\"muted\">(no response)</span>" : html);
    }

    /** Final render of the streaming card (no-op if nothing is streaming). */
    private void finishStream() {
        streamRenderTimer.stop();
        renderStream(false);
        streamElementId = null;
        streamText.setLength(0);
    }

    private void setInnerHtml(Element element, String html) {
        try {
            chatDoc.setInnerHTML(element, html);
            scrollToBottom();
        } catch (BadLocationException | IOException e) {
            // Leave the last good render in place
        }
    }

    /**
     * Appends a message card. {@code bodyHtml} must already be escaped
     * (MarkdownRenderer output or fixed markup). Returns the body element id.
     */
    private String appendCard(CardKind kind, String title, Instant time, String bodyHtml) {
        String accent = switch (kind) {
            case USER -> theme.userAccent();
            case ASSISTANT -> theme.assistantAccent();
            case ERROR -> theme.errorAccent();
        };
        String background = switch (kind) {
            case USER -> theme.userCard();
            case ASSISTANT -> theme.assistantCard();
            case ERROR -> theme.errorCard();
        };
        String bodyId = "msg" + (++cardCounter);
        // A div, not a table: table cells grow to fit long unbreakable lines and get clipped.
        String html = "<div class=\"card\" style=\"background-color: " + background
                + "; border-left: 4px solid " + accent + ";\">"
                + "<div class=\"hdr\"><b><font color=\"" + accent + "\">" + MarkdownRenderer.escape(title)
                + "</font></b>&nbsp;&nbsp;<span class=\"muted\">" + TIME_FORMAT.format(time) + "</span></div>"
                + "<div id=\"" + bodyId + "\">" + bodyHtml + "</div>"
                + "</div><div class=\"gap\"></div>";
        try {
            chatDoc.insertBeforeEnd(chatDoc.getElement("chat"), html);
            scrollToBottom();
        } catch (BadLocationException | IOException e) {
            // Ignore
        }
        return bodyId;
    }

    private void scrollToBottom() {
        chatDisplay.setCaretPosition(chatDoc.getLength());
    }

    /** New empty document with a stylesheet built from the current Look and Feel. */
    private void resetDocument() {
        theme = ChatTheme.fromLookAndFeel();
        chatDisplay.setBackground(theme.background());
        // Rules go on this document's own stylesheet; HTMLEditorKit.getStyleSheet() is JVM-wide.
        chatDoc = (HTMLDocument) htmlKit.createDefaultDocument();
        // TextLayout-based painting: the default painter mis-measures styled runs at
        // fractional sizes (Windows 125%/150% scaling), overlapping text around <b>/<code>.
        chatDoc.putProperty("i18n", Boolean.TRUE);
        for (String rule : theme.cssRules()) {
            chatDoc.getStyleSheet().addRule(rule);
        }
        try {
            htmlKit.read(new StringReader("<html><body><div id=\"chat\"></div></body></html>"), chatDoc, 0);
        } catch (BadLocationException | IOException e) {
            // Cannot happen for a fixed string
        }
        chatDisplay.setDocument(chatDoc);
        cardCounter = 0;
    }
}

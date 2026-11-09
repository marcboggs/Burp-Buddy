package ui.chat;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.List;

/**
 * Chat colours derived from the active Look and Feel, so cards read well in
 * both Burp's light and dark themes. Colour fields are "#rrggbb" for HTML.
 */
record ChatTheme(Color background, String text, String muted,
                 String userAccent, String userCard,
                 String assistantAccent, String assistantCard,
                 String errorAccent, String errorCard,
                 String codeBackground) {

    static ChatTheme fromLookAndFeel() {
        Color bg = uiColor("TextPane.background", "Panel.background", Color.WHITE);
        Color fg = uiColor("TextPane.foreground", "Label.foreground", Color.BLACK);
        boolean dark = luminance(bg) < 0.5;

        Color user = dark ? new Color(110, 165, 255) : new Color(0, 102, 204);
        Color assistant = dark ? new Color(95, 195, 120) : new Color(0, 128, 0);
        Color error = dark ? new Color(255, 115, 115) : new Color(200, 0, 0);

        return new ChatTheme(bg, hex(fg), hex(mix(fg, bg, 0.45)),
                hex(user), hex(mix(bg, user, dark ? 0.16 : 0.08)),
                hex(assistant), hex(mix(bg, fg, dark ? 0.06 : 0.035)),
                hex(error), hex(mix(bg, error, dark ? 0.18 : 0.08)),
                hex(mix(bg, fg, dark ? 0.12 : 0.07)));
    }

    /** Swing's CSS is roughly CSS1: no border-radius, pre-wrap or line-height. */
    List<String> cssRules() {
        return List.of(
                "body { font-family: SansSerif; font-size: 13pt; color: " + text + "; margin: 6px; }",
                "div.card { padding: 6px 10px 8px 10px; }",
                "div.hdr { font-size: 11pt; margin-bottom: 4px; }",
                ".muted { color: " + muted + "; font-size: 11pt; }",
                "div.gap { font-size: 6pt; margin-top: 6px; }",
                "p { margin-top: 2px; margin-bottom: 6px; }",
                "div.h1 { font-size: 16pt; font-weight: bold; margin-top: 6px; margin-bottom: 4px; }",
                "div.h2 { font-size: 15pt; font-weight: bold; margin-top: 6px; margin-bottom: 4px; }",
                "div.h3 { font-size: 14pt; font-weight: bold; margin-top: 4px; margin-bottom: 3px; }",
                "ul { margin-left: 18px; margin-top: 0px; margin-bottom: 6px; }",
                "ol { margin-left: 22px; margin-top: 0px; margin-bottom: 6px; }",
                "li { margin-bottom: 2px; }",
                "code { font-family: Monospaced; font-size: 12pt; background-color: " + codeBackground + "; }",
                "div.code { font-family: Monospaced; font-size: 12pt; background-color: " + codeBackground
                        + "; padding: 6px 8px 6px 8px; margin-top: 2px; margin-bottom: 6px; }",
                "hr { color: " + muted + "; }"
        );
    }

    private static Color uiColor(String key, String fallbackKey, Color fallback) {
        Color c = UIManager.getColor(key);
        if (c == null) c = UIManager.getColor(fallbackKey);
        // Copy: L&F colours can be UIResource subclasses
        return c == null ? fallback : new Color(c.getRGB());
    }

    private static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    /** Linear blend: amount 0 gives {@code a}, 1 gives {@code b}. */
    private static Color mix(Color a, Color b, double amount) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * amount),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * amount));
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}

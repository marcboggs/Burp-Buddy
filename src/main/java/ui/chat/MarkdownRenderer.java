package ui.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the Markdown subset LLMs emit into HTML for Swing's HTMLEditorKit.
 *
 * SECURITY: input is model output that untrusted HTTP traffic can steer, and
 * Swing HTML can fetch {@code <img>} URLs and instantiate Java classes via
 * {@code <object>}. Every character of input is escaped before any tag is
 * added, so the only markup in the result is what this class generates.
 *
 * shortcut: no tables, links, nested lists or blockquotes; add if replies need them.
 */
public final class MarkdownRenderer {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d{1,6})[.)]\\s+(.*)$");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*|__(?=\\S)(.+?)(?<=\\S)__");
    private static final Pattern ITALIC = Pattern.compile(
            "(?<![*\\w])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?![*\\w])|(?<!\\w)_(?=\\S)(.+?)(?<=\\S)_(?!\\w)");

    private MarkdownRenderer() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        List<String> code = null;       // non-null while inside a ``` fence
        String listTag = null;          // "ul" or "ol" while inside a list

        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (code != null) {
                if (line.trim().startsWith("```")) {
                    out.append(codeBlock(code));
                    code = null;
                } else {
                    code.add(line);
                }
                continue;
            }
            if (line.trim().startsWith("```")) {
                flushParagraph(out, paragraph);
                listTag = closeList(out, listTag);
                code = new ArrayList<>();
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(out, paragraph);
                listTag = closeList(out, listTag);
                continue;
            }
            Matcher m;
            if ((m = HEADING.matcher(line)).matches()) {
                flushParagraph(out, paragraph);
                listTag = closeList(out, listTag);
                int level = Math.min(m.group(1).length(), 3);
                out.append("<div class=\"h").append(level).append("\">")
                        .append(inline(m.group(2))).append("</div>");
            } else if (RULE.matcher(line).matches()) {
                flushParagraph(out, paragraph);
                listTag = closeList(out, listTag);
                out.append("<hr>");
            } else if ((m = BULLET.matcher(line)).matches() || (m = NUMBERED.matcher(line)).matches()) {
                flushParagraph(out, paragraph);
                boolean bullet = BULLET.matcher(line).matches();
                String tag = bullet ? "ul" : "ol";
                if (!tag.equals(listTag)) {
                    closeList(out, listTag);
                    out.append('<').append(tag);
                    // Keep the model's numbering when a code block splits a list ("2." stays 2)
                    int first = bullet ? 1 : Integer.parseInt(m.group(1));
                    if (first != 1) out.append(" start=\"").append(first).append('"');
                    out.append('>');
                    listTag = tag;
                }
                out.append("<li>").append(inline(m.group(bullet ? 1 : 2))).append("</li>");
            } else {
                listTag = closeList(out, listTag);
                paragraph.add(line);
            }
        }
        // An unclosed fence is normal mid-stream; show what has arrived.
        if (code != null) out.append(codeBlock(code));
        flushParagraph(out, paragraph);
        closeList(out, listTag);
        return out.toString();
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void flushParagraph(StringBuilder out, List<String> lines) {
        if (lines.isEmpty()) return;
        out.append("<p>");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("<br>");
            out.append(inline(lines.get(i).strip()));
        }
        out.append("</p>");
        lines.clear();
    }

    private static String closeList(StringBuilder out, String listTag) {
        if (listTag != null) out.append("</").append(listTag).append('>');
        return null;
    }

    /**
     * Swing has no white-space:pre-wrap and its {@code <pre>} never wraps, so
     * long lines (HTTP requests) would be cut off. Keep indentation and runs of
     * spaces with &amp;nbsp; and let the line wrap at the remaining spaces.
     */
    private static String codeBlock(List<String> lines) {
        StringBuilder sb = new StringBuilder("<div class=\"code\">");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append("<br>");
            sb.append(preserveSpaces(escape(lines.get(i).replace("\t", "    "))));
        }
        return sb.append("&nbsp;</div>").toString();
    }

    private static String preserveSpaces(String escaped) {
        StringBuilder sb = new StringBuilder(escaped.length());
        boolean prevSpace = true; // a leading space must be non-breaking too
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == ' ') {
                sb.append(prevSpace ? "&nbsp;" : " ");
                prevSpace = true;
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString();
    }

    private static String inline(String text) {
        // Split on `code` first so emphasis markers inside code stay literal.
        StringBuilder sb = new StringBuilder();
        Matcher m = INLINE_CODE.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(emphasis(escape(text.substring(last, m.start()))));
            sb.append("<code>").append(escape(m.group(1))).append("</code>");
            last = m.end();
        }
        sb.append(emphasis(escape(text.substring(last))));
        return sb.toString();
    }

    private static String emphasis(String escaped) {
        String s = BOLD.matcher(escaped).replaceAll(r ->
                "<b>" + Matcher.quoteReplacement(r.group(1) != null ? r.group(1) : r.group(2)) + "</b>");
        return ITALIC.matcher(s).replaceAll(r ->
                "<i>" + Matcher.quoteReplacement(r.group(1) != null ? r.group(1) : r.group(2)) + "</i>");
    }
}

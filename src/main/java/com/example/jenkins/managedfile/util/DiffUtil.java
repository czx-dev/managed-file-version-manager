package com.example.jenkins.managedfile.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text line-level diff. We deliberately do not parse YAML/JSON/Properties
 * because the source-of-truth content is plain text - any of those formats
 * diff as text just fine.
 *
 * <p>This is the classic Myers LCS based diff producing a list of
 * {@link DiffLine} entries with the change marker applied.</p>
 */
public final class DiffUtil {

    public enum LineKind { CONTEXT, ADD, REMOVE }

    public static final class DiffLine {
        private final LineKind kind;
        private final String text;

        public DiffLine(LineKind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        public LineKind getKind() {
            return kind;
        }

        public String getText() {
            return text;
        }
    }

    private DiffUtil() {
    }

    /**
     * Produces side-by-side diff data with paired lines for left (old) and right (new).
     */
    public static List<SideBySideLine> sideBySide(String oldText, String newText) {
        List<DiffLine> diff = diff(oldText, newText);
        List<SideBySideLine> result = new ArrayList<>();
        
        int i = 0;
        while (i < diff.size()) {
            DiffLine line = diff.get(i);
            
            if (line.kind == LineKind.CONTEXT) {
                result.add(new SideBySideLine(line.text, line.text));
                i++;
            } else if (line.kind == LineKind.REMOVE) {
                // Check if next line is ADD to pair them
                if (i + 1 < diff.size() && diff.get(i + 1).kind == LineKind.ADD) {
                    result.add(new SideBySideLine(line.text, diff.get(i + 1).text));
                    i += 2;
                } else {
                    result.add(new SideBySideLine(line.text, null));
                    i++;
                }
            } else if (line.kind == LineKind.ADD) {
                result.add(new SideBySideLine(null, line.text));
                i++;
            }
        }
        
        return result;
    }

    public static class SideBySideLine {
        private final String oldLine;
        private final String newLine;

        public SideBySideLine(String oldLine, String newLine) {
            this.oldLine = oldLine;
            this.newLine = newLine;
        }

        public String getOldLine() { return oldLine; }
        public String getNewLine() { return newLine; }
        public boolean getModified() { return oldLine != null && newLine != null && !oldLine.equals(newLine); }
        public boolean getAdded() { return oldLine == null; }
        public boolean getRemoved() { return newLine == null; }
        public boolean getUnchanged() { return oldLine != null && newLine != null && oldLine.equals(newLine); }
        public boolean getOldEmpty() { return oldLine == null; }
        public boolean getNewEmpty() { return newLine == null; }
    }

    public static List<DiffLine> diff(String oldText, String newText) {
        String[] a = splitLines(oldText == null ? "" : oldText);
        String[] b = splitLines(newText == null ? "" : newText);

        int n = a.length;
        int m = b.length;

        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a[i].equals(b[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<DiffLine> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.add(new DiffLine(LineKind.CONTEXT, a[i]));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new DiffLine(LineKind.REMOVE, a[i]));
                i++;
            } else {
                out.add(new DiffLine(LineKind.ADD, b[j]));
                j++;
            }
        }
        while (i < n) {
            out.add(new DiffLine(LineKind.REMOVE, a[i++]));
        }
        while (j < m) {
            out.add(new DiffLine(LineKind.ADD, b[j++]));
        }
        return out;
    }

    private static String[] splitLines(String text) {
        // Split dropping the trailing empty element produced by a final newline
        // so we don't render a spurious blank diff line for "file ends with \n".
        if (text.isEmpty()) {
            return new String[]{""};
        }
        String[] parts = text.split("\\r?\\n", -1);
        if (parts.length > 0 && parts[parts.length - 1].isEmpty()) {
            String[] trimmed = new String[parts.length - 1];
            System.arraycopy(parts, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return parts;
    }
}

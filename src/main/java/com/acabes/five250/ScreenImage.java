package com.acabes.five250;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Renders a screen snapshot (Terminal.snapshot() - the same map the CLI, GUI and replay files
 * all use) to a PNG that looks like a 5250 terminal: a fixed character grid, green on black,
 * input fields underlined, the cursor as a block, and a status line (cursor position, keyboard
 * state) underneath.
 *
 * There is no real terminal window anywhere in this stack to grab pixels from - the daemon only
 * ever holds the character buffer - so a "screenshot" is always drawn here, off-screen, into a
 * BufferedImage. That is exactly why it works identically whether or not a browser is open,
 * whether a run is "Headless" in the GUI's sense, from the CLI/CI, and on a machine with no
 * display at all (java.awt.headless=true): nothing here touches a window, a Toolkit screen
 * device, or Swing - BufferedImage + Graphics2D + ImageIO are all headless-safe.
 *
 * Every character is drawn into its own grid cell rather than laid out as a line of text. That
 * keeps columns exactly aligned no matter which glyphs the font substitutes (a logical
 * "Monospaced" font falls back to other physical fonts for e.g. Arabic, and those are not the
 * same width), and it keeps right-to-left text in the cell order the host sent it - which is
 * what a 5250 display shows - instead of letting Java's bidi layout reorder a row. The trade-off
 * is that Arabic letters are drawn unjoined, one per cell.
 */
final class ScreenImage {

    private static final int FONT_SIZE = 20;
    private static final int PADDING = 14;
    private static final Color BACKGROUND = new Color(0x00, 0x00, 0x00);
    private static final Color TEXT = new Color(0x33, 0xFF, 0x66);
    private static final Color FIELD_LINE = new Color(0x1F, 0x99, 0x3D);
    private static final Color STATUS_TEXT = new Color(0x9A, 0xA5, 0xB1);
    private static final Color STATUS_RULE = new Color(0x2A, 0x33, 0x3D);

    private ScreenImage() {}

    /** PNG bytes for the given snapshot. */
    @SuppressWarnings("unchecked")
    static byte[] png(Map<String, Object> snapshot) {
        int rows = ((Number) snapshot.get("rows")).intValue();
        int cols = ((Number) snapshot.get("cols")).intValue();
        String text = String.valueOf(snapshot.getOrDefault("text", ""));

        Font font = new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE);
        // Cell size has to come from real font metrics, which need a Graphics2D - a 1x1 scratch
        // image is the standard headless-safe way to get one before the real size is known.
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = scratch.createGraphics();
        FontMetrics fm = sg.getFontMetrics(font);
        int cellW = Math.max(fm.charWidth('M'), 1);
        int cellH = fm.getHeight() + 2;
        int ascent = fm.getAscent() + 1;
        sg.dispose();

        int gridW = cols * cellW;
        int gridH = rows * cellH;
        int statusH = cellH + 10;
        BufferedImage image = new BufferedImage(gridW + 2 * PADDING, gridH + 2 * PADDING + statusH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setFont(font);

            // Input fields: an underline along each one, the same cue a real 5250 emulator gives.
            g.setColor(FIELD_LINE);
            Object fields = snapshot.get("fields");
            if (fields instanceof List) {
                for (Object o : (List<Object>) fields) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> f = (Map<String, Object>) o;
                    if (Boolean.TRUE.equals(f.get("protected"))) continue;
                    int pos = (num(f.get("row"), 1) - 1) * cols + (num(f.get("col"), 1) - 1);
                    int length = num(f.get("length"), 0);
                    // A field can wrap past the end of its row, so underline cell by cell.
                    for (int i = 0; i < length && pos + i < rows * cols; i++) {
                        int r = (pos + i) / cols;
                        int c = (pos + i) % cols;
                        int y = PADDING + r * cellH + cellH - 2;
                        g.drawLine(PADDING + c * cellW, y, PADDING + (c + 1) * cellW - 1, y);
                    }
                }
            }

            int cursorRow = -1;
            int cursorCol = -1;
            Object cursor = snapshot.get("cursor");
            if (cursor instanceof Map) {
                cursorRow = num(((Map<String, Object>) cursor).get("row"), 0) - 1;
                cursorCol = num(((Map<String, Object>) cursor).get("col"), 0) - 1;
            }
            if (cursorRow >= 0 && cursorRow < rows && cursorCol >= 0 && cursorCol < cols) {
                g.setColor(TEXT);
                g.fillRect(PADDING + cursorCol * cellW, PADDING + cursorRow * cellH, cellW, cellH);
            }

            char[] one = new char[1];
            for (int i = 0; i < text.length() && i < rows * cols; i++) {
                char ch = text.charAt(i);
                if (ch <= ' ' || Character.isISOControl(ch)) continue;
                int r = i / cols;
                int c = i % cols;
                one[0] = ch;
                // Centre a glyph that isn't exactly one cell wide (a fallback-font character)
                // in its own cell, so it never drifts into its neighbour's column.
                int x = PADDING + c * cellW + (cellW - fm.charWidth(ch)) / 2;
                g.setColor(r == cursorRow && c == cursorCol ? BACKGROUND : TEXT);
                g.drawChars(one, 0, 1, x, PADDING + r * cellH + ascent);
            }

            int ruleY = PADDING + gridH + PADDING / 2;
            g.setColor(STATUS_RULE);
            g.drawLine(PADDING, ruleY, PADDING + gridW, ruleY);
            g.setColor(STATUS_TEXT);
            g.drawString(statusLine(snapshot, rows, cols, cursorRow, cursorCol), PADDING, ruleY + 6 + ascent);
        } finally {
            g.dispose();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer available");
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Could not encode screenshot as PNG: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static String statusLine(Map<String, Object> snapshot, int rows, int cols, int cursorRow, int cursorCol) {
        StringBuilder sb = new StringBuilder();
        sb.append(rows).append('x').append(cols);
        if (cursorRow >= 0) sb.append("   cursor ").append(cursorRow + 1).append(',').append(cursorCol + 1);
        Object oia = snapshot.get("oia");
        if (oia instanceof Map) {
            Map<String, Object> o = (Map<String, Object>) oia;
            if (Boolean.TRUE.equals(o.get("keyboardLocked"))) sb.append("   keyboard locked");
            if (Boolean.TRUE.equals(o.get("inputInhibited"))) sb.append("   input inhibited");
            if (Boolean.TRUE.equals(o.get("messageWait"))) sb.append("   message waiting");
        }
        return sb.toString();
    }

    private static int num(Object v, int fallback) {
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }
}

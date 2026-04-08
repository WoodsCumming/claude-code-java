package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Text highlighting utilities.
 * Translated from src/utils/textHighlighting.ts
 */
public class TextHighlighting {

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TextHighlight {
        private int start;
        private int end;
        private String color;
        private Boolean dimColor;
        private Boolean inverse;
        private int priority;

        public int getStart() { return start; }
        public void setStart(int v) { start = v; }
        public int getEnd() { return end; }
        public void setEnd(int v) { end = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public boolean isDimColor() { return dimColor; }
        public void setDimColor(Boolean v) { dimColor = v; }
        public boolean isInverse() { return inverse; }
        public void setInverse(Boolean v) { inverse = v; }
        public int getPriority() { return priority; }
        public void setPriority(int v) { priority = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TextSegment {
        private String text;
        private int start;
        private TextHighlight highlight;

        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public TextHighlight getHighlight() { return highlight; }
        public void setHighlight(TextHighlight v) { highlight = v; }
    }

    /**
     * Segment text by highlights.
     * Translated from segmentTextByHighlights() in textHighlighting.ts
     */
    public static List<TextSegment> segmentTextByHighlights(
            String text,
            List<TextHighlight> highlights) {

        if (highlights == null || highlights.isEmpty()) {
            return List.of(new TextSegment(text, 0, null));
        }

        List<TextHighlight> sorted = highlights.stream()
            .sorted(Comparator.comparingInt(TextHighlight::getStart)
                .thenComparingInt(h -> -h.getPriority()))
            .collect(Collectors.toList());

        List<TextSegment> segments = new ArrayList<>();
        int pos = 0;

        for (TextHighlight highlight : sorted) {
            if (highlight.getStart() > pos) {
                segments.add(new TextSegment(
                    text.substring(pos, highlight.getStart()),
                    pos,
                    null
                ));
            }

            int end = Math.min(highlight.getEnd(), text.length());
            if (highlight.getStart() < end) {
                segments.add(new TextSegment(
                    text.substring(highlight.getStart(), end),
                    highlight.getStart(),
                    highlight
                ));
                pos = end;
            }
        }

        if (pos < text.length()) {
            segments.add(new TextSegment(text.substring(pos), pos, null));
        }

        return segments;
    }

    private TextHighlighting() {}
}

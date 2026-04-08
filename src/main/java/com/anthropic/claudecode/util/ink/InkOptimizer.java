package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.List;

/**
 * Java equivalent of optimizer.ts.
 *
 * Reduces the number of terminal-write patches produced by the diff pipeline
 * by applying a single-pass set of merge / dedupe / cancel rules. The
 * TypeScript source operates on a mutable array; here we build a new
 * {@link List} and return it.
 *
 * Rules (in application order):
 * <ol>
 *   <li>Remove empty {@link InkFrame.StdoutPatch} patches.</li>
 *   <li>Remove no-op {@link InkFrame.CursorMovePatch}(0, 0) patches.</li>
 *   <li>Remove zero-count {@link InkFrame.ClearPatch} patches.</li>
 *   <li>Merge consecutive {@link InkFrame.CursorMovePatch} by summing dx/dy.</li>
 *   <li>Collapse consecutive {@link InkFrame.CursorToPatch} (keep last only).</li>
 *   <li>Concat adjacent {@link InkFrame.StyleStrPatch} strings.</li>
 *   <li>Deduplicate consecutive {@link InkFrame.HyperlinkPatch} with the same URI.</li>
 *   <li>Cancel cursor hide/show pairs.</li>
 * </ol>
 */
public final class InkOptimizer {

    private InkOptimizer() {}

    /**
     * Optimize {@code diff} and return a new list with redundant patches removed.
     *
     * @param diff list of patches to optimize; must not be {@code null}
     * @return optimized patch list (may be the same instance if {@code diff.size() <= 1})
     */
    public static List<InkFrame.Patch> optimize(List<InkFrame.Patch> diff) {
        if (diff.size() <= 1) return diff;

        List<InkFrame.Patch> result = new ArrayList<>(diff.size());

        for (InkFrame.Patch patch : diff) {
            // --- Skip no-ops ---
            if (patch instanceof InkFrame.StdoutPatch s && s.content().isEmpty()) continue;
            if (patch instanceof InkFrame.CursorMovePatch m && m.x() == 0 && m.y() == 0) continue;
            if (patch instanceof InkFrame.ClearPatch c && c.count() == 0) continue;

            // --- Try to merge with the previous patch ---
            if (!result.isEmpty()) {
                InkFrame.Patch last = result.get(result.size() - 1);

                // Merge consecutive cursorMove
                if (patch instanceof InkFrame.CursorMovePatch cm
                        && last instanceof InkFrame.CursorMovePatch lm) {
                    result.set(result.size() - 1,
                            new InkFrame.CursorMovePatch(lm.x() + cm.x(), lm.y() + cm.y()));
                    continue;
                }

                // Collapse consecutive cursorTo (keep last)
                if (patch instanceof InkFrame.CursorToPatch
                        && last instanceof InkFrame.CursorToPatch) {
                    result.set(result.size() - 1, patch);
                    continue;
                }

                // Concat adjacent styleStr patches
                if (patch instanceof InkFrame.StyleStrPatch sp
                        && last instanceof InkFrame.StyleStrPatch lsp) {
                    result.set(result.size() - 1,
                            new InkFrame.StyleStrPatch(lsp.str() + sp.str()));
                    continue;
                }

                // Deduplicate consecutive hyperlinks with the same URI
                if (patch instanceof InkFrame.HyperlinkPatch hp
                        && last instanceof InkFrame.HyperlinkPatch lhp
                        && hp.uri().equals(lhp.uri())) {
                    continue;
                }

                // Cancel cursor hide/show pairs
                boolean isHide = patch instanceof InkFrame.CursorHidePatch;
                boolean isShow = patch instanceof InkFrame.CursorShowPatch;
                boolean lastHide = last instanceof InkFrame.CursorHidePatch;
                boolean lastShow = last instanceof InkFrame.CursorShowPatch;
                if ((isShow && lastHide) || (isHide && lastShow)) {
                    result.remove(result.size() - 1);
                    continue;
                }
            }

            result.add(patch);
        }

        return result;
    }
}

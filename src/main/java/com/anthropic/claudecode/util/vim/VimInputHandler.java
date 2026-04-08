package com.anthropic.claudecode.util.vim;

import com.anthropic.claudecode.service.UserInputProcessorService.TextInputSession;
import com.anthropic.claudecode.service.UserInputProcessorService.TextInputSession.KeyEvent;
import com.anthropic.claudecode.util.vim.VimTypes.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;
import java.util.function.BiFunction;

/**
 * Vim-mode input handler for the REPL text input.
 *
 * Translated from {@code src/hooks/useVimInput.ts}.
 *
 * <h3>TypeScript → Java mapping</h3>
 * <pre>
 * useVimInput(props): VimInputState       → VimInputHandler (stateful class)
 *
 * vimStateRef.current (VimState)          → vimState field
 * persistentRef.current (PersistentState) → MutablePersistentState (mutable wrapper)
 * useState(mode)                          → mode field
 * useTextInput({...props, inputFilter})   → textInput (TextInputSession)
 *
 * switchToInsertMode(offset?)             → switchToInsertMode(Integer)
 * switchToNormalMode()                    → switchToNormalMode()
 * createOperatorContext(cursor, isReplay) → createTransitionContext(boolean)
 * replayLastChange()                      → replayLastChange()
 * handleVimInput(rawInput, key)           → handleInput(String, KeyEvent)
 * setModeExternal(newMode)               → setMode(VimMode)
 *
 * VimInputState { ...textInput, onInput, mode, setMode }
 *     → VimInputHandler exposes getMode(), setMode(), handleInput(),
 *       getValue(), getOffset(), getRenderedValue()
 * </pre>
 *
 * <p>The TypeScript hook uses {@code useRef} for {@code vimState} so that mutations
 * inside event handlers are immediately visible without triggering a re-render.
 * In Java, plain volatile fields serve the same purpose.
 *
 * <p>Thread-safety: this class is NOT thread-safe. Use it from a single
 * event-dispatch (REPL input) thread.
 */
@Slf4j
public class VimInputHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VimInputHandler.class);


    // =========================================================================
    // Vim modes — mirrors VimMode in src/vim/types.ts
    // =========================================================================

    /**
     * The two vim modes supported by the REPL.
     * Mirrors {@code type VimMode = 'INSERT' | 'NORMAL'} in types.ts.
     */
    public enum VimMode {
        INSERT,
        NORMAL
    }

    // =========================================================================
    // Mutable persistent state wrapper
    // =========================================================================

    /**
     * Mutable wrapper around {@link PersistentState} (which is an immutable record).
     *
     * Mirrors {@code persistentRef.current} in useVimInput.ts — a ref that holds
     * the persistent cross-command state (register, lastFind, lastChange).
     */
    private static class MutablePersistentState {
        RecordedChange lastChange = null;
        PersistentState.LastFind lastFind = null;
        String register = "";
        boolean registerLinewise = false;

        PersistentState.LastFind getLastFind() { return lastFind; }

        void setLastFind(FindType type, String character) {
            this.lastFind = new PersistentState.LastFind(type, character);
        }
    }

    // =========================================================================
    // State — mirrors vimStateRef and useState(mode)
    // =========================================================================

    /** Current vim mode. Mirrors {@code useState(mode)}. */
    @Getter
    private VimMode mode = VimMode.INSERT;

    /**
     * Full vim command state (VimState discriminated union).
     * Mirrors {@code vimStateRef.current}.
     */
    private VimState vimState = new VimState.Insert("");

    /**
     * Persistent cross-mode state.
     * Mirrors {@code persistentRef.current}.
     */
    private final MutablePersistentState persistent = new MutablePersistentState();

    // =========================================================================
    // Collaborators
    // =========================================================================

    /**
     * Underlying text-editing session.
     * Mirrors the {@code textInput} returned by the inner {@code useTextInput} call.
     */
    private final TextInputSession textInput;

    /**
     * Optional callback fired when the vim mode changes.
     * Mirrors the {@code onModeChange?(mode)} prop.
     */
    private final Consumer<VimMode> onModeChange;

    /**
     * Optional undo callback forwarded to the operator context.
     * Mirrors the {@code onUndo?} prop.
     */
    private final Runnable onUndo;

    /**
     * Optional input filter applied in INSERT mode.
     * Mirrors the {@code inputFilter?(input, key)} prop.
     *
     * Note: the TypeScript hook passes {@code inputFilter: undefined} to the inner
     * useTextInput, then applies the filter manually at the top of handleVimInput.
     * The Java port follows the same approach.
     */
    private final BiFunction<String, KeyEvent, String> inputFilter;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Create a VimInputHandler backed by an existing {@link TextInputSession}.
     *
     * Mirrors instantiating the {@code useVimInput} hook with the given props.
     *
     * @param textInput    the underlying text-editing session
     * @param onModeChange optional mode-change callback
     * @param onUndo       optional undo callback
     * @param inputFilter  optional input transformer (applied in INSERT mode)
     */
    public VimInputHandler(
            TextInputSession textInput,
            Consumer<VimMode> onModeChange,
            Runnable onUndo,
            BiFunction<String, KeyEvent, String> inputFilter) {

        this.textInput    = textInput;
        this.onModeChange = onModeChange;
        this.onUndo       = onUndo;
        this.inputFilter  = inputFilter;
    }

    /** Convenience constructor with no optional callbacks. */
    public VimInputHandler(TextInputSession textInput) {
        this(textInput, null, null, null);
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    public String getValue()         { return textInput.getValue(); }
    public int    getOffset()        { return textInput.getOffset(); }
    public String getRenderedValue() { return textInput.getRenderedValue(); }

    // =========================================================================
    // Mode switching
    // =========================================================================

    /**
     * Switch to INSERT mode, optionally moving the cursor first.
     * Mirrors {@code switchToInsertMode(offset?)} in useVimInput.ts.
     */
    public void switchToInsertMode(Integer cursorOffset) {
        if (cursorOffset != null) {
            textInput.setOffset(cursorOffset);
        }
        vimState = new VimState.Insert("");
        mode = VimMode.INSERT;
        if (onModeChange != null) onModeChange.accept(VimMode.INSERT);
    }

    /**
     * Switch to NORMAL mode.
     * Mirrors {@code switchToNormalMode()} in useVimInput.ts.
     *
     * Records last inserted text for dot-repeat and moves the cursor one
     * position left (standard vim behaviour on Esc from INSERT mode).
     */
    public void switchToNormalMode() {
        // Record last inserted text for dot-repeat
        if (vimState instanceof VimState.Insert ins
                && ins.insertedText() != null
                && !ins.insertedText().isEmpty()) {
            persistent.lastChange = new RecordedChange.Insert(ins.insertedText());
        }

        // Move cursor left by 1 when exiting INSERT (unless at col 0 or after newline)
        int offset = textInput.getOffset();
        String value = textInput.getValue();
        if (offset > 0 && (offset - 1 >= value.length() || value.charAt(offset - 1) != '\n')) {
            textInput.setOffset(Math.max(0, offset - 1));
        }

        vimState = new VimState.Normal(new CommandState.Idle());
        mode = VimMode.NORMAL;
        if (onModeChange != null) onModeChange.accept(VimMode.NORMAL);
    }

    /**
     * Set the vim mode externally.
     * Mirrors {@code setModeExternal(newMode)} in useVimInput.ts.
     */
    public void setMode(VimMode newMode) {
        if (newMode == VimMode.INSERT) {
            vimState = new VimState.Insert("");
        } else {
            vimState = new VimState.Normal(new CommandState.Idle());
        }
        mode = newMode;
        if (onModeChange != null) onModeChange.accept(newMode);
    }

    // =========================================================================
    // Main input handler — mirrors handleVimInput(rawInput, key)
    // =========================================================================

    /**
     * Process a key event in vim mode.
     *
     * Mirrors {@code handleVimInput(rawInput, key)} in useVimInput.ts.
     */
    public void handleInput(String rawInput, KeyEvent key) {
        if (rawInput == null) rawInput = "";

        // Run inputFilter in all modes so stateful filters disarm on any key.
        // Apply the result only in INSERT mode.
        String filtered = inputFilter != null ? inputFilter.apply(rawInput, key) : rawInput;
        String input = (vimState instanceof VimState.Insert) ? filtered : rawInput;

        // Ctrl keys → always forward to base handler
        if (key.isCtrl()) {
            textInput.onInput(input, key);
            return;
        }

        // Escape in INSERT → switch to NORMAL
        if (key.isEscape() && vimState instanceof VimState.Insert) {
            switchToNormalMode();
            return;
        }

        // Escape in NORMAL → cancel pending command
        if (key.isEscape() && vimState instanceof VimState.Normal) {
            vimState = new VimState.Normal(new CommandState.Idle());
            return;
        }

        // Enter → always forward (allows submission from both modes)
        if (key.isReturnKey()) {
            textInput.onInput(input, key);
            return;
        }

        // INSERT mode
        if (vimState instanceof VimState.Insert ins) {
            String insertedText = ins.insertedText();
            if (key.isBackspace() || key.isDelete()) {
                if (!insertedText.isEmpty()) {
                    vimState = new VimState.Insert(
                            insertedText.substring(0, Math.max(0, insertedText.length() - 1)));
                }
            } else {
                vimState = new VimState.Insert(insertedText + input);
            }
            textInput.onInput(input, key);
            return;
        }

        // NORMAL mode only from here
        if (!(vimState instanceof VimState.Normal normalState)) {
            return;
        }

        CommandState cmd = normalState.command();

        // Arrow keys in idle state → delegate to base handler
        if (cmd instanceof CommandState.Idle
                && (key.isUpArrow() || key.isDownArrow()
                    || key.isLeftArrow() || key.isRightArrow())) {
            textInput.onInput(input, key);
            return;
        }

        // Determine whether the state expects a motion (for backspace/delete mapping)
        boolean expectsMotion = cmd instanceof CommandState.Idle
                             || cmd instanceof CommandState.Count
                             || cmd instanceof CommandState.OperatorState
                             || cmd instanceof CommandState.OperatorCount;

        // Map arrow keys → vim motion chars
        String vimInput = input;
        if      (key.isLeftArrow())  vimInput = "h";
        else if (key.isRightArrow()) vimInput = "l";
        else if (key.isUpArrow())    vimInput = "k";
        else if (key.isDownArrow())  vimInput = "j";
        else if (expectsMotion && key.isBackspace()) vimInput = "h";
        else if (expectsMotion && !(cmd instanceof CommandState.Count) && key.isDelete())
            vimInput = "x";

        // Run FSM transition
        VimTransitions.TransitionResult result = VimTransitions.transition(
                cmd, vimInput, buildTransitionContext());

        if (result.execute() != null) {
            result.execute().run();
        }

        // Update command state only if execute() didn't switch to INSERT
        if (vimState instanceof VimState.Normal) {
            if (result.next() != null) {
                vimState = new VimState.Normal(result.next());
            } else if (result.execute() != null) {
                vimState = new VimState.Normal(new CommandState.Idle());
            }
        }

        // Special case: '?' in idle NORMAL → insert '?' (pass-through for search)
        if ("?".equals(input) && cmd instanceof CommandState.Idle) {
            textInput.setValue("?");
        }
    }

    // =========================================================================
    // Transition context factory
    // =========================================================================

    /**
     * Build a {@link VimTransitions.TransitionContext} for the current state.
     *
     * Mirrors {@code createOperatorContext(cursor, isReplay)} + the
     * {@code TransitionContext} assembly at the bottom of handleVimInput in
     * useVimInput.ts.
     */
    private VimTransitions.TransitionContext buildTransitionContext() {
        // Minimal Cursor adapter over the TextInputSession's offset
        OffsetCursor cursor = new OffsetCursor(
                textInput.getValue(), textInput.getOffset());

        return new VimTransitions.TransitionContext() {
            @Override public VimOperators.Cursor cursor()  { return cursor; }
            @Override public String text()               { return textInput.getValue(); }

            @Override public void setText(String newText) {
                textInput.setValue(newText);
            }

            @Override public void setOffset(int offset) {
                textInput.setOffset(offset);
            }

            @Override public void enterInsert(int offset) {
                switchToInsertMode(offset);
            }

            @Override public String getRegister()        { return persistent.register; }

            @Override public void setRegister(String content, boolean linewise) {
                persistent.register = content;
                persistent.registerLinewise = linewise;
            }

            @Override public PersistentState.LastFind getLastFind() {
                return persistent.getLastFind();
            }

            @Override public void setLastFind(FindType type, String character) {
                persistent.setLastFind(type, character);
            }

            @Override public void recordChange(RecordedChange change) {
                persistent.lastChange = change;
            }

            @Override public void onUndo() {
                if (VimInputHandler.this.onUndo != null) {
                    VimInputHandler.this.onUndo.run();
                }
            }

            @Override public void onDotRepeat() {
                replayLastChange();
            }
        };
    }

    // =========================================================================
    // Dot-repeat — mirrors replayLastChange() in useVimInput.ts
    // =========================================================================

    /**
     * Replay the last recorded change (dot-repeat).
     * Mirrors {@code replayLastChange()} in useVimInput.ts.
     */
    public void replayLastChange() {
        RecordedChange change = persistent.lastChange;
        if (change == null) return;

        // Build a replay context (recordChange is a no-op in replay mode)
        OffsetCursor cursor = new OffsetCursor(
                textInput.getValue(), textInput.getOffset());

        VimOperators.OperatorContext ctx = new VimOperators.OperatorContext() {
            @Override public VimOperators.Cursor cursor()  { return (VimOperators.Cursor) cursor; }
            @Override public String text()               { return textInput.getValue(); }
            @Override public void setText(String t)      { textInput.setValue(t); }
            @Override public void setOffset(int o)       { textInput.setOffset(o); }
            @Override public void enterInsert(int o)     { switchToInsertMode(o); }
            @Override public String getRegister()        { return persistent.register; }
            @Override public void setRegister(String c, boolean lw) {
                persistent.register = c; persistent.registerLinewise = lw;
            }
            @Override public PersistentState.LastFind getLastFind() {
                return persistent.getLastFind();
            }
            @Override public void setLastFind(FindType type, String ch) {
                persistent.setLastFind(type, ch);
            }
            @Override public void recordChange(RecordedChange rc) { /* no-op in replay */ }
        };

        switch (change) {
            case RecordedChange.Insert ins -> {
                if (ins.text() != null && !ins.text().isEmpty()) {
                    String value = textInput.getValue();
                    int off = textInput.getOffset();
                    String newText = value.substring(0, off) + ins.text() + value.substring(off);
                    textInput.setValue(newText);
                    textInput.setOffset(off + ins.text().length());
                }
            }
            case RecordedChange.X x ->
                VimOperators.executeX(x.count(), ctx);

            case RecordedChange.ReplaceChange r ->
                VimOperators.executeReplace(r.character(), r.count(), ctx);

            case RecordedChange.ToggleCase tc ->
                VimOperators.executeToggleCase(tc.count(), ctx);

            case RecordedChange.IndentChange ind ->
                VimOperators.executeIndent(ind.dir(), ind.count(), ctx);

            case RecordedChange.Join j ->
                VimOperators.executeJoin(j.count(), ctx);

            case RecordedChange.OpenLine ol ->
                VimOperators.executeOpenLine(ol.direction(), ctx);

            case RecordedChange.OperatorChange om ->
                VimOperators.executeOperatorMotion(om.op(), om.motion(), om.count(), ctx);

            case RecordedChange.OperatorFindChange of ->
                VimOperators.executeOperatorFind(
                        of.op(), of.find(), of.character(), of.count(), ctx);

            case RecordedChange.OperatorTextObjChange ot ->
                VimOperators.executeOperatorTextObj(
                        ot.op(), ot.scope(), ot.objType(), ot.count(), ctx);
        }
    }

    // =========================================================================
    // ExtendedCursor — extends Cursor with the additional methods called by
    // VimOperators (findCharacter, atOffset, isAtStart)
    // =========================================================================

    /**
     * Extension of {@link VimMotions.Cursor} adding the methods that
     * {@link VimOperators} calls but which are not declared on the base interface.
     *
     * In the TypeScript source these methods exist on the concrete {@code Cursor}
     * utility class; the Java translation should eventually add them to the
     * {@code VimMotions.Cursor} interface. Until then this local extension
     * satisfies the compiler.
     */
    public interface ExtendedCursor extends VimOperators.Cursor {
        boolean isAtStart();
    }

    // =========================================================================
    // OffsetCursor — minimal Cursor adapter over plain String + int offset
    // =========================================================================

    /**
     * Minimal {@link ExtendedCursor} implementation backed by a plain
     * {@code String} and integer offset.
     *
     * This is the simplest possible adapter: most navigation methods move the
     * offset by one character. The full implementation would replicate the
     * grapheme-cluster-aware {@code Cursor} class from {@code src/utils/Cursor.ts}.
     */
    private static class OffsetCursor implements ExtendedCursor {

        private final String text;
        private final int offset;

        OffsetCursor(String text, int offset) {
            this.text   = text != null ? text : "";
            this.offset = Math.max(0, Math.min(offset, this.text.length()));
        }

        @Override public int offset() { return offset; }

        @Override public boolean isAtEnd() {
            return offset >= text.length();
        }

        @Override public boolean isAtStart() {
            return offset == 0;
        }

        @Override public OffsetCursor atOffset(int newOffset) {
            return new OffsetCursor(text, newOffset);
        }

        @Override public int nextOffset(int o) {
            return Math.min(o + 1, text.length());
        }

        @Override public VimOperators.Position getPosition() {
            int sol = startOfCurrentLine();
            int line = 0;
            for (int i = 0; i < sol; i++) { if (text.charAt(i) == '\n') line++; }
            return new VimOperators.Position(line, offset - sol);
        }

        @Override public int snapOutOfImageRef(int off, String side) {
            return off;
        }

        @Override public OffsetCursor left() {
            return new OffsetCursor(text, Math.max(0, offset - 1));
        }

        @Override public OffsetCursor right() {
            return new OffsetCursor(text, Math.min(text.length(), offset + 1));
        }

        @Override public OffsetCursor down() {
            return downLogicalLine();
        }

        @Override public OffsetCursor up() {
            return upLogicalLine();
        }

        @Override public OffsetCursor downLogicalLine() {
            int eol = endOfCurrentLine();
            if (eol >= text.length()) return this;
            int nextSol = eol + 1;
            int colInLine = offset - startOfCurrentLine();
            int nextEol = text.indexOf('\n', nextSol);
            int nextLineEnd = nextEol == -1 ? text.length() : nextEol;
            return new OffsetCursor(text, Math.min(nextSol + colInLine, nextLineEnd));
        }

        @Override public OffsetCursor upLogicalLine() {
            int sol = startOfCurrentLine();
            if (sol == 0) return this;
            int prevEol = sol - 1;
            int prevSol = text.lastIndexOf('\n', prevEol - 1) + 1;
            int colInLine = offset - sol;
            return new OffsetCursor(text, Math.min(prevSol + colInLine, prevEol));
        }

        @Override public OffsetCursor nextVimWord() {
            int pos = offset;
            while (pos < text.length() && isWordChar(text.charAt(pos))) pos++;
            while (pos < text.length() && !isWordChar(text.charAt(pos)) && text.charAt(pos) != '\n') pos++;
            return new OffsetCursor(text, pos);
        }

        @Override public OffsetCursor prevVimWord() {
            int pos = offset - 1;
            while (pos > 0 && !isWordChar(text.charAt(pos - 1))) pos--;
            while (pos > 0 && isWordChar(text.charAt(pos - 1))) pos--;
            return new OffsetCursor(text, Math.max(0, pos));
        }

        @Override public OffsetCursor endOfVimWord() {
            int pos = offset + 1;
            while (pos < text.length() && !isWordChar(text.charAt(pos))) pos++;
            while (pos < text.length() - 1 && isWordChar(text.charAt(pos + 1))) pos++;
            return new OffsetCursor(text, Math.min(pos, text.length() - 1));
        }

        @Override public OffsetCursor nextWORD() {
            int pos = offset;
            while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) pos++;
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
            return new OffsetCursor(text, pos);
        }

        @Override public OffsetCursor prevWORD() {
            int pos = offset - 1;
            while (pos > 0 && Character.isWhitespace(text.charAt(pos - 1))) pos--;
            while (pos > 0 && !Character.isWhitespace(text.charAt(pos - 1))) pos--;
            return new OffsetCursor(text, Math.max(0, pos));
        }

        @Override public OffsetCursor endOfWORD() {
            int pos = offset + 1;
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
            while (pos < text.length() - 1 && !Character.isWhitespace(text.charAt(pos + 1))) pos++;
            return new OffsetCursor(text, Math.min(pos, text.length() - 1));
        }

        @Override public OffsetCursor startOfLogicalLine() {
            return new OffsetCursor(text, startOfCurrentLine());
        }

        @Override public OffsetCursor firstNonBlankInLogicalLine() {
            int sol = startOfCurrentLine();
            int pos = sol;
            while (pos < text.length() && text.charAt(pos) == ' ') pos++;
            return new OffsetCursor(text, pos);
        }

        @Override public OffsetCursor endOfLogicalLine() {
            return new OffsetCursor(text, endOfCurrentLine());
        }

        @Override public OffsetCursor startOfLastLine() {
            int lastNl = text.lastIndexOf('\n');
            return new OffsetCursor(text, lastNl == -1 ? 0 : lastNl + 1);
        }

        @Override public OffsetCursor startOfFirstLine() {
            return new OffsetCursor(text, 0);
        }

        @Override public OffsetCursor goToLine(int lineNumber) {
            String[] lines = text.split("\n", -1);
            int target = Math.max(0, Math.min(lineNumber - 1, lines.length - 1));
            int pos = 0;
            for (int i = 0; i < target; i++) pos += lines[i].length() + 1;
            return new OffsetCursor(text, pos);
        }

        @Override
        public Integer findCharacter(String character, FindType findType, int count) {
            if (character == null || character.isEmpty()) return null;
            char ch = character.charAt(0);
            int pos = offset;

            for (int i = 0; i < count; i++) {
                int found = switch (findType) {
                    case F -> { // forward to char
                        int p = pos + 1;
                        while (p < text.length() && text.charAt(p) != ch) p++;
                        yield p < text.length() ? p : -1;
                    }
                    case UPPER_F -> { // backward to char
                        int p = pos - 1;
                        while (p >= 0 && text.charAt(p) != ch) p--;
                        yield p >= 0 ? p : -1;
                    }
                    case T -> { // forward till (one before)
                        int p = pos + 1;
                        while (p < text.length() && text.charAt(p) != ch) p++;
                        yield p < text.length() ? p - 1 : -1;
                    }
                    case UPPER_T -> { // backward till (one after)
                        int p = pos - 1;
                        while (p >= 0 && text.charAt(p) != ch) p--;
                        yield p >= 0 ? p + 1 : -1;
                    }
                };
                if (found == -1) return null;
                pos = found;
            }
            return pos;
        }

        private int startOfCurrentLine() {
            int pos = offset;
            while (pos > 0 && text.charAt(pos - 1) != '\n') pos--;
            return pos;
        }

        private int endOfCurrentLine() {
            int pos = offset;
            while (pos < text.length() && text.charAt(pos) != '\n') pos++;
            return pos;
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof OffsetCursor other)) return false;
            return offset == other.offset && text.equals(other.text);
        }

        @Override public int hashCode() {
            return 31 * text.hashCode() + offset;
        }
    }
}

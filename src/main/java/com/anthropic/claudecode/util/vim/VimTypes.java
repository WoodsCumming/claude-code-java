package com.anthropic.claudecode.util.vim;

import java.util.Map;
import java.util.Set;

/**
 * Vim Mode State Machine Types.
 *
 * <p>Defines the complete state machine for vim input handling.
 * The types ARE the documentation — reading them tells you how the system works.
 *
 * <p>State Diagram:
 * <pre>
 *                              VimState
 *   ┌──────────────────────────────┬──────────────────────────────────────┐
 *   │  INSERT                      │  NORMAL                              │
 *   │  (tracks insertedText)       │  (CommandState machine)              │
 *   │                              │                                      │
 *   │                              │  idle ──┬─[d/c/y]──► operator        │
 *   │                              │         ├─[1-9]────► count           │
 *   │                              │         ├─[fFtT]───► find            │
 *   │                              │         ├─[g]──────► g               │
 *   │                              │         ├─[r]──────► replace         │
 *   │                              │         └─[><]─────► indent          │
 *   │                              │                                      │
 *   │                              │  operator ─┬─[motion]──► execute     │
 *   │                              │            ├─[0-9]────► operatorCount│
 *   │                              │            ├─[ia]─────► operatorTextObj
 *   │                              │            └─[fFtT]───► operatorFind │
 *   └──────────────────────────────┴──────────────────────────────────────┘
 * </pre>
 *
 * <p>Translated from: src/vim/types.ts
 */
public final class VimTypes {

    private VimTypes() {}

    // =========================================================================
    // Core Enums
    // =========================================================================

    /** The three vim operators. */
    public enum Operator {
        DELETE, CHANGE, YANK;

        /** Returns the single-character key associated with this operator. */
        public String key() {
            return switch (this) {
                case DELETE -> "d";
                case CHANGE -> "c";
                case YANK -> "y";
            };
        }
    }

    /** Find / till directions. */
    public enum FindType {
        F,   // forward to char
        UPPER_F, // backward to char
        T,   // forward till char
        UPPER_T  // backward till char
        ;

        /** Returns the single-character key for this find type. */
        public String key() {
            return switch (this) {
                case F -> "f";
                case UPPER_F -> "F";
                case T -> "t";
                case UPPER_T -> "T";
            };
        }

        public static FindType fromKey(String key) {
            return switch (key) {
                case "f" -> F;
                case "F" -> UPPER_F;
                case "t" -> T;
                case "T" -> UPPER_T;
                default -> throw new IllegalArgumentException("Unknown find key: " + key);
            };
        }
    }

    /** Whether a text-object operation targets the inner or surrounding (around) region. */
    public enum TextObjScope {
        INNER, AROUND;

        public static TextObjScope fromKey(String key) {
            return switch (key) {
                case "i" -> INNER;
                case "a" -> AROUND;
                default -> throw new IllegalArgumentException("Unknown text-obj scope key: " + key);
            };
        }
    }

    // =========================================================================
    // VimState — sealed hierarchy (INSERT | NORMAL)
    // =========================================================================

    /** Complete vim state. Mode determines what data is tracked. */
    public sealed interface VimState permits VimState.Insert, VimState.Normal {
        /** INSERT mode: tracks text being typed (for dot-repeat). */
        record Insert(String insertedText) implements VimState {}

        /** NORMAL mode: tracks the command being parsed (state machine). */
        record Normal(CommandState command) implements VimState {}
    }

    // =========================================================================
    // CommandState — sealed hierarchy for NORMAL mode parsing
    // =========================================================================

    /**
     * Command state machine for NORMAL mode.
     * Each state knows exactly what input it is waiting for.
     */
    public sealed interface CommandState
            permits CommandState.Idle,
                    CommandState.Count,
                    CommandState.OperatorState,
                    CommandState.OperatorCount,
                    CommandState.OperatorFind,
                    CommandState.OperatorTextObj,
                    CommandState.Find,
                    CommandState.G,
                    CommandState.OperatorG,
                    CommandState.Replace,
                    CommandState.Indent {

        record Idle() implements CommandState {}

        record Count(String digits) implements CommandState {}

        record OperatorState(Operator op, int count) implements CommandState {}

        record OperatorCount(Operator op, int count, String digits) implements CommandState {}

        record OperatorFind(Operator op, int count, FindType find) implements CommandState {}

        record OperatorTextObj(Operator op, int count, TextObjScope scope) implements CommandState {}

        record Find(FindType find, int count) implements CommandState {}

        record G(int count) implements CommandState {}

        record OperatorG(Operator op, int count) implements CommandState {}

        record Replace(int count) implements CommandState {}

        record Indent(String dir, int count) implements CommandState {} // dir is ">" or "<"
    }

    // =========================================================================
    // PersistentState
    // =========================================================================

    /**
     * Persistent state that survives across commands.
     * This is the "memory" of vim — what gets recalled for repeats and pastes.
     */
    public record PersistentState(
            RecordedChange lastChange,
            LastFind lastFind,
            String register,
            boolean registerIsLinewise
    ) {
        public record LastFind(FindType type, String character) {}
    }

    // =========================================================================
    // RecordedChange — sealed hierarchy for dot-repeat
    // =========================================================================

    /**
     * Recorded change for dot-repeat.
     * Captures everything needed to replay a command.
     */
    public sealed interface RecordedChange
            permits RecordedChange.Insert,
                    RecordedChange.OperatorChange,
                    RecordedChange.OperatorTextObjChange,
                    RecordedChange.OperatorFindChange,
                    RecordedChange.ReplaceChange,
                    RecordedChange.X,
                    RecordedChange.ToggleCase,
                    RecordedChange.IndentChange,
                    RecordedChange.OpenLine,
                    RecordedChange.Join {

        record Insert(String text) implements RecordedChange {}

        record OperatorChange(Operator op, String motion, int count) implements RecordedChange {}

        record OperatorTextObjChange(Operator op, String objType, TextObjScope scope, int count)
                implements RecordedChange {}

        record OperatorFindChange(Operator op, FindType find, String character, int count)
                implements RecordedChange {}

        record ReplaceChange(String character, int count) implements RecordedChange {}

        record X(int count) implements RecordedChange {}

        record ToggleCase(int count) implements RecordedChange {}

        record IndentChange(String dir, int count) implements RecordedChange {} // dir is ">" or "<"

        record OpenLine(String direction) implements RecordedChange {} // "above" | "below"

        record Join(int count) implements RecordedChange {}
    }

    // =========================================================================
    // Key Groups — named constants, no magic strings
    // =========================================================================

    /** Maps single-character operator keys to their {@link Operator} values. */
    public static final Map<String, Operator> OPERATORS = Map.of(
            "d", Operator.DELETE,
            "c", Operator.CHANGE,
            "y", Operator.YANK
    );

    public static boolean isOperatorKey(String key) {
        return OPERATORS.containsKey(key);
    }

    /** Simple motions that move the cursor without requiring a second key. */
    public static final Set<String> SIMPLE_MOTIONS = Set.of(
            "h", "l", "j", "k",            // Basic movement
            "w", "b", "e", "W", "B", "E",  // Word motions
            "0", "^", "$"                  // Line positions
    );

    /** Find / till direction keys. */
    public static final Set<String> FIND_KEYS = Set.of("f", "F", "t", "T");

    /** Maps text-object scope keys to their {@link TextObjScope} values. */
    public static final Map<String, TextObjScope> TEXT_OBJ_SCOPES = Map.of(
            "i", TextObjScope.INNER,
            "a", TextObjScope.AROUND
    );

    public static boolean isTextObjScopeKey(String key) {
        return TEXT_OBJ_SCOPES.containsKey(key);
    }

    /** All supported text-object type characters. */
    public static final Set<String> TEXT_OBJ_TYPES = Set.of(
            "w", "W",           // Word / WORD
            "\"", "'", "`",     // Quotes
            "(", ")", "b",      // Parens
            "[", "]",           // Brackets
            "{", "}", "B",      // Braces
            "<", ">"            // Angle brackets
    );

    /** Maximum count that vim commands will honour (guards against huge multipliers). */
    public static final int MAX_VIM_COUNT = 10_000;

    // =========================================================================
    // State Factories
    // =========================================================================

    /** Creates the initial {@link VimState} (INSERT mode with no typed text). */
    public static VimState createInitialVimState() {
        return new VimState.Insert("");
    }

    /** Creates the initial {@link PersistentState} (all empty / false). */
    public static PersistentState createInitialPersistentState() {
        return new PersistentState(null, null, "", false);
    }
}

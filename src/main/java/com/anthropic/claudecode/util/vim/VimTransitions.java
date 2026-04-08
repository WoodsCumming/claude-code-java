package com.anthropic.claudecode.util.vim;

import com.anthropic.claudecode.util.vim.VimTypes.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Vim State Transition Table.
 *
 * <p>This is the scannable source of truth for state transitions.
 * To understand what happens in any state, look up that state's transition function.
 *
 * <p>Translated from: src/vim/transitions.ts
 */
public final class VimTransitions {

    private VimTransitions() {}

    // =========================================================================
    // TransitionContext
    // =========================================================================

    /**
     * Context passed to transition functions.
     * Extends {@link VimOperators.OperatorContext} with undo and dot-repeat hooks.
     */
    public interface TransitionContext extends VimOperators.OperatorContext {
        /** Called when the user presses {@code u} (undo). May be {@code null}. */
        default void onUndo() {}

        /** Called when the user presses {@code .} (dot-repeat). May be a no-op. */
        default void onDotRepeat() {}
    }

    // =========================================================================
    // TransitionResult
    // =========================================================================

    /**
     * Result of a transition.
     *
     * <p>Exactly one of {@code next} and {@code execute} will be set, or neither
     * (meaning the input was consumed but no state change or action is needed).
     *
     * @param next    the next {@link CommandState} to transition into, or {@code null}
     * @param execute an action to run immediately, or {@code null}
     */
    public record TransitionResult(CommandState next, Runnable execute) {
        /** An empty result — no state change and no action. */
        public static final TransitionResult EMPTY = new TransitionResult(null, null);

        /** Convenience factory: result that only carries a state change. */
        public static TransitionResult nextState(CommandState state) {
            return new TransitionResult(state, null);
        }

        /** Convenience factory: result that only carries an execute action. */
        public static TransitionResult execute(Runnable action) {
            return new TransitionResult(null, action);
        }
    }

    // =========================================================================
    // Main dispatch
    // =========================================================================

    /**
     * Main transition function — dispatches based on current state type.
     *
     * @param state the current command state
     * @param input the key(s) pressed (single key or multi-key like "gj")
     * @param ctx   execution context
     * @return the transition result
     */
    public static TransitionResult transition(
            CommandState state, String input, TransitionContext ctx) {

        return switch (state) {
            case CommandState.Idle ignored       -> fromIdle(input, ctx);
            case CommandState.Count s            -> fromCount(s, input, ctx);
            case CommandState.OperatorState s    -> fromOperator(s, input, ctx);
            case CommandState.OperatorCount s    -> fromOperatorCount(s, input, ctx);
            case CommandState.OperatorFind s     -> fromOperatorFind(s, input, ctx);
            case CommandState.OperatorTextObj s  -> fromOperatorTextObj(s, input, ctx);
            case CommandState.Find s             -> fromFind(s, input, ctx);
            case CommandState.G s                -> fromG(s, input, ctx);
            case CommandState.OperatorG s        -> fromOperatorG(s, input, ctx);
            case CommandState.Replace s          -> fromReplace(s, input, ctx);
            case CommandState.Indent s           -> fromIndent(s, input, ctx);
        };
    }

    // =========================================================================
    // Shared input handling
    // =========================================================================

    /**
     * Handle input that is valid in both {@code idle} and {@code count} states.
     *
     * @return a {@link TransitionResult}, or {@code null} if input is not recognised
     */
    private static TransitionResult handleNormalInput(
            String input, int count, TransitionContext ctx) {

        if (VimTypes.isOperatorKey(input)) {
            Operator op = VimTypes.OPERATORS.get(input);
            return TransitionResult.nextState(new CommandState.OperatorState(op, count));
        }

        if (VimTypes.SIMPLE_MOTIONS.contains(input)) {
            return TransitionResult.execute(() -> {
                VimMotions.Cursor target = VimMotions.resolveMotion(input, ctx.cursor(), count);
                ctx.setOffset(target.offset());
            });
        }

        if (VimTypes.FIND_KEYS.contains(input)) {
            FindType find = FindType.fromKey(input);
            return TransitionResult.nextState(new CommandState.Find(find, count));
        }

        if ("g".equals(input))  return TransitionResult.nextState(new CommandState.G(count));
        if ("r".equals(input))  return TransitionResult.nextState(new CommandState.Replace(count));
        if (">".equals(input) || "<".equals(input)) {
            return TransitionResult.nextState(new CommandState.Indent(input, count));
        }
        if ("~".equals(input)) {
            return TransitionResult.execute(() -> VimOperators.executeToggleCase(count, ctx));
        }
        if ("x".equals(input)) {
            return TransitionResult.execute(() -> VimOperators.executeX(count, ctx));
        }
        if ("J".equals(input)) {
            return TransitionResult.execute(() -> VimOperators.executeJoin(count, ctx));
        }
        if ("p".equals(input) || "P".equals(input)) {
            boolean after = "p".equals(input);
            return TransitionResult.execute(() -> VimOperators.executePaste(after, count, ctx));
        }
        if ("D".equals(input)) {
            return TransitionResult.execute(
                    () -> VimOperators.executeOperatorMotion(Operator.DELETE, "$", 1, ctx));
        }
        if ("C".equals(input)) {
            return TransitionResult.execute(
                    () -> VimOperators.executeOperatorMotion(Operator.CHANGE, "$", 1, ctx));
        }
        if ("Y".equals(input)) {
            return TransitionResult.execute(() -> VimOperators.executeLineOp(Operator.YANK, count, ctx));
        }
        if ("G".equals(input)) {
            return TransitionResult.execute(() -> {
                if (count == 1) {
                    ctx.setOffset(ctx.cursor().startOfLastLine().offset());
                } else {
                    ctx.setOffset(ctx.cursor().goToLine(count).offset());
                }
            });
        }
        if (".".equals(input)) {
            return TransitionResult.execute(ctx::onDotRepeat);
        }
        if (";".equals(input) || ",".equals(input)) {
            boolean reverse = ",".equals(input);
            return TransitionResult.execute(() -> executeRepeatFind(reverse, count, ctx));
        }
        if ("u".equals(input)) {
            return TransitionResult.execute(ctx::onUndo);
        }
        if ("i".equals(input)) {
            return TransitionResult.execute(() -> ctx.enterInsert(ctx.cursor().offset()));
        }
        if ("I".equals(input)) {
            return TransitionResult.execute(() ->
                    ctx.enterInsert(ctx.cursor().firstNonBlankInLogicalLine().offset()));
        }
        if ("a".equals(input)) {
            return TransitionResult.execute(() -> {
                int newOffset = ctx.cursor().isAtEnd()
                        ? ctx.cursor().offset()
                        : ctx.cursor().right().offset();
                ctx.enterInsert(newOffset);
            });
        }
        if ("A".equals(input)) {
            return TransitionResult.execute(() ->
                    ctx.enterInsert(ctx.cursor().endOfLogicalLine().offset()));
        }
        if ("o".equals(input)) {
            return TransitionResult.execute(() -> VimOperators.executeOpenLine("below", ctx));
        }
        if ("O".equals(input)) {
            return TransitionResult.execute(() -> VimOperators.executeOpenLine("above", ctx));
        }

        return null;
    }

    /**
     * Handle operator input (motion, find, text-object scope).
     *
     * @return a {@link TransitionResult}, or {@code null} if input is not recognised
     */
    private static TransitionResult handleOperatorInput(
            Operator op, int count, String input, TransitionContext ctx) {

        if (VimTypes.isTextObjScopeKey(input)) {
            TextObjScope scope = VimTypes.TEXT_OBJ_SCOPES.get(input);
            return TransitionResult.nextState(
                    new CommandState.OperatorTextObj(op, count, scope));
        }

        if (VimTypes.FIND_KEYS.contains(input)) {
            FindType find = FindType.fromKey(input);
            return TransitionResult.nextState(
                    new CommandState.OperatorFind(op, count, find));
        }

        if (VimTypes.SIMPLE_MOTIONS.contains(input)) {
            return TransitionResult.execute(
                    () -> VimOperators.executeOperatorMotion(op, input, count, ctx));
        }

        if ("G".equals(input)) {
            return TransitionResult.execute(() -> VimOperators.executeOperatorG(op, count, ctx));
        }

        if ("g".equals(input)) {
            return TransitionResult.nextState(new CommandState.OperatorG(op, count));
        }

        return null;
    }

    // =========================================================================
    // Transition functions — one per state type
    // =========================================================================

    private static TransitionResult fromIdle(String input, TransitionContext ctx) {
        // '0' is a line-start motion, not a count prefix
        if (input.matches("[1-9]")) {
            return TransitionResult.nextState(new CommandState.Count(input));
        }
        if ("0".equals(input)) {
            return TransitionResult.execute(
                    () -> ctx.setOffset(ctx.cursor().startOfLogicalLine().offset()));
        }

        TransitionResult result = handleNormalInput(input, 1, ctx);
        return result != null ? result : TransitionResult.EMPTY;
    }

    private static TransitionResult fromCount(
            CommandState.Count state, String input, TransitionContext ctx) {

        if (input.matches("[0-9]")) {
            String newDigits = state.digits() + input;
            int count = Math.min(Integer.parseInt(newDigits), VimTypes.MAX_VIM_COUNT);
            return TransitionResult.nextState(new CommandState.Count(String.valueOf(count)));
        }

        int count = Integer.parseInt(state.digits());
        TransitionResult result = handleNormalInput(input, count, ctx);
        if (result != null) return result;

        return TransitionResult.nextState(new CommandState.Idle());
    }

    private static TransitionResult fromOperator(
            CommandState.OperatorState state, String input, TransitionContext ctx) {

        // dd, cc, yy = line operation
        if (input.equals(state.op().key())) {
            return TransitionResult.execute(
                    () -> VimOperators.executeLineOp(state.op(), state.count(), ctx));
        }

        if (input.matches("[0-9]")) {
            return TransitionResult.nextState(new CommandState.OperatorCount(
                    state.op(), state.count(), input));
        }

        TransitionResult result = handleOperatorInput(state.op(), state.count(), input, ctx);
        if (result != null) return result;

        return TransitionResult.nextState(new CommandState.Idle());
    }

    private static TransitionResult fromOperatorCount(
            CommandState.OperatorCount state, String input, TransitionContext ctx) {

        if (input.matches("[0-9]")) {
            String newDigits = state.digits() + input;
            int parsed = Math.min(Integer.parseInt(newDigits), VimTypes.MAX_VIM_COUNT);
            return TransitionResult.nextState(new CommandState.OperatorCount(
                    state.op(), state.count(), String.valueOf(parsed)));
        }

        int motionCount = Integer.parseInt(state.digits());
        int effectiveCount = state.count() * motionCount;
        TransitionResult result = handleOperatorInput(state.op(), effectiveCount, input, ctx);
        if (result != null) return result;

        return TransitionResult.nextState(new CommandState.Idle());
    }

    private static TransitionResult fromOperatorFind(
            CommandState.OperatorFind state, String input, TransitionContext ctx) {

        return TransitionResult.execute(
                () -> VimOperators.executeOperatorFind(
                        state.op(), state.find(), input, state.count(), ctx));
    }

    private static TransitionResult fromOperatorTextObj(
            CommandState.OperatorTextObj state, String input, TransitionContext ctx) {

        if (VimTypes.TEXT_OBJ_TYPES.contains(input)) {
            return TransitionResult.execute(() -> VimOperators.executeOperatorTextObj(
                    state.op(), state.scope(), input, state.count(), ctx));
        }
        return TransitionResult.nextState(new CommandState.Idle());
    }

    private static TransitionResult fromFind(
            CommandState.Find state, String input, TransitionContext ctx) {

        return TransitionResult.execute(() -> {
            Integer result = ctx.cursor().findCharacter(input, state.find(), state.count());
            if (result != null) {
                ctx.setOffset(result);
                ctx.setLastFind(state.find(), input);
            }
        });
    }

    private static TransitionResult fromG(
            CommandState.G state, String input, TransitionContext ctx) {

        if ("j".equals(input) || "k".equals(input)) {
            return TransitionResult.execute(() -> {
                VimMotions.Cursor target = VimMotions.resolveMotion("g" + input, ctx.cursor(), state.count());
                ctx.setOffset(target.offset());
            });
        }

        if ("g".equals(input)) {
            if (state.count() > 1) {
                return TransitionResult.execute(() -> {
                    String[] lines = ctx.text().split("\n", -1);
                    int targetLine = Math.min(state.count() - 1, lines.length - 1);
                    int offset = 0;
                    for (int i = 0; i < targetLine; i++) {
                        offset += lines[i].length() + 1; // +1 for '\n'
                    }
                    ctx.setOffset(offset);
                });
            }
            return TransitionResult.execute(
                    () -> ctx.setOffset(ctx.cursor().startOfFirstLine().offset()));
        }

        return TransitionResult.nextState(new CommandState.Idle());
    }

    private static TransitionResult fromOperatorG(
            CommandState.OperatorG state, String input, TransitionContext ctx) {

        if ("j".equals(input) || "k".equals(input)) {
            return TransitionResult.execute(
                    () -> VimOperators.executeOperatorMotion(state.op(), "g" + input, state.count(), ctx));
        }
        if ("g".equals(input)) {
            return TransitionResult.execute(
                    () -> VimOperators.executeOperatorGg(state.op(), state.count(), ctx));
        }
        return TransitionResult.nextState(new CommandState.Idle());
    }

    private static TransitionResult fromReplace(
            CommandState.Replace state, String input, TransitionContext ctx) {

        // Backspace/Delete arrive as empty input in literal-char states.
        // r<BS> cancels the replace.
        if (input.isEmpty()) return TransitionResult.nextState(new CommandState.Idle());
        return TransitionResult.execute(
                () -> VimOperators.executeReplace(input, state.count(), ctx));
    }

    private static TransitionResult fromIndent(
            CommandState.Indent state, String input, TransitionContext ctx) {

        if (input.equals(state.dir())) {
            return TransitionResult.execute(
                    () -> VimOperators.executeIndent(state.dir(), state.count(), ctx));
        }
        return TransitionResult.nextState(new CommandState.Idle());
    }

    // =========================================================================
    // Helper: repeat find
    // =========================================================================

    /**
     * Repeat the last find / till in the same or reversed direction.
     *
     * @param reverse {@code true} for {@code ,} (reverse), {@code false} for {@code ;}
     */
    private static void executeRepeatFind(boolean reverse, int count, TransitionContext ctx) {
        PersistentState.LastFind lastFind = ctx.getLastFind();
        if (lastFind == null) return;

        FindType findType = lastFind.type();
        if (reverse) {
            // Flip the direction
            findType = switch (findType) {
                case F       -> FindType.UPPER_F;
                case UPPER_F -> FindType.F;
                case T       -> FindType.UPPER_T;
                case UPPER_T -> FindType.T;
            };
        }

        Integer result = ctx.cursor().findCharacter(lastFind.character(), findType, count);
        if (result != null) {
            ctx.setOffset(result);
        }
    }

    // =========================================================================
    // Cursor extension (for callers that use VimTransitions)
    // =========================================================================

    /**
     * Extended cursor interface required by {@link TransitionContext}.
     * Delegates to both {@link VimMotions.Cursor} and {@link VimOperators.Cursor}.
     */
    public interface Cursor extends VimOperators.Cursor {}
}

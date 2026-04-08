package com.anthropic.claudecode.model;

/**
 * Vim mode state machine types.
 * Translated from src/vim/types.ts
 *
 * Defines the complete state machine for vim input handling.
 */
public class VimTypes {

    public enum VimMode {
        INSERT("INSERT"),
        NORMAL("NORMAL");

        private final String value;
        VimMode(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    public enum CommandState {
        IDLE("idle"),
        OPERATOR("operator"),
        COUNT("count"),
        FIND("find"),
        G("g"),
        REPLACE("replace"),
        INDENT("indent"),
        OPERATOR_COUNT("operatorCount"),
        OPERATOR_TEXT_OBJ("operatorTextObj"),
        OPERATOR_FIND("operatorFind");

        private final String value;
        CommandState(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Vim state machine state.
     */
    public static class VimState {
        private VimMode mode = VimMode.INSERT;
        private String insertedText = "";
        private CommandState commandState = CommandState.IDLE;
        private int count = 0;
        private String operator;
        private Character findChar;
        private boolean findForward = true;

        public VimMode getMode() { return mode; }
        public void setMode(VimMode mode) { this.mode = mode; }
        public String getInsertedText() { return insertedText; }
        public void setInsertedText(String text) { this.insertedText = text; }
        public CommandState getCommandState() { return commandState; }
        public void setCommandState(CommandState state) { this.commandState = state; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public Character getFindChar() { return findChar; }
        public void setFindChar(Character c) { this.findChar = c; }
        public boolean isFindForward() { return findForward; }
        public void setFindForward(boolean forward) { this.findForward = forward; }

        public boolean isInsertMode() { return mode == VimMode.INSERT; }
        public boolean isNormalMode() { return mode == VimMode.NORMAL; }
    }

    private VimTypes() {}
}

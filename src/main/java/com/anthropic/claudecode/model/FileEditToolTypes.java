package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Optional;

/**
 * Types for the FileEditTool.
 * Translated from src/tools/FileEditTool/types.ts
 */
public final class FileEditToolTypes {

    private FileEditToolTypes() {}

    /**
     * Full file-edit input including file_path.
     */
    @Data
    @Builder
    public static class FileEditInput {
        private String filePath;
        private String oldString;
        private String newString;
        @Builder.Default
        private boolean replaceAll = false;
    }

    /**
     * Individual edit without file_path.
     */
    @Data
    @Builder
    public static class EditInput {
        private String oldString;
        private String newString;
        @Builder.Default
        private boolean replaceAll = false;
    
        public String getOldString() { return oldString; }
    
        public String getNewString() { return newString; }
    
        public boolean isReplaceAll() { return replaceAll; }
    
        public static EditInputBuilder builder() { return new EditInputBuilder(); }
        public static class EditInputBuilder {
            private String oldString;
            private String newString;
            private boolean replaceAll;
            public EditInputBuilder oldString(String v) { this.oldString = v; return this; }
            public EditInputBuilder newString(String v) { this.newString = v; return this; }
            public EditInputBuilder replaceAll(boolean v) { this.replaceAll = v; return this; }
            public EditInput build() {
                EditInput o = new EditInput();
                o.oldString = oldString;
                o.newString = newString;
                o.replaceAll = replaceAll;
                return o;
            }
        }
    

        public EditInput() {}
    }

    /**
     * Runtime version where replaceAll is always defined.
     */
    public static class FileEdit {
        private String oldString;
        private String newString;
        private boolean replaceAll = false;

        public FileEdit() {}
        public FileEdit(String oldString, String newString, boolean replaceAll) {
            this.oldString = oldString; this.newString = newString; this.replaceAll = replaceAll;
        }
        public String getOldString() { return oldString; }
        public void setOldString(String v) { oldString = v; }
        public String getNewString() { return newString; }
        public void setNewString(String v) { newString = v; }
        public boolean isReplaceAll() { return replaceAll; }
        public void setReplaceAll(boolean v) { replaceAll = v; }

        public static FileEditBuilder builder() { return new FileEditBuilder(); }
        public static class FileEditBuilder {
            private String oldString;
            private String newString;
            private boolean replaceAll = false;
            public FileEditBuilder oldString(String v) { this.oldString = v; return this; }
            public FileEditBuilder newString(String v) { this.newString = v; return this; }
            public FileEditBuilder replaceAll(boolean v) { this.replaceAll = v; return this; }
            public FileEdit build() { return new FileEdit(oldString, newString, replaceAll); }
        }
    }

    /**
     * A single diff hunk (mirrors StructuredPatchHunk from the 'diff' npm package).
     */
    @Data
    @Builder
    public static class StructuredPatchHunk {
        private int oldStart;
        private int oldLines;
        private int newStart;
        private int newLines;
        private List<String> lines;
    
        public List<String> getLines() { return lines; }
    }

    /**
     * Git diff information for a file.
     */
    @Data
    @Builder
    public static class GitDiff {
        private String filename;
        private String status; // "modified" | "added"
        private int additions;
        private int deletions;
        private int changes;
        private String patch;
        private Optional<String> repository; // GitHub owner/repo when available
    }

    /**
     * Output of the FileEditTool.
     */
    @Data
    @Builder
    public static class FileEditOutput {
        private String filePath;
        private String oldString;
        private String newString;
        private String originalFile;
        private List<StructuredPatchHunk> structuredPatch;
        private boolean userModified;
        private boolean replaceAll;
        private Optional<GitDiff> gitDiff;
    }
}

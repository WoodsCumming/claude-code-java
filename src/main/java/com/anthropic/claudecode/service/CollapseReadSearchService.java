package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Service for collapsing consecutive read/search tool uses in the UI.
 * Translated from src/utils/collapseReadSearch.ts
 *
 * Groups consecutive read/search operations (Read, Glob, Grep) into
 * collapsible groups for a cleaner UI display.
 */
@Slf4j
@Service
public class CollapseReadSearchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CollapseReadSearchService.class);


    public static class SearchOrReadResult {
        public boolean collapsible;
        public boolean search;
        public boolean read;
        public boolean list;
        public boolean repl;
        public boolean memoryWrite;
        public boolean absorbedSilently;
        public SearchOrReadResult() {}
        public SearchOrReadResult(boolean collapsible, boolean search, boolean read, boolean list,
                boolean repl, boolean memoryWrite, boolean absorbedSilently) {
            this.collapsible = collapsible;
            this.search = search;
            this.read = read;
            this.list = list;
            this.repl = repl;
            this.memoryWrite = memoryWrite;
            this.absorbedSilently = absorbedSilently;
        }
        public boolean isCollapsible() { return collapsible; }
        public boolean isSearch() { return search; }
        public boolean isRead() { return read; }
        public boolean isList() { return list; }
        public boolean isRepl() { return repl; }
        public boolean isMemoryWrite() { return memoryWrite; }
        public boolean isAbsorbedSilently() { return absorbedSilently; }
    }

    /**
     * Check if a tool use is a search or read operation.
     * Translated from isSearchOrRead() in collapseReadSearch.ts
     */
    public SearchOrReadResult isSearchOrRead(String toolName, Map<String, Object> toolInput) {
        if (toolName == null) {
            return new SearchOrReadResult(false, false, false, false, false, false, false);
        }

        boolean isRead = "Read".equals(toolName);
        boolean isSearch = "Grep".equals(toolName) || "Glob".equals(toolName)
            || "WebSearch".equals(toolName) || "WebFetch".equals(toolName);
        boolean isList = "Glob".equals(toolName);
        boolean isRepl = "REPL".equals(toolName);
        boolean isToolSearch = "ToolSearch".equals(toolName);

        boolean isCollapsible = isRead || isSearch || isRepl || isToolSearch;

        return new SearchOrReadResult(
            isCollapsible, isSearch, isRead, isList, isRepl, false, isToolSearch
        );
    }

    /**
     * Check if a tool use is a write operation.
     */
    public boolean isWriteOperation(String toolName) {
        return "Write".equals(toolName) || "Edit".equals(toolName)
            || "NotebookEdit".equals(toolName);
    }

    /**
     * Check if a tool use is an edit operation.
     */
    public boolean isEditOperation(String toolName) {
        return "Edit".equals(toolName) || "Write".equals(toolName);
    }
}

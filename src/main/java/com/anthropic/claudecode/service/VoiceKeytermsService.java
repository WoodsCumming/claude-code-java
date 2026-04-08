package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Voice keyterms service for improving STT accuracy in the voice_stream endpoint.
 * Translated from src/services/voiceKeyterms.ts
 *
 * Provides domain-specific vocabulary hints (Deepgram "keywords") so the STT
 * engine correctly recognises coding terminology, project names, and branch
 * names that would otherwise be misheard.
 */
@Slf4j
@Service
public class VoiceKeytermsService {



    // ── Global keyterms ───────────────────────────────────────────────────────

    /**
     * Hardcoded global coding terms. "Claude" and "Anthropic" are already
     * server-side base keyterms. Terms nobody speaks aloud as-spelled are omitted
     * (e.g. stdout → "standard out").
     * Translated from GLOBAL_KEYTERMS in voiceKeyterms.ts
     */
    private static final List<String> GLOBAL_KEYTERMS = List.of(
            "MCP",
            "symlink",
            "grep",
            "regex",
            "localhost",
            "codebase",
            "TypeScript",
            "JSON",
            "OAuth",
            "webhook",
            "gRPC",
            "dotfiles",
            "subagent",
            "worktree"
    );

    private static final int MAX_KEYTERMS = 50;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Split an identifier (camelCase, PascalCase, kebab-case, snake_case, or path
     * segments) into individual words. Fragments of 2 chars or fewer are discarded.
     * Translated from splitIdentifier() in voiceKeyterms.ts
     *
     * @param name identifier string to split
     * @return list of non-trivial word fragments (3–20 chars)
     */
    public List<String> splitIdentifier(String name) {
        // Insert space between lowercase→uppercase transitions (camelCase / PascalCase)
        String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2");
        List<String> parts = new ArrayList<>();
        for (String part : spaced.split("[-_./\\s]+")) {
            String w = part.strip();
            if (w.length() > 2 && w.length() <= 20) {
                parts.add(w);
            }
        }
        return parts;
    }

    /**
     * Extract meaningful words from a file path's stem (basename without extension).
     * Translated from fileNameWords() in voiceKeyterms.ts
     */
    private List<String> fileNameWords(String filePath) {
        String base = new File(filePath).getName();
        // Remove extension
        int dot = base.lastIndexOf('.');
        String stem = dot >= 0 ? base.substring(0, dot) : base;
        return splitIdentifier(stem);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build a list of keyterms for the voice_stream STT endpoint.
     *
     * Combines hardcoded global coding terms with session context (project name,
     * git branch, recent files) without any model calls.
     * Translated from getVoiceKeyterms() in voiceKeyterms.ts
     *
     * @param recentFiles optional set of recently accessed file paths
     * @return list of up to {@value #MAX_KEYTERMS} keyterms
     */
    public CompletableFuture<List<String>> getVoiceKeyterms(Set<String> recentFiles) {
        return CompletableFuture.supplyAsync(() -> {
            LinkedHashSet<String> terms = new LinkedHashSet<>(GLOBAL_KEYTERMS);

            // Project root basename as a single term
            try {
                String projectRoot = System.getProperty("user.dir");
                if (projectRoot != null) {
                    String name = new File(projectRoot).getName();
                    if (name.length() > 2 && name.length() <= 50) {
                        terms.add(name);
                    }
                }
            } catch (Exception e) {
                log.debug("[voice-keyterms] Could not get project root: {}", e.getMessage());
            }

            // Git branch words (e.g. "feat/voice-keyterms" → "feat", "voice", "keyterms")
            try {
                String branch = getCurrentGitBranch();
                if (branch != null) {
                    terms.addAll(splitIdentifier(branch));
                }
            } catch (Exception e) {
                log.debug("[voice-keyterms] Could not get git branch: {}", e.getMessage());
            }

            // Recent file names — only scan enough to fill remaining slots
            if (recentFiles != null) {
                for (String filePath : recentFiles) {
                    if (terms.size() >= MAX_KEYTERMS) break;
                    terms.addAll(fileNameWords(filePath));
                }
            }

            List<String> result = new ArrayList<>(terms);
            return result.subList(0, Math.min(result.size(), MAX_KEYTERMS));
        });
    }

    /**
     * Convenience overload without recent files.
     */
    public CompletableFuture<List<String>> getVoiceKeyterms() {
        return getVoiceKeyterms(null);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String getCurrentGitBranch() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .directory(new File(System.getProperty("user.dir")))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            int exitCode = process.waitFor();
            return (exitCode == 0 && !output.isEmpty()) ? output : null;
        } catch (Exception e) {
            return null;
        }
    }
}

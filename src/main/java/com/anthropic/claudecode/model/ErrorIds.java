package com.anthropic.claudecode.model;

/**
 * Error IDs for tracking error sources in production.
 * Translated from src/constants/errorIds.ts
 *
 * These IDs are obfuscated identifiers that help trace which logError() call generated an error.
 */
public class ErrorIds {

    public static final int E_TOOL_USE_SUMMARY_GENERATION_FAILED = 344;

    // Next ID: 346

    private ErrorIds() {}
}

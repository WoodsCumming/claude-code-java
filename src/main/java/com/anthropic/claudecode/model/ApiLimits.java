package com.anthropic.claudecode.model;

/**
 * Anthropic API limits.
 * Translated from src/constants/apiLimits.ts
 */
public class ApiLimits {

    // Image limits
    public static final long API_IMAGE_MAX_BASE64_SIZE = 5L * 1024 * 1024; // 5MB
    public static final long IMAGE_TARGET_RAW_SIZE = (API_IMAGE_MAX_BASE64_SIZE * 3) / 4; // 3.75MB
    public static final int IMAGE_MAX_WIDTH = 2000;
    public static final int IMAGE_MAX_HEIGHT = 2000;

    // PDF limits
    public static final long PDF_TARGET_RAW_SIZE = 20L * 1024 * 1024; // 20MB
    public static final int API_PDF_MAX_PAGES = 100;
    public static final long PDF_EXTRACT_SIZE_THRESHOLD = 3L * 1024 * 1024; // 3MB
    public static final long PDF_MAX_EXTRACT_SIZE = 100L * 1024 * 1024; // 100MB
    public static final int PDF_MAX_PAGES_PER_READ = 20;
    public static final int PDF_AT_MENTION_INLINE_THRESHOLD = 10;

    // Media limits
    public static final int API_MAX_MEDIA_PER_REQUEST = 100;

    private ApiLimits() {}
}

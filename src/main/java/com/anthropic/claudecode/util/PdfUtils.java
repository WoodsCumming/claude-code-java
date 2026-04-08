package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * PDF utility functions.
 * Translated from src/utils/pdf.ts
 *
 * Handles reading, validating, and extracting pages from PDF files.
 */
@Slf4j
public class PdfUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfUtils.class);


    // PDF size limits (from constants/apiLimits.ts)
    // ~20MB raw — after base64 encoding (~33% larger) leaves room for conversation context
    public static final long PDF_TARGET_RAW_SIZE = 20L * 1024 * 1024;
    // 32MB hard cap for page extraction
    public static final long PDF_MAX_EXTRACT_SIZE = 32L * 1024 * 1024;
    public static final int API_PDF_MAX_PAGES = 100;

    /** Cached availability of pdftoppm binary. null = unchecked. */
    private static volatile Boolean pdftoppmAvailable = null;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Read a PDF file and return it as base64-encoded data.
     * Translated from readPDF() in pdf.ts
     *
     * @param filePath Path to the PDF file
     * @return Result containing PDF data or a structured error
     */
    public static CompletableFuture<PdfResult<PdfFileData>> readPDF(String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                long originalSize = file.length();

                // Check if file is empty
                if (originalSize == 0) {
                    return PdfResult.error(PdfErrorReason.EMPTY,
                            "PDF file is empty: " + filePath);
                }

                // Check if PDF exceeds maximum size
                if (originalSize > PDF_TARGET_RAW_SIZE) {
                    return PdfResult.<PdfFileData>error(PdfErrorReason.TOO_LARGE,
                            "PDF file exceeds maximum allowed size of " +
                            FormatUtils.formatFileSize(PDF_TARGET_RAW_SIZE) + ".");
                }

                byte[] bytes = Files.readAllBytes(file.toPath());

                // Validate PDF magic bytes — reject files that aren't actually PDFs.
                // Once an invalid PDF document block is in the message history, every
                // subsequent API call fails with 400 "The PDF specified was not valid"
                // and the session becomes unrecoverable without /clear.
                if (bytes.length < 5 || !new String(bytes, 0, 5, "ASCII").equals("%PDF-")) {
                    return PdfResult.<PdfFileData>error(PdfErrorReason.CORRUPTED,
                            "File is not a valid PDF (missing %PDF- header): " + filePath);
                }

                String base64 = Base64.getEncoder().encodeToString(bytes);
                return PdfResult.success(new PdfFileData(filePath, base64, originalSize));

            } catch (Exception e) {
                return PdfResult.<PdfFileData>error(PdfErrorReason.UNKNOWN, e.getMessage());
            }
        });
    }

    /**
     * Get the number of pages in a PDF file using {@code pdfinfo} (from poppler-utils).
     * Returns {@code null} if pdfinfo is not available or the page count cannot be determined.
     * Translated from getPDFPageCount() in pdf.ts
     */
    public static CompletableFuture<Integer> getPDFPageCount(String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("pdfinfo", filePath);
                pb.redirectErrorStream(false);
                Process process = pb.start();
                boolean finished = process.waitFor(10, TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    return null;
                }
                String stdout = new String(process.getInputStream().readAllBytes());
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("(?m)^Pages:\\s+(\\d+)").matcher(stdout);
                if (!m.find()) return null;
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException e) {
                    return null;
                }
            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * Reset the pdftoppm availability cache. Used by tests only.
     * Translated from resetPdftoppmCache() in pdf.ts
     */
    public static void resetPdftoppmCache() {
        pdftoppmAvailable = null;
    }

    /**
     * Check whether the {@code pdftoppm} binary (from poppler-utils) is available.
     * The result is cached for the lifetime of the process.
     * Translated from isPdftoppmAvailable() in pdf.ts
     */
    public static CompletableFuture<Boolean> isPdftoppmAvailable() {
        if (pdftoppmAvailable != null) {
            return CompletableFuture.completedFuture(pdftoppmAvailable);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("pdftoppm", "-v");
                pb.redirectErrorStream(false);
                Process process = pb.start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                byte[] stderr = process.getErrorStream().readAllBytes();
                // pdftoppm prints version info to stderr and exits 0 (or sometimes 99)
                boolean available = (finished && process.exitValue() == 0) || stderr.length > 0;
                pdftoppmAvailable = available;
                return available;
            } catch (Exception e) {
                pdftoppmAvailable = false;
                return false;
            }
        });
    }

    /**
     * Extract PDF pages as JPEG images using pdftoppm.
     * Produces page-01.jpg, page-02.jpg, etc. in an output directory.
     * Enables reading large PDFs and works with all API providers.
     *
     * @param filePath  Path to the PDF file
     * @param firstPage Optional first page (1-indexed, inclusive)
     * @param lastPage  Optional last page (1-indexed, inclusive); use {@link Integer#MAX_VALUE} for all
     * @return Result containing extraction metadata or a structured error
     */
    public static CompletableFuture<PdfResult<PdfExtractPagesResult>> extractPDFPages(
            String filePath, Integer firstPage, Integer lastPage) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                long originalSize = file.length();

                if (originalSize == 0) {
                    return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.EMPTY,
                            "PDF file is empty: " + filePath);
                }

                if (originalSize > PDF_MAX_EXTRACT_SIZE) {
                    return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.TOO_LARGE,
                            "PDF file exceeds maximum allowed size for text extraction (" +
                            FormatUtils.formatFileSize(PDF_MAX_EXTRACT_SIZE) + ").");
                }

                Boolean available = isPdftoppmAvailable().get();
                if (!available) {
                    return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.UNAVAILABLE,
                            "pdftoppm is not installed. Install poppler-utils " +
                            "(e.g. `brew install poppler` or `apt-get install poppler-utils`) " +
                            "to enable PDF page rendering.");
                }

                String uuid = UUID.randomUUID().toString();
                Path outputDir = Paths.get(getToolResultsDir(), "pdf-" + uuid);
                Files.createDirectories(outputDir);

                // pdftoppm produces files like <prefix>-01.jpg, <prefix>-02.jpg, etc.
                String prefix = outputDir.resolve("page").toString();
                List<String> args = new java.util.ArrayList<>(
                        Arrays.asList("pdftoppm", "-jpeg", "-r", "100"));
                if (firstPage != null) {
                    args.add("-f");
                    args.add(String.valueOf(firstPage));
                }
                if (lastPage != null && lastPage != Integer.MAX_VALUE) {
                    args.add("-l");
                    args.add(String.valueOf(lastPage));
                }
                args.add(filePath);
                args.add(prefix);

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(false);
                Process process = pb.start();
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                String stderr = new String(process.getErrorStream().readAllBytes());

                if (!finished || process.exitValue() != 0) {
                    if (stderr.toLowerCase().contains("password")) {
                        return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.PASSWORD_PROTECTED,
                                "PDF is password-protected. Please provide an unprotected version.");
                    }
                    if (stderr.toLowerCase().matches(".*(?:damaged|corrupt|invalid).*")) {
                        return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.CORRUPTED,
                                "PDF file is corrupted or invalid.");
                    }
                    return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.UNKNOWN,
                            "pdftoppm failed: " + stderr);
                }

                // Read generated image files and sort naturally
                File[] entries = outputDir.toFile().listFiles(f -> f.getName().endsWith(".jpg"));
                if (entries == null || entries.length == 0) {
                    return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.CORRUPTED,
                            "pdftoppm produced no output pages. The PDF may be invalid.");
                }
                Arrays.sort(entries);
                int count = entries.length;

                return PdfResult.success(new PdfExtractPagesResult(
                        filePath, originalSize, outputDir.toString(), count));

            } catch (Exception e) {
                return PdfResult.<PdfExtractPagesResult>error(PdfErrorReason.UNKNOWN, e.getMessage());
            }
        });
    }

    /** Convenience overload with no page range restriction. */
    public static CompletableFuture<PdfResult<PdfExtractPagesResult>> extractPDFPages(String filePath) {
        return extractPDFPages(filePath, null, null);
    }

    /**
     * Check if a file extension is a PDF.
     */
    public static boolean isPdfExtension(String path) {
        if (path == null) return false;
        return path.toLowerCase().endsWith(".pdf");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String getToolResultsDir() {
        String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
        return tmpDir + "/claude-tool-results";
    }

    private PdfUtils() {}

    // =========================================================================
    // Inner types
    // =========================================================================

    /** Possible error reasons for PDF operations. Translated from PDFError.reason union. */
    public enum PdfErrorReason {
        EMPTY,
        TOO_LARGE,
        PASSWORD_PROTECTED,
        CORRUPTED,
        UNKNOWN,
        UNAVAILABLE
    }

    /** Structured error for PDF operations. */
    public record PdfError(PdfErrorReason reason, String message) {}

    /**
     * Discriminated union result type for PDF operations.
     * Translated from PDFResult&lt;T&gt; in pdf.ts using a sealed interface.
     */
    public sealed interface PdfResult<T> permits PdfResult.Success, PdfResult.Failure {

        boolean isSuccess();

        record Success<T>(T data) implements PdfResult<T> {
            @Override public boolean isSuccess() { return true; }
        }

        record Failure<T>(PdfError error) implements PdfResult<T> {
            @Override public boolean isSuccess() { return false; }
        }

        static <T> PdfResult<T> success(T data) {
            return new Success<>(data);
        }

        static <T> PdfResult<T> error(PdfErrorReason reason, String message) {
            return new Failure<>(new PdfError(reason, message));
        }
    }

    /** Data returned from a successful {@link #readPDF} call. */
    public record PdfFileData(String filePath, String base64, long originalSize) {}

    /** Data returned from a successful {@link #extractPDFPages} call. Translated from PDFExtractPagesResult. */
    public record PdfExtractPagesResult(String filePath, long originalSize, String outputDir, int count) {}
}

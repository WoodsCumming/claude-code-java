package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service that extracts symbol/word context from a source file at a given
 * line and character position.
 *
 * <p>Translated from src/tools/LSPTool/symbolContext.ts</p>
 *
 * <p>The TypeScript original calls synchronous FS APIs from a React render
 * function.  In the Java translation this is a Spring {@code @Service}
 * but the core extraction method is intentionally synchronous so it can be
 * called from rendering/display code that doesn't use futures.</p>
 */
@Slf4j
@Service
public class LspSymbolContextService {



    /**
     * Maximum bytes read from the file to locate the symbol.
     * Mirrors {@code MAX_READ_BYTES = 64 * 1024} in the TypeScript source.
     */
    private static final int MAX_READ_BYTES = 64 * 1024;

    /**
     * Maximum length of the returned symbol string.
     * Mirrors the {@code truncate(symbol, 30)} call in the TypeScript source.
     */
    private static final int MAX_SYMBOL_LENGTH = 30;

    /**
     * Pattern that matches identifiers and operator sequences.
     * Mirrors the TypeScript {@code /[\w$'!]+|[+\-*\/%&|^~<>=]+/g} pattern.
     * Note: In Java regex the character class uses a literal hyphen at the end
     * to avoid being interpreted as a range.
     */
    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("[\\w$'!]+|[+\\-*/%&|^~<>=]+");

    /**
     * Extracts the symbol/word at a specific position in a file.
     *
     * <p>Only the first {@link #MAX_READ_BYTES} bytes of the file are read.
     * If the target line falls outside that window the method returns
     * {@code null} so the caller can fall back to showing
     * {@code position: line:char}.</p>
     *
     * @param filePath  absolute or relative path to the source file
     * @param line      0-indexed line number
     * @param character 0-indexed character position on the line
     * @return the symbol at that position (up to 30 characters), or
     *         {@code null} if extraction fails for any reason
     */
    public String getSymbolAtPosition(String filePath, int line, int character) {
        try {
            String absolutePath = Paths.get(filePath).toAbsolutePath().toString();

            // Read only the first 64 KB.  Most LSP hover/goto targets are near
            // recent edits; 64 KB covers roughly 1 000 lines of typical code.
            byte[] rawBytes   = readFirstBytes(absolutePath, MAX_READ_BYTES);
            boolean filledBuf = rawBytes.length == MAX_READ_BYTES;

            String content = new String(rawBytes, StandardCharsets.UTF_8);
            String[] lines  = content.split("\n", -1);

            if (line < 0 || line >= lines.length) {
                return null;
            }

            // If the buffer was full the last split element may be truncated.
            if (filledBuf && line == lines.length - 1) {
                return null;
            }

            String lineContent = lines[line];
            if (lineContent == null || character < 0 || character >= lineContent.length()) {
                return null;
            }

            Matcher matcher = SYMBOL_PATTERN.matcher(lineContent);
            while (matcher.find()) {
                int start = matcher.start();
                int end   = matcher.end();
                if (character >= start && character < end) {
                    String symbol = matcher.group();
                    // Limit to MAX_SYMBOL_LENGTH characters
                    if (symbol.length() > MAX_SYMBOL_LENGTH) {
                        symbol = symbol.substring(0, MAX_SYMBOL_LENGTH);
                    }
                    return symbol;
                }
            }

            return null;

        } catch (Exception e) {
            if (e instanceof IOException) {
                log.warn("Symbol extraction failed for {}:{}:{}: {}",
                        filePath, line, character, e.getMessage());
            } else {
                log.warn("Symbol extraction failed for {}:{}:{}: {}",
                        filePath, line, character, e.getMessage());
            }
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Reads up to {@code maxBytes} from the beginning of the file.
     * Uses {@link FileChannel} for efficient partial reads.
     */
    private byte[] readFirstBytes(String absolutePath, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(absolutePath, "r");
             FileChannel channel = raf.getChannel()) {

            long fileSize = channel.size();
            int toRead    = (int) Math.min(fileSize, maxBytes);
            ByteBuffer buf = ByteBuffer.allocate(toRead);

            int totalRead = 0;
            while (totalRead < toRead) {
                int n = channel.read(buf);
                if (n < 0) break;
                totalRead += n;
            }

            buf.flip();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return bytes;
        }
    }
}

package com.anthropic.claudecode.util;

import java.util.*;
import java.util.regex.*;

/**
 * Argument substitution utilities for skill/command prompts.
 * Translated from src/utils/argumentSubstitution.ts
 *
 * Supports:
 *   $ARGUMENTS          – replaced with the full arguments string
 *   $ARGUMENTS[0], etc. – replaced with individual indexed arguments
 *   $0, $1, etc.        – shorthand for $ARGUMENTS[0], $ARGUMENTS[1]
 *   Named arguments     – e.g. $foo, $bar when names are defined in frontmatter
 *
 * Arguments are parsed with basic shell-quote semantics (quoted strings).
 */
public class ArgumentSubstitutionUtils {

    // -------------------------------------------------------------------------
    // parseArguments
    // -------------------------------------------------------------------------

    /**
     * Parse an arguments string into an array of individual arguments.
     * Uses basic shell-quote semantics (quoted strings, single/double quotes).
     * Translated from parseArguments() in argumentSubstitution.ts
     *
     * Examples:
     *   "foo bar baz"           => ["foo", "bar", "baz"]
     *   'foo "hello world" baz' => ["foo", "hello world", "baz"]
     *
     * @param args raw arguments string
     * @return list of parsed argument tokens
     */
    public static List<String> parseArguments(String args) {
        if (args == null || args.isBlank()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    // -------------------------------------------------------------------------
    // parseArgumentNames
    // -------------------------------------------------------------------------

    /**
     * Parse argument names from the frontmatter 'arguments' field.
     * Accepts either a space-separated string or a list of strings.
     * Filters out empty strings and purely numeric names (which conflict with
     * the $0, $1 shorthand).
     * Translated from parseArgumentNames() in argumentSubstitution.ts
     *
     * @param argumentNames either a {@code String} or {@code List<String>}; may be null
     * @return list of valid argument name strings
     */
    public static List<String> parseArgumentNames(Object argumentNames) {
        if (argumentNames == null) {
            return Collections.emptyList();
        }

        List<String> raw;
        if (argumentNames instanceof List<?> list) {
            raw = list.stream()
                    .filter(o -> o instanceof String)
                    .map(Object::toString)
                    .toList();
        } else if (argumentNames instanceof String s) {
            raw = Arrays.asList(s.trim().split("\\s+"));
        } else {
            return Collections.emptyList();
        }

        return raw.stream()
                .filter(name -> name != null && !name.isBlank() && !name.matches("^\\d+$"))
                .toList();
    }

    // -------------------------------------------------------------------------
    // generateProgressiveArgumentHint
    // -------------------------------------------------------------------------

    /**
     * Generate an argument hint showing remaining unfilled arguments.
     * Translated from generateProgressiveArgumentHint() in argumentSubstitution.ts
     *
     * @param argNames   array of argument names from frontmatter
     * @param typedArgs  arguments the user has typed so far
     * @return hint string like "[arg2] [arg3]", or null if all filled
     */
    public static String generateProgressiveArgumentHint(List<String> argNames,
                                                          List<String> typedArgs) {
        int filled = typedArgs != null ? typedArgs.size() : 0;
        if (filled >= argNames.size()) {
            return null;
        }
        List<String> remaining = argNames.subList(filled, argNames.size());
        StringBuilder sb = new StringBuilder();
        for (String name : remaining) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append('[').append(name).append(']');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // substituteArguments
    // -------------------------------------------------------------------------

    /**
     * Substitute {@code $ARGUMENTS} placeholders in content with actual argument values.
     * Translated from substituteArguments() in argumentSubstitution.ts
     *
     * @param content               content containing placeholders
     * @param args                  raw arguments string (may be null — means no args)
     * @param appendIfNoPlaceholder if true and no placeholders are found, appends
     *                              "ARGUMENTS: {args}" to the content
     * @param argumentNames         optional named arguments that map to indexed positions
     * @return content with placeholders substituted
     */
    public static String substituteArguments(String content,
                                              String args,
                                              boolean appendIfNoPlaceholder,
                                              List<String> argumentNames) {
        // null means no args provided — return content unchanged
        if (args == null) {
            return content;
        }

        List<String> parsedArgs = parseArguments(args);
        String originalContent = content;

        // Replace named arguments: $foo, $bar (but not $foo[...] or $fooXxx)
        if (argumentNames != null) {
            for (int i = 0; i < argumentNames.size(); i++) {
                String name = argumentNames.get(i);
                if (name == null || name.isBlank()) continue;

                String replacement = (i < parsedArgs.size()) ? parsedArgs.get(i) : "";
                Pattern namedPattern = Pattern.compile(
                        "\\$" + Pattern.quote(name) + "(?![\\[\\w])");
                Matcher m = namedPattern.matcher(content);
                content = m.replaceAll(Matcher.quoteReplacement(replacement));
            }
        }

        // Replace $ARGUMENTS[N] with indexed argument
        Pattern indexedPattern = Pattern.compile("\\$ARGUMENTS\\[(\\d+)\\]");
        Matcher m = indexedPattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            String replacement = (index < parsedArgs.size()) ? parsedArgs.get(index) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        content = sb.toString();

        // Replace $N (shorthand) with indexed argument — but not followed by word chars
        Pattern shorthandPattern = Pattern.compile("\\$(\\d+)(?!\\w)");
        m = shorthandPattern.matcher(content);
        sb = new StringBuffer();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            String replacement = (index < parsedArgs.size()) ? parsedArgs.get(index) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        content = sb.toString();

        // Replace $ARGUMENTS with the full arguments string
        content = content.replace("$ARGUMENTS", args);

        // Append args if no placeholder was found
        if (content.equals(originalContent) && appendIfNoPlaceholder && !args.isBlank()) {
            content = content + "\n\nARGUMENTS: " + args;
        }

        return content;
    }

    /**
     * Convenience overload with {@code appendIfNoPlaceholder = true} and no named arguments.
     *
     * @param content content containing placeholders
     * @param args    raw arguments string (may be null)
     * @return content with placeholders substituted
     */
    public static String substituteArguments(String content, String args) {
        return substituteArguments(content, args, true, Collections.emptyList());
    }

    private ArgumentSubstitutionUtils() {}
}

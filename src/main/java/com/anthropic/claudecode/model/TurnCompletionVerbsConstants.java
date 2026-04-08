package com.anthropic.claudecode.model;

import java.util.List;

/**
 * Past-tense verbs for turn completion messages.
 * Translated from src/constants/turnCompletionVerbs.ts
 *
 * These verbs work naturally with "for [duration]" (e.g., "Worked for 5s").
 */
public class TurnCompletionVerbsConstants {

    public static final List<String> TURN_COMPLETION_VERBS = List.of(
        "Baked",
        "Brewed",
        "Churned",
        "Cogitated",
        "Cooked",
        "Crunched",
        "Saut\u00e9ed",
        "Worked"
    );

    private TurnCompletionVerbsConstants() {}
}

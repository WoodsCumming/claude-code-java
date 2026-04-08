package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * BTW ("by the way") command service — models the '/btw' slash-command metadata
 * and executes side-question logic.
 * Translated from src/commands/btw/index.ts
 *
 * <p>The TypeScript source registers a lazy-loaded local-jsx command with the
 * name "btw" and the {@code immediate: true} flag, which means the command is
 * submitted without requiring an explicit send gesture. It lets users ask a
 * quick side question without interrupting the main conversation thread.
 */
@Slf4j
@Service
public class BtwCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BtwCommandService.class);


    /** Command name. Translated from: name: 'btw' in index.ts */
    public static final String NAME = "btw";

    /** Command description. Translated from: description in index.ts */
    public static final String DESCRIPTION =
        "Ask a quick side question without interrupting the main conversation";

    /**
     * Argument hint. Translated from: argumentHint: '<question>' in index.ts
     * The angle-brackets indicate a required argument.
     */
    public static final String ARGUMENT_HINT = "<question>";

    /** Command type. Translated from: type: 'local-jsx' in index.ts */
    public static final String TYPE = "local-jsx";

    /**
     * Whether the command is submitted immediately (no explicit send required).
     * Translated from: immediate: true in index.ts
     */
    public static final boolean IMMEDIATE = true;

    private final SideQuestionService sideQuestionService;

    @Autowired
    public BtwCommandService(SideQuestionService sideQuestionService) {
        this.sideQuestionService = sideQuestionService;
    }

    /**
     * Execute a quick side question without interrupting the main conversation.
     * Translated from the lazy load of btw.js in index.ts
     *
     * @param question the side question text to ask
     * @return a future resolving to the answer string
     */
    public CompletableFuture<String> ask(String question) {
        if (question == null || question.isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("A question is required for /btw"));
        }
        log.info("BTW side question: {}", question);
        return sideQuestionService.askSideQuestion(question);
    }
}

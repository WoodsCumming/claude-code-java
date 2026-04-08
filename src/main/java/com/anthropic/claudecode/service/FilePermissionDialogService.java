package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Service for handling file permission dialogs with common accept/reject logic.
 * Translated from src/components/permissions/FilePermissionDialog/useFilePermissionDialog.ts
 *
 * The React hook's useState fields are modelled with mutable state holders
 * (AtomicReference / AtomicBoolean) held in a per-dialog DialogState object.
 * Callers create a DialogState instance and pass it together with the
 * context parameters to the various handler methods.
 */
@Slf4j
@Service
public class FilePermissionDialogService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FilePermissionDialogService.class);


    private final AnalyticsService analyticsService;
    private final PermissionHandlerService permissionHandlerService;

    public FilePermissionDialogService(AnalyticsService analyticsService,
                                       PermissionHandlerService permissionHandlerService) {
        this.analyticsService = analyticsService;
        this.permissionHandlerService = permissionHandlerService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates a fresh, initialised dialog state for a new permission dialog.
     * Mirrors the collection of useState() calls in the TypeScript hook.
     */
    public DialogState createDialogState() {
        return new DialogState();
    }

    /**
     * Handles an option selection (accept-once, accept-session, or reject).
     * Translated from the onChange callback inside useFilePermissionDialog().
     *
     * @param option              the selected permission option
     * @param parsedInput         the tool input (parsed by the caller's parseInput function)
     * @param feedback            optional user-typed feedback string
     * @param context             the dialog context (file path, completion type, etc.)
     * @param state               the mutable dialog state
     * @param onAllow             callback: (input, permissionUpdates, feedback) → void
     * @param onReject            called when the user rejects
     * @param onDone              called when the dialog is finished
     */
    public void onChange(
            PermissionOption option,
            Map<String, Object> parsedInput,
            String feedback,
            DialogContext context,
            DialogState state,
            TriConsumer<Map<String, Object>, List<Object>, String> onAllow,
            Runnable onReject,
            Runnable onDone) {

        PermissionHandlerService.HandlerParams params = new PermissionHandlerService.HandlerParams(
                context.messageId(),
                context.filePath(),
                context.toolUseConfirm(),
                context.toolPermissionContext(),
                onDone,
                onReject,
                context.completionType(),
                context.languageName(),
                context.operationType());

        // Wrap onAllow so the parsed input is used instead of the raw confirm input
        PermissionHandlerService.AllowCallback wrappedAllow =
                (ignored, updates, fb) -> onAllow.accept(parsedInput, updates, fb);

        PermissionHandlerService.HandlerOptions options = new PermissionHandlerService.HandlerOptions(
                feedback != null && !feedback.trim().isEmpty(),
                feedback,
                option.type().equals("accept-once")
                        ? state.yesFeedbackModeEntered.get()
                        : state.noFeedbackModeEntered.get(),
                option.scope());

        permissionHandlerService.handle(option.type(), params, options, wrappedAllow);
    }

    /**
     * Handles the cycleMode keyboard shortcut – selects the accept-session option.
     * Translated from handleCycleMode() + useKeybindings() in useFilePermissionDialog.ts
     *
     * @param options         list of permission options currently displayed
     * @param rawInput        the raw tool input from toolUseConfirm
     * @param parseInput      function to parse the raw input into a typed map
     * @param context         the dialog context
     * @param state           the mutable dialog state
     * @param onAllow         the allow callback
     * @param onReject        the reject callback
     * @param onDone          the done callback
     */
    public void handleCycleMode(
            List<PermissionOptionWithLabel> options,
            Object rawInput,
            Function<Object, Map<String, Object>> parseInput,
            DialogContext context,
            DialogState state,
            TriConsumer<Map<String, Object>, List<Object>, String> onAllow,
            Runnable onReject,
            Runnable onDone) {

        options.stream()
                .filter(o -> "accept-session".equals(o.option().type()))
                .findFirst()
                .ifPresent(sessionOption -> {
                    Map<String, Object> parsed = parseInput.apply(rawInput);
                    onChange(sessionOption.option(), parsed, null, context, state,
                            onAllow, onReject, onDone);
                });
    }

    /**
     * Handles focus changes between Yes/No options.
     * Resets input mode when navigating away without any typed feedback.
     * Translated from handleFocusedOptionChange() in useFilePermissionDialog.ts
     */
    public void handleFocusedOptionChange(String value, DialogState state) {
        // Reset yes-input mode when navigating away without typed text
        if (!"yes".equals(value) && state.yesInputMode.get()
                && state.acceptFeedback.get().trim().isEmpty()) {
            state.yesInputMode.set(false);
        }
        // Reset no-input mode when navigating away without typed text
        if (!"no".equals(value) && state.noInputMode.get()
                && state.rejectFeedback.get().trim().isEmpty()) {
            state.noInputMode.set(false);
        }
        state.focusedOption.set(value);
    }

    /**
     * Handles Tab-key toggling of yes/no feedback input mode.
     * Translated from handleInputModeToggle() in useFilePermissionDialog.ts
     */
    public void handleInputModeToggle(String value, String toolName, boolean isMcp, DialogState state) {
        Map<String, Object> analyticsProps = Map.of(
                "toolName", toolName,
                "isMcp", isMcp);

        if ("yes".equals(value)) {
            if (state.yesInputMode.get()) {
                state.yesInputMode.set(false);
                analyticsService.logEvent("tengu_accept_feedback_mode_collapsed", analyticsProps);
            } else {
                state.yesInputMode.set(true);
                state.yesFeedbackModeEntered.set(true);
                analyticsService.logEvent("tengu_accept_feedback_mode_entered", analyticsProps);
            }
        } else if ("no".equals(value)) {
            if (state.noInputMode.get()) {
                state.noInputMode.set(false);
                analyticsService.logEvent("tengu_reject_feedback_mode_collapsed", analyticsProps);
            } else {
                state.noInputMode.set(true);
                state.noFeedbackModeEntered.set(true);
                analyticsService.logEvent("tengu_reject_feedback_mode_entered", analyticsProps);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------

    /**
     * Mutable state for a single file-permission dialog instance.
     * Replaces all useState() calls from the TypeScript hook.
     */
    public static final class DialogState {
        public final AtomicReference<String> acceptFeedback = new AtomicReference<>("");
        public final AtomicReference<String> rejectFeedback = new AtomicReference<>("");
        public final AtomicReference<String> focusedOption = new AtomicReference<>("yes");
        public final AtomicBoolean yesInputMode = new AtomicBoolean(false);
        public final AtomicBoolean noInputMode = new AtomicBoolean(false);
        public final AtomicBoolean yesFeedbackModeEntered = new AtomicBoolean(false);
        public final AtomicBoolean noFeedbackModeEntered = new AtomicBoolean(false);
    }

    /**
     * Immutable context passed into all handler methods.
     * Corresponds to UseFilePermissionDialogProps<T> in the TypeScript source.
     */
    public record DialogContext(
            String filePath,
            String completionType,
            String languageName,
            Object toolUseConfirm,
            Object toolPermissionContext,
            String operationType,
            String messageId) {

        /** Convenience constructor – operationType defaults to "write". */
        public DialogContext(String filePath, String completionType, String languageName,
                             Object toolUseConfirm, Object toolPermissionContext, String messageId) {
            this(filePath, completionType, languageName,
                    toolUseConfirm, toolPermissionContext, "write", messageId);
        }
    }

    /**
     * Represents a permission option type and optional scope.
     * Translated from PermissionOption in permissionOptions.ts
     */
    public record PermissionOption(String type, String scope) {
        public PermissionOption(String type) {
            this(type, null);
        }
    }

    /**
     * A permission option paired with its display label.
     * Translated from PermissionOptionWithLabel in permissionOptions.ts
     */
    public record PermissionOptionWithLabel(PermissionOption option, String label) {}

    /** Functional interface for three-argument callbacks (onAllow). */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}

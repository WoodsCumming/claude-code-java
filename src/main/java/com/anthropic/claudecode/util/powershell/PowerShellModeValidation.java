package com.anthropic.claudecode.util.powershell;

import com.anthropic.claudecode.model.PermissionMode;
import com.anthropic.claudecode.model.PermissionResult;
import com.anthropic.claudecode.model.ToolPermissionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PowerShell permission mode validation.
 *
 * Checks if commands should be auto-allowed based on the current permission mode.
 * In acceptEdits mode, filesystem-modifying PowerShell cmdlets are auto-allowed.
 * Follows the same patterns as BashTool/modeValidation.ts.
 *
 * Translated from src/tools/PowerShellTool/modeValidation.ts
 */
@Slf4j
public final class PowerShellModeValidation {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PowerShellModeValidation.class);


    // -------------------------------------------------------------------------
    // Types mirroring the PowerShell parser AST structures used here
    // -------------------------------------------------------------------------

    /**
     * Security-relevant flags derived from the PowerShell AST.
     * Mirrors the return type of deriveSecurityFlags() in parser.ts.
     */
    public record SecurityFlags(
            boolean hasSubExpressions,
            boolean hasScriptBlocks,
            boolean hasMemberInvocations,
            boolean hasSplatting,
            boolean hasAssignments,
            boolean hasStopParsing,
            boolean hasExpandableStrings
    ) {}

    /**
     * A single parsed command element (one invocation inside a pipeline).
     * Mirrors ParsedCommandElement from parser.ts.
     */
    public record ParsedCommandElement(
            String name,
            String nameType,      // "cmdlet" | "application" | "unknown"
            String elementType,   // "CommandAst" | "CommandExpressionAst" | ...
            List<String> args,
            String text,
            List<String> elementTypes  // nullable
    ) {}

    /**
     * A pipeline segment — holds the commands in one pipeline plus any
     * nested commands from control-flow body blocks.
     */
    public record PipelineSegment(
            List<ParsedCommandElement> commands,
            List<ParsedCommandElement> nestedCommands  // nullable
    ) {}

    /**
     * The full parsed result from the PowerShell AST parser.
     * Only the fields used by this class are represented here.
     */
    public record ParsedPowerShellCommand(
            boolean valid,
            List<PipelineSegment> segments,
            SecurityFlags securityFlags
    ) {}

    // -------------------------------------------------------------------------
    // Common PowerShell aliases (subset used for canonicalization)
    // -------------------------------------------------------------------------

    /**
     * Commonly used PowerShell aliases mapped to their canonical cmdlet names.
     * Subset relevant to acceptEdits validation.
     */
    private static final Map<String, String> COMMON_ALIASES = Map.ofEntries(
            Map.entry("rm",    "remove-item"),
            Map.entry("del",   "remove-item"),
            Map.entry("rd",    "remove-item"),
            Map.entry("rmdir", "remove-item"),
            Map.entry("ri",    "remove-item"),
            Map.entry("erase", "remove-item"),
            Map.entry("ac",    "add-content"),
            Map.entry("sc",    "set-content"),
            Map.entry("clc",   "clear-content"),
            Map.entry("sl",    "set-location"),
            Map.entry("cd",    "set-location"),
            Map.entry("chdir", "set-location"),
            Map.entry("pushd", "push-location"),
            Map.entry("popd",  "pop-location"),
            Map.entry("mkdir", "new-item"),
            Map.entry("ni",    "new-item"),
            Map.entry("cp",    "copy-item"),
            Map.entry("copy",  "copy-item"),
            Map.entry("mv",    "move-item"),
            Map.entry("move",  "move-item")
    );

    /**
     * Filesystem-modifying cmdlets that are auto-allowed in acceptEdits mode.
     * Stored as canonical (lowercase) cmdlet names.
     *
     * Tier 3 cmdlets with complex parameter binding removed — they fall through to
     * 'ask'. Only simple write cmdlets (first positional = -Path) are auto-allowed.
     */
    private static final Set<String> ACCEPT_EDITS_ALLOWED_CMDLETS = Set.of(
            "set-content",
            "add-content",
            "remove-item",
            "clear-content"
    );

    /**
     * New-Item -ItemType values that create filesystem links (reparse points or
     * hard links). All three redirect path resolution at runtime.
     */
    private static final Set<String> LINK_ITEM_TYPES = Set.of(
            "symboliclink",
            "junction",
            "hardlink"
    );

    /**
     * Cmdlets that change the current working directory.
     */
    private static final Set<String> CWD_CHANGING_CMDLETS = Set.of(
            "set-location",
            "push-location",
            "pop-location"
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Checks if commands should be handled differently based on the current permission mode.
     *
     * In acceptEdits mode, auto-allows filesystem-modifying PowerShell cmdlets.
     * Uses the AST to resolve aliases before checking the allowlist.
     *
     * @param command               the raw PowerShell command string
     * @param parsed                parsed AST of the command
     * @param toolPermissionContext context containing mode and permissions
     * @return
     *   <ul>
     *     <li>{@code allow} if the current mode permits auto-approval</li>
     *     <li>{@code passthrough} if no mode-specific handling applies</li>
     *   </ul>
     */
    public static PermissionResult checkPermissionMode(
            String command,
            ParsedPowerShellCommand parsed,
            ToolPermissionContext toolPermissionContext) {

        PermissionMode mode = toolPermissionContext.getMode();

        // Skip bypass and dontAsk modes (handled elsewhere)
        if (mode == PermissionMode.BYPASS_PERMISSIONS || mode == PermissionMode.DONT_ASK) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("Mode is handled in main permission flow")
                    .build();
        }

        if (mode != PermissionMode.ACCEPT_EDITS) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("No mode-specific validation required")
                    .build();
        }

        // acceptEdits mode: check if all commands are filesystem-modifying cmdlets
        if (!parsed.valid()) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("Cannot validate mode for unparsed command")
                    .build();
        }

        // SECURITY: Check for subexpressions, script blocks, member invocations, etc.
        // that could be used to smuggle arbitrary code through acceptEdits mode.
        SecurityFlags securityFlags = parsed.securityFlags();
        if (securityFlags.hasSubExpressions()
                || securityFlags.hasScriptBlocks()
                || securityFlags.hasMemberInvocations()
                || securityFlags.hasSplatting()
                || securityFlags.hasAssignments()
                || securityFlags.hasStopParsing()
                || securityFlags.hasExpandableStrings()) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("Command contains subexpressions, script blocks, or member invocations" +
                            " that require approval")
                    .build();
        }

        List<PipelineSegment> segments = parsed.segments();

        // SECURITY: Empty segments with valid parse → no commands to check, don't auto-allow
        if (segments.isEmpty()) {
            return PermissionResult.PassthroughDecision.builder()
                    .message("No commands found to validate for acceptEdits mode")
                    .build();
        }

        // SECURITY: Compound cwd desync guard — BashTool parity.
        // When any statement contains Set-Location/Push-Location/Pop-Location,
        // path validation resolves relative paths against the stale process cwd.
        int totalCommands = segments.stream()
                .mapToInt(seg -> seg.commands().size())
                .sum();

        if (totalCommands > 1) {
            boolean hasCdCommand = false;
            boolean hasSymlinkCreate = false;
            boolean hasWriteCommand = false;

            for (PipelineSegment seg : segments) {
                for (ParsedCommandElement cmd : seg.commands()) {
                    if (!"CommandAst".equals(cmd.elementType())) continue;
                    if (isCwdChangingCmdlet(cmd.name())) hasCdCommand = true;
                    if (isSymlinkCreatingCommand(cmd)) hasSymlinkCreate = true;
                    if (isAcceptEditsAllowedCmdlet(cmd.name())) hasWriteCommand = true;
                }
            }

            if (hasCdCommand && hasWriteCommand) {
                return PermissionResult.PassthroughDecision.builder()
                        .message("Compound command contains a directory-changing command" +
                                " (Set-Location/Push-Location/Pop-Location) with a write operation" +
                                " — cannot auto-allow because path validation uses stale cwd")
                        .build();
            }

            // SECURITY: Link-create compound guard (finding #18).
            if (hasSymlinkCreate) {
                return PermissionResult.PassthroughDecision.builder()
                        .message("Compound command creates a filesystem link" +
                                " (New-Item -ItemType SymbolicLink/Junction/HardLink)" +
                                " — cannot auto-allow because path validation cannot follow just-created links")
                        .build();
            }
        }

        // Check all pipeline segments and nested commands
        for (PipelineSegment segment : segments) {
            PermissionResult segResult = validateSegmentCommands(segment.commands(), command);
            if (!"passthrough".equals(segResult.getBehavior())) return segResult;

            if (segment.nestedCommands() != null) {
                PermissionResult nestedResult = validateNestedCommands(segment.nestedCommands(), command);
                if (!"passthrough".equals(nestedResult.getBehavior())) return nestedResult;
            }
        }

        // All commands are filesystem-modifying cmdlets — auto-allow
        return PermissionResult.AllowDecision.builder()
                .updatedInput(Map.of("command", command))
                .decisionReason(new PermissionResult.PermissionDecisionReason.ModeReason(
                        PermissionMode.ACCEPT_EDITS))
                .build();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static PermissionResult validateSegmentCommands(
            List<ParsedCommandElement> commands, String rawCommand) {
        for (ParsedCommandElement cmd : commands) {
            if (!"CommandAst".equals(cmd.elementType())) {
                // SECURITY: Non-CommandAst element (expression pipeline source,
                // control-flow statement, or non-PipelineAst redirection coverage).
                return PermissionResult.PassthroughDecision.builder()
                        .message("Pipeline contains expression source (" + cmd.elementType() +
                                ") that cannot be statically validated")
                        .build();
            }

            // SECURITY: nameType 'application' = raw name had path chars (. \\ /).
            // scripts\\Remove-Item would strip to Remove-Item and match allowlist,
            // but PowerShell runs scripts\\Remove-Item.ps1.
            if ("application".equals(cmd.nameType())) {
                return PermissionResult.PassthroughDecision.builder()
                        .message("Command '" + cmd.name() +
                                "' resolved from a path-like name and requires approval")
                        .build();
            }

            // SECURITY: elementTypes whitelist — only StringConstant and Parameter allowed.
            // Variable, Other, etc. can hide env-var expansions or nested expressions.
            if (cmd.elementTypes() != null) {
                for (int i = 1; i < cmd.elementTypes().size(); i++) {
                    String t = cmd.elementTypes().get(i);
                    if (!"StringConstant".equals(t) && !"Parameter".equals(t)) {
                        return PermissionResult.PassthroughDecision.builder()
                                .message("Command argument has unvalidatable type (" + t +
                                        ") — variable paths cannot be statically resolved")
                                .build();
                    }
                    if ("Parameter".equals(t)) {
                        // elementTypes[i] ↔ args[i-1]
                        String arg = (cmd.args() != null && i - 1 < cmd.args().size())
                                ? cmd.args().get(i - 1) : "";
                        int colonIdx = arg.indexOf(':');
                        if (colonIdx > 0 && arg.substring(colonIdx + 1).matches(".*[$(@{[].*")) {
                            return PermissionResult.PassthroughDecision.builder()
                                    .message("Colon-bound parameter contains an expression" +
                                            " that cannot be statically validated")
                                    .build();
                        }
                    }
                }
            }

            // Safe output cmdlets and allowlisted pipeline-tail transformers are fine
            if (isSafeOutputCommand(cmd.name())) continue;

            if (!isAcceptEditsAllowedCmdlet(cmd.name())) {
                return PermissionResult.PassthroughDecision.builder()
                        .message("No mode-specific handling for '" + cmd.name() +
                                "' in acceptEdits mode")
                        .build();
            }
        }
        return PermissionResult.PassthroughDecision.builder()
                .message("passthrough")
                .build();
    }

    private static PermissionResult validateNestedCommands(
            List<ParsedCommandElement> nestedCommands, String rawCommand) {
        for (ParsedCommandElement cmd : nestedCommands) {
            if (!"CommandAst".equals(cmd.elementType())) {
                return PermissionResult.PassthroughDecision.builder()
                        .message("Nested expression element (" + cmd.elementType() +
                                ") cannot be statically validated")
                        .build();
            }
            if ("application".equals(cmd.nameType())) {
                return PermissionResult.PassthroughDecision.builder()
                        .message("Nested command '" + cmd.name() +
                                "' resolved from a path-like name and requires approval")
                        .build();
            }
            if (isSafeOutputCommand(cmd.name())) continue;
            if (!isAcceptEditsAllowedCmdlet(cmd.name())) {
                return PermissionResult.PassthroughDecision.builder()
                        .message("No mode-specific handling for '" + cmd.name() +
                                "' in acceptEdits mode")
                        .build();
            }
        }
        return PermissionResult.PassthroughDecision.builder()
                .message("passthrough")
                .build();
    }

    /**
     * Resolves a cmdlet name to its canonical form via COMMON_ALIASES.
     * Returns the input (lowercased) if no alias mapping exists.
     */
    static String resolveToCanonical(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase();
        return COMMON_ALIASES.getOrDefault(lower, lower);
    }

    /**
     * Returns {@code true} if the cmdlet (or its alias) is in the acceptEdits allowlist.
     */
    static boolean isAcceptEditsAllowedCmdlet(String name) {
        return ACCEPT_EDITS_ALLOWED_CMDLETS.contains(resolveToCanonical(name));
    }

    /**
     * Returns {@code true} if the cmdlet (or its alias) changes the current working directory.
     */
    static boolean isCwdChangingCmdlet(String name) {
        return CWD_CHANGING_CMDLETS.contains(resolveToCanonical(name));
    }

    /**
     * Returns {@code true} if the cmdlet is a safe output-only command that can be skipped
     * during acceptEdits validation (Out-Null, Write-Output, etc.).
     */
    static boolean isSafeOutputCommand(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.equals("out-null")
                || lower.equals("write-output")
                || lower.equals("write-host")
                || lower.equals("write-verbose")
                || lower.equals("write-debug")
                || lower.equals("write-information");
    }

    /**
     * Detects New-Item creating a filesystem link (-ItemType SymbolicLink / Junction / HardLink,
     * or the -Type alias). Links poison subsequent path resolution.
     *
     * Handles PS parameter abbreviation, unicode dash prefixes, and colon-bound values.
     */
    static boolean isSymlinkCreatingCommand(ParsedCommandElement cmd) {
        if (!"new-item".equals(resolveToCanonical(cmd.name()))) return false;
        List<String> args = cmd.args();
        if (args == null) return false;

        for (int i = 0; i < args.size(); i++) {
            String raw = args.get(i);
            if (raw == null || raw.isEmpty()) continue;

            // Normalize unicode dash prefixes and forward-slash (PS 5.1) → ASCII `-`
            char first = raw.charAt(0);
            boolean isDashLike = first == '\u002D' || first == '\u2013'
                    || first == '\u2014' || first == '\u2015';
            String normalized = (isDashLike || first == '/') ? "-" + raw.substring(1) : raw;
            String lower = normalized.toLowerCase();

            // Split colon-bound value: -it:SymbolicLink → param='-it', val='symboliclink'
            int colonIdx = lower.indexOf(':', 1);
            String paramRaw = colonIdx > 0 ? lower.substring(0, colonIdx) : lower;
            // Strip backtick escapes from param name
            String param = paramRaw.replace("`", "");

            if (!isItemTypeParamAbbrev(param)) continue;

            String rawVal;
            if (colonIdx > 0) {
                rawVal = lower.substring(colonIdx + 1);
            } else if (i + 1 < args.size()) {
                rawVal = args.get(i + 1).toLowerCase();
            } else {
                rawVal = "";
            }
            // Strip backtick escapes and surrounding quotes from value
            String val = rawVal.replace("`", "").replaceAll("^['\"]|['\"]$", "");
            if (LINK_ITEM_TYPES.contains(val)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code p} is an unambiguous PS abbreviation of
     * New-Item's {@code -ItemType} or {@code -Type} parameter.
     * Min prefix lengths: {@code -it} (3 chars) and {@code -ty} (3 chars).
     */
    private static boolean isItemTypeParamAbbrev(String p) {
        return (p.length() >= 3 && "-itemtype".startsWith(p))
                || (p.length() >= 3 && "-type".startsWith(p));
    }

    private PowerShellModeValidation() {}
}

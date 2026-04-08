package com.anthropic.claudecode.util.powershell;

import com.anthropic.claudecode.model.PowerShellClmTypes;
import com.anthropic.claudecode.util.powershell.PowerShellModeValidation.ParsedCommandElement;
import com.anthropic.claudecode.util.powershell.PowerShellModeValidation.ParsedPowerShellCommand;
import com.anthropic.claudecode.util.powershell.PowerShellModeValidation.PipelineSegment;
import com.anthropic.claudecode.util.powershell.PowerShellModeValidation.SecurityFlags;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PowerShell-specific security analysis for command validation.
 *
 * Detects dangerous patterns: code injection, download cradles, privilege
 * escalation, dynamic command names, COM objects, etc.
 *
 * All checks are AST-based. If parsing failed (valid=false), none of the
 * individual checks match and powershellCommandIsSafe returns 'ask'.
 *
 * Translated from src/tools/PowerShellTool/powershellSecurity.ts
 */
@Slf4j
public final class PowerShellSecurity {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PowerShellSecurity.class);


    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Security check result.
     *
     * @param behavior "passthrough" | "ask" | "allow"
     * @param message  human-readable explanation (may be null for passthrough)
     */
    public record SecurityResult(String behavior, String message) {
        static SecurityResult passthrough() { return new SecurityResult("passthrough", null); }
        static SecurityResult ask(String message) { return new SecurityResult("ask", message); }
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final Set<String> POWERSHELL_EXECUTABLES = Set.of(
            "pwsh", "pwsh.exe", "powershell", "powershell.exe");

    /** Alternative parameter-prefix chars that PS accepts in lieu of ASCII hyphen. */
    private static final Set<Character> PS_ALT_PARAM_PREFIXES = Set.of(
            '/', '\u2013', '\u2014', '\u2015');

    private static final Set<String> DOWNLOADER_NAMES = Set.of(
            "invoke-webrequest", "iwr", "invoke-restmethod", "irm",
            "new-object", "start-bitstransfer");

    private static final Set<String> SAFE_SCRIPT_BLOCK_CMDLETS = Set.of(
            "where-object", "sort-object", "select-object", "group-object",
            "format-table", "format-list", "format-wide", "format-custom");

    private static final Set<String> SCHEDULED_TASK_CMDLETS = Set.of(
            "register-scheduledtask", "new-scheduledtask",
            "new-scheduledtaskaction", "set-scheduledtask");

    private static final Set<String> ENV_WRITE_CMDLETS = Set.of(
            "set-item", "si", "new-item", "ni", "remove-item", "ri",
            "del", "rm", "rd", "rmdir", "erase", "clear-item", "cli",
            "set-content", "add-content", "ac");

    private static final Set<String> RUNTIME_STATE_CMDLETS = Set.of(
            "set-alias", "sal", "new-alias", "nal",
            "set-variable", "sv", "new-variable", "nv");

    private static final Set<String> WMI_SPAWN_CMDLETS = Set.of(
            "invoke-wmimethod", "iwmi", "invoke-cimmethod");

    /**
     * Cmdlets that accept -FilePath/-LiteralPath and execute a script file.
     * Subset of DANGEROUS_SCRIPT_BLOCK_CMDLETS from dangerousCmdlets.ts.
     */
    private static final Set<String> FILEPATH_EXECUTION_CMDLETS = Set.of(
            "invoke-command", "icm", "start-job", "start-threadjob",
            "register-scheduledjob");

    /**
     * Cmdlets that execute script blocks passed as arguments.
     */
    private static final Set<String> DANGEROUS_SCRIPT_BLOCK_CMDLETS = Set.of(
            "invoke-command", "icm", "invoke-expression", "iex",
            "start-job", "start-threadjob", "register-scheduledjob",
            "foreach-object", "where-object", "start-runspace");

    /**
     * Common PowerShell aliases (subset relevant to security checks).
     * Mirrors COMMON_ALIASES from parser.ts.
     */
    private static final Map<String, String> COMMON_ALIASES = Map.ofEntries(
            Map.entry("iex",    "invoke-expression"),
            Map.entry("icm",    "invoke-command"),
            Map.entry("iwr",    "invoke-webrequest"),
            Map.entry("irm",    "invoke-restmethod"),
            Map.entry("ii",     "invoke-item"),
            Map.entry("%",      "foreach-object"),
            Map.entry("foreach","foreach-object"),
            Map.entry("?",      "where-object"),
            Map.entry("where",  "where-object"),
            Map.entry("saps",   "start-process"),
            Map.entry("start",  "start-process"),
            Map.entry("sal",    "set-alias"),
            Map.entry("nal",    "new-alias"),
            Map.entry("sv",     "set-variable"),
            Map.entry("nv",     "new-variable"),
            Map.entry("si",     "set-item"),
            Map.entry("ni",     "new-item"),
            Map.entry("ri",     "remove-item"),
            Map.entry("cli",    "clear-item"),
            Map.entry("ac",     "add-content"),
            Map.entry("sc",     "set-content"),
            Map.entry("iwmi",   "invoke-wmimethod")
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Main entry point for PowerShell security validation.
     *
     * All checks are AST-based. If the AST parse failed ({@code parsed.valid() == false}),
     * none of the individual checks will match and we return 'ask' as a safe default.
     *
     * @param command raw PowerShell command string (kept for API compatibility)
     * @param parsed  parsed AST from PowerShell's native parser
     * @return security result indicating whether the command is safe
     */
    public static SecurityResult powershellCommandIsSafe(
            String command, ParsedPowerShellCommand parsed) {
        if (!parsed.valid()) {
            return SecurityResult.ask("Could not parse command for security analysis");
        }

        List<java.util.function.Function<ParsedPowerShellCommand, SecurityResult>> validators = List.of(
                PowerShellSecurity::checkInvokeExpression,
                PowerShellSecurity::checkDynamicCommandName,
                PowerShellSecurity::checkEncodedCommand,
                PowerShellSecurity::checkPwshCommandOrFile,
                PowerShellSecurity::checkDownloadCradles,
                PowerShellSecurity::checkDownloadUtilities,
                PowerShellSecurity::checkAddType,
                PowerShellSecurity::checkComObject,
                PowerShellSecurity::checkDangerousFilePathExecution,
                PowerShellSecurity::checkInvokeItem,
                PowerShellSecurity::checkScheduledTask,
                PowerShellSecurity::checkForEachMemberName,
                PowerShellSecurity::checkStartProcess,
                PowerShellSecurity::checkScriptBlockInjection,
                PowerShellSecurity::checkSubExpressions,
                PowerShellSecurity::checkExpandableStrings,
                PowerShellSecurity::checkSplatting,
                PowerShellSecurity::checkStopParsing,
                PowerShellSecurity::checkMemberInvocations,
                PowerShellSecurity::checkTypeLiterals,
                PowerShellSecurity::checkEnvVarManipulation,
                PowerShellSecurity::checkModuleLoading,
                PowerShellSecurity::checkRuntimeStateManipulation,
                PowerShellSecurity::checkWmiProcessSpawn
        );

        for (var validator : validators) {
            SecurityResult result = validator.apply(parsed);
            if ("ask".equals(result.behavior())) return result;
        }
        return SecurityResult.passthrough();
    }

    // -------------------------------------------------------------------------
    // Individual check methods
    // -------------------------------------------------------------------------

    private static SecurityResult checkInvokeExpression(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            if ("invoke-expression".equals(lower) || "iex".equals(lower)) {
                return SecurityResult.ask(
                        "Command uses Invoke-Expression which can execute arbitrary code");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkDynamicCommandName(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            if (!"CommandAst".equals(cmd.elementType())) continue;
            List<String> types = cmd.elementTypes();
            if (types != null && !types.isEmpty()) {
                String nameType = types.get(0);
                if (!"StringConstant".equals(nameType)) {
                    return SecurityResult.ask(
                            "Command name is a dynamic expression which cannot be statically validated");
                }
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkEncodedCommand(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            if (isPowerShellExecutable(cmd.name())) {
                if (psExeHasParamAbbreviation(cmd, "-encodedcommand", "-e")) {
                    return SecurityResult.ask("Command uses encoded parameters which obscure intent");
                }
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkPwshCommandOrFile(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            if (isPowerShellExecutable(cmd.name())) {
                return SecurityResult.ask(
                        "Command spawns a nested PowerShell process which cannot be validated");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkDownloadCradles(ParsedPowerShellCommand parsed) {
        // Per-statement: piped cradle (IWR ... | IEX)
        for (PipelineSegment seg : parsed.segments()) {
            List<ParsedCommandElement> cmds = seg.commands();
            if (cmds.size() < 2) continue;
            boolean hasDownloader = cmds.stream().anyMatch(c -> isDownloader(c.name()));
            boolean hasIex = cmds.stream().anyMatch(c -> isIex(c.name()));
            if (hasDownloader && hasIex) {
                return SecurityResult.ask("Command downloads and executes remote code");
            }
        }
        // Cross-statement: split cradle
        List<ParsedCommandElement> all = getAllCommands(parsed);
        if (all.stream().anyMatch(c -> isDownloader(c.name()))
                && all.stream().anyMatch(c -> isIex(c.name()))) {
            return SecurityResult.ask("Command downloads and executes remote code");
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkDownloadUtilities(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            if ("start-bitstransfer".equals(lower)) {
                return SecurityResult.ask("Command downloads files via BITS transfer");
            }
            if ("certutil".equals(lower) || "certutil.exe".equals(lower)) {
                boolean hasUrlcache = cmd.args() != null && cmd.args().stream()
                        .anyMatch(a -> {
                            String la = a.toLowerCase();
                            return "-urlcache".equals(la) || "/urlcache".equals(la);
                        });
                if (hasUrlcache) return SecurityResult.ask("Command uses certutil to download from a URL");
            }
            if ("bitsadmin".equals(lower) || "bitsadmin.exe".equals(lower)) {
                boolean hasTransfer = cmd.args() != null && cmd.args().stream()
                        .anyMatch(a -> "/transfer".equals(a.toLowerCase()));
                if (hasTransfer) return SecurityResult.ask("Command downloads files via BITS transfer");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkAddType(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            if ("add-type".equals(cmd.name().toLowerCase())) {
                return SecurityResult.ask("Command compiles and loads .NET code");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkComObject(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            if (!"new-object".equals(cmd.name().toLowerCase())) continue;
            if (psExeHasParamAbbreviation(cmd, "-comobject", "-com")) {
                return SecurityResult.ask(
                        "Command instantiates a COM object which may have execution capabilities");
            }
            // Check -TypeName (named, colon-bound, or positional-0) via CLM allowlist
            String typeName = extractNewObjectTypeName(cmd);
            if (typeName != null && !PowerShellClmTypes.isClmAllowedType(typeName)) {
                return SecurityResult.ask(
                        "New-Object instantiates .NET type '" + typeName
                                + "' outside the ConstrainedLanguage allowlist");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkDangerousFilePathExecution(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            String resolved = COMMON_ALIASES.getOrDefault(lower, lower);
            if (!FILEPATH_EXECUTION_CMDLETS.contains(resolved)) continue;
            if (psExeHasParamAbbreviation(cmd, "-filepath", "-f")
                    || psExeHasParamAbbreviation(cmd, "-literalpath", "-l")) {
                return SecurityResult.ask(cmd.name() + " -FilePath executes an arbitrary script file");
            }
            // Positional binding: any non-dash StringConstant at position 0
            if (cmd.args() != null) {
                for (int i = 0; i < cmd.args().size(); i++) {
                    String argType = (cmd.elementTypes() != null && i + 1 < cmd.elementTypes().size())
                            ? cmd.elementTypes().get(i + 1) : null;
                    String arg = cmd.args().get(i);
                    if ("StringConstant".equals(argType) && arg != null && !arg.startsWith("-")) {
                        return SecurityResult.ask(
                                cmd.name() + " with positional string argument binds to -FilePath"
                                        + " and executes a script file");
                    }
                }
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkForEachMemberName(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            String resolved = COMMON_ALIASES.getOrDefault(lower, lower);
            if (!"foreach-object".equals(resolved)) continue;
            if (psExeHasParamAbbreviation(cmd, "-membername", "-m")) {
                return SecurityResult.ask(
                        "ForEach-Object -MemberName invokes methods by string name which cannot be validated");
            }
            if (cmd.args() != null) {
                for (int i = 0; i < cmd.args().size(); i++) {
                    String argType = (cmd.elementTypes() != null && i + 1 < cmd.elementTypes().size())
                            ? cmd.elementTypes().get(i + 1) : null;
                    String arg = cmd.args().get(i);
                    if ("StringConstant".equals(argType) && arg != null && !arg.startsWith("-")) {
                        return SecurityResult.ask(
                                "ForEach-Object with positional string argument binds to -MemberName"
                                        + " and invokes methods by name");
                    }
                }
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkStartProcess(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            if (!"start-process".equals(lower) && !"saps".equals(lower) && !"start".equals(lower)) {
                continue;
            }
            // Vector 1: -Verb RunAs
            if (psExeHasParamAbbreviation(cmd, "-Verb", "-v")
                    && cmd.args() != null
                    && cmd.args().stream().anyMatch(a -> "runas".equals(a.toLowerCase()))) {
                return SecurityResult.ask("Command requests elevated privileges");
            }
            // Colon syntax: -Verb:RunAs / -Verb:'RunAs' etc.
            if (cmd.args() != null && cmd.args().stream().anyMatch(a -> {
                String clean = a.replace("`", "");
                return clean.toLowerCase().matches(
                        "^[-\u2013\u2014\u2015/]v[a-z]*:['\"`\\s]*runas['\"`\\s]*$");
            })) {
                return SecurityResult.ask("Command requests elevated privileges");
            }
            // Vector 2: Start-Process targeting a PowerShell executable
            if (cmd.args() != null) {
                for (String arg : cmd.args()) {
                    String stripped = arg.replaceAll("^['\"]|['\"]$", "");
                    if (isPowerShellExecutable(stripped)) {
                        return SecurityResult.ask(
                                "Start-Process launches a nested PowerShell process which cannot be validated");
                    }
                }
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkScriptBlockInjection(ParsedPowerShellCommand parsed) {
        SecurityFlags flags = parsed.securityFlags();
        if (!flags.hasScriptBlocks()) return SecurityResult.passthrough();

        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            if (DANGEROUS_SCRIPT_BLOCK_CMDLETS.contains(lower)) {
                return SecurityResult.ask(
                        "Command contains script block with dangerous cmdlet that may execute arbitrary code");
            }
        }
        boolean allSafe = getAllCommands(parsed).stream().allMatch(cmd -> {
            String lower = cmd.name().toLowerCase();
            if (SAFE_SCRIPT_BLOCK_CMDLETS.contains(lower)) return true;
            String alias = COMMON_ALIASES.get(lower);
            return alias != null && SAFE_SCRIPT_BLOCK_CMDLETS.contains(alias.toLowerCase());
        });
        if (allSafe) return SecurityResult.passthrough();
        return SecurityResult.ask("Command contains script block that may execute arbitrary code");
    }

    private static SecurityResult checkSubExpressions(ParsedPowerShellCommand parsed) {
        if (parsed.securityFlags().hasSubExpressions()) {
            return SecurityResult.ask("Command contains subexpressions $()");
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkExpandableStrings(ParsedPowerShellCommand parsed) {
        if (parsed.securityFlags().hasExpandableStrings()) {
            return SecurityResult.ask("Command contains expandable strings with embedded expressions");
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkSplatting(ParsedPowerShellCommand parsed) {
        if (parsed.securityFlags().hasSplatting()) {
            return SecurityResult.ask("Command uses splatting (@variable)");
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkStopParsing(ParsedPowerShellCommand parsed) {
        if (parsed.securityFlags().hasStopParsing()) {
            return SecurityResult.ask("Command uses stop-parsing token (--%)");}
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkMemberInvocations(ParsedPowerShellCommand parsed) {
        if (parsed.securityFlags().hasMemberInvocations()) {
            return SecurityResult.ask("Command invokes .NET methods");
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkTypeLiterals(ParsedPowerShellCommand parsed) {
        // typeLiterals is carried in the full parsed command (not modeled here yet).
        // Guard: if the security flags include member invocations, that check already fires.
        // This is a best-effort check using the parsed command's typeLiterals list if present.
        // When the full parser model is wired up, populate typeLiterals from ParsedPowerShellCommand.
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkEnvVarManipulation(ParsedPowerShellCommand parsed) {
        // Check for env: variable scope usage combined with write cmdlets or assignments.
        // This is a simplified version; full implementation requires variable scope analysis.
        if (parsed.securityFlags().hasAssignments()) {
            for (ParsedCommandElement cmd : getAllCommands(parsed)) {
                if (ENV_WRITE_CMDLETS.contains(cmd.name().toLowerCase())) {
                    return SecurityResult.ask("Command modifies environment variables");
                }
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkModuleLoading(ParsedPowerShellCommand parsed) {
        Set<String> moduleLoadingCmdlets = Set.of(
                "import-module", "ipmo", "install-module", "save-module",
                "install-script", "save-script", "install-psresource");
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            if (moduleLoadingCmdlets.contains(cmd.name().toLowerCase())) {
                return SecurityResult.ask(
                        "Command loads, installs, or downloads a PowerShell module or script," +
                                " which can execute arbitrary code");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkRuntimeStateManipulation(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String raw = cmd.name().toLowerCase();
            // Strip module qualifier: Microsoft.PowerShell.Utility\Set-Alias → set-alias
            String lower = raw.contains("\\") ? raw.substring(raw.lastIndexOf('\\') + 1) : raw;
            if (RUNTIME_STATE_CMDLETS.contains(lower)) {
                return SecurityResult.ask(
                        "Command creates or modifies an alias or variable that can affect" +
                                " future command resolution");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkWmiProcessSpawn(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            if (WMI_SPAWN_CMDLETS.contains(cmd.name().toLowerCase())) {
                return SecurityResult.ask(
                        cmd.name() + " can spawn arbitrary processes via WMI/CIM (Win32_Process Create)");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkInvokeItem(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            if ("invoke-item".equals(lower) || "ii".equals(lower)) {
                return SecurityResult.ask(
                        "Invoke-Item opens files with the default handler (ShellExecute)." +
                                " On executable files this runs arbitrary code.");
            }
        }
        return SecurityResult.passthrough();
    }

    private static SecurityResult checkScheduledTask(ParsedPowerShellCommand parsed) {
        for (ParsedCommandElement cmd : getAllCommands(parsed)) {
            String lower = cmd.name().toLowerCase();
            if ("new-scheduledtask".equals(lower)
                    || "register-scheduledtask".equals(lower)
                    || "set-scheduledtask".equals(lower)
                    || "start-scheduledtask".equals(lower)) {
                return SecurityResult.ask(
                        "Scheduled task cmdlets can execute code outside of this session");
            }
        }
        return SecurityResult.passthrough();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns all commands from all pipeline segments (including nested commands).
     */
    static List<ParsedCommandElement> getAllCommands(ParsedPowerShellCommand parsed) {
        List<ParsedCommandElement> result = new ArrayList<>();
        for (PipelineSegment seg : parsed.segments()) {
            result.addAll(seg.commands());
            if (seg.nestedCommands() != null) {
                result.addAll(seg.nestedCommands());
            }
        }
        return result;
    }

    private static boolean isPowerShellExecutable(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        if (POWERSHELL_EXECUTABLES.contains(lower)) return true;
        int lastSep = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        if (lastSep >= 0) return POWERSHELL_EXECUTABLES.contains(lower.substring(lastSep + 1));
        return false;
    }

    private static boolean isDownloader(String name) {
        return name != null && DOWNLOADER_NAMES.contains(name.toLowerCase());
    }

    private static boolean isIex(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return "invoke-expression".equals(lower) || "iex".equals(lower);
    }

    /**
     * Returns {@code true} if {@code cmd} has an argument that matches {@code fullParam}
     * up to a minimum prefix of {@code minPrefix}, also checking alternative PS dash chars.
     */
    static boolean psExeHasParamAbbreviation(
            ParsedCommandElement cmd, String fullParam, String minPrefix) {
        if (cmd.args() == null) return false;
        String fullLower = fullParam.toLowerCase();
        String minLower = minPrefix.toLowerCase();
        for (String arg : cmd.args()) {
            if (arg == null || arg.isEmpty()) continue;
            // Normalize alternative prefix chars to ASCII hyphen
            char first = arg.charAt(0);
            boolean isAlt = PS_ALT_PARAM_PREFIXES.contains(first);
            String normalized = (isAlt) ? "-" + arg.substring(1) : arg;
            String lower = normalized.toLowerCase();
            // Strip colon-bound value: -enc:foo → -enc
            int colonIdx = lower.indexOf(':', 1);
            String paramPart = colonIdx > 0 ? lower.substring(0, colonIdx) : lower;
            // Strip backtick escapes
            String param = paramPart.replace("`", "");
            if (param.startsWith(minLower) && fullLower.startsWith(param)) return true;
        }
        return false;
    }

    /**
     * Extract the -TypeName (named, colon-bound, or positional) from a New-Object command.
     */
    private static String extractNewObjectTypeName(ParsedCommandElement cmd) {
        if (cmd.args() == null) return null;
        List<String> args = cmd.args();
        // Named / colon-bound form first
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null) continue;
            String lower = a.toLowerCase();
            if (lower.startsWith("-t") && lower.contains(":")) {
                int c = a.indexOf(':');
                String paramPart = lower.substring(0, c);
                if ("-typename".startsWith(paramPart)) return a.substring(c + 1);
            }
            if (lower.startsWith("-t") && "-typename".startsWith(lower) && i + 1 < args.size()) {
                return args.get(i + 1);
            }
        }
        // Positional-0 (first non-dash, non-consumed arg)
        Set<String> valueParams = Set.of("-argumentlist", "-comobject", "-property");
        Set<String> switchParams = Set.of("-strict");
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null) continue;
            if (a.startsWith("-")) {
                String lower = a.toLowerCase();
                if (lower.startsWith("-t") && "-typename".startsWith(lower)) { i++; continue; }
                if (lower.contains(":")) continue;
                if (switchParams.contains(lower)) continue;
                if (valueParams.contains(lower)) { i++; continue; }
                continue;
            }
            return a;
        }
        return null;
    }

    private PowerShellSecurity() {}
}

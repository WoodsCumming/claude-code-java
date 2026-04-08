package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Client-side secret scanner for team memory (PSR M22174).
 * Translated from src/services/teamMemorySync/secretScanner.ts
 *
 * Scans content for credentials before upload so secrets never leave the
 * user's machine. Uses a curated subset of high-confidence rules from
 * gitleaks (https://github.com/gitleaks/gitleaks, MIT license) — only
 * rules with distinctive prefixes that have near-zero false-positive
 * rates are included. Generic keyword-context rules are omitted.
 *
 * Rule IDs and regexes sourced directly from the public gitleaks config:
 * https://github.com/gitleaks/gitleaks/blob/master/config/gitleaks.toml
 */
@Slf4j
@Service
public class SecretScannerService {


    // Anthropic API key prefix assembled at runtime so the literal byte
    // sequence isn't present in the source bundle.
    private static final String ANT_KEY_PFX = "sk-ant-api";

    private record SecretRule(String id, String source, String flags) {
        SecretRule(String id, String source) {
            this(id, source, null);
        }
    }

    /**
     * Curated high-confidence secret detection rules from gitleaks.
     * Ordered roughly by likelihood of appearing in dev-team content.
     */
    private static final List<SecretRule> SECRET_RULES = List.of(
        // — Cloud providers —
        new SecretRule("aws-access-token",
            "\\b((?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16})\\b"),
        new SecretRule("gcp-api-key",
            "\\b(AIza[\\w-]{35})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("azure-ad-client-secret",
            "(?:^|[\\\\'\"`\\s>=:(,)])([a-zA-Z0-9_~.]{3}\\dQ~[a-zA-Z0-9_~.-]{31,34})(?:$|[\\\\'\"`\\s<),])"),
        new SecretRule("digitalocean-pat",
            "\\b(dop_v1_[a-f0-9]{64})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("digitalocean-access-token",
            "\\b(doo_v1_[a-f0-9]{64})(?:[`'\"\\s;]|\\\\[nr]|$)"),

        // — AI APIs —
        new SecretRule("anthropic-api-key",
            "\\b(" + ANT_KEY_PFX + "03-[a-zA-Z0-9_\\-]{93}AA)(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("anthropic-admin-api-key",
            "\\b(sk-ant-admin01-[a-zA-Z0-9_\\-]{93}AA)(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("openai-api-key",
            "\\b(sk-(?:proj|svcacct|admin)-(?:[A-Za-z0-9_-]{74}|[A-Za-z0-9_-]{58})T3BlbkFJ(?:[A-Za-z0-9_-]{74}|[A-Za-z0-9_-]{58})\\b|sk-[a-zA-Z0-9]{20}T3BlbkFJ[a-zA-Z0-9]{20})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("huggingface-access-token",
            "\\b(hf_[a-zA-Z]{34})(?:[`'\"\\s;]|\\\\[nr]|$)"),

        // — Version control —
        new SecretRule("github-pat",
            "ghp_[0-9a-zA-Z]{36}"),
        new SecretRule("github-fine-grained-pat",
            "github_pat_\\w{82}"),
        new SecretRule("github-app-token",
            "(?:ghu|ghs)_[0-9a-zA-Z]{36}"),
        new SecretRule("github-oauth",
            "gho_[0-9a-zA-Z]{36}"),
        new SecretRule("github-refresh-token",
            "ghr_[0-9a-zA-Z]{36}"),
        new SecretRule("gitlab-pat",
            "glpat-[\\w-]{20}"),
        new SecretRule("gitlab-deploy-token",
            "gldt-[0-9a-zA-Z_\\-]{20}"),

        // — Communication —
        new SecretRule("slack-bot-token",
            "xoxb-[0-9]{10,13}-[0-9]{10,13}[a-zA-Z0-9-]*"),
        new SecretRule("slack-user-token",
            "xox[pe](?:-[0-9]{10,13}){3}-[a-zA-Z0-9-]{28,34}"),
        new SecretRule("slack-app-token",
            "xapp-\\d-[A-Z0-9]+-\\d+-[a-z0-9]+", "i"),
        new SecretRule("twilio-api-key",
            "SK[0-9a-fA-F]{32}"),
        new SecretRule("sendgrid-api-token",
            "\\b(SG\\.[a-zA-Z0-9=_\\-.]{66})(?:[`'\"\\s;]|\\\\[nr]|$)"),

        // — Dev tooling —
        new SecretRule("npm-access-token",
            "\\b(npm_[a-zA-Z0-9]{36})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("pypi-upload-token",
            "pypi-AgEIcHlwaS5vcmc[\\w-]{50,1000}"),
        new SecretRule("databricks-api-token",
            "\\b(dapi[a-f0-9]{32}(?:-\\d)?)(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("hashicorp-tf-api-token",
            "[a-zA-Z0-9]{14}\\.atlasv1\\.[a-zA-Z0-9\\-_=]{60,70}"),
        new SecretRule("pulumi-api-token",
            "\\b(pul-[a-f0-9]{40})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("postman-api-token",
            "\\b(PMAK-[a-fA-F0-9]{24}-[a-fA-F0-9]{34})(?:[`'\"\\s;]|\\\\[nr]|$)"),

        // — Observability —
        new SecretRule("grafana-api-key",
            "\\b(eyJrIjoi[A-Za-z0-9+/]{70,400}={0,3})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("grafana-cloud-api-token",
            "\\b(glc_[A-Za-z0-9+/]{32,400}={0,3})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("grafana-service-account-token",
            "\\b(glsa_[A-Za-z0-9]{32}_[A-Fa-f0-9]{8})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("sentry-user-token",
            "\\b(sntryu_[a-f0-9]{64})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("sentry-org-token",
            "\\bsntrys_eyJpYXQiO[a-zA-Z0-9+/]{10,200}(?:LCJyZWdpb25fdXJs|InJlZ2lvbl91cmwi|cmVnaW9uX3VybCI6)[a-zA-Z0-9+/]{10,200}={0,2}_[a-zA-Z0-9+/]{43}"),

        // — Payment / commerce —
        new SecretRule("stripe-access-token",
            "\\b((?:sk|rk)_(?:test|live|prod)_[a-zA-Z0-9]{10,99})(?:[`'\"\\s;]|\\\\[nr]|$)"),
        new SecretRule("shopify-access-token",
            "shpat_[a-fA-F0-9]{32}"),
        new SecretRule("shopify-shared-secret",
            "shpss_[a-fA-F0-9]{32}"),

        // — Crypto —
        new SecretRule("private-key",
            "-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----[\\s\\S-]{64,}?-----END[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----",
            "i")
    );

    /**
     * Special-case capitalizations for rule ID words (kebab-case segments).
     */
    private static final Map<String, String> SPECIAL_CASE = Map.ofEntries(
        Map.entry("aws", "AWS"),
        Map.entry("gcp", "GCP"),
        Map.entry("api", "API"),
        Map.entry("pat", "PAT"),
        Map.entry("ad", "AD"),
        Map.entry("tf", "TF"),
        Map.entry("oauth", "OAuth"),
        Map.entry("npm", "NPM"),
        Map.entry("pypi", "PyPI"),
        Map.entry("jwt", "JWT"),
        Map.entry("github", "GitHub"),
        Map.entry("gitlab", "GitLab"),
        Map.entry("openai", "OpenAI"),
        Map.entry("digitalocean", "DigitalOcean"),
        Map.entry("huggingface", "HuggingFace"),
        Map.entry("hashicorp", "HashiCorp"),
        Map.entry("sendgrid", "SendGrid")
    );

    /**
     * Lazily compiled pattern cache — compiled once on first use.
     */
    private final AtomicReference<List<CompiledRule>> compiledRulesRef = new AtomicReference<>();

    private record CompiledRule(String id, Pattern pattern) {}

    private List<CompiledRule> getCompiledRules() {
        List<CompiledRule> rules = compiledRulesRef.get();
        if (rules == null) {
            List<CompiledRule> compiled = new ArrayList<>(SECRET_RULES.size());
            for (SecretRule rule : SECRET_RULES) {
                try {
                    int flags = Pattern.DOTALL; // [\s\S] patterns need DOTALL
                    if ("i".equalsIgnoreCase(rule.flags())) {
                        flags |= Pattern.CASE_INSENSITIVE;
                    }
                    compiled.add(new CompiledRule(rule.id(), Pattern.compile(rule.source(), flags)));
                } catch (PatternSyntaxException e) {
                    log.debug("Invalid secret rule pattern for {}: {}", rule.id(), e.getMessage());
                }
            }
            rules = Collections.unmodifiableList(compiled);
            compiledRulesRef.compareAndSet(null, rules);
            rules = compiledRulesRef.get();
        }
        return rules;
    }

    /**
     * Convert a gitleaks rule ID (kebab-case) to a human-readable label.
     * e.g., "github-pat" -> "GitHub PAT", "aws-access-token" -> "AWS Access Token"
     */
    public String ruleIdToLabel(String ruleId) {
        String[] parts = ruleId.split("-");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            String special = SPECIAL_CASE.get(part.toLowerCase());
            if (special != null) {
                sb.append(special);
            } else if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Scan a string for potential secrets.
     *
     * Returns one match per rule that fired (deduplicated by rule ID).
     * The actual matched text is intentionally NOT returned — we never log
     * or display secret values.
     * Translated from scanForSecrets() in secretScanner.ts
     */
    public List<SecretMatch> scanForSecrets(String content) {
        if (content == null || content.isBlank()) return List.of();

        List<SecretMatch> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (CompiledRule rule : getCompiledRules()) {
            if (seen.contains(rule.id())) continue;
            if (rule.pattern().matcher(content).find()) {
                seen.add(rule.id());
                matches.add(new SecretMatch(rule.id(), ruleIdToLabel(rule.id())));
            }
        }
        return matches;
    }

    /**
     * Get a human-readable label for a gitleaks rule ID.
     * Falls back to kebab-to-Title conversion for unknown IDs.
     * Translated from getSecretLabel() in secretScanner.ts
     */
    public String getSecretLabel(String ruleId) {
        return ruleIdToLabel(ruleId);
    }

    /**
     * Redact any matched secrets in-place with [REDACTED].
     * Unlike scanForSecrets, this returns the content with spans replaced
     * so the surrounding text can still be written to disk safely.
     * Translated from redactSecrets() in secretScanner.ts
     */
    public String redactSecrets(String content) {
        if (content == null) return null;

        for (SecretRule rule : SECRET_RULES) {
            try {
                int flags = Pattern.DOTALL;
                if ("i".equalsIgnoreCase(rule.flags())) flags |= Pattern.CASE_INSENSITIVE;
                Pattern p = Pattern.compile(rule.source(), flags);
                Matcher m = p.matcher(content);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    // Replace only the captured group (group 1) if present,
                    // otherwise replace the full match — mirroring TS behaviour.
                    if (m.groupCount() >= 1 && m.group(1) != null) {
                        String fullMatch = m.group(0);
                        String replaced = fullMatch.replace(m.group(1), "[REDACTED]");
                        m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
                    } else {
                        m.appendReplacement(sb, "[REDACTED]");
                    }
                }
                m.appendTail(sb);
                content = sb.toString();
            } catch (PatternSyntaxException e) {
                log.debug("Invalid redact rule pattern for {}: {}", rule.id(), e.getMessage());
            }
        }
        return content;
    }

    /**
     * Match result for a detected secret.
     * Translated from SecretMatch type in secretScanner.ts
     */
    public static class SecretMatch {
        /** Gitleaks rule ID that matched (e.g., "github-pat", "aws-access-token") */
        private final String ruleId;
        /** Human-readable label derived from the rule ID */
        private final String label;

        public SecretMatch(String ruleId, String label) {
            this.ruleId = ruleId; this.label = label;
        }
        public String getRuleId() { return ruleId; }
        public String getLabel() { return label; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SecretMatch)) return false;
            SecretMatch that = (SecretMatch) o;
            return java.util.Objects.equals(ruleId, that.ruleId) && java.util.Objects.equals(label, that.label);
        }
        @Override public int hashCode() { return java.util.Objects.hash(ruleId, label); }
    }
}

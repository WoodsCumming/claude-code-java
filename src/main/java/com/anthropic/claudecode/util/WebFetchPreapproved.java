package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-approved host list for the WebFetch tool.
 * Translated from src/tools/WebFetchTool/preapproved.ts
 *
 * <p>SECURITY WARNING: These preapproved domains are ONLY for WebFetch (GET requests only).
 * The sandbox system deliberately does NOT inherit this list for network restrictions,
 * as arbitrary network access (POST, uploads, etc.) to these domains could enable
 * data exfiltration.</p>
 */
public class WebFetchPreapproved {

    /**
     * Full set of preapproved host entries.
     * Some entries contain a path prefix (e.g. "github.com/anthropics").
     */
    public static final Set<String> PREAPPROVED_HOSTS = Set.of(
            // Anthropic
            "platform.claude.com",
            "code.claude.com",
            "modelcontextprotocol.io",
            "github.com/anthropics",
            "agentskills.io",

            // Top Programming Languages
            "docs.python.org",
            "en.cppreference.com",
            "docs.oracle.com",
            "learn.microsoft.com",
            "developer.mozilla.org",
            "go.dev",
            "pkg.go.dev",
            "www.php.net",
            "docs.swift.org",
            "kotlinlang.org",
            "ruby-doc.org",
            "doc.rust-lang.org",
            "www.typescriptlang.org",

            // Web & JavaScript Frameworks/Libraries
            "react.dev",
            "angular.io",
            "vuejs.org",
            "nextjs.org",
            "expressjs.com",
            "nodejs.org",
            "bun.sh",
            "jquery.com",
            "getbootstrap.com",
            "tailwindcss.com",
            "d3js.org",
            "threejs.org",
            "redux.js.org",
            "webpack.js.org",
            "jestjs.io",
            "reactrouter.com",

            // Python Frameworks & Libraries
            "docs.djangoproject.com",
            "flask.palletsprojects.com",
            "fastapi.tiangolo.com",
            "pandas.pydata.org",
            "numpy.org",
            "www.tensorflow.org",
            "pytorch.org",
            "scikit-learn.org",
            "matplotlib.org",
            "requests.readthedocs.io",
            "jupyter.org",

            // PHP Frameworks
            "laravel.com",
            "symfony.com",
            "wordpress.org",

            // Java Frameworks & Libraries
            "docs.spring.io",
            "hibernate.org",
            "tomcat.apache.org",
            "gradle.org",
            "maven.apache.org",

            // .NET & C# Frameworks
            "asp.net",
            "dotnet.microsoft.com",
            "nuget.org",
            "blazor.net",

            // Mobile Development
            "reactnative.dev",
            "docs.flutter.dev",
            "developer.apple.com",
            "developer.android.com",

            // Data Science & Machine Learning
            "keras.io",
            "spark.apache.org",
            "huggingface.co",
            "www.kaggle.com",

            // Databases
            "www.mongodb.com",
            "redis.io",
            "www.postgresql.org",
            "dev.mysql.com",
            "www.sqlite.org",
            "graphql.org",
            "prisma.io",

            // Cloud & DevOps
            "docs.aws.amazon.com",
            "cloud.google.com",
            "kubernetes.io",
            "www.docker.com",
            "www.terraform.io",
            "www.ansible.com",
            "vercel.com/docs",
            "docs.netlify.com",
            "devcenter.heroku.com",

            // Testing & Monitoring
            "cypress.io",
            "selenium.dev",

            // Game Development
            "docs.unity.com",
            "docs.unrealengine.com",

            // Other Essential Tools
            "git-scm.com",
            "nginx.org",
            "httpd.apache.org"
    );

    // Pre-split at class-load time for O(1) Set.has() on hostname-only entries
    // and a small per-host path-prefix list for path-scoped entries.
    private static final Set<String> HOSTNAME_ONLY;
    private static final Map<String, List<String>> PATH_PREFIXES;

    static {
        java.util.HashSet<String> hosts = new java.util.HashSet<>();
        Map<String, List<String>> paths = new HashMap<>();

        for (String entry : PREAPPROVED_HOSTS) {
            int slash = entry.indexOf('/');
            if (slash == -1) {
                hosts.add(entry);
            } else {
                String host = entry.substring(0, slash);
                String path = entry.substring(slash);
                paths.computeIfAbsent(host, k -> new ArrayList<>()).add(path);
            }
        }

        HOSTNAME_ONLY = Set.copyOf(hosts);
        PATH_PREFIXES = Map.copyOf(paths);
    }

    /**
     * Returns true if the given hostname (and optional pathname) matches a preapproved entry.
     * Translated from isPreapprovedHost() in preapproved.ts
     *
     * <p>Path-segment boundaries are enforced: "/anthropics" must not match
     * "/anthropics-evil/malware". Only exact match or a "/" after the prefix is allowed.</p>
     *
     * @param hostname the URL hostname (e.g. "github.com")
     * @param pathname the URL path (e.g. "/anthropics/some-repo")
     */
    public static boolean isPreapprovedHost(String hostname, String pathname) {
        if (HOSTNAME_ONLY.contains(hostname)) return true;
        List<String> prefixes = PATH_PREFIXES.get(hostname);
        if (prefixes != null) {
            for (String p : prefixes) {
                if (pathname.equals(p) || pathname.startsWith(p + "/")) return true;
            }
        }
        return false;
    }

    private WebFetchPreapproved() {}
}

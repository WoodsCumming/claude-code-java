package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.GitUtils;
import com.anthropic.claudecode.util.TempFileUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Teleport git bundle service.
 * Translated from src/utils/teleport/gitBundle.ts
 *
 * Creates and uploads git bundles for CCR seed-bundle seeding.
 *
 * Flow:
 *   1. git stash create → update-ref refs/seed/stash (makes it reachable)
 *   2. git bundle create with fallback chain: --all → HEAD → squashed-root
 *   3. Upload to /v1/files
 *   4. Cleanup refs/seed/stash + refs/seed/root (don't pollute the repo)
 *   5. Caller sets seed_bundle_file_id on SessionContext
 */
@Slf4j
@Service
public class TeleportGitBundleService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeleportGitBundleService.class);


    // Tunable via feature flag (tengu_ccr_bundle_max_bytes)
    private static final long DEFAULT_BUNDLE_MAX_BYTES = 100L * 1024 * 1024; // 100 MB

    private final FilesApiService filesApiService;

    @Autowired
    public TeleportGitBundleService(FilesApiService filesApiService) {
        this.filesApiService = filesApiService;
    }

    // =========================================================================
    // Type definitions — mirrors TypeScript types in gitBundle.ts
    // =========================================================================

    public enum BundleScope { all, head, squashed }

    public sealed interface BundleUploadResult
        permits BundleUploadResult.Success, BundleUploadResult.Failure {

        record Success(
            String fileId,
            long bundleSizeBytes,
            BundleScope scope,
            boolean hasWip
        ) implements BundleUploadResult {}

        record Failure(
            String error,
            BundleFailReason failReason
        ) implements BundleUploadResult {}
    }

    public enum BundleFailReason { git_error, too_large, empty_repo }

    private sealed interface BundleCreateResult
        permits BundleCreateResult.Ok, BundleCreateResult.Err {

        record Ok(long size, BundleScope scope) implements BundleCreateResult {}
        record Err(String error, BundleFailReason failReason) implements BundleCreateResult {}
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Create a git bundle and upload it to the Files API.
     * Translated from createAndUploadGitBundle() in gitBundle.ts
     *
     * Uses --all → HEAD → squashed-root fallback chain.
     * Captures WIP via stash create → refs/seed/stash.
     */
    public CompletableFuture<BundleUploadResult> createAndUploadGitBundle(
            FilesApiService.FilesApiConfig config,
            String cwd) {
        return createAndUploadGitBundle(config, cwd, null);
    }

    public CompletableFuture<BundleUploadResult> createAndUploadGitBundle(
            FilesApiService.FilesApiConfig config,
            String cwd,
            String abortSignalMarker) {  // placeholder for abort signal concept

        return CompletableFuture.supplyAsync(() -> {
            String workdir = cwd != null ? cwd : System.getProperty("user.dir");
            Optional<String> gitRootOpt = GitUtils.findGitRoot(workdir);
            if (gitRootOpt.isEmpty()) {
                return (BundleUploadResult) new BundleUploadResult.Failure(
                    "Not in a git repository", null);
            }
            String gitRoot = gitRootOpt.get();

            // Sweep stale refs from a crashed prior run
            for (String ref : List.of("refs/seed/stash", "refs/seed/root")) {
                runGit(gitRoot, "update-ref", "-d", ref); // non-fatal
            }

            // Check for any existing refs (empty repo guard)
            ExecResult refCheck = runGit(gitRoot, "for-each-ref", "--count=1", "refs/");
            if (refCheck.code == 0 && refCheck.stdout.trim().isEmpty()) {
                log.debug("[gitBundle] empty_repo");
                return (BundleUploadResult) new BundleUploadResult.Failure(
                    "Repository has no commits yet", BundleFailReason.empty_repo);
            }

            // Capture WIP via stash create (doesn't touch the working tree)
            ExecResult stashResult = runGit(gitRoot, "stash", "create");
            String wipStashSha = stashResult.code == 0 ? stashResult.stdout.trim() : "";
            boolean hasWip = !wipStashSha.isEmpty();
            if (stashResult.code != 0) {
                log.debug("[gitBundle] git stash create failed ({}), proceeding without WIP: {}",
                    stashResult.code, truncate(stashResult.stderr, 200));
            } else if (hasWip) {
                log.debug("[gitBundle] Captured WIP as stash {}", wipStashSha);
                runGit(gitRoot, "update-ref", "refs/seed/stash", wipStashSha);
            }

            String bundlePath = TempFileUtils.generateTempFilePath("ccr-seed", ".bundle");
            try {
                long maxBytes = DEFAULT_BUNDLE_MAX_BYTES;

                BundleCreateResult bundle = bundleWithFallback(gitRoot, bundlePath, maxBytes, hasWip);
                if (bundle instanceof BundleCreateResult.Err err) {
                    log.debug("[gitBundle] {}", err.error());
                    return (BundleUploadResult) new BundleUploadResult.Failure(err.error(), err.failReason());
                }
                BundleCreateResult.Ok ok = (BundleCreateResult.Ok) bundle;

                // Upload to Files API
                FilesApiService.FileSpec spec = new FilesApiService.FileSpec(bundlePath, "_source_seed.bundle");
                List<FilesApiService.UploadResult> results =
                    filesApiService.uploadSessionFiles(List.of(spec), config, 1).join();

                if (results.isEmpty() || !results.get(0).isSuccess()) {
                    return (BundleUploadResult) new BundleUploadResult.Failure(
                        "Upload failed", null);
                }

                FilesApiService.UploadResult upload = results.get(0);
                log.debug("[gitBundle] Uploaded {} bytes as file_id {}", ok.size(), upload.getFileId());
                return (BundleUploadResult) new BundleUploadResult.Success(
                    upload.getFileId(), ok.size(), ok.scope(), hasWip);

            } finally {
                // Cleanup temp bundle file
                try { Files.deleteIfExists(Paths.get(bundlePath)); } catch (IOException e) {
                    log.debug("[gitBundle] Could not delete {} (non-fatal)", bundlePath);
                }
                // Always clean seed refs
                for (String ref : List.of("refs/seed/stash", "refs/seed/root")) {
                    runGit(gitRoot, "update-ref", "-d", ref);
                }
            }
        });
    }

    // =========================================================================
    // Bundle creation with fallback chain
    // =========================================================================

    /**
     * Attempt --all → HEAD → squashed-root bundles in order.
     * Translated from _bundleWithFallback() in gitBundle.ts
     */
    private BundleCreateResult bundleWithFallback(String gitRoot, String bundlePath,
                                                   long maxBytes, boolean hasStash) {
        List<String> extra = hasStash ? List.of("refs/seed/stash") : List.of();

        // --- Attempt 1: --all ---
        List<String> allArgs = buildBundleArgs(bundlePath, "--all", extra);
        ExecResult allResult = runGitArgs(gitRoot, allArgs);
        if (allResult.code != 0) {
            return new BundleCreateResult.Err(
                "git bundle create --all failed (" + allResult.code + "): " + truncate(allResult.stderr, 200),
                BundleFailReason.git_error);
        }
        long allSize = new File(bundlePath).length();
        if (allSize <= maxBytes) {
            return new BundleCreateResult.Ok(allSize, BundleScope.all);
        }

        log.debug("[gitBundle] --all bundle is {}MB (> {}MB), retrying HEAD-only",
            mb(allSize), mb(maxBytes));

        // --- Attempt 2: HEAD ---
        List<String> headArgs = buildBundleArgs(bundlePath, "HEAD", extra);
        ExecResult headResult = runGitArgs(gitRoot, headArgs);
        if (headResult.code != 0) {
            return new BundleCreateResult.Err(
                "git bundle create HEAD failed (" + headResult.code + "): " + truncate(headResult.stderr, 200),
                BundleFailReason.git_error);
        }
        long headSize = new File(bundlePath).length();
        if (headSize <= maxBytes) {
            return new BundleCreateResult.Ok(headSize, BundleScope.head);
        }

        log.debug("[gitBundle] HEAD bundle is {}MB, retrying squashed-root", mb(headSize));

        // --- Attempt 3: squash to single parentless commit ---
        String treeRef = hasStash ? "refs/seed/stash^{tree}" : "HEAD^{tree}";
        ExecResult commitTree = runGit(gitRoot, "commit-tree", treeRef, "-m", "seed");
        if (commitTree.code != 0) {
            return new BundleCreateResult.Err(
                "git commit-tree failed (" + commitTree.code + "): " + truncate(commitTree.stderr, 200),
                BundleFailReason.git_error);
        }
        String squashedSha = commitTree.stdout.trim();
        runGit(gitRoot, "update-ref", "refs/seed/root", squashedSha);

        ExecResult squashResult = runGit(gitRoot, "bundle", "create", bundlePath, "refs/seed/root");
        if (squashResult.code != 0) {
            return new BundleCreateResult.Err(
                "git bundle create refs/seed/root failed (" + squashResult.code + "): "
                + truncate(squashResult.stderr, 200),
                BundleFailReason.git_error);
        }
        long squashSize = new File(bundlePath).length();
        if (squashSize <= maxBytes) {
            return new BundleCreateResult.Ok(squashSize, BundleScope.squashed);
        }

        return new BundleCreateResult.Err(
            "Repo is too large to bundle. Please setup GitHub on https://claude.ai/code",
            BundleFailReason.too_large);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<String> buildBundleArgs(String bundlePath, String base, List<String> extra) {
        List<String> args = new ArrayList<>(List.of("bundle", "create", bundlePath, base));
        args.addAll(extra);
        return args;
    }

    private ExecResult runGit(String cwd, String... args) {
        return runGitArgs(cwd, List.of(args));
    }

    private ExecResult runGitArgs(String cwd, List<String> args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(GitUtils.gitExe());
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(cwd));
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            String stderr = new String(proc.getErrorStream().readAllBytes());
            int code = proc.waitFor();
            return new ExecResult(code, stdout, stderr);
        } catch (Exception e) {
            return new ExecResult(-1, "", e.getMessage() != null ? e.getMessage() : "");
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : (s != null ? s : "");
    }

    private static double mb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private record ExecResult(int code, String stdout, String stderr) {}
}

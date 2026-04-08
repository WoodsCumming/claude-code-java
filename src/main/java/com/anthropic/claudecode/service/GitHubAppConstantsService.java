package com.anthropic.claudecode.service;

import org.springframework.stereotype.Service;

/**
 * Provides GitHub App workflow content constants.
 * Translated from src/constants/github-app.ts
 */
@Service
public class GitHubAppConstantsService {

    /**
     * Returns the Claude PR assistant workflow YAML content.
     * Translated from WORKFLOW_CONTENT in github-app.ts
     */
    public String getWorkflowContent() {
        return "# Claude PR Assistant workflow\n" +
               "name: Claude\n" +
               "on:\n" +
               "  issue_comment:\n" +
               "    types: [created]\n" +
               "  pull_request_review_comment:\n" +
               "    types: [created]\n" +
               "  issues:\n" +
               "    types: [opened, assigned]\n" +
               "  pull_request_review:\n" +
               "    types: [submitted]\n" +
               "jobs:\n" +
               "  claude:\n" +
               "    if: |\n" +
               "      (github.event_name == 'issue_comment' && contains(github.event.comment.body, '@claude')) ||\n" +
               "      (github.event_name == 'pull_request_review_comment' && contains(github.event.comment.body, '@claude')) ||\n" +
               "      (github.event_name == 'pull_request_review' && contains(github.event.review.body, '@claude')) ||\n" +
               "      (github.event_name == 'issues' && (contains(github.event.issue.body, '@claude') || contains(github.event.issue.title, '@claude')))\n" +
               "    runs-on: ubuntu-latest\n" +
               "    permissions:\n" +
               "      contents: write\n" +
               "      pull-requests: write\n" +
               "      issues: write\n" +
               "      id-token: write\n" +
               "    steps:\n" +
               "      - name: Checkout repository\n" +
               "        uses: actions/checkout@v4\n" +
               "        with:\n" +
               "          fetch-depth: 1\n" +
               "      - name: Run Claude\n" +
               "        id: claude\n" +
               "        uses: anthropics/claude-code-action@beta\n" +
               "        with:\n" +
               "          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}\n";
    }

    /**
     * Returns the Claude Code Review workflow YAML content.
     * Translated from CODE_REVIEW_WORKFLOW_CONTENT in github-app.ts
     */
    public String getCodeReviewWorkflowContent() {
        return "# Claude Code Review workflow\n" +
               "name: Claude Code Review\n" +
               "on:\n" +
               "  pull_request:\n" +
               "    types: [opened, synchronize]\n" +
               "jobs:\n" +
               "  claude-code-review:\n" +
               "    runs-on: ubuntu-latest\n" +
               "    permissions:\n" +
               "      contents: read\n" +
               "      pull-requests: write\n" +
               "    steps:\n" +
               "      - name: Checkout repository\n" +
               "        uses: actions/checkout@v4\n" +
               "      - name: Run Claude Code Review\n" +
               "        uses: anthropics/claude-code-action@beta\n" +
               "        with:\n" +
               "          use_sticky_comment: true\n" +
               "          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}\n";
    }

    /**
     * Returns the default PR title for the setup workflow PR.
     * Translated from PR_TITLE in github-app.ts
     */
    public String getPrTitle() {
        return "Add Claude GitHub Actions workflow";
    }

    /**
     * Returns the default PR body for the setup workflow PR.
     * Translated from PR_BODY in github-app.ts
     */
    public String getPrBody() {
        return "This PR adds Claude GitHub Actions workflow(s) to your repository.\n\n" +
               "Once merged, you can mention `@claude` in issues and pull requests to " +
               "have Claude help with your code.\n\n" +
               "Learn more at https://github.com/anthropics/claude-code-action";
    }
}

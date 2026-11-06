---
name: review-queue
description: Find open GitHub PRs in this repo that need my review — ones I haven't reviewed, or ones I reviewed that need a re-review (re-requested, review dismissed, or new commits since). Sorted by approvals still needed, so PRs closest to the merge threshold come first. Use when asked "what should I review", "find PRs to review", "my review queue", or "what PRs need re-review".
---

# Review queue

Run the bundled script with `gh`. It figures out who "me" is from the authenticated `gh` user, reads the base
branch's rulesets to get the approval threshold and the team whose approvals count, and returns
open, non-draft PRs that:

- were not authored by me,
- have not met the approval threshold yet, and
- I have never reviewed (`new`), or I reviewed but need to look again: the author re-requested
  me (`re-requested`), my review was dismissed (`dismissed`), or commits were pushed after my
  last review (`new commits`).

A PR I already reviewed against its current head commit is skipped. That includes one where I
requested changes and the author hasn't pushed since, because it's waiting on them.

## Steps

1. Run the script from the repo root:

   ```bash
   .claude/skills/review-queue/review-queue.sh --json
   ```

   Options: a positional `OWNER/REPO` (default `OpenIntegrationEngine/engine`), `-n TOP` to
   change how many PRs are returned (default 5, `0` for all), and `--all` to also include PRs
   that already meet the threshold. Without `--json` it prints a table.

2. Present the results as a table in the order the script returns them (remaining approvals
   ascending, then approvals descending, then PR number): remaining, approvals/required, PR
   number, status, title, author, link. Don't re-sort.

3. If the list is empty, say so. Don't widen the search unless asked.

## Notes

- Only GitHub is used. Don't look anything up in Jira or other tools.
- An approval counts only if its reviewer is in the team the ruleset requires. If that team's
  members can't be read, the script falls back to counting reviewers who have push access.
- GitHub's `reviewDecision` decides whether a PR is merge-ready by reviews. If the script's own
  count meets the threshold but GitHub disagrees (for example, the last push wasn't approved),
  the PR is shown as needing 1 more.
- Only the first 100 reviews on each PR are read.

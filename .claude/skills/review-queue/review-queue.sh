#!/usr/bin/env bash
#
# List open PRs that need your review, closest-to-mergeable first.
#
# A PR is listed when it is open, not a draft, not authored by you, has not
# met the repo's approval threshold, and either:
#   - you have never reviewed it ("new"), or
#   - you reviewed it but it needs another look: the author re-requested you
#     ("re-requested"), your approval/rejection was dismissed ("dismissed"),
#     or commits landed after your last review ("new commits").
#
# The approval threshold and the team whose approvals count are read from the
# base branch's rulesets. Sorted by approvals still needed (ascending), so a
# PR one approval away from merge is at the top.
#
# Requirements: gh (authenticated), jq
#
# Usage:
#   review-queue.sh [OWNER/REPO] [-n TOP] [--all] [--json]
#
#   -n TOP  show the top TOP PRs (default 5; 0 = show all)
#   --all   also list PRs that already meet the threshold
#   --json  print JSON instead of a table

set -euo pipefail

REPO="OpenIntegrationEngine/engine"
TOP=5          # 0 = show all; override with -n
SHOW_ALL=0
AS_JSON=0

usage() {
  sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--top)   TOP="${2:?-n requires a number}"; shift 2 ;;
    --all)      SHOW_ALL=1; shift ;;
    --json)     AS_JSON=1; shift ;;
    -h|--help)  usage 0 ;;
    -*)         echo "Unknown option: $1" >&2; usage 1 ;;
    */*)        REPO="$1"; shift ;;
    *)          echo "Invalid argument: $1 (expected OWNER/REPO)" >&2; usage 1 ;;
  esac
done

for cmd in gh jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Error: '$cmd' is required but not installed." >&2
    exit 1
  fi
done

OWNER="${REPO%%/*}"
NAME="${REPO##*/}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Prints {required, members} for a base branch: the required approval count
# and, when a team's approvals are required, that team's members. If the team
# can't be read (no org access), members is null and repo push access is used
# as the proxy.
branch_rule() {
  local pr_rules required team_id slug members='null'
  pr_rules="$(gh api "repos/$REPO/rules/branches/$1" \
    --jq '[.[] | select(.type == "pull_request") | .parameters]' 2>/dev/null || echo '[]')"
  required="$(jq '[.[] | .required_approving_review_count, (.required_reviewers[]?.minimum_approvals)] | max // 1' <<<"$pr_rules")"
  team_id="$(jq '[.[] | .required_reviewers[]? | select(.reviewer.type == "Team") | .reviewer.id] | first // empty' <<<"$pr_rules")"
  if [[ -n "$team_id" ]]; then
    slug="$(gh api "orgs/$OWNER/teams" --paginate --jq ".[] | select(.id == $team_id) | .slug" 2>/dev/null || true)"
    if [[ -n "$slug" ]]; then
      members="$(gh api "orgs/$OWNER/teams/$slug/members" --paginate --jq '[.[].login]' 2>/dev/null || echo 'null')"
    fi
  fi
  jq -n --argjson r "$required" --argjson m "$members" '{required: $r, members: $m}'
}

# Most PRs target the default branch, so look up its rules while the PR query
# runs. Any other base branch is looked up afterwards.
(
  base="$(gh api "repos/$REPO" --jq .default_branch)"
  jq -n --arg b "$base" --argjson rule "$(branch_rule "$base")" '{($b): $rule}' > "$TMP/rules.json"
) &
RULES_PID=$!

read -r -d '' QUERY <<'GRAPHQL' || true
query($owner:String!, $repo:String!, $endCursor:String) {
  viewer { login }
  repository(owner:$owner, name:$repo) {
    pullRequests(states:OPEN, first:100, after:$endCursor) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number
        title
        url
        isDraft
        baseRefName
        headRefOid
        author { login }
        reviewRequests(first:50) {
          nodes { requestedReviewer { ... on User { login } } }
        }
        reviews(first:100) {
          nodes {
            state
            submittedAt
            authorCanPushToRepository
            author { login }
            commit { oid }
          }
        }
      }
    }
  }
}
GRAPHQL

PAGES="$(gh api graphql -F owner="$OWNER" -F repo="$NAME" --paginate -f query="$QUERY" | jq -s .)"
ME="$(jq -r '.[0].data.viewer.login' <<<"$PAGES")"
PRS="$(jq '[ .[].data.repository.pullRequests.nodes[] ]' <<<"$PAGES")"

wait "$RULES_PID" || true
RULES="$(cat "$TMP/rules.json" 2>/dev/null || echo '{}')"
while IFS= read -r base; do
  RULES="$(jq --arg b "$base" --argjson rule "$(branch_rule "$base")" '. + {($b): $rule}' <<<"$RULES")"
done < <(jq -r --argjson rules "$RULES" 'map(.baseRefName) | unique[] | select($rules[.] == null)' <<<"$PRS")

CANDIDATES="$(jq --arg me "$ME" --argjson rules "$RULES" '
  map(select((.isDraft | not) and (.author.login // "ghost") != $me))
  | map(
      . as $pr
      | $rules[$pr.baseRefName] as $rule
      | ($pr.reviews.nodes | map(select(.author != null and .state != "PENDING"))) as $reviews
      # Latest approve/reject/dismissed review per reviewer; COMMENTED reviews
      # do not change a reviewer'"'"'s standing.
      | ($reviews
          | map(select(.state == "APPROVED" or .state == "CHANGES_REQUESTED" or .state == "DISMISSED"))
          | group_by(.author.login)
          | map(sort_by(.submittedAt) | last)) as $standing
      | ($standing
          | map(select(.state == "APPROVED"
                       and .author.login != $pr.author.login
                       and (if $rule.members then (.author.login | IN($rule.members[]))
                            else .authorCanPushToRepository end)))
          | length) as $approvals
      | ($reviews | map(select(.author.login == $me)) | sort_by(.submittedAt)) as $mine
      | ($standing | map(select(.author.login == $me)) | first) as $myStanding
      | (if ($mine | length) == 0 then "new"
         elif any($pr.reviewRequests.nodes[]; .requestedReviewer.login? == $me) then "re-requested"
         elif $myStanding.state? == "DISMISSED" then "dismissed"
         elif ($mine | last | .commit.oid?) != $pr.headRefOid then "new commits"
         else null end) as $status
      | {
          number: $pr.number,
          title: $pr.title,
          url: $pr.url,
          author: ($pr.author.login // "ghost"),
          status: $status,
          approvals: $approvals,
          required: $rule.required
        })
  | map(select(.status != null))
' <<<"$PRS")"

# reviewDecision is authoritative for "mergeable by reviews", but it is by far
# the slowest field to fetch, so only ask for it on PRs whose count already
# meets the threshold. If GitHub disagrees with the count (e.g. the last push
# was not approved), at least one more approval is still needed.
DECISIONS='{}'
met="$(jq -r 'map(select(.approvals >= .required) | "pr\(.number): pullRequest(number:\(.number)) { reviewDecision }") | join(" ")' <<<"$CANDIDATES")"
if [[ -n "$met" ]]; then
  DECISIONS="$(gh api graphql -F owner="$OWNER" -F repo="$NAME" \
    -f query="query(\$owner:String!, \$repo:String!) { repository(owner:\$owner, name:\$repo) { $met } }" \
    --jq '.data.repository | with_entries(.key |= ltrimstr("pr") | .value |= .reviewDecision)')"
fi

RESULT="$(jq --argjson decisions "$DECISIONS" --argjson all "$SHOW_ALL" --argjson top "$TOP" '
  map(. + {remaining: (if .approvals < .required then .required - .approvals
                       elif $decisions[.number | tostring] == "APPROVED" then 0
                       else 1 end)})
  | map(select($all == 1 or .remaining > 0))
  | sort_by(.remaining, -.approvals, .number)
  | if $top > 0 then .[:$top] else . end
' <<<"$CANDIDATES")"

if [[ "$AS_JSON" -eq 1 ]]; then
  echo "$RESULT"
  exit 0
fi

if [[ "$(jq 'length' <<<"$RESULT")" -eq 0 ]]; then
  echo "No open PRs in $REPO need a review from @$ME."
  exit 0
fi

echo "Open PRs in $REPO needing review from @$ME (fewest approvals remaining first):"
echo
{
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "REMAINING" "APPROVALS" "PR" "STATUS" "TITLE (AUTHOR)" "LINK"
  jq -r '.[] | "\(.remaining)\t\(.approvals)/\(.required)\t#\(.number)\t\(.status)\t\(.title)  —  @\(.author)\t\(.url)"' <<<"$RESULT"
} | column -t -s $'\t'

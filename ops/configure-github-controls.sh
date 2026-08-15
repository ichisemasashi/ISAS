#!/bin/sh
set -eu

repository=${1:-${GITHUB_REPOSITORY:-}}
: "${repository:?usage: configure-github-controls.sh OWNER/REPOSITORY}"
: "${STAGING_REVIEWERS_JSON:?STAGING_REVIEWERS_JSON is required}"
: "${PRODUCTION_REVIEWERS_JSON:?PRODUCTION_REVIEWERS_JSON is required}"

case "$repository" in */*) ;; *) echo "repository must be OWNER/REPOSITORY" >&2; exit 64 ;; esac
jq -e 'type == "array" and length >= 1 and length <= 6 and all(.[]; (.type == "User" or .type == "Team") and (.id | type == "number"))' <<EOF >/dev/null
$STAGING_REVIEWERS_JSON
EOF
jq -e 'type == "array" and length >= 2 and length <= 6 and ([.[].id] | unique | length) >= 2 and all(.[]; (.type == "User" or .type == "Team") and (.id | type == "number"))' <<EOF >/dev/null
$PRODUCTION_REVIEWERS_JSON
EOF

temporary_directory=$(mktemp -d)
trap 'rm -rf "$temporary_directory"' EXIT HUP INT TERM

jq -n '{
  required_status_checks: {strict: true, contexts: ["BFF", "Web", "Policy", "Filesystem security", "Container (bff)", "Container (web)", "Container (migration)"]},
  enforce_admins: true,
  required_pull_request_reviews: {
    dismiss_stale_reviews: true,
    require_code_owner_reviews: true,
    required_approving_review_count: 2,
    require_last_push_approval: true
  },
  restrictions: null,
  required_linear_history: true,
  allow_force_pushes: false,
  allow_deletions: false,
  block_creations: false,
  required_conversation_resolution: true,
  lock_branch: false,
  allow_fork_syncing: false
}' >"$temporary_directory/branch.json"

gh api --method PUT "repos/$repository/branches/main/protection" --input "$temporary_directory/branch.json" >/dev/null
gh api --method POST "repos/$repository/branches/main/protection/required_signatures" >/dev/null

configure_environment() {
  environment=$1
  reviewers=$2
  jq -n --argjson reviewers "$reviewers" '{
    wait_timer: 0,
    prevent_self_review: true,
    reviewers: $reviewers,
    deployment_branch_policy: {protected_branches: true, custom_branch_policies: false}
  }' >"$temporary_directory/$environment.json"
  gh api --method PUT "repos/$repository/environments/$environment" --input "$temporary_directory/$environment.json" >/dev/null
}

configure_environment staging "$STAGING_REVIEWERS_JSON"
configure_environment production-canary "$PRODUCTION_REVIEWERS_JSON"
configure_environment production "$PRODUCTION_REVIEWERS_JSON"

echo "GitHub branch protection, signed commits, CODEOWNERS, and environment approval gates configured for $repository"

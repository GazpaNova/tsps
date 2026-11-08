# Voidburn development and review workflow

Open **H:\Voidburn\Development\tsps** in Claude. Do not use the parent folder as Claude's workspace: server-management credentials live outside this checkout and are not needed for fixes.

## Roles

- Claude develops and tests bugfixes in this local checkout.
- Codex manages production, reviews the exact proposed commits, checks compatibility with live customizations, validates an isolated deployment candidate, and deploys only the reviewed change.
- A local commit or GitHub push is a handoff, not an automatic production deployment. Restart timing is agreed with Gazpa; do not restart or publish an upstream PR merely because a local fix is finished.

## Baseline

Upstream: `66541832b12349e8c3608d240fc79831f4b5b640`.
Source snapshot tag: `voidburn/live-2026-11-08`.
Branch: `voidburn/main`. The snapshot contains all 143 customized source files verified byte-for-byte against the running server on 2026-11-08, including browser and RuneLite gateway source.

`upstream` points to RSPSApp/tsps. `origin` points to GazpaNova/tsps. Nothing from this new branch has been pushed to GitHub.

The local source snapshot includes existing Voidburn customizations; do not send that entire baseline as an upstream bugfix PR. Only the new fix and necessary regression checks belong in such a PR. The repository is a shallow checkout at the live upstream revision; fetch additional history explicitly when needed.

## Make a fix

1. Read root AGENTS.md and server/AGENTS.md, plus relevant module guidance. Preserve the generated-data restrictions and plugin conventions.
2. Start from the synchronized `voidburn/main`, then create a branch such as `fix/descriptive-name`. For an additional checkout, follow `scripts/wt` rather than manually creating a Git worktree.
3. Reproduce the issue. Keep the fix narrow, preserve XP/reward policies and all other unrelated customizations, and add meaningful regression coverage where appropriate.
4. Build and run the relevant checks. Explain what was actually tested and what still needs an in-game check.
5. Commit only the intended files. Avoid blanket `git add .` because local environments and test saves must stay private.
6. Give Gazpa the branch, commit SHA, summary and test results. If Gazpa explicitly requests a push, push the fix branch to `origin`; do not force-push or merge it into a production branch.

A normal same-checkout branch can be created with:

```powershell
git switch voidburn/main
git switch -c fix/descriptive-name
```

Codex can review local commits directly; a GitHub push is optional for this workflow. For a GitHub handoff, provide the branch or PR link.

## Local environment

- Node.js 22.16+ is required; this setup was verified with Node 24.22.1.
- Yarn 4.12.0 is available locally at `.dev-tools/yarn.cjs`.
- Root and server dependencies are installed; the game cache is copied under `server/caches`.
- `.env` is local-only and contains no production secrets.
- Local player saves use `.local-data/players.sqlite`, never production characters.
- Client port: 3310; game WebSocket: 43810; gateway: 43910; agent MCP: 49810 (loopback). Agent MCP is disabled by default; set AGENT_MCP=1 explicitly when a local in-game test needs it, and restore it afterward.
- World settings reproduce Voidburn's non-secret gameplay configuration. Player bots are disabled by the local environment for testing. No world-registration token or production login token was copied.
- The game WebSocket's existing listener binds according to upstream behavior; this setup does not add a public route, firewall rule or production service. Stop local processes after testing.
- Browser dependencies and the Java/gateway toolchain may need separate installation for tasks that require those builds. They are not claimed as verified by the server-only build.

From the repository root:

```powershell
# Build the server
node .dev-tools/yarn.cjs --cwd server build

# Run a relevant existing server test
Push-Location server
node ../scripts/run-with-env.mjs - --test tests/plague-city-progression.test.cjs
Pop-Location

# Start only this local compiled server when needed
node .dev-tools/yarn.cjs --cwd server start
```

Use the local test account you create; real player saves were not copied. User-facing browser testing needs client dependencies and the client dev server as well. Do not advertise a test URL until the corresponding server is running and checked.

## Codex review and deployment

Codex reviews the fix commits against their recorded baseline and the latest live version. It checks that the fix does not accidentally include old customizations, secrets, generated artifacts or player-state changes. It reproduces/tests the relevant behavior and stages changes through the customization-preserving deployment process.

If the live server has advanced, reconcile the fix with that version before deployment. Do not replace the live checkout with the older local snapshot. After deployment, record the accepted commit and resynchronize the local baseline while preserving any unfinished Claude branches.

Standard GitHub-main updates and private Claude fixes are separate inputs: `voidburn-update prepare` does not automatically fetch every branch on GazpaNova/tsps. A reviewed private fix must first be integrated into the live customization set, then prepared and validated for activation.

No production SSH, server environment, passwords, tokens, private keys, player databases, backups or deployment assets are included in this checkout. This is a source development copy, not a standalone copy of the live hosting infrastructure or launcher build environment.

## Setup verification (2026-11-08)

- 143 live-customized source files matched the export SHA256 manifest.
- Server TypeScript build passed on Windows with Node 24.22.1.
- 26 checks in plague-city-progression.test.cjs and biohazard-watchtower.test.cjs passed with agent MCP disabled.
- Local environment, cache, dependencies, tools and test saves are excluded from Git; working tree is clean after workflow documentation commit.
- No production deployment, restart or GitHub push performed.

# This app

Node.js + Express REST API. It starts as a single `server.js`; build what the
task asks for from there (split into modules freely).

## Task workflow

Understand the goal and scope before editing. Read relevant current files,
BRIEF.md and BRAIN.md when present; check whether the requested behavior already
exists. A starter map is navigation, not proof that files are unchanged. Batch
independent reads and keep discovery proportional to the task. For bugs, inspect
relevant logs and reproduce the failure before selecting a fix.

Clear implementation requests need no approval ritual. Ideas, reviews and
questions need discussion or inspection unless implementation is requested.
Choose sensible minor defaults and respect delegated choices. For consequential
unresolved decisions, use the bridge question workflow below and leave dependent
work unimplemented until answered. Keep BRIEF.md concise with product scope,
user decisions, assumptions, acceptance criteria and open questions; update it
when decisions change, not on every trivial edit. Keep technical gotchas in
BRAIN.md. A follow-up answer should resolve its question in the same project.

## How it runs (platform-managed, do not fight it)

- A supervisor runs `node server.js` on port 3000 (declared in
  `sandbox.yaml`). There is no live reload: the platform restarts the process
  after each task, so your changes take effect when you finish. Never start a
  second server and never change the port.
- The server must listen on `0.0.0.0:3000` and keep answering `GET /health`
  with 200; the platform's readiness probe depends on it.
- Dependencies: the supervisor runs `pnpm install` on boot when needed. Run
  `pnpm install` yourself after editing `package.json`.
- Verify before finishing: `node --check server.js` plus relevant isolated
  request-handler tests when available. Do not kill the supervised process;
  changes become live after the task ends. Report runtime behavior as unverified
  until the restarted app has actually been exercised.

## Bridge to the product chat (only when $BRIDGE_URL is set)

When the env vars `BRIDGE_URL` and `BRIDGE_TOKEN` exist, a chat assistant
sits between you and the user. POST JSON with
`Authorization: Bearer $BRIDGE_TOKEN`:
`{"kind":"report","text":"…"}` for milestone progress,
`{"kind":"question","text":"…"}` for something the user should decide
(continue only independent work; if blocked, checkpoint in BRIEF.md, report
what needs input and end the task for a later update), and `{"kind":"library"}` returns every file the
user has uploaded, each with a `url` to curl. A task may open with a list
headed FILES THE USER ATTACHED: those files are already in the workspace at
the listed paths (`public/media/`, with a `manifest.json`); use them where
the task says and never ask for them again. Your last report, sent just
before you finish, says in one line what now works and anything you could
not do; the user's assistant relays exactly that line, so never round up.

## Keys and secrets

An API key or any secret the app needs is declared, never written. Declare
it in `sandbox.yaml` under `env:` and read it from the environment on the
server side only:

    env:
      - name: OPENAI_API_KEY
        required: true
        hint: Used by the chat feature; from platform.openai.com

The platform stores the value the person enters (sealed, outside the
workspace) and starts the app with it as an environment variable. So: never
write a real value into any file, never ask for one through the bridge,
never put a secret in a `VITE_` / `NEXT_PUBLIC_` variable or anywhere the
browser loads, and build the feature so the app still runs, saying it needs
its key, until the value is set. A remix of the app carries the declaration
and never the value.

## Working style

Inspect before changing, batch independent operations, and use focused edits.
Keep components and modules small. Verify the requested behavior with sufficient
checks at meaningful stages; compilation alone does not prove feature completion.

## Session memory

New tasks start fresh; live follow-ups retain their current session. Keep durable
context in files so a later task can continue:

- `BRAIN.md` carries project state, decisions, and gotchas from earlier
  sessions. Read it before starting; append durable learnings before you
  finish.
- This file carries stable facts about the stack and workflow. If you change
  them, update this file so the next session starts right instead of
  rediscovering it.

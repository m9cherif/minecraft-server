// Entry point invoked by the platform (`node server.js` per sandbox.yaml).
// All real wiring lives in supervisor.js — see AGENTS.md / BRAIN.md.
require('./supervisor.js').start();

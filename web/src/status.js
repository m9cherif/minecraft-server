'use strict';

// Cached status lookups. A status page that people leave open, plus any bot
// polling /api/status, would otherwise open a TCP connection to the game server
// on every request — which is exactly the traffic a small server does not want.
//
// Two things happen here: results are reused for `cacheMs`, and concurrent
// requests share one in-flight ping instead of each starting their own.

const { pingServer } = require('./ping');

function createStatusService({ host, port, cacheMs, timeoutMs }) {
  let cached = null;
  let cachedAt = 0;
  let inFlight = null;

  async function get({ force = false } = {}) {
    const age = Date.now() - cachedAt;
    if (!force && cached && age < cacheMs) {
      return { ...cached, cached: true, ageMs: age };
    }
    if (!inFlight) {
      inFlight = pingServer({ host, port, timeoutMs })
        .then((result) => {
          cached = result;
          cachedAt = Date.now();
          return result;
        })
        .finally(() => {
          inFlight = null;
        });
    }
    const result = await inFlight;
    return { ...result, cached: false, ageMs: 0 };
  }

  return { get };
}

module.exports = { createStatusService };

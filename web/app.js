'use strict';

// Entry point. Hostinger's Node.js app runner starts the file named as the
// "startup file" (app.js by default) and passes the port in $PORT, so there is
// nothing to configure here beyond the environment. See deploy/HOSTINGER.md.

const config = require('./src/config');
const { createServer } = require('./src/server');

if (config.tunnelErrors.length) {
  console.error('Tunnel configuration is incomplete:');
  for (const message of config.tunnelErrors) console.error(`  - ${message}`);
  console.error('Fix these, or unset TUNNEL_ENABLED to run the status page alone.');
  process.exit(1);
}

const server = createServer(config);

server.listen(config.port, config.host, () => {
  console.log(`==> Listening on http://${config.host}:${config.port}`);
  console.log(`==> Reporting on ${config.mc.host}:${config.mc.port} (shown as ${config.mc.address})`);
  if (config.tunnel.enabled) {
    console.log(
      `==> Tunnel on, forwarding only to ${config.tunnel.targetHost}:${config.tunnel.targetPort}` +
        ` (max ${config.tunnel.maxClients} clients)`
    );
  } else {
    console.log('==> Tunnel off (set TUNNEL_ENABLED=true to turn it on)');
  }
});

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`Port ${config.port} is already in use.`);
    process.exit(1);
  }
  throw err;
});

// Shared hosts restart apps by signalling them. Finish in-flight requests
// rather than cutting them off, but do not hang forever if a socket is idle.
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    console.log(`\n==> ${signal} received, shutting down.`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  });
}

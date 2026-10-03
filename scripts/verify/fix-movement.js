/**
 * Client-side workaround for Paper's "Invalid move player packet received"
 * kick (multiplayer.disconnect.invalid_player_movement).
 *
 * What actually goes wrong for a headless bot here:
 *
 *  1. The server disconnects a player that lets TWO position packets arrive in
 *     one server tick (`receivedPositionThisTick` in
 *     ServerGamePacketListenerImpl#handleMovePlayer).
 *  2. mineflayer answers the login position and the hub's join teleport
 *     (OnJoinSpawn, one tick later) with two acks ~15ms apart, which is well
 *     inside one 50ms server tick.
 *  3. Simply dropping one of them is NOT enough: the server then sits in
 *     "awaiting teleport" expecting the teleported coordinates, and the next
 *     physics packet (which has already drifted a few cm from gravity) is
 *     rejected as invalid movement.
 *
 * So this guard does two things:
 *  - spaces outgoing movement packets by at least MIN_GAP_MS (> one tick), and
 *  - treats a packet whose coordinates match a server teleport as an
 *    ACKNOWLEDGEMENT: acknowledgements are never overwritten by a physics
 *    packet, so the server always receives the exact position it asked for.
 */
const MIN_GAP_MS = parseInt(process.env.MOVE_MIN_GAP_MS || '70', 10);
const EPS = 0.001;

module.exports = function installMovementGuard(bot) {
  const client = bot._client;
  const originalWrite = client.write.bind(client);

  // coordinates of the most recent server teleport, if we owe an ack
  let awaitingAck = null;
  let lastSent = 0;
  let timer = null;
  let pendingMove = null;

  client.on('packet', (data) => {
    if (data && data.name === 'position' && typeof data.x === 'number') {
      awaitingAck = { x: data.x, y: data.y, z: data.z };
    }
  });

  const isAckFor = (d) => awaitingAck
    && Math.abs(d.x - awaitingAck.x) < EPS
    && Math.abs(d.y - awaitingAck.y) < EPS
    && Math.abs(d.z - awaitingAck.z) < EPS;

  function flush() {
    timer = null;
    // an acknowledgement always wins over a queued physics packet
    const payload = pendingMove;
    pendingMove = null;
    if (!payload) return;
    if (isAckFor(payload.data)) {
      awaitingAck = null;
    }
    lastSent = Date.now();
    originalWrite(payload.name, payload.data);
  }

  function schedule() {
    if (timer !== null) return;
    const wait = Math.max(0, lastSent + MIN_GAP_MS - Date.now());
    timer = setTimeout(flush, wait);
  }

  client.write = function guardedWrite(name, data) {
    if (name !== 'position' && name !== 'position_look') {
      return originalWrite(name, data);
    }
    if (isAckFor(data)) {
      // ack: replace any queued packet, we must deliver these coordinates
      pendingMove = { name, data };
    } else if (pendingMove === null || !isAckFor(pendingMove.data)) {
      pendingMove = { name, data };
    } else {
      return true; // an ack is queued; don't clobber it with drift
    }
    schedule();
    return true;
  };

  bot.on('end', () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  });
};

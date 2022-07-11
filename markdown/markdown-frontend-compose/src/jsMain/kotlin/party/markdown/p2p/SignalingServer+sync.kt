package party.markdown.p2p

import io.github.alexandrepiveteau.echo.ReceiveExchange
import io.github.alexandrepiveteau.echo.SendExchange
import io.github.alexandrepiveteau.echo.protocol.Message.Incoming
import io.github.alexandrepiveteau.echo.protocol.Message.Outgoing
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import party.markdown.signaling.PeerIdentifier

/** The delay before retrying to [sync]. */
private val RetryDelay = 1000.milliseconds

/**
 * Syncs the [SendExchange] to a single peer.
 *
 * @receiver the [SignalingServer] which provides a way to establish a connection with another site.
 * @param peer the [PeerIdentifier] to which we're interested in syncing.
 * @param exchange the [ReceiveExchange] to which we're interested in syncing.
 */
suspend fun SignalingServer.sync(
    peer: PeerIdentifier,
    exchange: ReceiveExchange<Incoming, Outgoing>,
) {
  while (true) {
    val connection = connect(peer)
    exchange
        .receive(connection.incoming.consumeAsFlow().map(DefaultStringFormat::decodeFromString))
        .onEach { connection.outgoing.send(DefaultStringFormat.encodeToString(it)) }
        .collect()
    delay(RetryDelay)
  }
}

/**
 * Syncs the [SendExchange] to the peers available in the [SignalingServer]. The synchronisation
 * process won't terminate, but may be cancelled or throw an exception if the communication channel
 * gets closed.
 *
 * @receiver the [SignalingServer] which provides information about the sites.
 * @param exchange the [ReceiveExchange] to which we're interested in syncing.
 */
suspend fun SignalingServer.sync(
    exchange: ReceiveExchange<Incoming, Outgoing>,
): Nothing = coroutineScope {
  val jobs = mutableMapOf<PeerIdentifier, Job>()

  // Preserve state for remaining sites across collect invocations, such that existing jobs are
  // preserved and outdated jobs are cancelled appropriately. Existing jobs are preserved, so
  // the ongoing connection remains active.
  peers.collect { peers ->
    val added = peers - jobs.keys
    val removed = jobs - peers

    // Remove all the outdated peers.
    for ((peer, job) in removed) {
      job.cancel()
      jobs -= peer
    }

    // Add all the new peers.
    for (peer in added) {
      jobs[peer] = launch { sync(peer, exchange) }
    }
  }
}

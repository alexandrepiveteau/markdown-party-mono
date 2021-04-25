@file:OptIn(InternalCoroutinesApi::class)
@file:Suppress("SameParameterValue")

package io.github.alexandrepiveteau.echo.protocol.fsm

import io.github.alexandrepiveteau.echo.causal.SequenceNumber
import io.github.alexandrepiveteau.echo.causal.SequenceNumber.Companion.Zero
import io.github.alexandrepiveteau.echo.causal.SiteIdentifier
import io.github.alexandrepiveteau.echo.logs.ImmutableEventLog
import io.github.alexandrepiveteau.echo.protocol.Message.Incoming as Inc
import io.github.alexandrepiveteau.echo.protocol.Message.Outgoing as Out
import io.github.alexandrepiveteau.echo.protocol.fsm.Effect.Move
import io.github.alexandrepiveteau.echo.protocol.fsm.Effect.Terminate
import io.github.alexandrepiveteau.echo.util.plusBoundOverflows
import kotlinx.collections.immutable.*
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.selects.select

/**
 * A sealed class representing the different states that the finite state machine may be in. Each
 * step may decide to continue or stop processing with the [keepGoing] method, and defines state
 * transitions as part of the [step].
 *
 * @param T the type of the events.
 */
// TODO : Update the documentation.
internal sealed class IncomingState<T, C> : State<Out<T>, Inc<T>, T, C, IncomingState<T, C>> {

  companion object {

    /** Creates a new [IncomingState] that's the beginning of the FSM. */
    operator fun <T, C> invoke(pending: Iterable<SiteIdentifier>): IncomingState<T, C> =
        IncomingNew(
            alreadySent = persistentListOf(),
            remainingToSend = pending.toPersistentList(),
        )
  }
}

// FINITE STATE MACHINE

// 1. We have some pending advertisements to send before we can issue the Ready, so
//    we simply issue them and move them from our pending queue to the advertised
//    queue.
// 2. We have advertised all of our pending sites, so we can issue a Ready event and
//    move to the Sending state.
// 3. We receive a Done event, so we move to Cancelling.
// 4. We receive an unsupported message, so we fail.
private data class IncomingNew<T, C>(
    private val alreadySent: PersistentList<SiteIdentifier>,
    private val remainingToSend: PersistentList<SiteIdentifier>,
) : IncomingState<T, C>() {

  override suspend fun IncomingStepScope<T, C>.step(
      log: ImmutableEventLog<T, C>
  ): Effect<IncomingState<T, C>> {
    return select {
      // Priority is given to the reception of cancellation messages.
      onReceiveOrClosed { v ->
        when (v.valueOrNull) {
          null -> Terminate
          else -> Effect.MoveToError(IllegalStateException())
        }
      }
      val pending = remainingToSend.lastOrNull()
      if (pending != null) {
        onSend(Inc.Advertisement(pending)) {
          Move(
              copy(
                  alreadySent = alreadySent.add(pending),
                  remainingToSend = remainingToSend.removeAt(remainingToSend.lastIndex),
              ))
        }
      } else {
        onSend(Inc.Ready) { Move(IncomingSending(advertised = alreadySent)) }
      }
    }
  }
}

private data class IncomingSending<T, C>(
    private val advertised: PersistentList<SiteIdentifier>,
    private val nextSequenceNumberPerSite: PersistentMap<SiteIdentifier, SequenceNumber> =
        persistentMapOf(),
    private val receivedCredits: PersistentMap<SiteIdentifier, UInt> = persistentMapOf(),
) : IncomingState<T, C>() {

  /** Returns the next [Inc.Advertisement] message to send, if there's any. */
  fun nextAdvertisementOrNull(log: ImmutableEventLog<T, C>): Inc.Advertisement? {
    val missing = log.sites.asSequence().minus(advertised).firstOrNull() ?: return null
    return Inc.Advertisement(missing)
  }

  /**
   * Returns the [IncomingState] if the [Inc.Advertisement] for the given [msg] was successfully
   * processed.
   */
  fun handleAdvSent(msg: Inc.Advertisement): IncomingState<T, C> {
    return copy(advertised = advertised + msg.site)
  }

  /**
   * Returns the next [Inc.Event] message to send, if there's any.
   *
   * @param log the [ImmutableEventLog] that is in charge of generating the events.
   */
  @Suppress("DEPRECATION")
  fun nextEventOrNull(log: ImmutableEventLog<T, C>): Inc.Event<T>? {
    val event =
        receivedCredits
            .asSequence()
            .filter { (_, credits) -> credits > 0U }
            .filter { (site, _) -> site in advertised }
            .mapNotNull { (site, _) ->
              nextSequenceNumberPerSite[site]?.let { log.events(site, it) }
            }
            .flatten()
            .firstOrNull()
            ?: return null

    return Inc.Event(
        seqno = event.identifier.seqno,
        site = event.identifier.site,
        body = event.body,
    )
  }

  /**
   * Returns the [IncomingState] if the [Inc.Event] for the given [msg] was successfully processed.
   */
  fun handleEventSent(msg: Inc.Event<T>): IncomingState<T, C> {
    val remainingCredits = receivedCredits[msg.site]?.minus(1U) ?: 0U
    val nextSequenceNumber = maxOf(msg.seqno.inc(), Zero)
    return copy(
        nextSequenceNumberPerSite = nextSequenceNumberPerSite.put(msg.site, nextSequenceNumber),
        receivedCredits = receivedCredits.put(msg.site, remainingCredits),
    )
  }

  /** Returns the [IncomingState] if the [Out.Acknowledge] [msg] is received. */
  fun handleAcknowledgeReceived(msg: Out.Acknowledge): IncomingState<T, C> {
    return copy(
        nextSequenceNumberPerSite = nextSequenceNumberPerSite.put(msg.site, msg.nextSeqno),
        receivedCredits = receivedCredits.put(msg.site, 0U),
    )
  }

  /** Returns the [IncomingState] if the [Out.Request] [msg] is received. */
  fun handleRequestReceived(msg: Out.Request): IncomingState<T, C> {
    val currentCredits = receivedCredits[msg.site] ?: 0U
    val remainingCredits = currentCredits.plusBoundOverflows(msg.count)
    return copy(
        receivedCredits = receivedCredits.put(msg.site, remainingCredits),
    )
  }

  override suspend fun IncomingStepScope<T, C>.step(
      log: ImmutableEventLog<T, C>
  ): Effect<IncomingState<T, C>> {
    return select {
      onReceiveOrClosed { v ->
        when (val msg = v.valueOrNull) {
          is Out.Acknowledge -> Move(handleAcknowledgeReceived(msg))
          is Out.Request -> Move(handleRequestReceived(msg))
          null -> Terminate
        }
      }

      val event = nextEventOrNull(log)
      if (event != null) onSend(event) { Move(handleEventSent(event)) }

      val advertisement = nextAdvertisementOrNull(log)
      if (advertisement != null) onSend(advertisement) { Move(handleAdvSent(advertisement)) }

      // On new events, request another pass of the step.
      onInsert { Move(this@IncomingSending) }
    }
  }
}

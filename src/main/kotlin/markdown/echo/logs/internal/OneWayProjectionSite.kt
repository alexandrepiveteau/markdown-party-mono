package markdown.echo.logs.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import markdown.echo.EchoEventLogPreview
import markdown.echo.MutableSite
import markdown.echo.causal.EventIdentifier
import markdown.echo.causal.SiteIdentifier
import markdown.echo.channelLink
import markdown.echo.events.EventScope
import markdown.echo.logs.*
import markdown.echo.projections.OneWayProjection

/**
 * An implementation of [MutableSite] that delegates the management of data to a
 * [PersistentEventLog]. This way, optimizations can be applied directly at the [ImmutableEventLog]
 * level.
 *
 * @param identifier the globally unique identifier for this site.
 * @param log the backing [PersistentEventLog].
 * @param initial the initial [M].
 * @param projection the [OneWayProjection] that's used.
 *
 * @param T the type of the events.
 * @param M the type of the model.
 */
@OptIn(
    EchoEventLogPreview::class,
    ExperimentalCoroutinesApi::class,
    FlowPreview::class,
)
internal class OneWayProjectionSite<T, M>(
    override val identifier: SiteIdentifier,
    private var log: PersistentEventLog<T> = persistentEventLogOf(),
    private val initial: M,
    private val projection: OneWayProjection<M, EventValue<T>>,
) : MutableSite<T, M> {

  /** A [Mutex] which ensures serial access to the [log] and the [inserted] value. */
  private val mutex = Mutex()

  /** A [MutableStateFlow] that acts as a sentinel value that new events were inserted. */
  private val inserted = MutableStateFlow<EventIdentifier?>(null)

  /**
   * Runs an exchange based on a final state machine. The FSM starts with an [initial] state, and
   * performs steps as messages are received or sent.
   *
   * @param initial a lambda that creates the initial FSM state.
   *
   * @param I the type of the input messages.
   * @param O the type of the output messages.
   * @param S the type of the FSM states.
   */
  private fun <I, O, S : State<I, O, T, S>> exchange(initial: suspend () -> S) =
      channelLink<I, O> { inc ->
        var state = initial()
        val insertions = inserted.map { mutex.withLock { log } }.produceIn(this)

        // Prepare some context information for the step.
        val scope =
            StepScopeImpl(inc, this, insertions) { seqno, site, body: T ->
              mutex.withLock {
                // Update the log, and issue an update to all sites if relevant.
                val update = log[seqno, site] == null
                log = log.set(seqno, site, body)
                if (update) inserted.value = EventIdentifier(seqno, site)
              }
            }

        while (true) {
          // Run the effects, until we've moved to an error state or we were explicitly asked to
          // terminate.
          when (val effect = with(state) { scope.step(mutex.withLock { log }) }) {
            is Effect.Move -> state = effect.next
            is Effect.MoveToError -> throw effect.problem
            is Effect.Terminate -> break
          }
        }

        // Clear the jobs registered in channelLink.
        inc.cancel()
        insertions.cancel()
      }

  override fun outgoing() = exchange { OutgoingState() }
  override fun incoming() = exchange { mutex.withLock { IncomingState(log.sites) } }

  @OptIn(EchoEventLogPreview::class)
  override suspend fun event(
      scope: suspend EventScope<T>.(M) -> Unit,
  ) {
    mutex.withLock {
      // TODO : Concurrent modification of log ?
      val model = log.foldl(initial, projection::forward)
      var next = log.expected
      // TODO : fun interface when b/KT-40165 is fixed.
      val impl =
          object : EventScope<T> {
            override suspend fun yield(event: T): EventIdentifier {
              log = log.set(next, identifier, event)
              return EventIdentifier(next++, identifier).apply { inserted.value = this }
            }
          }
      scope(impl, model)
    }
  }
}

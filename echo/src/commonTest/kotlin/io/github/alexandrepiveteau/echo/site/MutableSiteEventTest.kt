package io.github.alexandrepiveteau.echo.site

import io.github.alexandrepiveteau.echo.causal.EventIdentifier
import io.github.alexandrepiveteau.echo.causal.SequenceNumber
import io.github.alexandrepiveteau.echo.causal.SiteIdentifier
import io.github.alexandrepiveteau.echo.mutableSite
import io.github.alexandrepiveteau.echo.suspendTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MutableSiteEventTest {

  @Test
  fun empty_event_terminates() = suspendTest {
    val alice = mutableSite<Int>(SiteIdentifier(123))
    alice.event {}
  }

  @Test
  fun multiple_empty_event_terminates() = suspendTest {
    val echo = mutableSite<Int>(SiteIdentifier(456))
    repeat(2) { echo.event {} }
  }

  @Test
  fun multiple_nonEmpty_event_terminates() = suspendTest {
    val site = mutableSite<Int>(SiteIdentifier(456))
    repeat(2) { iteration -> site.event { yield(iteration) } }
  }

  @Test
  fun singleYield_terminates() = suspendTest {
    val site = SiteIdentifier(145)
    with(mutableSite<Int>(site)) {
      event { assertEquals(EventIdentifier(SequenceNumber(0U), site), yield(123)) }
    }
  }

  @Test
  fun multipleYield_terminates() = suspendTest {
    val site = SiteIdentifier(145)
    with(mutableSite<Int>(site)) {
      event {
        assertEquals(EventIdentifier(SequenceNumber(0U), site), yield(123))
        assertEquals(EventIdentifier(SequenceNumber(1U), site), yield(456))
      }
    }
  }
}
/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud.updates

import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class TestPublishMessageException {

  @Test
  fun correctlyBuilds() {
    val message = "error"
    val cause = Throwable("throwable")
    val exception = PublishMessageException(message, cause)

    expectThat(exception.message).isEqualTo(message)
    expectThat(exception.cause).isEqualTo(cause)
  }
}

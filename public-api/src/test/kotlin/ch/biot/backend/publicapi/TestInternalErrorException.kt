/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.publicapi

import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class TestInternalErrorException {

  @Test
  fun correctlyBuilds() {
    val message = "error"
    val cause = Throwable("throwable")
    val exception = InternalErrorException(message, cause)

    expectThat(exception.message).isEqualTo(message)
    expectThat(exception.cause).isEqualTo(cause)
  }
}

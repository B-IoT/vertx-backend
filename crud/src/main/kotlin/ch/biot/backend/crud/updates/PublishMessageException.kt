/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud.updates

/**
 * Exception representing an error when publishing a message on the event bus.
 */
class PublishMessageException(message: String, cause: Throwable? = null) : Exception(message, cause)

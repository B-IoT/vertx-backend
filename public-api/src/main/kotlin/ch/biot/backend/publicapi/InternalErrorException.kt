package ch.biot.backend.publicapi

/**
 * Exception modeling an internal error (HTTP code 500)
 */
class InternalErrorException(message: String, cause: Throwable?): Exception(message, cause)

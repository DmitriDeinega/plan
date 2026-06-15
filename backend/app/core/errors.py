class BusinessError(Exception):
    """An expected, user-facing error.

    The message is safe to return to clients. Unexpected exceptions are NOT
    BusinessErrors and must be logged + returned as a generic message so internal
    details (DB errors, stack info) never leak to callers.
    """
    pass


def safe_error_message(exc: Exception) -> str:
    """Message safe to return to a client: validation text for BusinessError,
    a generic string for anything unexpected (so internals never leak)."""
    return str(exc) if isinstance(exc, BusinessError) else "Internal server error"

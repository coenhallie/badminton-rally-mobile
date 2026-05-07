package com.badmintontracker.shared.repo

sealed class ShareError(message: String) : Exception(message) {
    data object NotOwner        : ShareError("not_owner")
    data object NoSuchUser      : ShareError("no_such_user")
    data object CannotShareSelf : ShareError("cannot_share_with_self")
    data class  Unknown(val original: Throwable) : ShareError(original.message ?: "unknown")
}

internal fun Throwable.toShareError(): ShareError = when {
    this is ShareError                                    -> this
    message?.contains("not_owner")              == true   -> ShareError.NotOwner
    message?.contains("no_such_user")           == true   -> ShareError.NoSuchUser
    message?.contains("cannot_share_with_self") == true   -> ShareError.CannotShareSelf
    else                                                  -> ShareError.Unknown(this)
}

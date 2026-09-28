package com.badmintontracker.shared.auth

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthErrorCode

/**
 * What a sign-in that never got an answer from the server says: no network, a
 * timeout, anything that is not the server turning the credentials down.
 * Public so iOS can show the same words when the call itself throws into
 * Swift, rather than typing its own copy of them.
 */
const val SIGN_IN_UNREACHABLE_MESSAGE = "Something went wrong. Please check your connection and try again."

fun friendlyAuthError(e: Throwable): String {
    if (e is AuthRestException) {
        return when (e.errorCode) {
            AuthErrorCode.InvalidCredentials    -> "Incorrect email or password."
            AuthErrorCode.EmailNotConfirmed     -> "Please confirm your email before signing in."
            AuthErrorCode.UserAlreadyExists     -> "An account with this email already exists."
            AuthErrorCode.WeakPassword          -> "Password is too weak. Try a longer one."
            AuthErrorCode.ValidationFailed      -> "Please check your email and password."
            AuthErrorCode.OverRequestRateLimit,
            AuthErrorCode.OverEmailSendRateLimit,
            AuthErrorCode.OverSmsSendRateLimit  -> "Too many attempts. Please wait a moment and try again."
            else                                -> "Sign-in failed. Please try again."
        }
    }
    return SIGN_IN_UNREACHABLE_MESSAGE
}

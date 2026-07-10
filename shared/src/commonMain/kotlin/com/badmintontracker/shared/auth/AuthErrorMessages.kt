package com.badmintontracker.shared.auth

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthErrorCode

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
    return "Something went wrong. Please check your connection and try again."
}

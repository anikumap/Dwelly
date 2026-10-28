package com.example.dwelly.model

/**
 * The only error replies the system gives (LOGIC §6). Services return them as
 * Result failures. SQL functions raise them as 'replyName' or
 * 'replyName: reason' (see 0001_schema.sql), and [fromServerMessage] maps them back.
 */
sealed class DwellyError(message: String) : Exception(message) {
    class AccessDenied(val detail: String? = null) :
        DwellyError(if (detail == null) "Access denied" else "Access denied: $detail")

    class PromptLocation : DwellyError("Set your location first")

    class ListingUnavailable : DwellyError("Listing unavailable")

    class RejectChange(val reason: String) : DwellyError("Change rejected: $reason")

    class RejectAgreement(val reason: String) : DwellyError("Agreement action rejected: $reason")

    companion object {
        /** Returns null when [message] is not one of the spec replies. */
        fun fromServerMessage(message: String?): DwellyError? {
            if (message == null) return null
            val name = message.substringBefore(':').trim()
            val reason = message.substringAfter(':', "").trim().ifEmpty { null }
            return when (name) {
                "accessDenied" -> AccessDenied(reason)
                "promptLocation" -> PromptLocation()
                "listingUnavailable" -> ListingUnavailable()
                "rejectChange" -> RejectChange(reason ?: "not allowed")
                "rejectAgreement" -> RejectAgreement(reason ?: "not allowed")
                else -> null
            }
        }
    }
}

package me.rerere.rikkahub.standalone.api

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int,
)

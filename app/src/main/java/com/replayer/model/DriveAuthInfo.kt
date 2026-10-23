package com.replayer.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceCodeResponse(
    val device_code: String,
    val user_code: String,
    val verification_url: String,
    val expires_in: Int,
    val interval: Int
)

@Serializable
data class TokenResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val expires_in: Int? = null,
    val token_type: String? = null,
    val error: String? = null
)

package me.mudkip.moememos.data.api

import android.util.Base64
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import org.json.JSONObject

/**
 * Compatibility helpers for Memos V1 API differences between ~0.22–0.24 and ≥0.27.
 */

suspend fun MemosV1Api.getProfile(): ApiResponse<MemosProfile> {
    val instance = getInstanceProfile()
    val version = instance.getOrNull()?.version?.trim().orEmpty()
    if (version.isNotEmpty()) {
        return instance
    }
    return getWorkspaceProfile()
}

/**
 * Resolve the signed-in user.
 * - ≥0.27: GET /api/v1/auth/me
 * - ~0.22–0.24: JWT `sub` + GET /api/v1/users/{id}
 */
suspend fun MemosV1Api.resolveCurrentUser(accessToken: String?): ApiResponse<MemosV1User> {
    val me = getCurrentUser()
    val user = me.getOrNull()?.user
    if (user != null) {
        return ApiResponse.Success(user)
    }

    val userId = accessToken?.let { parseJwtSubject(it) }?.trim().orEmpty()
    if (userId.isEmpty()) {
        return ApiResponse.Failure.Exception(
            IllegalStateException("Unable to resolve current user (auth/me unavailable and token has no subject)")
        )
    }
    return getUser(userId)
}

fun parseJwtSubject(token: String): String? {
    return try {
        val parts = token.split('.')
        if (parts.size < 2) return null
        var payload = parts[1]
        val padding = (4 - payload.length % 4) % 4
        if (padding > 0) {
            payload += "=".repeat(padding)
        }
        val json = String(
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP),
            Charsets.UTF_8
        )
        val sub = JSONObject(json).opt("sub") ?: return null
        sub.toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

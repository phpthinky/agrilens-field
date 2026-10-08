package com.agrilens.field.storage

import android.content.Context
import android.content.SharedPreferences

/** Holds the Sanctum bearer token and basic user info between app launches. */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agrilens_session", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    val isLoggedIn: Boolean
        get() = !token.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_USERNAME = "username"
    }
}

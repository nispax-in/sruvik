package `in`.nispax.sruvik.ui

import android.content.Context

data class SruvikProfile(
    val name: String = "",
    val age: Int = 0
) {
    val isComplete: Boolean
        get() = name.isNotBlank() && age in 1..120
}

/**
 * Small, offline-only preference store. Profile fields and UI preferences stay
 * in this app's private SharedPreferences file; nothing is synced or uploaded.
 */
object SruvikPreferences {
    private const val FILE = "sruvik_user_preferences"
    private const val KEY_NAME = "profile_name"
    private const val KEY_AGE = "profile_age"
    private const val KEY_SHOW_DAILY_TIP = "show_daily_tip"
    private const val KEY_VAULT_BIOMETRIC = "vault_require_biometric"
    private const val KEY_VAULT_AUTO_LOCK = "vault_auto_lock_minutes"
    private const val KEY_SRUGEN_MEMORY_ENABLED = "srugen_memory_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun loadProfile(context: Context) = SruvikProfile(
        name = prefs(context).getString(KEY_NAME, "").orEmpty(),
        age = prefs(context).getInt(KEY_AGE, 0)
    )

    fun saveProfile(context: Context, profile: SruvikProfile) {
        val cleanName = profile.name.trim().take(32)
        require(cleanName.isNotBlank())
        require(profile.age in 1..120)
        prefs(context).edit()
            .putString(KEY_NAME, cleanName)
            .putInt(KEY_AGE, profile.age)
            .apply()
    }

    fun showDailyTip(context: Context) =
        prefs(context).getBoolean(KEY_SHOW_DAILY_TIP, true)

    fun setShowDailyTip(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DAILY_TIP, enabled).apply()
    }

    fun requireVaultBiometric(context: Context) =
        prefs(context).getBoolean(KEY_VAULT_BIOMETRIC, true)

    fun setRequireVaultBiometric(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VAULT_BIOMETRIC, enabled).apply()
    }

    fun sruGenMemoryEnabled(context: Context) =
        prefs(context).getBoolean(KEY_SRUGEN_MEMORY_ENABLED, false)

    fun setSruGenMemoryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SRUGEN_MEMORY_ENABLED, enabled).apply()
    }

    fun vaultAutoLockMinutes(context: Context) =
        prefs(context).getInt(KEY_VAULT_AUTO_LOCK, 1)

    fun setVaultAutoLockMinutes(context: Context, minutes: Int) {
        require(minutes in setOf(0, 1, 5, 15, 30))
        prefs(context).edit().putInt(KEY_VAULT_AUTO_LOCK, minutes).apply()
    }
}

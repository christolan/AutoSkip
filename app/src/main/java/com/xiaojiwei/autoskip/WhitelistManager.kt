package com.xiaojiwei.autoskip

import android.content.Context
import android.content.SharedPreferences

class WhitelistManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("autoskip_whitelist", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WHITELIST = "whitelist_packages"
        private const val KEY_AUTO_SKIP_ENABLED = "auto_skip_enabled"
        private const val KEY_TOAST_ENABLED = "toast_enabled"
    }

    fun getWhitelistPackages(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    fun addPackage(packageName: String) {
        val current = getWhitelistPackages().toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    fun addPackages(packageNames: Set<String>) {
        if (packageNames.isEmpty()) return
        val current = getWhitelistPackages().toMutableSet()
        current.addAll(packageNames)
        prefs.edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    fun removePackage(packageName: String) {
        val current = getWhitelistPackages().toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    fun isInWhitelist(packageName: String): Boolean {
        return getWhitelistPackages().contains(packageName)
    }

    fun isAutoSkipEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SKIP_ENABLED, true)
    }

    fun setAutoSkipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SKIP_ENABLED, enabled).apply()
    }

    fun isToastEnabled(): Boolean {
        return prefs.getBoolean(KEY_TOAST_ENABLED, false)
    }

    fun setToastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_ENABLED, enabled).apply()
    }
}

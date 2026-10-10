package dev.surgeon.partition.util

import android.content.Context

object Prefs {

    private const val NAME = "surgeon"
    const val DEFAULT_OUTPUT_DIR = "/sdcard/PartitionSurgeon"

    private const val KEY_OUTPUT_DIR = "output_dir"
    private const val KEY_FORCE_BACKUP = "force_backup"
    private const val KEY_VERIFY = "verify_after_flash"
    private const val KEY_EXPERT = "expert_mode"
    private const val KEY_DISCLAIMER = "disclaimer_agreed"

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun outputDir(context: Context): String =
        sp(context).getString(KEY_OUTPUT_DIR, DEFAULT_OUTPUT_DIR)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_OUTPUT_DIR

    fun setOutputDir(context: Context, value: String) {
        sp(context).edit().putString(KEY_OUTPUT_DIR, value.trim()).apply()
    }

    fun forceBackup(context: Context): Boolean = sp(context).getBoolean(KEY_FORCE_BACKUP, true)

    fun setForceBackup(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_FORCE_BACKUP, value).apply()
    }

    fun verifyAfterFlash(context: Context): Boolean = sp(context).getBoolean(KEY_VERIFY, false)

    fun setVerifyAfterFlash(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_VERIFY, value).apply()
    }

    fun expertMode(context: Context): Boolean = sp(context).getBoolean(KEY_EXPERT, false)

    fun setExpertMode(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_EXPERT, value).apply()
    }

    /** 是否已阅读并同意免责声明。未同意时不进入任何功能。 */
    fun disclaimerAgreed(context: Context): Boolean =
        sp(context).getBoolean(KEY_DISCLAIMER, false)

    fun setDisclaimerAgreed(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_DISCLAIMER, value).apply()
    }
}

package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.shilapi.xcertplay.UsbPermissionSetup
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File
import org.json.JSONObject

/**
 * Updates of a private car build from its own GitHub repository instead of the upstream releases.
 * The build sets the repository and a read-only token as manifest meta-data (DIPLAY_UPDATE_REPO,
 * DIPLAY_UPDATE_TOKEN); without them DiPlay checks the upstream releases as before. Its tags are
 * `v0.2.21-geely.N`: the upstream version, then the build number.
 *
 * The APK installs through the head unit's own adbd when DiPlay already has access to it: a
 * detached script installs it, puts UsbAutoConfirmService back into the accessibility list
 * (Android 9 drops it on a package update, and the boot start needs it) and opens DiPlay again.
 */
internal object GeelyUpdates {
    private const val TAG = "DiPlay-Update"
    private const val REPO_KEY = "com.shilapi.xcertplay.UPDATE_REPO"
    private const val TOKEN_KEY = "com.shilapi.xcertplay.UPDATE_TOKEN"
    private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val BUILD = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-geely(?:\\.(\\d+))?)?")
    private const val INSTALL_DIRECTORY = "update"
    private const val LOG = "install.log"
    // pm reads the file as the shell; the shell's own directory, as `adb install` uses.
    private const val TMP_APK = "/data/local/tmp/diplay-update.apk"

    @Volatile private var repo: String? = null
    @Volatile private var token: String? = null
    @Volatile private var attached = false
    @Volatile private var installStartedAt = 0L
    private const val INSTALL_GUARD_MILLIS = 3 * 60 * 1000L

    fun attach(context: Context) {
        if (attached) return
        val metaData = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        configure(metaData?.getString(REPO_KEY), metaData?.getString(TOKEN_KEY))
        attached = true
        if (active) reportLastInstall(context)
    }

    internal fun configure(repository: String?, readToken: String?) {
        repo = repository?.takeIf { REPO.matches(it) }
        token = readToken?.takeIf { it.isNotBlank() }
    }

    val active: Boolean get() = repo != null && token != null

    fun releasesUrl(): String =
        repo?.takeIf { active }?.let { "https://api.github.com/repos/$it/releases?per_page=3" } ?: UpdateClient.RELEASES_URL

    /** The token goes only to this repository's API; GitHub's redirect to the file host drops it. */
    fun headers(url: String): Map<String, String> {
        val own = repo?.takeIf { active } ?: return emptyMap()
        if (!inRepository(url, own)) return emptyMap()
        val headers = mutableMapOf("Authorization" to "Bearer $token", "X-GitHub-Api-Version" to "2022-11-28")
        if ("/releases/assets/" in url) headers["Accept"] = "application/octet-stream"
        return headers
    }

    /** Whether a cached release came from this source; one found by an older build may be upstream's. */
    fun ownRelease(apkUrl: String): Boolean =
        repo?.takeIf { active }?.let { inRepository(apkUrl, it) } ?: true

    // GitHub writes asset URLs with the repository's own spelling, which may differ in case.
    private fun inRepository(url: String, own: String): Boolean =
        url.startsWith("https://api.github.com/repos/$own/", ignoreCase = true)

    /** A private repository's files download only through the API, with the token. */
    fun assetUrl(asset: JSONObject): String =
        asset.optString(if (active) "url" else "browser_download_url")

    /** Null when the upstream comparison applies. */
    fun isNewer(remote: String, installed: String): Boolean? {
        if (!active) return null
        val target = build(remote) ?: return false
        val current = build(installed) ?: return true
        return compareValuesBy(target, current, { it[0] }, { it[1] }, { it[2] }, { it[3] }) > 0
    }

    private fun build(value: String): List<Int>? = BUILD.find(value)?.groupValues?.let { groups ->
        listOf(groups[1].toInt(), groups[2].toInt(), groups[3].toInt(), groups[4].toIntOrNull() ?: 0)
    }

    /**
     * Starts the install through adbd, or returns false when DiPlay has no approved access to it and
     * the system installer has to ask instead. Blocking: run it off the main thread.
     */
    @Synchronized
    fun installQuietly(context: Context, apk: File): Boolean {
        if (!active) return false
        // A second tap while the first install runs would start another one.
        if (System.currentTimeMillis() - installStartedAt < INSTALL_GUARD_MILLIS) return true
        var adb: LocalAdb? = null
        return try {
            val directory = File(context.getExternalFilesDir(null) ?: return false, INSTALL_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) return false
            // The app's external files are readable by the adbd shell, its private cache is not. Any app
            // with storage access can write there on Android 9, so the script goes inline instead of in a
            // file, and the shell checks its own copy against the hash of the private file before installing.
            val copy = if (apk.parentFile == directory) apk else apk.copyTo(File(directory, apk.name), overwrite = true)
            val hash = UpdateChecksums.sha256Hex(apk)
            val log = File(directory, LOG).apply { delete() }
            val local = LocalAdb(AdbKeys.load(context)).also { adb = it }
            if (local.connect(mayAsk = false) != LocalAdb.Access.READY) return false
            // Detached: installing the package ends this process, and adbd closes the stream with it.
            val body = quote(script(context.packageName, copy, hash, log))
            val started = local.shell("(command -v setsid >/dev/null && setsid sh -c $body || nohup sh -c $body) </dev/null >/dev/null 2>&1 &") != null
            if (started) installStartedAt = System.currentTimeMillis()
            started
        } catch (failure: Exception) {
            Log.w(TAG, "quiet install failed to start", failure)
            false
        } finally {
            adb?.close()
        }
    }

    /** The package manager's refusal once the started install failed, or null while it runs or after it worked. */
    fun installFailure(context: Context): String? {
        val log = context.getExternalFilesDir(null)?.let { File(File(it, INSTALL_DIRECTORY), LOG) } ?: return null
        val lines = runCatching { log.readLines() }.getOrNull() ?: return null
        val status = lines.firstOrNull { it.startsWith("pm=") }?.removePrefix("pm=") ?: return null
        if (status == "0") return null
        installStartedAt = 0L
        return lines.lastOrNull { it.startsWith("Failure") || it.startsWith("Error") } ?: "pm=$status"
    }

    internal fun script(packageName: String, apk: File, sha256: String, log: File): String {
        val accessibility = UsbPermissionSetup.command(UsbPermissionSetup.Permission.ACCESSIBILITY, packageName)
        val logPath = quote(log.absolutePath)
        return """
            sleep 1
            echo "install ${apk.name}" > $logPath
            rm -f $TMP_APK
            cp ${quote(apk.absolutePath)} $TMP_APK
            if [ "${'$'}(sha256sum $TMP_APK | cut -d' ' -f1)" = "$sha256" ]; then
              pm install -r $TMP_APK >> $logPath 2>&1
              echo "pm=${'$'}?" >> $logPath
            else
              echo "Failure [checksum of the copied APK does not match]" >> $logPath
              echo "pm=checksum" >> $logPath
            fi
            rm -f $TMP_APK
            sleep 3
            ( $accessibility ) >> $logPath 2>&1
            echo "accessibility=${'$'}?" >> $logPath
            am start -n ${quote("$packageName/com.shilapi.xcertplay.DiPlayActivity")} >> $logPath 2>&1
            rm -f ${quote(apk.absolutePath)}
        """.trimIndent() + "\n"
    }

    // The new version logs how its own install went, once.
    private fun reportLastInstall(context: Context) {
        val directory = context.getExternalFilesDir(null)?.let { File(it, INSTALL_DIRECTORY) } ?: return
        val log = File(directory, LOG)
        if (!log.isFile) return
        runCatching {
            Log.i(TAG, "last install: " + log.readText().lines().filter { it.isNotBlank() }.joinToString(" | "))
            log.renameTo(File(directory, "$LOG.last"))
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

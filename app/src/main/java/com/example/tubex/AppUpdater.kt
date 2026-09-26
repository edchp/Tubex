package com.example.tubex

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

object AppUpdater {
    private const val GITHUB_REPO = "edchp/Tubex"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val MIRROR_URL = "https://poseidon.tail7e7844.ts.net/app/tubex-update.apk"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private val executor = Executors.newSingleThreadExecutor()
    private var pendingInstallFile: File? = null

    fun checkForUpdate(activity: AppCompatActivity) {
        executor.execute {
            try {
                val json = JSONObject(downloadText(LATEST_RELEASE_URL))
                val remoteCode = parseVersionCode(json.optString("tag_name", ""))
                if (remoteCode <= BuildConfig.VERSION_CODE) return@execute

                val versionName = json.optString("name", json.optString("tag_name", remoteCode.toString()))
                val notes = json.optString("body")
                val apkUrl = firstAssetUrl(json.optJSONArray("assets"))
                if (apkUrl.isBlank()) return@execute

                activity.runOnUiThread {
                    showUpdateDialog(activity, versionName, notes, apkUrl)
                }
            } catch (_: Exception) {
                // La comprobacion de actualizaciones no debe bloquear el uso normal de la app.
            }
        }
    }

    private fun parseVersionCode(tag: String): Int {
        val match = Regex("(\\d+)").find(tag) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    private fun firstAssetUrl(assets: JSONArray?): String {
        if (assets == null || assets.length() == 0) return ""
        return assets.getJSONObject(0).optString("browser_download_url", "")
    }

    fun retryPendingInstall(activity: AppCompatActivity) {
        val apkFile = pendingInstallFile ?: return
        if (!apkFile.exists()) {
            pendingInstallFile = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            installApk(activity, apkFile)
        }
    }

    private fun showUpdateDialog(
        activity: AppCompatActivity,
        versionName: String,
        notes: String,
        apkUrl: String
    ) {
        val message = buildString {
            append("Hay una nueva version disponible ($versionName).")
            if (notes.isNotBlank()) {
                append("\n\n")
                append(notes)
            }
            append("\n\n¿Deseas descargarla e instalarla?")
        }

        AlertDialog.Builder(activity)
            .setTitle("Actualizar Tubex")
            .setMessage(message)
            .setPositiveButton("Actualizar") { _, _ -> downloadAndInstall(activity, apkUrl) }
            .setNegativeButton("Ahora no", null)
            .show()
    }

private fun downloadAndInstall(activity: AppCompatActivity, apkUrl: String) {
        Toast.makeText(activity, "Descargando actualizacion...", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                val apkFile = File(dir, "tubex-update.apk")
                var error: Exception? = null
                val sources = listOf(apkUrl, MIRROR_URL)
                for (source in sources) {
                    var attempt = 0
                    while (attempt < 3) {
                        attempt++
                        error = try {
                            downloadFile(activity, source, apkFile)
                            null
                        } catch (e: Exception) {
                            Log.e("AppUpdater", "descarga ${if (source == MIRROR_URL) "espejo" else "github"} intento $attempt: ${e.message}", e)
                            e
                        }
                        if (error == null) break
                    }
                    if (error == null) break
                }
                if (error != null) throw error
                activity.runOnUiThread { installApk(activity, apkFile) }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "No se pudo descargar la actualizacion: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(activity: AppCompatActivity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = apkFile
            Toast.makeText(activity, "Permite instalar apps desconocidas para actualizar Tubex", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            return
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            pendingInstallFile = null
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No hay instalador disponible", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadText(urlText: String): String {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.setRequestProperty("User-Agent", "Tubex Android/${BuildConfig.VERSION_NAME}")
        return try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

private fun downloadFile(activity: AppCompatActivity, urlText: String, destination: File) {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.setInstanceFollowRedirects(true)
        connection.setRequestProperty("User-Agent", "Tubex Android/${BuildConfig.VERSION_NAME}")
        val socketFactory = SslSupport.build(activity)?.sslSocketFactory
        if (socketFactory != null && connection is HttpsURLConnection) {
            connection.sslSocketFactory = socketFactory
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

}
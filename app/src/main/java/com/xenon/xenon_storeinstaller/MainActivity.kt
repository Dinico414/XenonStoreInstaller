package com.xenon.xenon_storeinstaller

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var downloadButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private val githubRepoUrl = "https://api.github.com/repos/xenon-app/xenon-store/releases/latest"
    private val apkFileName = "xenonstore.apk"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startDownload()
            } else {
                Toast.makeText(this, "Storage permission is required to download the app.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        downloadButton = findViewById(R.id.downloadButton)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)

        downloadButton.setOnClickListener {
            checkStoragePermissionAndDownload()
        }
    }

    private fun checkStoragePermissionAndDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            startDownload()
        } else {
            // Request permission for devices running Android below 11
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun startDownload() {
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Fetching latest release info..."
        downloadButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val latestReleaseUrl = fetchLatestReleaseUrl()
                if (latestReleaseUrl.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressText.text = "Preparing download..."
                    }
                    downloadApk(latestReleaseUrl)
                } else {
                    withContext(Dispatchers.Main) {
                        progressText.text = "Failed to fetch release information."
                        Toast.makeText(this@MainActivity, "Could not retrieve the latest release URL.", Toast.LENGTH_LONG).show()
                        resetUI()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    progressText.text = "Network error."
                    Toast.makeText(this@MainActivity, "Error fetching release information: ${e.message}", Toast.LENGTH_LONG).show()
                    resetUI()
                }
            }
        }
    }

    private suspend fun fetchLatestReleaseUrl(): String {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(githubRepoUrl)
            .build()

        return withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body?.string()
                val jsonObject = JSONObject(jsonString)
                val assets = jsonObject.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        return@withContext asset.getString("browser_download_url")
                    }
                }
            }
            ""
        }
    }

    private fun downloadApk(apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Xenon Store Download")
            .setDescription("Downloading the latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, apkFileName)
            .setMimeType("application/vnd.android.package-archive")
            .allowScanningByMediaScanner()

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // Optionally, you can monitor the download progress using a ContentObserver
        lifecycleScope.launch(Dispatchers.IO) {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    if (bytesTotal > 0) {
                        val progress = (bytesDownloaded * 100 / bytesTotal).toInt()
                        withContext(Dispatchers.Main) {
                            progressBar.progress = progress
                            progressText.text = "Downloading: $progress%"
                        }
                    }

                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        downloading = false
                        withContext(Dispatchers.Main) {
                            resetUI()
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                Toast.makeText(this@MainActivity, "Download complete. Check your Downloads folder.", Toast.LENGTH_LONG).show()
                                installApk(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), apkFileName))
                            } else {
                                Toast.makeText(this@MainActivity, "Download failed.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                cursor.close()
                if (downloading) {
                    kotlinx.coroutines.delay(500) // Update progress every 500 milliseconds
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        val installIntent = Intent(Intent.ACTION_VIEW)
        val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        installIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(installIntent)
    }

    private fun resetUI() {
        downloadButton.isEnabled = true
        progressBar.visibility = View.INVISIBLE
        progressText.visibility = View.INVISIBLE
        progressBar.progress = 0
        progressText.text = ""
    }
}

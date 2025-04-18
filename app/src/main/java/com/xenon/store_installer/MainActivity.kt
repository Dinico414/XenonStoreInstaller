package com.xenon.store_installer

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_UNINSTALL_PACKAGE
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import com.xenon.commons.accesspoint.R.color
import com.xenon.commons.accesspoint.R.drawable
import com.xenon.store_installer.databinding.ActivityMainBinding
import java.io.File

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val apkFileName = "XenonStore.apk"
    private var downloadID: Long = 0
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Permission granted")
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Uninstall successful
                Log.d("MainActivity", "Uninstall successful")
                showSnackbar("Uninstall successful")
                // Update UI or app state as needed
            } else {
                // Uninstall failed or was cancelled
                Log.e("MainActivity", "Uninstall failed or cancelled")
                showSnackbar("Uninstall failed or cancelled")
            }
        }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkStoragePermission()

        binding.downloadButton.setOnClickListener {
            startDownload("https://api.github.com/repos/dinico414/xenonstore/releases/latest") // Replace with your actual download URL
        }

        // Register a receiver to handle download completion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }
    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onDownloadComplete)
    }


    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadID) {
                showSnackbar("Download completed.")
                val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkFileName)
                Handler(Looper.getMainLooper()).postDelayed({
                    installApk(apkFile)
                }, 1000) // 1-second delay
            }
        }
    }


    private fun showSnackbar(message: String) {
        runOnUiThread {
            val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            val backgroundDrawable =
                resources.getDrawable(drawable.tile_popup, null)

            // Set the background
            snackbar.view.background = backgroundDrawable
            // Customize text color
            snackbar.setTextColor(
                resources.getColor(
                    color.onError,
                    null
                )
            )

            snackbar.setBackgroundTint(
                resources.getColor(
                    color.error,
                    null
                )
            )
            snackbar.show()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            // Permission already granted or not needed (Android 11+)
            Log.d("MainActivity", "Storage permission already granted or not needed")
        }
    }

    private fun startDownload(downloadUrl: String) {
        if (downloadUrl.isEmpty()) {
            showSnackbar("Invalid download URL")
            return
        }

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Downloading Xenon Store")
            .setDescription("Downloading the latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, apkFileName)
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadID = downloadManager.enqueue(request)
        showSnackbar("Download started. Check notifications for progress.")
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            showSnackbar("APK file not found")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !packageManager.canRequestPackageInstalls()) {
            // Request INSTALL_PACKAGES permission on Android 12+
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            showSnackbar("Allow installing apps from this source in settings, then try again.")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val apkUri = FileProvider.getUriForFile(this, "${packageName}.provider", apkFile)
                val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(apkUri)
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, packageName)
                startActivity(installIntent)
            } else {
                val apkUri = Uri.fromFile(apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(installIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Installation failed: ${e.message}", e)
            showSnackbar("Installation failed: ${e.message}")
        }

        // Check for com.xenon.store after attempting install
        Handler(Looper.getMainLooper()).postDelayed({
            checkForXenonStoreAndUninstall()
        }, 2000) // 2-second delay to allow install check
    }

    private fun checkForXenonStoreAndUninstall() {
        if (isPackageInstalled("com.xenon.store")) {
            uninstallApp("com.xenon.store_installer")
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }


    private fun uninstallApp(packageName: String) {
        if (packageName.isEmpty()) {
            showSnackbar("Invalid package name for uninstall")
            return
        }

        try {
            val intent = Intent(ACTION_UNINSTALL_PACKAGE)
            intent.data = Uri.parse("package:$packageName")

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT // Use FLAG_IMMUTABLE for security
            )

            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            uninstallLauncher.launch(intentSenderRequest)

        } catch (e: PackageManager.NameNotFoundException) {
            showSnackbar("App not found: $packageName")
        } catch (e: Exception) {
            Log.e("MainActivity", "Uninstall failed: ${e.message}", e)
            showSnackbar("Uninstall failed: ${e.message}")
        }
    }}
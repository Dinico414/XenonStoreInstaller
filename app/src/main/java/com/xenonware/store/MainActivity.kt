package com.xenonware.store

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import com.xenonware.store.ui.theme.XenonStoreInstallerTheme
import com.xenonware.store.viewmodel.AppListViewModel
import java.io.File

@Suppress("DEPRECATION", "SameParameterValue", "UNNECESSARY_SAFE_CALL")
class MainActivity : ComponentActivity() {
    private lateinit var appListModel: AppListViewModel
    private lateinit var installPermissionLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("UseKtx")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appListModel = ViewModelProvider(this)[AppListViewModel::class.java]

        installPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult())
            { _ ->
                if (checkInstallPermission()) {
                    showToast(applicationContext.getString(R.string.permission_granted))
                    val pendingApk = appListModel.downloadedApkQueue.peek()
                    if (pendingApk != null) {
                        installApk(pendingApk)
                    }
                } else {
                    showToast(applicationContext.getString(R.string.permission_denied))
                }
            }

        appListModel.requestInstallPermission.observe(this) { _ ->
            launchInstallPrompt()
        }

        appListModel.downloadedApkFile.observe(this) { apkFile ->
            apkFile?.let {
                installApk(it)
                appListModel.downloadedApkFile.postValue(null)
                appListModel.downloadedApkQueue.clear()
            }
        }

        setContent {
            XenonStoreInstallerTheme {
                MainScreen(viewModel = appListModel)
            }
        }
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.provider",
            apkFile
        )
        if (checkInstallPermission()) {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(installIntent)
            } catch (_: Exception) {
            }
        } else {
            launchInstallPrompt()
        }
    }

    private fun checkInstallPermission(): Boolean {
        return packageManager.canRequestPackageInstalls()
    }

    private fun launchInstallPrompt() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:$packageName".toUri()
        }
        installPermissionLauncher.launch(intent)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}

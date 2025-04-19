package com.xenon.store_installer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AnimationDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.xenon.commons.accesspoint.R.color
import com.xenon.commons.accesspoint.R.drawable
import com.xenon.store_installer.AppEntryState.DOWNLOADING
import com.xenon.store_installer.AppEntryState.INSTALLED
import com.xenon.store_installer.AppEntryState.INSTALLED_AND_OUTDATED
import com.xenon.store_installer.AppEntryState.NOT_INSTALLED
import com.xenon.store_installer.databinding.ActivityMainBinding
import com.xenon.store_installer.viewmodel.AppListViewModel
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Suppress("DEPRECATION", "SameParameterValue")
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appListModel: AppListViewModel
    private val tag = MainActivity::class.qualifiedName
    private var activeSnackbar: Snackbar? = null
    private lateinit var networkChangeListener: NetworkChangeListener

    private lateinit var client: OkHttpClient

    private lateinit var installPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val constraintLayout: ConstraintLayout = findViewById(R.id.mainLayout)
        val animationDrawable = constraintLayout.background as? AnimationDrawable
        animationDrawable?.apply {
            setEnterFadeDuration(2500)
            setExitFadeDuration(5000)
            start()
        }

        client = OkHttpClient.Builder()
            .cache(Cache(
                directory = File(baseContext.cacheDir, "http_cache"),
                maxSize = 10L * 1024L * 1024L // 10 MiB
            ))
            .eventListener(object : EventListener() {
                override fun cacheHit(call: Call, response: Response) {
                    super.cacheHit(call, response)
                    Log.d(tag, "CACHE HIT ${response.request.url}")
                }
            })
            .build()

        installPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult())
            { _ ->
                if (checkInstallPermission()) {
                    showToast(getString(R.string.permission_granted))
                } else {
                    showToast(getString(R.string.permission_denied))
                }
            }

        // Request install permission on create (consider if this is the best UX)
        if (!checkInstallPermission()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            installPermissionLauncher.launch(intent)
        }

        appListModel = ViewModelProvider(this)[AppListViewModel::class.java]
        appListModel.appItemUpdated.observe(this) { appItem ->
            when (appItem.state) {
                DOWNLOADING -> {
                    binding.downloadButton.text = ""
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = appItem.bytesDownloaded.toInt()
                    binding.progressBar.max = appItem.fileSize.toInt()
                }

                INSTALLED,
                INSTALLED_AND_OUTDATED -> {
                    binding.downloadButton.text = getString(R.string.uninstall)
                    binding.progressBar.visibility = View.GONE
                }

                NOT_INSTALLED -> {
                    binding.downloadButton.text = getString(R.string.install)
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        networkChangeListener = NetworkChangeListener(
            baseContext,
            onNetworkAvailable = {
                Log.d(tag, "Network connected")
                activeSnackbar?.dismiss()
                activeSnackbar = null
                refreshAppItem(appListModel.storeAppItem)
            },
            onNetworkUnavailable = {
                Log.d(tag, "Network disconnected")
                showNoInternetSnackbar()
            }
        )

        binding.downloadButton.setOnClickListener {
            when (appListModel.storeAppItem.state) {
                NOT_INSTALLED, INSTALLED_AND_OUTDATED -> {
                    downloadAppItem(appListModel.storeAppItem)
                }
                INSTALLED -> {
                    // Call uninstallPackages to handle both packages
                    uninstallPackages()
                }
                DOWNLOADING -> {}
            }
        }

        appListModel.downloadedApkFile.observe(this) { _ ->
            appListModel.downloadedApkFile.postValue(null)
            while (true) {
                val apkFile = appListModel.downloadedApkQueue.poll() ?: break
                installApk(apkFile)
            }
        }

        refreshAppItem(appListModel.storeAppItem)
    }
    override fun onResume() {
        super.onResume()
        Log.d(tag, "onResume")
        networkChangeListener.register()
        if (isNetworkAvailable()) {
            networkChangeListener.onNetworkAvailable()
        } else {
            networkChangeListener.onNetworkUnavailable()
        }
    }

    override fun onPause() {
        super.onPause()
        networkChangeListener.unregister()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = baseContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            ?: return false

        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    interface GithubReleaseAPICallback {
        fun onCompleted(version: String, downloadUrl: String)
        fun onFailure(error: String)
    }

    private fun getNewReleaseVersionGithub(
        owner: String,
        repo: String,
        preRelease: Boolean,
        useCache: Boolean,
        callback: GithubReleaseAPICallback
    ) {
        val url = if (preRelease) "https://api.github.com/repos/$owner/$repo/releases?per_page=1"
        else "https://api.github.com/repos/$owner/$repo/releases/latest"

        downloadToString(url, object : DownloadListener<String> {
            override fun onProgress(downloaded: Long, size: Long) {}
            override fun onCompleted(result: String) {
                Log.d("response body $repo", result)

                // Parse json
                val latestRelease: JSONObject
                if (preRelease) {
                    val releases = JSONArray(result)
                    latestRelease = releases.getJSONObject(0)
                } else {
                    latestRelease = JSONObject(result)
                }

                val assets = latestRelease.getJSONArray("assets")
                val newVersion = latestRelease.getString("tag_name")
                if (assets.length() > 0) {
                    val asset = assets.getJSONObject(0)
                    callback.onCompleted(newVersion, asset.getString("browser_download_url"))
                } else {
                    val noAssets = getString(R.string.no_assets)
                    callback.onFailure("$noAssets $repo")
                }
            }
            override fun onFailure(error: String) {
                callback.onFailure(error)
            }
        }, useCache)
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledAppVersion(packageName: String): String? {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo?.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    interface DownloadListener<T> {
        fun onProgress(downloaded: Long, size: Long)
        fun onCompleted(result: T)
        fun onFailure(error: String)
    }

    private fun refreshAppItem(appItem: AppItem, useCache: Boolean = true) {
        val preReleases = false

        if (appItem.newIsPreRelease != preReleases) {
            appItem.newVersion = ""
            appItem.downloadUrl = ""
            appItem.newIsPreRelease = false
        }

        appItem.installedVersion = getInstalledAppVersion(appItem.packageName) ?: ""

        if (appItem.state == DOWNLOADING) {
//            downloadAppItem(appItem)
        } else if (appItem.isOutdated()) {
            appItem.state = INSTALLED_AND_OUTDATED
        } else if (appItem.installedVersion != "") {
            appItem.state = INSTALLED
        } else {
            appItem.state = NOT_INSTALLED
        }
        appListModel.appItemUpdated.postValue(appItem)

        if (isNetworkAvailable() && (appItem.downloadUrl == "" || !useCache)) {

            getNewReleaseVersionGithub(appItem.owner, appItem.repo, preReleases, useCache,
                object : GithubReleaseAPICallback {
                    override fun onCompleted(version: String, downloadUrl: String) {
                        appItem.newIsPreRelease = preReleases
                        appItem.downloadUrl = downloadUrl
                        if (appItem.isNewerVersion(version)) {
                            appItem.newVersion = version
                            if (appItem.state == INSTALLED) {
                                appItem.state = INSTALLED_AND_OUTDATED
                                appListModel.appItemUpdated.postValue(appItem)
                            }
                        }
                    }

                    override fun onFailure(error: String) {
                        showErrorSnackbar("$error ${appItem.repo}")
                    }
                })
        }
    }

    private fun downloadAppItem(appItem: AppItem) {
        if (appItem.downloadUrl.isEmpty()) {
            showSnackbar(getString(R.string.failed_to_find))
            return
        }
        appListModel.storeAppItem.state = DOWNLOADING

        downloadToFile(
            appItem.downloadUrl,
            "${appItem.packageName}.apk",
            object : DownloadListener<File> {
                override fun onProgress(downloaded: Long, size: Long) {
                    appItem.bytesDownloaded = downloaded
                    appItem.fileSize = size
                    appListModel.appItemUpdated.postValue(appItem)
                }

                override fun onCompleted(result: File) {
                    Log.d(tag, "Completed download: $result")
                    appListModel.downloadedApkQueue.add(result)
                    appListModel.downloadedApkFile.postValue(result)
                    appItem.state = NOT_INSTALLED
                    refreshAppItem(appItem)
                }

                override fun onFailure(error: String) {
                    appItem.state = NOT_INSTALLED
                    refreshAppItem(appItem)
                    showErrorSnackbar(getString(R.string.download_failed))
                }
            },
            useCache = false)
    }

    private fun downloadToString(
        url: String,
        progressListener: DownloadListener<String>,
        useCache: Boolean = true,
        synchronous: Boolean = false,
    ) {
        Log.d(tag, "downloadToString(url=$url, useCache=$useCache)")
        val request = Request.Builder()
            .url(url)
            .build()
        (if (useCache) client else OkHttpClient()).newCall(request).apply {
            val callback = object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        progressListener.onFailure(getString(R.string.response_error_code, response.code.toString()))
                        return
                    }

                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        progressListener.onFailure(getString(R.string.empty_body))
                        return
                    }
                    progressListener.onCompleted(responseBody)
                }

                override fun onFailure(call: Call, e: IOException) {
                    progressListener.onFailure(getString(R.string.download_failed))
                }
            }
            if (!synchronous) this.enqueue(callback)
            else {
                try {
                    val response = this.execute()
                    callback.onResponse(this, response)
                } catch (e: IOException) {
                    callback.onFailure(this, e)
                }
            }
        }
    }

    private fun downloadToFile(
        url: String,
        filename: String,
        progressListener: DownloadListener<File>,
        useCache: Boolean = true,
        synchronous: Boolean = false,
    ) {
        Log.d(tag, "downloadToFile(url=$url, useCache=$useCache)")
        val request = Request.Builder()
            .url(url)
            .build()
        (if (useCache) client else OkHttpClient()).newCall(request).apply {
            val callback = object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        progressListener.onFailure(getString(R.string.response_error_code, response.code.toString()))
                        return
                    }

                    val contentLength = response.body?.contentLength() ?: -1
                    var downloadedBytes: Long = 0
                    val buffer = ByteArray(8192)
                    val inputStream = response.body?.byteStream()
                    val tempFile = File(getExternalFilesDir(null), filename)
                    val outputStream = FileOutputStream(tempFile)

                    try {
                        var bytesRead: Int
                        while (inputStream?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            progressListener.onProgress(downloadedBytes, contentLength)
                        }
                        progressListener.onCompleted(tempFile)
                    } catch (e: Exception) {
                        progressListener.onFailure(getString(R.string.download_failed))
                    } finally {
                        inputStream?.close()
                        outputStream.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    progressListener.onFailure(getString(R.string.download_failed))
                }
            }
            if (!synchronous) this.enqueue(callback)
            else {
                try {
                    val response = this.execute()
                    callback.onResponse(this, response)
                } catch (e: IOException) {
                    callback.onFailure(this, e)
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.provider",
            apkFile
        )
        if (checkInstallPermission()) {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        } else {
            launchInstallPrompt(uri)
        }
    }

    private fun uninstallPackages() {
        // Uninstall com.xenon.store_installer first (if installed)
        if (isAppInstalled("com.xenon.store_installer")) {
            "com.xenon.store_installer".uninstallPackage()
        }
    }

    private fun String.uninstallPackage() {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = "package:${this@uninstallPackage}".toUri()
        }
        startActivity(intent)
    }

    private fun checkInstallPermission(): Boolean {
        return packageManager.canRequestPackageInstalls()
    }

    private fun launchInstallPrompt(uri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        installPermissionLauncher.launch(installIntent)
    }


    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNoInternetSnackbar() {
        runOnUiThread {
            val snackbar = Snackbar.make(
                binding.root,
                getString(R.string.offline_message),
                Snackbar.LENGTH_INDEFINITE
            )
            val backgroundDrawable =
                ResourcesCompat.getDrawable(resources, drawable.tile_popup, null)

            snackbar.view.background = backgroundDrawable
            snackbar.setTextColor(resources.getColor(color.inverseOnSurface, null))
            snackbar.setBackgroundTint(resources.getColor(color.inverseSurface, null))
            snackbar.setAction(getString(R.string.open_settings)) {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                startActivity(intent)
            }
            activeSnackbar = snackbar
            snackbar.show()
        }
    }

    private fun showErrorSnackbar(message: String) {
        runOnUiThread {
            val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            val backgroundDrawable =
                ResourcesCompat.getDrawable(resources, drawable.tile_popup, null)

            snackbar.view.background = backgroundDrawable
            snackbar.setTextColor(resources.getColor(color.onError, null))
            snackbar.setBackgroundTint(resources.getColor(color.error, null))
            activeSnackbar = snackbar
            snackbar.show()
        }
    }

    inner class NetworkChangeListener(
        private val context: Context,
        val onNetworkAvailable: () -> Unit,
        val onNetworkUnavailable: () -> Unit
    ) : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            onNetworkAvailable()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            onNetworkUnavailable()
        }

        fun register() {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, this)
        }

        fun unregister() {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(this)
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun showSnackbar(message: String) {
        runOnUiThread {
            val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            val backgroundDrawable = resources.getDrawable(drawable.tile_popup, null)

            snackbar.view.background = backgroundDrawable
            snackbar.setTextColor(resources.getColor(color.onError, null))
            snackbar.setBackgroundTint(resources.getColor(color.error, null))
            snackbar.show()
        }
    }
}
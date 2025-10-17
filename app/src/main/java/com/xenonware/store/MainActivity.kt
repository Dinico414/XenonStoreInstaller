package com.xenonware.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import com.xenonware.store.AppEntryState.DOWNLOADING
import com.xenonware.store.AppEntryState.NOT_INSTALLED
import com.xenonware.store.databinding.ActivityMainBinding
import com.xenonware.store.viewmodel.AppListViewModel
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
import java.util.Timer
import java.util.TimerTask

@Suppress("DEPRECATION", "SameParameterValue", "UNNECESSARY_SAFE_CALL")
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appListModel: AppListViewModel
    private val tag = MainActivity::class.qualifiedName
    private lateinit var networkChangeListener: NetworkChangeListener

    private lateinit var client: OkHttpClient

    private lateinit var installPermissionLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("UseKtx")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.welcomeText) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = insets.top)
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.downloadButton) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = insets.bottom + (32 * resources.displayMetrics.density).toInt()
            view.layoutParams = params
            windowInsets
        }

        val typefaceBold = ResourcesCompat.getFont(this, R.font.quicksand_bold)
        binding.welcomeText.typeface = typefaceBold

        val typefaceLight = ResourcesCompat.getFont(this, R.font.quicksand_light)
        binding.downloadButton.typeface = typefaceLight

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
                    // Potentially re-trigger download/install if an APK was pending
                    val pendingApk = appListModel.downloadedApkQueue.peek()
                    if (pendingApk != null) {
                         installApk(pendingApk)
                    }
                } else {
                    showToast(getString(R.string.permission_denied))
                }
            }

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
                    setMainBackground(R.drawable.gradient_list_2) // Downloading/Progress gradient
                }
                NOT_INSTALLED -> {
                    binding.downloadButton.text = getString(R.string.install)
                    binding.progressBar.visibility = View.GONE
                    setMainBackground(R.drawable.gradient_list_1) // Initial/Ready to install gradient
                }
            }
        }

        networkChangeListener = NetworkChangeListener(
            baseContext,
            onNetworkAvailable = {
                Log.d(tag, "Network connected")
                refreshAppItem(appListModel.storeAppItem)
            },
            onNetworkUnavailable = {
                Log.d(tag, "Network disconnected")
                // Optionally update UI to indicate no network
                 appListModel.storeAppItem.state = NOT_INSTALLED // Reset state
                 appListModel.appItemUpdated.postValue(appListModel.storeAppItem)
            }
        )

        binding.downloadButton.setOnClickListener {
            if (appListModel.storeAppItem.state == NOT_INSTALLED && appListModel.storeAppItem.downloadUrl.isNotEmpty()) {
                downloadAppItem(appListModel.storeAppItem)
            } else if (appListModel.storeAppItem.state == DOWNLOADING) {
                // Optionally allow cancelling download, for now, do nothing
            } else if (!isNetworkAvailable()){
            } else {
                 // Potentially refresh if URL is empty and state is NOT_INSTALLED
                 refreshAppItem(appListModel.storeAppItem, useCache = false)
            }
        }

        appListModel.downloadedApkFile.observe(this) { apkFile ->
            // Using a single value LiveData, consume it here
            apkFile?.let {
                installApk(it)
                appListModel.downloadedApkFile.postValue(null) // Consume the event
                 // Clear the queue as we are processing the latest one
                appListModel.downloadedApkQueue.clear()
            }
        }
        // Initial refresh
        refreshAppItem(appListModel.storeAppItem)
    }

    private var currentMainBackground: Int = 0

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun setMainBackground(background: Int, reset: Boolean = false) {
        if (background == currentMainBackground && !reset) return
        Log.d("AAA", "Set background $background")

        if (currentMainBackground == 0) {
            currentMainBackground = background
            binding.mainLayout.setBackgroundResource(background)
            (binding.mainLayout.background as? AnimationDrawable)?.apply {
                setEnterFadeDuration(2500)
                setExitFadeDuration(5000)
                start()
            }
            return
        }

        currentMainBackground = background
        val d = getDrawable(background) as? AnimationDrawable
        if (d == null) { // Fallback if the background is not an animation drawable
            binding.mainLayout.setBackgroundResource(background)
            return
        }

        val transitionDurationMs = 800
        (binding.mainLayout.background as? AnimationDrawable)?.apply {
            setEnterFadeDuration(transitionDurationMs)
            setExitFadeDuration(transitionDurationMs)
            addFrame(d.getFrame(0), 50000) // Arbitrary long duration for the transition frame
            selectDrawable(numberOfFrames - 1) // Select the newly added frame
        }

        Timer().schedule(object : TimerTask() {
            val expectedBackground = background
            override fun run() {
                runOnUiThread { // Ensure UI updates are on the main thread
                    if (expectedBackground != currentMainBackground) return@runOnUiThread
                    binding.mainLayout.setBackgroundResource(currentMainBackground)
                    (binding.mainLayout.background as? AnimationDrawable)?.apply {
                        setEnterFadeDuration(2500)
                        setExitFadeDuration(5000)
                        start()
                    }
                }
            }
        }, transitionDurationMs.toLong())
    }

    override fun onResume() {
        super.onResume()
        Log.d(tag, "onResume")
        networkChangeListener.register()
        if (isNetworkAvailable()) {
            // If coming back to app and not downloading, refresh
            if (appListModel.storeAppItem.state != DOWNLOADING) {
                 refreshAppItem(appListModel.storeAppItem)
            }
            networkChangeListener.onNetworkAvailable() // Explicit call for immediate UI update if needed
        } else {
            networkChangeListener.onNetworkUnavailable()
        }

        // Set background based on current state, primarily for when app resumes
        when (appListModel.storeAppItem.state) {
            NOT_INSTALLED -> setMainBackground(R.drawable.gradient_list_1)
            DOWNLOADING -> setMainBackground(R.drawable.gradient_list_2)
        }
    }

    override fun onPause() {
        super.onPause()
        networkChangeListener.unregister()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = baseContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
                try {
                    val latestRelease: JSONObject = if (preRelease) {
                        val releases = JSONArray(result)
                        if (releases.length() > 0) releases.getJSONObject(0) else {
                            return
                        }
                    } else {
                        JSONObject(result)
                    }

                    val assets = latestRelease.getJSONArray("assets")
                    val newVersion = latestRelease.getString("tag_name")
                    // Basic check: if a version tag exists, consider it > "0.0"
                    if (newVersion.isNotBlank() && assets.length() > 0) {
                        val asset = assets.getJSONObject(0) // Assuming first asset is the APK
                        callback.onCompleted(newVersion, asset.getString("browser_download_url"))
                    } else {
                        val noAssets = getString(R.string.no_assets)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing Github release JSON", e)
                }
            }
            override fun onFailure(error: String) {
                callback.onFailure(error)
            }
        }, useCache)
    }

    interface DownloadListener<T> {
        fun onProgress(downloaded: Long, size: Long)
        fun onCompleted(result: T)
        fun onFailure(error: String)
    }

    private fun refreshAppItem(appItem: AppItem, useCache: Boolean = true) {
        // This app only installs, so it's always "NOT_INSTALLED" until downloading
        // or if an error occurs.
        if (appItem.state == DOWNLOADING) {
            // If already downloading, let the download progress update the UI.
            // Only post update if values changed that are not download progress.
             appListModel.appItemUpdated.postValue(appItem)
            return
        }
        // Set to NOT_INSTALLED by default before fetching, allows UI to show "Install"
        // if downloadUrl is already known from a previous fetch.
        appItem.state = NOT_INSTALLED
        appListModel.appItemUpdated.postValue(appItem)


        if (isNetworkAvailable() && (appItem.downloadUrl.isEmpty() || !useCache)) {
            // Fetch latest release info
            getNewReleaseVersionGithub(appItem.owner, appItem.repo, false, useCache, // Assuming preRelease is false
                object : GithubReleaseAPICallback {
                    override fun onCompleted(version: String, downloadUrl: String) {
                        appItem.newVersion = version
                        appItem.downloadUrl = downloadUrl
                        // Version > "0.0" is implied by a successful callback with a version
                        if (appItem.state != DOWNLOADING) { // Ensure we don't interrupt a download state
                           appItem.state = NOT_INSTALLED // Ready to be installed
                        }
                        appListModel.appItemUpdated.postValue(appItem)
                    }

                    override fun onFailure(error: String) {
                        appItem.downloadUrl = "" // Clear previous URL if fetch failed
                        appItem.newVersion = ""
                        appItem.state = NOT_INSTALLED // Allow retry
                        appListModel.appItemUpdated.postValue(appItem)
                    }
                })
        } else if (!isNetworkAvailable()) {
             appItem.state = NOT_INSTALLED // Reset state, allow retry when network is back
             appListModel.appItemUpdated.postValue(appItem)
        }
        // If downloadUrl is already populated and useCache is true, UI should already reflect NOT_INSTALLED
    }

    private fun downloadAppItem(appItem: AppItem) {
        if (appItem.downloadUrl.isEmpty()) {
            appItem.state = NOT_INSTALLED // Reset state
            appListModel.appItemUpdated.postValue(appItem)
            return
        }
        if (!checkInstallPermission()) {
             val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            installPermissionLauncher.launch(intent)
            // Do not proceed to download until permission is granted.
            // The user can click install again after granting permission.
            return
        }

        appItem.state = DOWNLOADING
        appListModel.appItemUpdated.postValue(appItem) // Update UI to show downloading state

        downloadToFile(
            appItem.downloadUrl,
            "${appItem.packageName}.apk", // Consistent naming
            object : DownloadListener<File> {
                override fun onProgress(downloaded: Long, size: Long) {
                    appItem.bytesDownloaded = downloaded
                    appItem.fileSize = size
                    // Only update if state is still DOWNLOADING to avoid race conditions
                    if(appItem.state == DOWNLOADING) {
                        appListModel.appItemUpdated.postValue(appItem)
                    }
                }

                override fun onCompleted(result: File) {
                    Log.d(tag, "Completed download: $result")
                    // Use the single LiveData for APK file to trigger install
                    appListModel.downloadedApkQueue.clear() // Clear old before adding new
                    appListModel.downloadedApkQueue.add(result)
                    appListModel.downloadedApkFile.postValue(result)
                    
                    appItem.state = NOT_INSTALLED // Reset state after download for next potential install
                    // Don't call refreshAppItem here, let install process complete or fail.
                    // UI should reflect that download is done and install is pending/attempted.
                    appListModel.appItemUpdated.postValue(appItem)
                }

                override fun onFailure(error: String) {
                    appItem.state = NOT_INSTALLED // Reset state on failure
                    refreshAppItem(appItem, useCache = false) // Attempt to refresh data
                }
            },
            useCache = false) // Always download fresh APK
    }

    private fun downloadToString(
        url: String,
        progressListener: DownloadListener<String>,
        useCache: Boolean = true,
        synchronous: Boolean = false,
    ) {
        Log.d(tag, "downloadToString(url=$url, useCache=$useCache)")
        val request = Request.Builder().url(url).build()
        (if (useCache) client else OkHttpClient()).newCall(request).apply {
            val callback = object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        runOnUiThread { progressListener.onFailure(getString(R.string.response_error_code, response.code.toString())) }
                        return
                    }
                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        runOnUiThread { progressListener.onFailure(getString(R.string.empty_body)) }
                        return
                    }
                    runOnUiThread { progressListener.onCompleted(responseBody) }
                }
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { progressListener.onFailure(getString(R.string.download_failed) + ": " + e.message) }
                }
            }
            if (!synchronous) this.enqueue(callback)
            else {
                try {
                    val response = this.execute()
                    callback.onResponse(this, response) // This will run on current thread. Ensure listeners are thread-safe or use runOnUiThread
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
        useCache: Boolean = true, // Should generally be false for APK files
        synchronous: Boolean = false,
    ) {
        Log.d(tag, "downloadToFile(url=$url, filename=$filename, useCache=$useCache)")
        val request = Request.Builder().url(url).build()
        // Use a new client for APK download to ensure no caching if not desired
        val httpClient = if (useCache) client else OkHttpClient()

        httpClient.newCall(request).apply {
            val callback = object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        runOnUiThread{ progressListener.onFailure(getString(R.string.response_error_code, response.code.toString()))}
                        return
                    }

                    val contentLength = response.body?.contentLength() ?: -1L
                    var downloadedBytes = 0L
                    val buffer = ByteArray(8192) // 8KB buffer
                    val inputStream = response.body?.byteStream()
                    // Use app-specific directory in external storage for APKs
                    val targetDir = File(getExternalFilesDir(null), "apk_downloads")
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val tempFile = File(targetDir, filename)
                    val outputStream = FileOutputStream(tempFile)

                    try {
                        var bytesRead: Int
                        while (inputStream?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            // Update progress on UI thread
                           runOnUiThread { progressListener.onProgress(downloadedBytes, contentLength) }
                        }
                        runOnUiThread{ progressListener.onCompleted(tempFile) }
                    } catch (e: Exception) {
                        Log.e(tag, "Error during file download", e)
                        runOnUiThread{ progressListener.onFailure(getString(R.string.download_failed) + ": " + e.message) }
                    } finally {
                        try { inputStream?.close() } catch (e: IOException) { Log.e(tag, "Error closing input stream", e)}
                        try { outputStream.close()} catch (e: IOException) { Log.e(tag, "Error closing output stream", e)}
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                     Log.e(tag, "Download network failure", e)
                    runOnUiThread{ progressListener.onFailure(getString(R.string.download_failed) + ": " + e.message) }
                }
            }
             if (!synchronous) this.enqueue(callback)
            else {
                // Synchronous execution (not recommended for network operations on main thread)
                try {
                    val response = this.execute()
                    // Ensure callback methods are designed to be called synchronously or dispatch to UI thread
                    callback.onResponse(this, response)
                } catch (e: IOException) {
                    callback.onFailure(this, e)
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()){
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.provider", // Ensure this matches AndroidManifest.xml
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
            } catch (e: Exception) {
                Log.e(tag, "Error starting APK install activity", e)
            }
        } else {
            // Permission not granted, store URI and prompt again, or guide user.
            // For simplicity, we just show a toast here. The installPermissionLauncher callback
            // will try to install if permission is granted later.
            launchInstallPrompt(uri) // Re-launching prompt, or use a specific stored URI
        }
    }

    private fun checkInstallPermission(): Boolean {
        return packageManager.canRequestPackageInstalls()
    }

    private fun launchInstallPrompt(uri: Uri?) { // Allow nullable URI if we don't always have one ready
         val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
        }
        // We ask for general permission. If URI is available, it means an install was pending.
        // The ActivityResult callback for installPermissionLauncher can check if a download was ready.
        installPermissionLauncher.launch(intent)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show() // Longer for important messages
        }
    }

    inner class NetworkChangeListener(
        private val context: Context,
        val onNetworkAvailable: () -> Unit,
        val onNetworkUnavailable: () -> Unit
    ) : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            runOnUiThread { onNetworkAvailable() }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            runOnUiThread { onNetworkUnavailable() }
        }

        fun register() {
            val connectivityManager =
                context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                connectivityManager.registerNetworkCallback(networkRequest, this)
            } catch (e: SecurityException) {
                Log.e(tag, "Failed to register network callback", e)
                // Handle cases where ACCESS_NETWORK_STATE permission might be missing (though unlikely for app core functionality)
            }
        }

        fun unregister() {
            val connectivityManager =
                context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(this)
            } catch (e: IllegalArgumentException) {
                // Already unregistered or invalid listener
                Log.w(tag, "Network callback already unregistered or invalid.")
            }
        }
    }
}

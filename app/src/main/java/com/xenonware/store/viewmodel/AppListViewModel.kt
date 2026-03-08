package com.xenonware.store.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.xenonware.store.AppEntryState
import com.xenonware.store.AppItem
import com.xenonware.store.SingleLiveEvent
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

class AppListViewModel : ViewModel() {
    val appItemUpdated: MutableLiveData<AppItem> = MutableLiveData()
    var storeAppItem = AppItem(
        "https://github.com/Dinico414/XenonStoreCompose",
        "com.xenonware.store"
    )
    val downloadedApkFile: MutableLiveData<File> = MutableLiveData()
    val downloadedApkQueue: ConcurrentLinkedQueue<File> = ConcurrentLinkedQueue()
    val requestInstallPermission = SingleLiveEvent<Unit>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(
            Cache(
                directory = File(downloadedApkFile.value?.parent, "http_cache"),
                maxSize = 10L * 1024L * 1024L // 10 MiB
            )
        )
        .eventListener(object : EventListener() {
            override fun cacheHit(call: Call, response: Response) {
                super.cacheHit(call, response)
                Log.d("AppListViewModel", "CACHE HIT ${response.request.url}")
            }
        })
        .build()

    fun downloadAppItem(appItem: AppItem, context: Context) {
        if (appItem.downloadUrl.isEmpty()) {
            storeAppItem = appItem.copy(state = AppEntryState.NOT_INSTALLED)
            appItemUpdated.postValue(storeAppItem)
            return
        }

        if (!checkInstallPermission(context)) {
            requestInstallPermission.postValue(Unit)
            return
        }

        storeAppItem = appItem.copy(state = AppEntryState.DOWNLOADING)
        appItemUpdated.postValue(storeAppItem)

        downloadToFile(
            storeAppItem.downloadUrl,
            "${storeAppItem.packageName}.apk", // Consistent naming
            object : DownloadListener<File> {
                override fun onProgress(downloaded: Long, size: Long) {
                    storeAppItem = storeAppItem.copy(
                        bytesDownloaded = downloaded,
                        fileSize = size
                    )
                    if (storeAppItem.state == AppEntryState.DOWNLOADING) {
                        appItemUpdated.postValue(storeAppItem)
                    }
                }

                override fun onCompleted(result: File) {
                    Log.d("AppListViewModel", "Completed download: $result")
                    downloadedApkQueue.clear()
                    downloadedApkQueue.add(result)
                    downloadedApkFile.postValue(result)

                    storeAppItem = storeAppItem.copy(state = AppEntryState.NOT_INSTALLED)
                    appItemUpdated.postValue(storeAppItem)
                }

                override fun onFailure(error: String) {
                    storeAppItem = storeAppItem.copy(state = AppEntryState.NOT_INSTALLED)
                    appItemUpdated.postValue(storeAppItem)
                    refreshAppItem(storeAppItem, useCache = false)
                }
            },
            context,
            useCache = false
        )
    }

    fun refreshAppItem(appItem: AppItem, useCache: Boolean = true) {
        if (appItem.state == AppEntryState.DOWNLOADING) {
            appItemUpdated.postValue(appItem)
            return
        }
        storeAppItem = appItem.copy(state = AppEntryState.NOT_INSTALLED)
        appItemUpdated.postValue(storeAppItem)

        if (storeAppItem.downloadUrl.isEmpty() || !useCache) {
            getNewReleaseVersionGithub(
                storeAppItem.owner, storeAppItem.repo, useCache,
                object : GithubReleaseAPICallback {
                    override fun onCompleted(version: String, downloadUrl: String) {
                        storeAppItem = storeAppItem.copy(
                            newVersion = version,
                            downloadUrl = downloadUrl,
                            state = AppEntryState.NOT_INSTALLED
                        )
                        appItemUpdated.postValue(storeAppItem)
                    }

                    override fun onFailure(error: String) {
                        storeAppItem = storeAppItem.copy(
                            downloadUrl = "",
                            newVersion = "",
                            state = AppEntryState.NOT_INSTALLED
                        )
                        appItemUpdated.postValue(storeAppItem)
                    }
                })
        }
    }

    private fun getNewReleaseVersionGithub(
        owner: String,
        repo: String,
        useCache: Boolean,
        callback: GithubReleaseAPICallback
    ) {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"

        downloadToString(url, object : DownloadListener<String> {
            override fun onProgress(downloaded: Long, size: Long) {}
            override fun onCompleted(result: String) {
                Log.d("response body $repo", result)
                try {
                    val latestRelease = JSONObject(result)

                    val assets = latestRelease.getJSONArray("assets")
                    val newVersion = latestRelease.getString("tag_name")
                    if (newVersion.isNotBlank() && assets.length() > 0) {
                        val asset = assets.getJSONObject(0)
                        callback.onCompleted(newVersion, asset.getString("browser_download_url"))
                    }
                } catch (e: Exception) {
                    Log.e("AppListViewModel", "Error parsing Github release JSON", e)
                }
            }
            override fun onFailure(error: String) {
                callback.onFailure(error)
            }
        }, useCache)
    }

    private fun downloadToString(
        url: String,
        progressListener: DownloadListener<String>,
        useCache: Boolean = true,
        synchronous: Boolean = false,
    ) {
        val request = Request.Builder().url(url).build()
        (if (useCache) client else OkHttpClient()).newCall(request).apply {
            val callback = object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        progressListener.onFailure("Response error code: ${response.code}")
                        return
                    }
                    val responseBody = response.body.string()
                    progressListener.onCompleted(responseBody)
                }
                override fun onFailure(call: Call, e: IOException) {
                    progressListener.onFailure("Download failed: ${e.message}")
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
        context: Context,
        useCache: Boolean = true,
        synchronous: Boolean = false,
    ) {
        val request = Request.Builder().url(url).build()
        val httpClient = if (useCache) client else OkHttpClient()

        httpClient.newCall(request).apply {
            val callback = object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        progressListener.onFailure("Response error code: ${response.code}")
                        return
                    }

                    val contentLength = response.body.contentLength()
                    var downloadedBytes = 0L
                    val buffer = ByteArray(8192)
                    val inputStream = response.body.byteStream()
                    val targetDir = File(context.getExternalFilesDir(null), "apk_downloads")
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val tempFile = File(targetDir, filename)
                    val outputStream = FileOutputStream(tempFile)

                    try {
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            progressListener.onProgress(downloadedBytes, contentLength)
                        }
                        progressListener.onCompleted(tempFile)
                    } catch (e: Exception) {
                        Log.e("AppListViewModel", "Error during file download", e)
                        progressListener.onFailure("Download failed: ${e.message}")
                    } finally {
                        try {
                            inputStream.close()
                        } catch (e: IOException) { Log.e("AppListViewModel", "Error closing input stream", e)}
                        try { outputStream.close()} catch (e: IOException) { Log.e("AppListViewModel", "Error closing output stream", e)}
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                     Log.e("AppListViewModel", "Download network failure", e)
                    progressListener.onFailure("Download failed: ${e.message}")
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

    private fun checkInstallPermission(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }
}

interface DownloadListener<T> {
    fun onProgress(downloaded: Long, size: Long)
    fun onCompleted(result: T)
    fun onFailure(error: String)
}

interface GithubReleaseAPICallback {
    fun onCompleted(version: String, downloadUrl: String)
    fun onFailure(error: String)
}

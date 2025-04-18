package com.xenon.store_installer

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class AppListViewModel : ViewModel() {
    val appItemUpdated: MutableLiveData<AppItem> = MutableLiveData()
    val storeAppItem = AppItem(
        HashMap<String, String>(),
        "@mipmap/ic_launcher",
        "https://github.com/Dinico414/XenonStore",
        "com.xenon.store"
    ).apply {
        nameMap["en"] = "Xenon Store"
        downloadUrl = "https://api.github.com/repos/dinico414/xenonstore/releases/latest" // Replace with the actual download URL
    }
    val downloadedApkFile: MutableLiveData<File> = MutableLiveData()
    val downloadedApkQueue: ConcurrentLinkedQueue<File> = ConcurrentLinkedQueue()

    private val downloadListener = object : AppListFragment.DownloadListener<File> {
        override fun onProgress(downloaded: Long, size: Long) {
            storeAppItem.bytesDownloaded = downloaded
            storeAppItem.fileSize = size
            // Directly update the UI state
            update(storeAppItem)
        }

        override fun onCompleted(result: File) {
            downloadedApkQueue.add(result)
            downloadedApkFile.postValue(result)
            storeAppItem.state = AppEntryState.INSTALLED // Set state to INSTALLED after successful download
            update(storeAppItem)
        }

        override fun onFailure(error: String) {
            storeAppItem.state = AppEntryState.NOT_INSTALLED
            update(storeAppItem)
            uiUpdateListener?.onError("Download failed: $error") // Or a more specific message
        }
    }
    var uiUpdateListener: UIUpdateListener? = null

    fun setUIUpdateListener(listener: UIUpdateListener) {
        uiUpdateListener = listener
    }
    interface UIUpdateListener {
        fun onAppItemUpdated(appItem: AppItem)
        fun onError(message: String)
    }

    init {
        storeAppItem.state = if (isAppInstalled(storeAppItem.packageName)) AppEntryState.INSTALLED else AppEntryState.NOT_INSTALLED
    }


    fun update(item: AppItem) {
        appItemUpdated.postValue(item)
        uiUpdateListener?.onAppItemUpdated(item)

    }


    fun downloadAppItem(appItem: AppItem) {
        val fragment = AppListFragment()
        fragment.downloadToFile(appItem.downloadUrl, "${appItem.packageName}.apk", downloadListener)
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            true // Replace with actual check if possible
        } catch (e: Exception) {
            false
        }
    }

}
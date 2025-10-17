package com.xenonware.store.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.xenonware.store.AppItem
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class AppListViewModel : ViewModel() {
    val appItemUpdated: MutableLiveData<AppItem> = MutableLiveData()
    val storeAppItem = AppItem(
        "https://github.com/Dinico414/XenonStoreCompose",
        "com.xenonware.store"
    )
    val downloadedApkFile: MutableLiveData<File> = MutableLiveData()
    val downloadedApkQueue: ConcurrentLinkedQueue<File> = ConcurrentLinkedQueue()
}
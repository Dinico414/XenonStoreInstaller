package com.xenon.store_installer.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.xenon.store_installer.AppEntryState
import com.xenon.store_installer.AppItem
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class AppListViewModel : ViewModel() {
    val appItemUpdated: MutableLiveData<AppItem> = MutableLiveData()
    val storeAppItem = AppItem(
        "https://github.com/Dinico414/XenonStore",
        "com.xenon.store"
    )
    val downloadedApkFile: MutableLiveData<File> = MutableLiveData()
    val downloadedApkQueue: ConcurrentLinkedQueue<File> = ConcurrentLinkedQueue()
}
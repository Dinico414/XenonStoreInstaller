package com.xenon.store_installer

enum class AppEntryState {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
}

data class AppItem(
    val nameMap: HashMap<String, String>,
    val iconPath: String,
    val githubUrl: String,
    val packageName: String,
) {
    var state: AppEntryState = AppEntryState.NOT_INSTALLED

    var bytesDownloaded: Long = 0
    var fileSize: Long = 0
    var downloadUrl: String = ""

}
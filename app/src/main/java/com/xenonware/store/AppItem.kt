package com.xenonware.store

enum class AppEntryState {
    NOT_INSTALLED,
    DOWNLOADING,
}

data class AppItem(
    val githubUrl: String,
    val packageName: String,
    val state: AppEntryState = AppEntryState.NOT_INSTALLED,
    val installedVersion: String = "",
    val newVersion: String = "",
    val newIsPreRelease: Boolean = false,
    val bytesDownloaded: Long = 0,
    val fileSize: Long = 0,
    val downloadUrl: String = ""
) {
    private val ownerRepoRegex = "^https://[^/]*github\\.com/([^/]+)/([^/]+)".toRegex()
    val owner: String = ownerRepoRegex.find(githubUrl)?.groups?.get(1)?.value ?: ""
    val repo: String = ownerRepoRegex.find(githubUrl)?.groups?.get(2)?.value ?: ""

}

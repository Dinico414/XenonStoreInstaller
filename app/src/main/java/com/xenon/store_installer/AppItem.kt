package com.xenon.store_installer

enum class AppEntryState {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    INSTALLED_AND_OUTDATED,
}

data class AppItem(
    val githubUrl: String,
    val packageName: String,
) {

    var state: AppEntryState = AppEntryState.NOT_INSTALLED
    var installedVersion: String = ""
    var newVersion: String = ""
    var newIsPreRelease = false

    fun isOutdated(): Boolean {
        return installedVersion != "" && isNewerVersion(newVersion)
    }

    // Download progressbar variables
    var bytesDownloaded: Long = 0
    var fileSize: Long = 0
    var downloadUrl: String = ""

    private val ownerRepoRegex = "^https://[^/]*github\\.com/([^/]+)/([^/]+)".toRegex()
    // Github url is also checked for validity
    val owner = ownerRepoRegex.find(githubUrl)!!.groups[1]!!.value
    val repo = ownerRepoRegex.find(githubUrl)!!.groups[2]!!.value

    fun isNewerVersion(latestVersion: String): Boolean {
        return Util.isNewerVersion(installedVersion, latestVersion)
    }
}
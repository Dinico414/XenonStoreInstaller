package com.xenon.store_installer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.xenon.commons.accesspoint.R.color
import com.xenon.commons.accesspoint.R.drawable
import com.xenon.store_installer.AppEntryState.DOWNLOADING
import com.xenon.store_installer.AppEntryState.INSTALLED
import com.xenon.store_installer.AppEntryState.NOT_INSTALLED
import com.xenon.store_installer.databinding.ActivityMainBinding

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appListModel: AppListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        appListModel = ViewModelProvider(this)[AppListViewModel::class.java]
        appListModel.appItemUpdated.observe(this) { appItem ->
            updateButtonState(appItem)
        }
        appListModel.setUIUpdateListener(object : AppListViewModel.UIUpdateListener {
            override fun onAppItemUpdated(appItem: AppItem) {
                updateButtonState(appItem)
            }

            override fun onError(message: String) {
                showSnackbar(message)
            }
        })

        packageHandling()
    }

    private fun packageHandling() {
        val appItem = appListModel.storeAppItem
        updateButtonState(appItem)

        binding.downloadButton.setOnClickListener {
            when (appItem.state) {
                NOT_INSTALLED -> startDownload(appItem)
                INSTALLED -> uninstallApp(appItem)
                DOWNLOADING -> {
                    // Consider adding a cancel download option here if needed
                }
            }
        }
    }

    private fun updateButtonState(appItem: AppItem) {
        when (appItem.state) {
            DOWNLOADING -> {
                binding.downloadButton.text = ""
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.progress = appItem.bytesDownloaded.toInt()
                binding.progressBar.max = appItem.fileSize.toInt()
            }

            INSTALLED -> {
                binding.downloadButton.text = getString(R.string.uninstall)
                binding.progressBar.visibility = View.GONE
            }

            NOT_INSTALLED -> {
                binding.downloadButton.text = getString(R.string.install)
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun startDownload(appItem: AppItem) {
        binding.downloadButton.text = ""
        if (appItem.downloadUrl.isEmpty()) {
            showSnackbar(getString(R.string.failed_to_find))
            return
        }
        appItem.state = DOWNLOADING
        appListModel.update(appItem)
        appListModel.downloadAppItem(appItem)
    }
    private fun uninstallApp(appItem: AppItem) {
        val packageName = appItem.packageName
        if (packageName.isNotEmpty()) {
            uninstallPackage(packageName)
        } else {
            showSnackbar("Package name not available")
        }
    }

    private fun uninstallPackage(packageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:com.xenon.store_installer")
        }
        startActivity(intent)
    }

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
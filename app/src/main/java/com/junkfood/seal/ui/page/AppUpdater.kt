package com.junkfood.seal.ui.page

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.junkfood.seal.R
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.UpdateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppUpdater() {

    val context = LocalContext.current

    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var currentDownloadStatus by remember {
        mutableStateOf(UpdateUtil.DownloadStatus.NotYet as UpdateUtil.DownloadStatus)
    }
    val scope = rememberCoroutineScope()
    var updateJob: Job? = null
    var release by remember { mutableStateOf(UpdateUtil.Release()) }
    var showChangelogDialog by rememberSaveable { mutableStateOf(false) }
    var changelogText by remember { mutableStateOf("") }
    var changelogVersion by remember { mutableStateOf("") }
    var changelogHtmlUrl by remember { mutableStateOf<String?>(null) }

    val settings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            UpdateUtil.installLatestApk()
        }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
            if (result) {
                UpdateUtil.installLatestApk()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!context.packageManager.canRequestPackageInstalls())
                        settings.launch(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    else UpdateUtil.installLatestApk()
                }
            }
        }

    LaunchedEffect(Unit) {
        // 1. Post-update check
        val currentVersion = UpdateUtil.run { context.getCurrentVersion() }
        val currentVersionName = currentVersion.toVersionName()
        val lastRunVersionName = PreferenceUtil.getLastRunVersion()

        if (lastRunVersionName.isEmpty()) {
            PreferenceUtil.setLastRunVersion(currentVersionName)
        } else if (lastRunVersionName != currentVersionName) {
            val lastRunVersion = UpdateUtil.run { lastRunVersionName.toVersion() }
            if (currentVersion > lastRunVersion) {
                val tagName = "v$currentVersionName"
                withContext(Dispatchers.IO) {
                    runCatching {
                        val currentRelease = UpdateUtil.getReleaseByTagName(tagName)
                        if (currentRelease != null) {
                            val rawBody = currentRelease.body.toString()
                            val systemLanguageCode = java.util.Locale.getDefault().language
                            val extractedChangelog = UpdateUtil.extractChangelogForLanguage(rawBody, systemLanguageCode)
                            withContext(Dispatchers.Main) {
                                changelogText = extractedChangelog
                                changelogVersion = currentVersionName
                                changelogHtmlUrl = currentRelease.htmlUrl
                                showChangelogDialog = true
                            }
                        }
                    }.onFailure { it.printStackTrace() }
                }
            }
            PreferenceUtil.setLastRunVersion(currentVersionName)
        }

        // 2. Startup update check
        withContext(Dispatchers.IO) {
            runCatching {
                UpdateUtil.checkForUpdate()?.let {
                    val skippedVersion = PreferenceUtil.getSkippedVersion()
                    if (it.name.toString() != skippedVersion) {
                        release = it
                        showUpdateDialog = true
                    }
                }
            }.onFailure { it.printStackTrace() }
        }
    }

    if (showUpdateDialog) {
        UpdateDialogImpl(
            onDismissRequest = {
                showUpdateDialog = false
                updateJob?.cancel()
            },
            onOmit = {
                PreferenceUtil.setSkippedVersion(release.name.toString())
                showUpdateDialog = false
                updateJob?.cancel()
            },
            title = release.name.toString(),
            onConfirmUpdate = {
                updateJob =
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                                UpdateUtil.downloadApk(release = release).collect { downloadStatus
                                    ->
                                    currentDownloadStatus = downloadStatus
                                    if (downloadStatus is UpdateUtil.DownloadStatus.Finished) {
                                        launcher.launch(
                                            Manifest.permission.REQUEST_INSTALL_PACKAGES
                                        )
                                    }
                                }
                            }
                            .onFailure {
                                it.printStackTrace()
                                currentDownloadStatus = UpdateUtil.DownloadStatus.NotYet
                                ToastUtil.makeToastSuspend(
                                    context.getString(R.string.app_update_failed)
                                )
                                return@launch
                            }
                    }
            },
            releaseNote =
                UpdateUtil.extractChangelogForLanguage(
                    body = release.body.toString(),
                    languageCode = java.util.Locale.getDefault().language,
                ),
            downloadStatus = currentDownloadStatus,
        )
    }

    if (showChangelogDialog) {
        ChangelogDialog(
            onDismissRequest = { showChangelogDialog = false },
            versionName = changelogVersion,
            changelogText = changelogText,
            htmlUrl = changelogHtmlUrl,
        )
    }
}

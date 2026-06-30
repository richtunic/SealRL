package com.junkfood.seal.ui.page

import android.app.PendingIntent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.database.objects.QueueItemEntity
import com.junkfood.seal.database.objects.QueueStatus
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.junkfood.seal.ui.common.AsyncImageImpl
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.Settings
import android.os.Environment
import com.junkfood.seal.ui.common.Route
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.app.DownloadManager
import androidx.core.content.FileProvider
import android.provider.DocumentsContract


import com.junkfood.seal.ui.page.bulk.BulkDownloadViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.util.DownloadUtil
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import com.junkfood.seal.ui.page.bulk.QueueItemRow
import com.junkfood.seal.ui.page.settings.network.CookiesViewModel
import com.junkfood.seal.database.objects.CookieProfile
import androidx.compose.foundation.BorderStroke
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private fun hasStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleMainScreen(
    onNavigateToRoute: (String) -> Unit,
    cookiesViewModel: CookiesViewModel = koinViewModel(),
    dialogViewModel: DownloadDialogViewModel = koinViewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var hasPermission by remember { mutableStateOf(hasStoragePermission(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, context.getString(R.string.permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.permission_denied_toast), Toast.LENGTH_SHORT).show()
        }
    }

    val launchAllFilesSettings = {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                } catch (ex: Exception) {
                    Toast.makeText(context, context.getString(R.string.could_not_open_settings), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasStoragePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val bulkViewModel: BulkDownloadViewModel = koinViewModel()
    val inputText by bulkViewModel.inputText.collectAsStateWithLifecycle()
    var inputFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(inputText) {
        if (inputText != inputFieldValue.text) {
            inputFieldValue = TextFieldValue(
                text = inputText,
                selection = TextRange(inputText.length),
            )
        }
    }
    val queueItems by bulkViewModel.queueItems.collectAsStateWithLifecycle()
    val metrics by bulkViewModel.metrics.collectAsStateWithLifecycle()

    val historyItems by DatabaseUtil.getDownloadHistoryFlow().collectAsState(initial = emptyList())

    val pagerState = rememberPagerState(pageCount = { 2 })
    var showQueueMenu by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<DownloadedVideoInfo?>(null) }

    var preferences by remember {
        mutableStateOf(DownloadUtil.DownloadPreferences.createFromPreferences())
    }
    val sheetValue by dialogViewModel.sheetValueFlow.collectAsStateWithLifecycle()
    val state by dialogViewModel.sheetStateFlow.collectAsStateWithLifecycle()
    val selectionState by dialogViewModel.selectionStateFlow.collectAsStateWithLifecycle()

    var showDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetValue) {
        android.util.Log.d("Antigravity", "sheetValue changed: $sheetValue")
        if (sheetValue == DownloadDialogViewModel.SheetValue.Expanded) {
            showDialog = true
            launch {
                try {
                    sheetState.show()
                    android.util.Log.d("Antigravity", "sheetState.show() succeeded")
                } catch (e: Exception) {
                    android.util.Log.e("Antigravity", "sheetState.show() failed", e)
                }
            }
        } else {
            launch {
                try {
                    sheetState.hide()
                    android.util.Log.d("Antigravity", "sheetState.hide() succeeded")
                } catch (e: Exception) {
                    android.util.Log.e("Antigravity", "sheetState.hide() failed", e)
                }
            }.invokeOnCompletion { showDialog = false }
        }
    }

    LaunchedEffect(state) {
        android.util.Log.d("Antigravity", "state changed: $state")
        if (state is DownloadDialogViewModel.SheetState.Configure) {
            val urlList = (state as DownloadDialogViewModel.SheetState.Configure).urlList
            android.util.Log.d("Antigravity", "Configure state URLs: $urlList")
            if (urlList.isNotEmpty()) {
                bulkViewModel.onInputTextChange(urlList.joinToString("\n"))
                bulkViewModel.parseAndAddToQueue()
                dialogViewModel.postAction(DownloadDialogViewModel.Action.HideSheet)
                dialogViewModel.postAction(DownloadDialogViewModel.Action.Reset)
                pagerState.animateScrollToPage(1)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = stringResource(R.string.app_name), 
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ) 
                },
                actions = {
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { showQueueMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Queue Actions", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showQueueMenu,
                            onDismissRequest = { showQueueMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.retry_failed)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    bulkViewModel.retryFailed()
                                    showQueueMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cancel_queue)) },
                                leadingIcon = { Icon(Icons.Default.Cancel, null) },
                                onClick = {
                                    bulkViewModel.cancelQueue()
                                    showQueueMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_completed)) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                onClick = {
                                    bulkViewModel.deleteCompleted()
                                    showQueueMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bulk_clear)) },
                                leadingIcon = { Icon(Icons.Default.DeleteForever, null) },
                                onClick = {
                                    bulkViewModel.clearQueue()
                                    showQueueMenu = false
                                }
                            )
                        }
                    }

                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.menu_general_settings), tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_general_settings)) },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = {
                                onNavigateToRoute(Route.SETTINGS_PAGE)
                                showSettingsMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_login_webview)) },
                            leadingIcon = { Icon(Icons.Default.Language, null) },
                            onClick = {
                                showLoginDialog = true
                                showSettingsMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_cookie_profiles)) },
                            leadingIcon = { Icon(Icons.Default.Cookie, null) },
                            onClick = {
                                onNavigateToRoute(Route.COOKIE_PROFILE)
                                showSettingsMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = {
                                onNavigateToRoute(Route.ABOUT)
                                showSettingsMenu = false
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF000000),
                    titleContentColor = Color(0xFFFFFFFF),
                    actionIconContentColor = Color(0xFFFFFFFF)
                )
            )
        },
        containerColor = Color(0xFF000000)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color(0xFF000000),
                contentColor = Color(0xFFE11D48),
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = Color(0xFFE11D48)
                    )
                }
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { 
                        Text(
                            stringResource(R.string.tab_download), 
                            fontWeight = FontWeight.Bold,
                            color = if (pagerState.currentPage == 0) Color(0xFFE11D48) else Color(0xFFB0B0B0)
                        ) 
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { 
                        Text(
                            stringResource(R.string.tab_downloads), 
                            fontWeight = FontWeight.Bold,
                            color = if (pagerState.currentPage == 1) Color(0xFFE11D48) else Color(0xFFB0B0B0)
                        ) 
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
            when (page) {
                0 -> {
                    // TAB 0: DESCARGAR
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        AnimatedVisibility(visible = !hasPermission) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                                    .border(1.dp, Color(0xFFE11D48), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = stringResource(R.string.storage_permission_required),
                                            tint = Color(0xFFE11D48),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.storage_permission_required),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.storage_permission_desc),
                                        color = Color(0xFFB0B0B0),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= 30) {
                                                launchAllFilesSettings()
                                            } else {
                                                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFE11D48),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text(stringResource(R.string.grant_permission), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.paste_links_hint),
                            color = Color(0xFFB0B0B0),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = inputFieldValue,
                            onValueChange = {
                                inputFieldValue = it
                                bulkViewModel.onInputTextChange(it.text)
                            },
                            placeholder = {
                                Text(
                                    text = "https://instagram.com/reel/...\nhttps://x.com/status/...\nhttps://tiktok.com/...\nhttps://facebook.com/...",
                                    color = Color(0xFF666666)
                                )
                            },

                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 8,

                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFFFFFFF),
                                unfocusedTextColor = Color(0xFFFFFFFF),
                                focusedContainerColor = Color(0xFF0D0D0D),
                                unfocusedContainerColor = Color(0xFF0D0D0D),
                                focusedBorderColor = Color(0xFFE11D48),
                                unfocusedBorderColor = Color(0xFF1E1E1E)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val audioOnly by bulkViewModel.audioOnly.collectAsStateWithLifecycle()
                            Switch(
                                checked = audioOnly,
                                onCheckedChange = { bulkViewModel.toggleAudioOnly(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFE11D48),
                                    uncheckedThumbColor = Color(0xFF666666),
                                    uncheckedTrackColor = Color(0xFF0D0D0D),
                                    uncheckedBorderColor = Color(0xFF1E1E1E)
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.audio_only_label),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }


                        Spacer(modifier = Modifier.height(16.dp))


                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text
                                    if (!clipText.isNullOrBlank()) {
                                        val currentText = inputFieldValue.text
                                        bulkViewModel.onInputTextChange(
                                            currentText + (if (currentText.isNotEmpty()) "\n" else "") + clipText
                                        )
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.paste_fail_msg), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0D0D0D),
                                    contentColor = Color(0xFFFFFFFF)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.paste_button), style = MaterialTheme.typography.bodyMedium)
                            }

                            Button(
                                onClick = {
                                    if (hasPermission) {
                                        if (inputText.isNotBlank()) {
                                            bulkViewModel.parseAndAddToQueue()
                                            scope.launch { pagerState.animateScrollToPage(1) }
                                        }
                                    } else {
                                        showPermissionDialog = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE11D48),
                                    contentColor = Color(0xFFFFFFFF)
                                ),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.download_button), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                1 -> {
                    // TAB 1: DESCARGAS
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        // Queue Metrics Banner
                        if (queueItems.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    SimpleMetricItem(label = stringResource(R.string.metric_total), count = metrics.total)
                                    SimpleMetricItem(label = stringResource(R.string.metric_pending), count = metrics.pending, color = Color(0xFFB0B0B0))
                                    SimpleMetricItem(label = stringResource(R.string.metric_downloading), count = metrics.downloading, color = Color(0xFFF59E0B))
                                    SimpleMetricItem(label = stringResource(R.string.metric_completed), count = metrics.completed, color = Color(0xFF22C55E))
                                    SimpleMetricItem(label = stringResource(R.string.metric_failed), count = metrics.failed, color = Color(0xFFEF4444))
                                }
                            }
                        }

                        // Open Downloads Folder Button
                        item {
                            Button(
                                onClick = {
                                    val downloadDir = FileUtil.getExternalDownloadDirectory()
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        downloadDir
                                    )
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "resource/folder")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                                                val documentUri = DocumentsContract.buildDocumentUri(
                                                    "com.android.externalstorage.documents",
                                                    "primary:Download/Seal"
                                                )
                                                setDataAndType(documentUri, "vnd.android.document/directory")
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            }
                                            context.startActivity(fallbackIntent)
                                        } catch (ex: Exception) {
                                            try {
                                                val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(downloadsIntent)
                                            } catch (exc: Exception) {
                                                Toast.makeText(context, context.getString(R.string.could_not_open_file_explorer), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0D0D0D),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = stringResource(R.string.open_download_folder),
                                    tint = Color(0xFFE11D48),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.open_download_folder), fontWeight = FontWeight.Bold)
                            }
                        }

                        // Active / Queue Section
                        val activeItems = queueItems.filter { it.status != QueueStatus.COMPLETED }
                        if (activeItems.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.in_progress_header),
                                    color = Color(0xFFE11D48),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(activeItems, key = { "queue_${it.id}" }) { item ->
                                QueueItemRow(
                                    item = item,
                                    onDelete = { bulkViewModel.deleteItem(item.id) },
                                    onRetry = { bulkViewModel.retryItem(item.id) }
                                )
                            }
                        }

                        // Completed / History Section
                        if (historyItems.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.download_history_header),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                                )
                            }
                            items(historyItems, key = { "history_${it.id}" }) { item ->
                                HistoryItemRow(
                                    item = item,
                                    onOpen = {
                                        val intent = FileUtil.createIntentForOpeningFile(item.videoPath)
                                        if (intent != null) {
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, context.getString(R.string.no_compatible_player), Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, context.getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onOpenFolder = {
                                        try {
                                            val file = java.io.File(item.videoPath)
                                            val parentDir = file.parentFile ?: java.io.File(FileUtil.getExternalDownloadDirectory().absolutePath)
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.provider",
                                                parentDir
                                            )
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "resource/folder")
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    val documentUri = DocumentsContract.buildDocumentUri(
                                                        "com.android.externalstorage.documents",
                                                        "primary:Download/Seal"
                                                    )
                                                    setDataAndType(documentUri, "vnd.android.document/directory")
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                }
                                                context.startActivity(fallbackIntent)
                                            } catch (ex: Exception) {
                                                try {
                                                    val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(downloadsIntent)
                                                } catch (exc: Exception) {
                                                    Toast.makeText(context, context.getString(R.string.could_not_open_folder), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    onShare = {
                                        val intent = FileUtil.createIntentForSharingFile(item.videoPath)
                                        if (intent != null) {
                                            try {
                                                val chooser = Intent.createChooser(intent, context.getString(R.string.share_chooser_title))
                                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(chooser)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, context.getString(R.string.could_not_share_file), Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, context.getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onDelete = {
                                        itemToDelete = item
                                    }
                                )
                            }
                        } else if (activeItems.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.no_downloads_empty),
                                        color = Color(0xFF666666),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        // Deletion Dialog
        if (itemToDelete != null) {
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text(stringResource(R.string.delete_download_title), color = Color.White) },
                text = { Text(stringResource(R.string.delete_download_msg), color = Color(0xFFB0B0B0)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = itemToDelete
                            if (target != null) {
                                scope.launch(Dispatchers.IO) {
                                    DatabaseUtil.deleteInfoList(listOf(target), deleteFile = true)
                                }
                            }
                            itemToDelete = null
                        }
                    ) {
                        Text(stringResource(R.string.delete_file_and_record), color = Color(0xFFEF4444))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            val target = itemToDelete
                            if (target != null) {
                                scope.launch(Dispatchers.IO) {
                                    DatabaseUtil.deleteInfoList(listOf(target), deleteFile = false)
                                }
                            }
                            itemToDelete = null
                        }
                    ) {
                        Text(stringResource(R.string.delete_record_only), color = Color(0xFFB0B0B0))
                    }
                },
                containerColor = Color(0xFF0D0D0D)
            )
        }
        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text(stringResource(R.string.storage_permission_title), color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        stringResource(R.string.storage_permission_msg),
                        color = Color(0xFFB0B0B0)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showPermissionDialog = false
                            if (Build.VERSION.SDK_INT >= 30) {
                                launchAllFilesSettings()
                            } else {
                                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.configure), color = Color(0xFFE11D48), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionDialog = false }) {
                        Text(stringResource(R.string.dismiss), color = Color(0xFFB0B0B0))
                    }
                },
                containerColor = Color(0xFF0D0D0D),
                modifier = Modifier.border(1.5.dp, Color(0xFFE11D48), RoundedCornerShape(28.dp))
            )
        }

        if (showLoginDialog) {
            val platforms = listOf(
                "Instagram" to "https://www.instagram.com",
                "Facebook" to "https://m.facebook.com/login/",
                "X / Twitter" to "https://x.com/i/flow/login",
                "Threads" to "https://www.threads.com"
            )
            var customUrl by remember { mutableStateOf("") }
            var showCustomInput by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showLoginDialog = false },
                title = {
                    Text(
                        stringResource(R.string.login_platform_dialog_title),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!showCustomInput) {
                            Text(
                                stringResource(R.string.select_platform_login),
                                color = Color(0xFFB0B0B0),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            platforms.forEach { (name, url) ->
                                OutlinedButton(
                                    onClick = {
                                        showLoginDialog = false
                                        cookiesViewModel.setEditingProfile(
                                            CookieProfile(
                                                id = CookiesViewModel.NEW_PROFILE_ID,
                                                url = url,
                                                content = ""
                                            )
                                        )
                                        onNavigateToRoute(Route.COOKIE_GENERATOR_WEBVIEW)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.White
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFF1E1E1E))
                                ) {
                                    Text(name, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    showCustomInput = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFE11D48)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFE11D48))
                            ) {
                                Text(stringResource(R.string.other_url), fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Text(
                                stringResource(R.string.other_url),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = { customUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("https://example.com") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFE11D48),
                                    unfocusedBorderColor = Color(0xFF333333),
                                    focusedContainerColor = Color(0xFF000000),
                                    unfocusedContainerColor = Color(0xFF000000)
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    if (showCustomInput) {
                        TextButton(
                            onClick = {
                                val finalUrl = if (!customUrl.startsWith("http://") && !customUrl.startsWith("https://")) {
                                    "https://$customUrl"
                                } else {
                                    customUrl
                                }
                                showLoginDialog = false
                                cookiesViewModel.setEditingProfile(
                                    CookieProfile(
                                        id = CookiesViewModel.NEW_PROFILE_ID,
                                        url = finalUrl,
                                        content = ""
                                    )
                                )
                                onNavigateToRoute(Route.COOKIE_GENERATOR_WEBVIEW)
                            },
                            enabled = customUrl.isNotBlank()
                        ) {
                            Text(stringResource(R.string.configure), color = Color(0xFFE11D48), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (showCustomInput) {
                                showCustomInput = false
                            } else {
                                showLoginDialog = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.dismiss), color = Color(0xFFB0B0B0))
                    }
                },
                containerColor = Color(0xFF0D0D0D),
                modifier = Modifier.border(1.5.dp, Color(0xFFE11D48), RoundedCornerShape(28.dp))
            )
        }

        val mediaSelectionList by bulkViewModel.mediaSelectionList.collectAsStateWithLifecycle()
        val isLoadingMedia by bulkViewModel.isLoadingMedia.collectAsStateWithLifecycle()

        if (isLoadingMedia) {
            Dialog(onDismissRequest = {}) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFE11D48), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFE11D48))
                }
            }
        }

        mediaSelectionList?.let { items ->
            var selectedItems by remember(items) { mutableStateOf(items.toSet()) }

            AlertDialog(
                onDismissRequest = { bulkViewModel.cancelMediaSelection() },
                title = {
                    val author = items.firstOrNull()?.author
                    Column {
                        Text(
                            text = stringResource(R.string.select_downloads),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        if (!author.isNullOrEmpty()) {
                            Text(
                                text = author,
                                color = Color(0xFF999999),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedItems.size == items.size,
                                onCheckedChange = { checked ->
                                    selectedItems = if (checked) items.toSet() else emptySet()
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFE11D48),
                                    uncheckedColor = Color(0xFF444444),
                                    checkmarkColor = Color.White
                                )
                            )
                            Text(
                                text = "Seleccionar todo",
                                color = Color.White,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(items) { item ->
                                val isSelected = selectedItems.contains(item)
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E1E1E))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) Color(0xFFE11D48) else Color(0xFF333333),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            selectedItems = if (isSelected) {
                                                selectedItems - item
                                            } else {
                                                selectedItems + item
                                            }
                                        }
                                ) {
                                    val modelUrl = item.thumbnailUrl ?: item.mediaUrl
                                    if (modelUrl.isNotEmpty()) {
                                        AsyncImageImpl(
                                            model = modelUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }

                                    if (item.isVideo) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .align(Alignment.Center)
                                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                                .padding(4.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                if (isSelected) Color.Black.copy(alpha = 0.3f)
                                                else Color.Transparent
                                            )
                                    )

                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFFE11D48),
                                            modifier = Modifier
                                                .size(20.dp)
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (selectedItems.isNotEmpty()) {
                                bulkViewModel.enqueueSelectedItems(selectedItems.toList())
                                scope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE11D48),
                            contentColor = Color.White
                        ),
                        enabled = selectedItems.isNotEmpty()
                    ) {
                        Text(
                            text = "Descargar (${selectedItems.size})",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { bulkViewModel.cancelMediaSelection() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFFB0B0B0)
                        )
                    ) {
                        Text("Cancelar")
                    }
                },
                containerColor = Color(0xFF0D0D0D),
                textContentColor = Color.White,
                titleContentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, Color(0xFFE11D48), RoundedCornerShape(16.dp))
            )
        }

        if (showDialog) {
            android.util.Log.d("Antigravity", "Composing DownloadDialog, state: $state, showDialog: $showDialog")
            DownloadDialog(
                state = state,
                sheetState = sheetState,
                config = Config(),
                preferences = preferences,
                onPreferencesUpdate = { preferences = it },
                onActionPost = { dialogViewModel.postAction(it) },
            )
        }

        when (val selState = selectionState) {
            is DownloadDialogViewModel.SelectionState.FormatSelection ->
                FormatPage(
                    state = selState,
                    onDismissRequest = { dialogViewModel.postAction(DownloadDialogViewModel.Action.Reset) },
                )

            is DownloadDialogViewModel.SelectionState.PlaylistSelection -> {
                PlaylistSelectionPage(
                    state = selState,
                    onDismissRequest = { dialogViewModel.postAction(DownloadDialogViewModel.Action.Reset) },
                )
            }

            DownloadDialogViewModel.SelectionState.Idle -> {}
        }
    }
}

@Composable
fun SimpleMetricItem(label: String, count: Int, color: Color = Color(0xFFFFFFFF)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count.toString(), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(text = label, color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun HistoryItemRow(
    item: DownloadedVideoInfo,
    onOpen: () -> Unit,
    onOpenFolder: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val isImage = item.videoPath.run {
        endsWith(".jpg", ignoreCase = true) ||
        endsWith(".jpeg", ignoreCase = true) ||
        endsWith(".png", ignoreCase = true) ||
        endsWith(".webp", ignoreCase = true)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail image container
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF121212))
                    .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                val imageModel = if (isImage) item.videoPath else item.thumbnailUrl
                if (imageModel.isNotEmpty()) {
                    AsyncImageImpl(
                        model = imageModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (isImage || item.extractor.lowercase().contains("instagram") || item.videoUrl.contains("instagram")) {
                            Icons.Default.Image
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = null,
                        tint = Color(0xFF666666),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Platform & Media Type Badges
                val platformName = com.junkfood.seal.util.BulkUrlParser.getPlatformName(item.videoUrl)
                val badgeText = if (platformName == "Instagram" && item.videoTitle.contains("Story", ignoreCase = true)) "Story" else platformName
                val badgeColor = Color(com.junkfood.seal.util.BulkUrlParser.getPlatformColor(badgeText))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(badgeColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = if (badgeText == "Threads") Color.Black else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (isImage) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE11D48), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "IMG",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Title
                Text(
                    text = item.videoTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                // Path/Subtitle
                val filename = item.videoPath.substringAfterLast('/')
                Text(
                    text = filename.ifEmpty { stringResource(R.string.local_file) },
                    color = Color(0xFF666666),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Actions
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isImage) Icons.Default.Image else Icons.Default.PlayArrow,
                    contentDescription = if (isImage) "View" else "Play",
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onOpenFolder, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Open folder",
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

package com.junkfood.seal.ui.page.bulk

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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.QueueItemEntity
import com.junkfood.seal.database.objects.QueueStatus
import com.junkfood.seal.ui.component.BackButton
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkDownloadScreen(
    onMenuOpen: () -> Unit,
    viewModel: BulkDownloadViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val queueItems by viewModel.queueItems.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.bulk_download)) },
                navigationIcon = {
                    IconButton(onClick = onMenuOpen) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.retry_failed)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = {
                                viewModel.retryFailed()
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cancel_queue)) },
                            leadingIcon = { Icon(Icons.Default.Cancel, null) },
                            onClick = {
                                viewModel.cancelQueue()
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_completed)) },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            onClick = {
                                viewModel.deleteCompleted()
                                showMenu = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bulk_clear)) },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, null) },
                            onClick = {
                                viewModel.clearQueue()
                                showMenu = false
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color(0xFF000000),
                    titleContentColor = Color(0xFFFFFFFF),
                    navigationIconContentColor = Color(0xFFFFFFFF),
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
                .background(Color(0xFF000000))
        ) {
            // Paste Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { viewModel.onInputTextChange(it) },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.paste_links_placeholder),
                            color = Color(0xFF666666)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFFFFFFF),
                        unfocusedTextColor = Color(0xFFFFFFFF),
                        focusedContainerColor = Color(0xFF000000),
                        unfocusedContainerColor = Color(0xFF000000),
                        focusedBorderColor = Color(0xFFE11D48),
                        unfocusedBorderColor = Color(0xFF1E1E1E)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                viewModel.onInputTextChange(inputText + (if (inputText.isNotEmpty()) "\n" else "") + clipText)
                            } else {
                                Toast.makeText(context, context.getString(R.string.paste_fail_msg), Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E1E1E),
                            contentColor = Color(0xFFFFFFFF)
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.paste_from_clipboard), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Button(
                        onClick = { viewModel.parseAndAddToQueue() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE11D48),
                            contentColor = Color(0xFFFFFFFF)
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.add_to_queue), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Metrics Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem(label = "Total", count = metrics.total)
                MetricItem(label = "Pendientes", count = metrics.pending, color = Color(0xFFB0B0B0))
                MetricItem(label = "Descargando", count = metrics.downloading, color = Color(0xFFF59E0B))
                MetricItem(label = "Completados", count = metrics.completed, color = Color(0xFF22C55E))
                MetricItem(label = "Fallidos", count = metrics.failed, color = Color(0xFFEF4444))
            }

            // Queue List Section
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(queueItems, key = { it.id }) { item ->
                    QueueItemRow(
                        item = item,
                        onDelete = { viewModel.deleteItem(item.id) },
                        onRetry = { viewModel.retryItem(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, count: Int, color: Color = Color(0xFFFFFFFF)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count.toString(), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(text = label, color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun QueueItemRow(
    item: QueueItemEntity,
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = sdf.format(Date(item.createdAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Platform badge
                val badgeColor = Color(com.junkfood.seal.util.BulkUrlParser.getPlatformColor(item.platform))
                Box(
                    modifier = Modifier
                        .background(badgeColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.platform,
                        color = if (item.platform == "Threads") Color.Black else Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Time
                Text(
                    text = timeStr,
                    color = Color(0xFF666666),
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.weight(1f))

                // Status Tag
                val statusText: String
                val statusColor: Color
                when (item.status) {
                    QueueStatus.PENDING -> {
                        statusText = "Pendiente"
                        statusColor = Color(0xFFB0B0B0)
                    }
                    QueueStatus.DOWNLOADING -> {
                        statusText = "Descargando"
                        statusColor = Color(0xFFF59E0B)
                    }
                    QueueStatus.COMPLETED -> {
                        statusText = "Completado"
                        statusColor = Color(0xFF22C55E)
                    }
                    QueueStatus.FAILED -> {
                        statusText = "Fallido"
                        statusColor = Color(0xFFEF4444)
                    }
                    QueueStatus.CANCELED -> {
                        statusText = "Cancelado"
                        statusColor = Color(0xFF666666)
                    }
                    QueueStatus.PAUSED -> {
                        statusText = "Pausado"
                        statusColor = Color(0xFFF59E0B)
                    }
                }

                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Row action buttons
                if (item.status == QueueStatus.FAILED || item.status == QueueStatus.CANCELED) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color(0xFFE11D48), modifier = Modifier.size(18.dp))
                    }
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF666666), modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // URL/Title text
            val displayText = item.title ?: item.url
            Text(
                text = displayText,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Progress indicator (always visible, forced LTR)
            Spacer(modifier = Modifier.height(8.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp),
                        color = Color(0xFFE11D48),
                        trackColor = Color(0xFF1E1E1E)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(item.progress * 100).toInt()}%",
                        color = Color(0xFFFFFFFF),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Error message if failed
            AnimatedVisibility(visible = item.status == QueueStatus.FAILED && !item.errorMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.errorMessage ?: "",
                    color = Color(0xFFEF4444),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

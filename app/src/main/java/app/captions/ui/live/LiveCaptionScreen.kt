package app.captions.ui.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.captions.R
import app.captions.pipeline.CaptureStatus
import app.captions.pipeline.LiveCaptionState
import app.captions.service.CaptionForegroundService
import app.captions.transcription.CaptionLine
import app.captions.ui.caption.SpeakerColors

@Composable
fun LiveCaptionScreen(
    onBack: () -> Unit,
    viewModel: LiveCaptionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) CaptionForegroundService.start(context)
    }

    LiveCaptionContent(
        state = uiState,
        onBack = onBack,
        onStart = {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                CaptionForegroundService.start(context)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onStop = { CaptionForegroundService.stop(context) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCaptionContent(
    state: LiveCaptionState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val listState = rememberLazyListState()
    val dark = isSystemInDarkTheme()
    val displayLines = buildList {
        addAll(state.lines)
        state.partial?.let { add(it) }
    }

    LaunchedEffect(displayLines.size, state.partial?.text) {
        if (displayLines.isNotEmpty()) {
            listState.animateScrollToItem(displayLines.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            StatusRow(state = state)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.level },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                if (displayLines.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.live_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(displayLines, key = { it.id }) { line ->
                    CaptionBubble(line = line, darkTheme = dark)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val listening = state.status == CaptureStatus.Listening ||
                    state.status == CaptureStatus.Connecting
                if (listening) {
                    Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.stop))
                    }
                } else {
                    FilledTonalButton(onClick = onStart, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.start_listening))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(state: LiveCaptionState) {
    val label = when (state.status) {
        CaptureStatus.Idle -> stringResource(R.string.status_idle)
        CaptureStatus.Connecting -> stringResource(R.string.status_connecting)
        CaptureStatus.Listening -> stringResource(R.string.status_listening)
        CaptureStatus.Error -> state.errorMessage ?: stringResource(R.string.status_error)
    }
    val color = when (state.status) {
        CaptureStatus.Error -> MaterialTheme.colorScheme.error
        CaptureStatus.Listening -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
}

@Composable
private fun CaptionBubble(line: CaptionLine, darkTheme: Boolean) {
    val speakerColor = Color(SpeakerColors.colorFor(line.speaker, darkTheme))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(speakerColor),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(speakerColor.copy(alpha = if (line.isFinal) 0.14f else 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (line.isFinal) 1f else 0.7f,
                ),
            )
        }
    }
}

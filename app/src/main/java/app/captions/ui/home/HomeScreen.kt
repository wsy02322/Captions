package app.captions.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.captions.R

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenLive: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onOpenLive = onOpenLive,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    uiState: HomeUiState,
    onOpenSettings: () -> Unit,
    onOpenLive: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(0.2f).height(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(24.dp))
            if (uiState.loaded) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            when {
                                uiState.canStartLive -> R.string.home_ready
                                uiState.hasOpenRouterKey -> R.string.home_need_deepgram
                                else -> R.string.home_setup_needed
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onOpenLive,
                    enabled = uiState.canStartLive,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.start_live))
                }
            }
        }
    }
}

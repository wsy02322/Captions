package app.captions.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.captions.R
import app.captions.data.keys.ApiProvider
import app.captions.providers.openrouter.ModelOption

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        uiState = uiState,
        onKeyChanged = viewModel::onKeyChanged,
        onVerify = viewModel::onVerify,
        onTranslationModelSelected = viewModel::onTranslationModelSelected,
        onOpenRouterSttModelSelected = viewModel::onOpenRouterSttModelSelected,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onKeyChanged: (ApiProvider, String) -> Unit,
    onVerify: (ApiProvider) -> Unit,
    onTranslationModelSelected: (String) -> Unit,
    onOpenRouterSttModelSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_api_keys),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (uiState.loaded) {
                ApiKeyCard(
                    title = stringResource(R.string.settings_openrouter_key),
                    description = stringResource(R.string.settings_openrouter_desc),
                    hint = stringResource(R.string.settings_key_hint_openrouter),
                    state = uiState.openRouter,
                    onTextChanged = { onKeyChanged(ApiProvider.OPENROUTER, it) },
                    onVerify = { onVerify(ApiProvider.OPENROUTER) },
                )
                ApiKeyCard(
                    title = stringResource(R.string.settings_deepgram_key),
                    description = stringResource(R.string.settings_deepgram_desc),
                    hint = stringResource(R.string.settings_key_hint_deepgram),
                    state = uiState.deepgram,
                    onTextChanged = { onKeyChanged(ApiProvider.DEEPGRAM, it) },
                    onVerify = { onVerify(ApiProvider.DEEPGRAM) },
                )
                ApiKeyCard(
                    title = stringResource(R.string.settings_elevenlabs_key),
                    description = stringResource(R.string.settings_elevenlabs_desc),
                    hint = stringResource(R.string.settings_key_hint_elevenlabs),
                    state = uiState.elevenLabs,
                    onTextChanged = { onKeyChanged(ApiProvider.ELEVENLABS, it) },
                    onVerify = { onVerify(ApiProvider.ELEVENLABS) },
                )
                Text(
                    text = stringResource(R.string.settings_models),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                ModelSelectionCard(
                    title = stringResource(R.string.settings_translation_model),
                    description = stringResource(R.string.settings_translation_model_desc),
                    selectedModelId = uiState.translationModel,
                    options = uiState.translationOptions,
                    loading = uiState.modelsLoading,
                    error = uiState.modelsError,
                    onModelSelected = onTranslationModelSelected,
                )
                ModelSelectionCard(
                    title = stringResource(R.string.settings_openrouter_stt_model),
                    description = stringResource(R.string.settings_openrouter_stt_model_desc),
                    selectedModelId = uiState.openRouterSttModel,
                    options = uiState.sttOptions,
                    loading = uiState.modelsLoading,
                    error = uiState.modelsError,
                    onModelSelected = onOpenRouterSttModelSelected,
                )
            }
        }
    }
}

@Composable
private fun ModelSelectionCard(
    title: String,
    description: String,
    selectedModelId: String,
    options: List<ModelOption>,
    loading: Boolean,
    error: String?,
    onModelSelected: (String) -> Unit,
) {
    val knownSelected = options.any { it.id == selectedModelId }
    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable(selectedModelId) {
        mutableStateOf(if (knownSelected) "" else selectedModelId)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option.id == selectedModelId,
                                onClick = {
                                    customExpanded = false
                                    onModelSelected(option.id)
                                },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.id == selectedModelId,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = !knownSelected || customExpanded,
                onClick = { customExpanded = !customExpanded },
                label = { Text(stringResource(R.string.settings_custom_model)) },
            )
            if (customExpanded || !knownSelected) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = {
                        customText = it
                        if (it.isNotBlank()) onModelSelected(it.trim())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_custom_model_hint)) },
                    label = { Text(stringResource(R.string.settings_custom_model)) },
                )
            }
            if (loading) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_models_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_models_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    title: String,
    description: String,
    hint: String,
    state: KeyFieldState,
    onTextChanged: (String) -> Unit,
    onVerify: () -> Unit,
) {
    var showKey by rememberSaveable { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(hint) },
                singleLine = true,
                visualTransformation =
                    if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector =
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (showKey) R.string.settings_hide_key else R.string.settings_show_key
                            ),
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIndicator(status = state.status, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = onVerify, enabled = state.status != KeyFieldStatus.VERIFYING) {
                    Text(stringResource(R.string.settings_verify))
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: KeyFieldStatus, modifier: Modifier = Modifier) {
    data class Display(val icon: ImageVector?, val textRes: Int?, val isError: Boolean)

    val display = when (status) {
        KeyFieldStatus.IDLE -> Display(null, null, false)
        KeyFieldStatus.SAVED -> Display(Icons.Default.Cloud, R.string.settings_key_saved, false)
        KeyFieldStatus.VERIFYING -> Display(null, R.string.settings_key_verifying, false)
        KeyFieldStatus.VALID -> Display(Icons.Default.CheckCircle, R.string.settings_key_valid, false)
        KeyFieldStatus.INVALID -> Display(Icons.Default.Error, R.string.settings_key_invalid, true)
        KeyFieldStatus.NETWORK_ERROR ->
            Display(Icons.Default.CloudOff, R.string.settings_key_network_error, true)
        KeyFieldStatus.EMPTY -> Display(Icons.Default.Error, R.string.settings_key_empty, true)
    }
    val color =
        if (display.isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (status == KeyFieldStatus.VERIFYING) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (display.icon != null) {
            Icon(
                imageVector = display.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        display.textRes?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

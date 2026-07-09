package app.captions.ui.live

import androidx.lifecycle.ViewModel
import app.captions.pipeline.CaptionSessionController
import app.captions.pipeline.LiveCaptionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LiveCaptionViewModel @Inject constructor(
    controller: CaptionSessionController,
) : ViewModel() {
    val uiState: StateFlow<LiveCaptionState> = controller.state
}

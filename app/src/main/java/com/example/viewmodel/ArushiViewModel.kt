package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.bridge.AndroidActionBridge
import com.example.gemini.GeminiLiveManager
import com.example.model.ActionResult
import com.example.model.AssistantStatus
import com.example.model.ChatMessage
import com.example.model.ContactItem
import com.example.model.Sender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArushiUiState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val statusText: String = "Tap mic to talk with Arushi",
    val isConnected: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val currentAmplitude: Float = 0f,
    val currentLanguage: String = "Auto Detect (Hindi, English, Hinglish…)",
    val pendingDisambiguation: List<ContactItem>? = null,
    val isMicPermissionGranted: Boolean = false,
    val isContactsPermissionGranted: Boolean = false,
    val isCallPermissionGranted: Boolean = false
)

class ArushiViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ArushiUiState())
    val uiState: StateFlow<ArushiUiState> = _uiState.asStateFlow()

    val actionBridge = AndroidActionBridge(application.applicationContext)
    val audioPlayer = AudioPlayer(sampleRate = 24000)
    val audioRecorder = AudioRecorder(sampleRate = 16000)

    val geminiManager = GeminiLiveManager(
        actionBridge = actionBridge,
        onPcmAudioReceived = { pcmBytes ->
            audioPlayer.playChunk(pcmBytes)
        },
        onTextReceived = { text, isUser, language ->
            viewModelScope.launch {
                val msg = ChatMessage(
                    sender = if (isUser) Sender.USER else Sender.ARUSHI,
                    text = text,
                    language = language
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + msg,
                        currentLanguage = language ?: state.currentLanguage,
                        status = if (isUser) AssistantStatus.THINKING else AssistantStatus.SPEAKING
                    )
                }
            }
        },
        onActionTriggered = { actionName, success, details, result ->
            viewModelScope.launch {
                val candidateList = if (result is ActionResult.MultipleContacts) result.contacts else null
                val actionMsg = ChatMessage(
                    sender = Sender.ACTION,
                    text = details,
                    actionName = actionName,
                    actionSuccess = success,
                    candidateContacts = candidateList
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + actionMsg,
                        status = AssistantStatus.EXECUTING_ACTION,
                        statusText = details,
                        pendingDisambiguation = candidateList
                    )
                }
            }
        },
        onStatusChanged = { statusText, isConnected ->
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        statusText = statusText,
                        isConnected = isConnected
                    )
                }
            }
        }
    )

    init {
        // Wire audio amplitude for live orb visualization
        audioPlayer.onAmplitudeUpdated = { amp ->
            if (audioPlayer.isPlaying) {
                _uiState.update { it.copy(currentAmplitude = amp) }
            }
        }

        audioPlayer.onPlayingStateChanged = { playing ->
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        status = if (playing) AssistantStatus.SPEAKING else AssistantStatus.IDLE,
                        statusText = if (playing) "Arushi is speaking…" else "Tap mic to talk with Arushi",
                        currentAmplitude = if (playing) it.currentAmplitude else 0f
                    )
                }
            }
        }

        audioRecorder.onAmplitudeUpdated = { amp ->
            if (audioRecorder.isRecording) {
                _uiState.update { it.copy(currentAmplitude = amp) }
            }
        }

        audioRecorder.onAudioChunk = { _, base64, _ ->
            geminiManager.sendAudioChunk(base64)
        }

        // Add welcome message from Arushi
        val welcomeMessage = ChatMessage(
            sender = Sender.ARUSHI,
            text = "Namaste! I am Arushi, your AI companion. I speak Hindi, English, Hinglish, and many languages. You can ask me anything or say 'WhatsApp kholo', 'Call Mom', or 'Open YouTube'!",
            language = "Hinglish / English"
        )
        _uiState.update { it.copy(messages = listOf(welcomeMessage)) }

        // Start Gemini session
        geminiManager.startSession()
    }

    fun toggleListening() {
        if (audioRecorder.isRecording) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        // If Arushi is speaking, interrupt immediately
        if (audioPlayer.isPlaying) {
            interruptArushi()
        }

        val started = audioRecorder.startRecording()
        if (started) {
            _uiState.update {
                it.copy(
                    status = AssistantStatus.LISTENING,
                    statusText = "Listening to you… (Speak in any language)"
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    status = AssistantStatus.ERROR,
                    statusText = "Microphone access needed. Please grant permission."
                )
            }
        }
    }

    fun stopListening() {
        audioRecorder.stopRecording()
        _uiState.update {
            it.copy(
                status = AssistantStatus.THINKING,
                statusText = "Arushi is processing…",
                currentAmplitude = 0f
            )
        }
    }

    fun interruptArushi() {
        audioPlayer.stopAndFlush()
        _uiState.update {
            it.copy(
                status = AssistantStatus.IDLE,
                statusText = "Interrupted. Tap mic to speak.",
                currentAmplitude = 0f
            )
        }
    }

    fun sendTextMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        interruptArushi()
        _uiState.update {
            it.copy(
                status = AssistantStatus.THINKING,
                statusText = "Processing: \"$trimmed\""
            )
        }
        geminiManager.sendTextMessage(trimmed)
    }

    fun selectContact(contact: ContactItem) {
        val res = actionBridge.makeCall(contact.phoneNumber, contact.name)
        val msg = ChatMessage(
            sender = Sender.ACTION,
            text = res.message,
            actionName = "makeCall",
            actionSuccess = res.isSuccess
        )
        _uiState.update {
            it.copy(
                messages = it.messages + msg,
                pendingDisambiguation = null,
                statusText = res.message
            )
        }
    }

    fun dismissDisambiguation() {
        _uiState.update { it.copy(pendingDisambiguation = null) }
    }

    fun updatePermissions(mic: Boolean, contacts: Boolean, call: Boolean) {
        _uiState.update {
            it.copy(
                isMicPermissionGranted = mic,
                isContactsPermissionGranted = contacts,
                isCallPermissionGranted = call
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        audioPlayer.release()
        geminiManager.stopSession()
    }
}

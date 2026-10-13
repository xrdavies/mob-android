package com.xrdavies.mob.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xrdavies.mob.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val apiClient: ApiClient) : ViewModel() {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    
    private val _activeQuestion = MutableStateFlow<Question?>(null)
    val activeQuestion: StateFlow<Question?> = _activeQuestion
    
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting
    
    init {
        loadSessions()
    }
    
    fun loadSessions() {
        viewModelScope.launch {
            try {
                val response = apiClient.api.listSessions()
                _session.value = response.sessions.firstOrNull()
                
                _session.value?.let { session ->
                    startPolling(session.id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun submit(prompt: String) {
        _isSubmitting.value = true
        
        viewModelScope.launch {
            try {
                val task = apiClient.api.createTask(
                    CreateTaskRequest(
                        prompt = prompt,
                        sessionId = _session.value?.id
                    )
                )
                
                startPolling(task.sessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSubmitting.value = false
            }
        }
    }
    
    fun answerQuestion(question: Question, answer: String? = null, approved: Boolean? = null) {
        viewModelScope.launch {
            try {
                apiClient.api.answerQuestion(
                    question.taskId,
                    question.id,
                    AnswerQuestionRequest(answer, approved)
                )
                _activeQuestion.value = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun startPolling(sessionId: String) {
        viewModelScope.launch {
            while (true) {
                try {
                    val detail = apiClient.api.getSession(sessionId)
                    _session.value = detail.session
                    
                    val pending = detail.questions.firstOrNull { it.status == "pending" }
                    if (pending != null && pending.id != _activeQuestion.value?.id) {
                        _activeQuestion.value = pending
                    }
                } catch (e: Exception) {
                    // Silent fail during polling
                }
                
                delay(2000)
            }
        }
    }
}

package com.example.model

enum class AssistantStatus {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    EXECUTING_ACTION,
    ERROR
}

data class ContactItem(
    val id: String,
    val name: String,
    val phoneNumber: String
)

sealed class ActionResult(val isSuccess: Boolean, val message: String) {
    class Success(message: String, val details: String? = null) : ActionResult(true, message)
    class Failure(message: String) : ActionResult(false, message)
    class MultipleContacts(val query: String, val contacts: List<ContactItem>) :
        ActionResult(false, "Found ${contacts.size} contacts matching '$query'")
}

enum class Sender {
    USER,
    ARUSHI,
    SYSTEM,
    ACTION
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val language: String? = null,
    val actionName: String? = null,
    val actionSuccess: Boolean? = null,
    val candidateContacts: List<ContactItem>? = null,
    val timestamp: Long = System.currentTimeMillis()
)

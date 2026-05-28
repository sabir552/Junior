package com.junior.assistant.model

enum class SenderType {
    USER,
    JUNIOR
}

data class ChatMessage(
    val id: String,
    val text: String,
    val sender: SenderType,
    val timestamp: Long = System.currentTimeMillis()
)

package com.junior.assistant.model

enum class CommandActionType {
    OPEN_APP,
    SYSTEM_VOLUME_UP,
    SYSTEM_VOLUME_DOWN,
    TOGGLE_FLASHLIGHT,
    CALL_CONTACT,
    SEND_SMS,
    WHATSAPP_MESSAGE,
    GLOBAL_HOME,
    LOOK_SCREEN,
    SCROLL_DOWN,
    SCROLL_UP,
    SOS_MODE,
    EXPENSE_LOG,
    GEOFENCE_REMINDER,
    ANTI_THEFT_ARM,
    ANTI_THEFT_DISARM,
    CAMERA_CLICK,
    UNKNOWN
}

data class AppCommand(
    val name: String,
    val pattern: String,
    val packageName: String? = null,
    val actionType: CommandActionType
)

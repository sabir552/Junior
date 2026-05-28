package com.junior.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.junior.assistant.model.CommandActionType
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

class CommandParser(private val context: Context) {

    data class CommandResult(
        val isHandled: Boolean,
        val actionType: CommandActionType,
        val responseMessage: String,
        val targetData: String? = null
    )

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("JuniorPrefs", Context.MODE_PRIVATE)

    private val appPackageMap = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "settings" to "com.android.settings",
        "calculator" to "com.android.calculator2",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "phone" to "com.android.phone",
        "contacts" to "com.android.contacts",
        "play store" to "com.android.vending",
        "amazon" to "com.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "zoom" to "us.zoom.videomeetings",
        "meet" to "com.google.android.apps.meetings",
        "teams" to "com.microsoft.teams",
        "tiktok" to "com.zhiliaoapp.musically",
        "discord" to "com.discord",
        "linkedin" to "com.linkedin.android"
    )

    fun parse(text: String): CommandResult {
        val sanitized = text.lowercase().trim()

        for ((appName, packageName) in appPackageMap) {
            val openPattern = "open $appName|launch $appName|$appName kholo|$appName open karo"
            if (Pattern.compile(openPattern).matcher(sanitized).find()) {
                return CommandResult(true, CommandActionType.OPEN_APP, "Opening $appName for you, Sir.", packageName)
            }
        }

        if (sanitized.contains("go home") || sanitized.contains("home screen") || sanitized.contains("minimize") || sanitized.contains("dismiss")) {
            return CommandResult(true, CommandActionType.GLOBAL_HOME, "Going home, Sir.")
        }

        if (sanitized.contains("volume up") || sanitized.contains("awaaz badhao") || sanitized.contains("volume badhao")) {
            return CommandResult(true, CommandActionType.SYSTEM_VOLUME_UP, "Increasing volume, Sir.")
        }

        if (sanitized.contains("volume down") || sanitized.contains("awaaz kam karo") || sanitized.contains("volume kam karo")) {
            return CommandResult(true, CommandActionType.SYSTEM_VOLUME_DOWN, "Decreasing volume, Sir.")
        }

        if (sanitized.contains("flashlight on") || sanitized.contains("torch on") || sanitized.contains("flashlight jalao") || sanitized.contains("torch jalao") || sanitized.contains("turn on flashlight")) {
            return CommandResult(true, CommandActionType.TOGGLE_FLASHLIGHT, "Turning flashlight on, Sir.", "ON")
        }

        if (sanitized.contains("flashlight off") || sanitized.contains("torch off") || sanitized.contains("flashlight band karo") || sanitized.contains("torch band karo") || sanitized.contains("turn off flashlight")) {
            return CommandResult(true, CommandActionType.TOGGLE_FLASHLIGHT, "Turning flashlight off, Sir.", "OFF")
        }

        if (sanitized.contains("screen dekho") || sanitized.contains("screen par kya hai") || sanitized.contains("look at the screen") || sanitized.contains("look at screen") || sanitized.contains("look screen")) {
            return CommandResult(true, CommandActionType.LOOK_SCREEN, "Let me look at your screen, Sir.")
        }

        if (sanitized.contains("scroll down") || sanitized.contains("niche scroll karo")) {
            return CommandResult(true, CommandActionType.SCROLL_DOWN, "Scrolling down, Sir.")
        }

        if (sanitized.contains("scroll up") || sanitized.contains("upar scroll karo")) {
            return CommandResult(true, CommandActionType.SCROLL_UP, "Scrolling up, Sir.")
        }

        val primeContacts = getPrimeContacts()
        if (sanitized.contains("call my close friend")) {
            val contact = primeContacts.getOrNull(0)
            if (contact != null) {
                return CommandResult(true, CommandActionType.CALL_CONTACT, "Calling your close friend, ${contact.first}.", contact.second)
            }
            val legacyName = sharedPrefs.getString("prime_name", null)
            val legacyNum = sharedPrefs.getString("prime_number", null)
            if (legacyName != null && legacyNum != null) {
                return CommandResult(true, CommandActionType.CALL_CONTACT, "Calling your close friend, $legacyName.", legacyNum)
            }
            return CommandResult(false, CommandActionType.UNKNOWN, "No close friend configured in settings, Sir.")
        }

        if (sanitized.contains("message my love")) {
            val contact = primeContacts.getOrNull(0)
            if (contact != null) {
                return CommandResult(true, CommandActionType.WHATSAPP_MESSAGE, "Sending WhatsApp message to your love, ${contact.first}.", contact.second)
            }
            return CommandResult(false, CommandActionType.UNKNOWN, "No WhatsApp contact configured as index 0, Sir.")
        }

        if (sanitized.contains("call my second contact")) {
            val contact = primeContacts.getOrNull(1)
            if (contact != null) {
                return CommandResult(true, CommandActionType.CALL_CONTACT, "Calling your second contact, ${contact.first}.", contact.second)
            }
            return CommandResult(false, CommandActionType.UNKNOWN, "Second contact is not configured in settings, Sir.")
        }

        // 1. EMERGENCY PANIC WORD & SOS MODE
        if (sanitized.contains("backup chahiye") || sanitized.contains("emergency rescue") || sanitized.contains("save me please")) {
            return CommandResult(true, CommandActionType.SOS_MODE, "Initiating SOS Panic action. Dispatching coordinates to all Prime Contacts!")
        }

        // 2. SMART EXPENSE VOICE LOGGER
        val expenseRegex1 = "([0-9]+)\\s*(?:rupey|rupees|rs)\\s*(?:ka|par|ki)?\\s*([a-zA-Z0-9\\s]+)".toRegex()
        val match1 = expenseRegex1.find(sanitized)
        if (match1 != null) {
            val amount = match1.groupValues[1]
            val category = match1.groupValues[2].trim()
            return CommandResult(true, CommandActionType.EXPENSE_LOG, "Logging expense: Rs $amount for $category", "$amount|$category")
        }
        val expenseRegex2 = "([a-zA-Z0-9\\s]+?)\\s*par\\s*([0-9]+)\\s*(?:rupey|rupees|rs)".toRegex()
        val match2 = expenseRegex2.find(sanitized)
        if (match2 != null) {
            val category = match2.groupValues[1].trim()
            val amount = match2.groupValues[2]
            return CommandResult(true, CommandActionType.EXPENSE_LOG, "Logging expense: Rs $amount for $category", "$amount|$category")
        }

        // 3. GEO-FENCING TRIGGERS
        val gfRegex1 = "remind me to (.+?) when i reach (.+)".toRegex()
        val gfMatch1 = gfRegex1.find(sanitized)
        if (gfMatch1 != null) {
            val task = gfMatch1.groupValues[1].trim()
            val location = gfMatch1.groupValues[2].trim()
            return CommandResult(true, CommandActionType.GEOFENCE_REMINDER, "Setting location geo-fence alert for $location to remind you about: $task", "$task|$location")
        }
        val gfRegex2 = "jab main (.+?) pahunchu? (?:toh|tab) (?:mujhe )?(.+?) (?:yaad|remind)".toRegex()
        val gfMatch2 = gfRegex2.find(sanitized)
        if (gfMatch2 != null) {
            val location = gfMatch2.groupValues[1].trim()
            val task = gfMatch2.groupValues[2].trim()
            return CommandResult(true, CommandActionType.GEOFENCE_REMINDER, "Set. Jab aap $location pahunchenge toh aapko remind kar dunga: $task", "$task|$location")
        }

        // 4. ANTI-THEFT AUDIO TRAP
        if (sanitized.contains("anti-theft mode arm") || sanitized.contains("anti theft mode arm") || sanitized.contains("anti theft arm") || sanitized.contains("turn on anti theft")) {
            return CommandResult(true, CommandActionType.ANTI_THEFT_ARM, "Arming Anti-theft motion sensors. Touch action will sound alarm!")
        }
        if (sanitized.contains("anti-theft mode disarm") || sanitized.contains("anti theft mode disarm") || sanitized.contains("anti theft disarm") || sanitized.contains("turn off anti theft") || sanitized.contains("anti theft off")) {
            return CommandResult(true, CommandActionType.ANTI_THEFT_DISARM, "Disarming Anti-theft system.")
        }

        // 5. HANDS-FREE VOICE CAMERA CONTROLS
        if (sanitized.contains("photo click") || sanitized.contains("take a photo") || sanitized.contains("click photo") || sanitized.contains("photo khincho")) {
            val timerRegex = "([0-9]+)\\s*(?:second|seconds|sec)\\s*timer".toRegex()
            val timerMatch = timerRegex.find(sanitized)
            val delayValue = timerMatch?.groupValues?.get(1) ?: "0"
            return CommandResult(true, CommandActionType.CAMERA_CLICK, "Taking front photo frame in $delayValue seconds!", delayValue)
        }

        val callPattern = Pattern.compile("(?:call|phone|milao) ([a-zA-Z0-9 ]+)")
        val callMatcher = callPattern.matcher(sanitized)
        if (callMatcher.find()) {
            val name = callMatcher.group(1)?.trim() ?: ""
            if (name.isNotEmpty() && name != "my close friend" && name != "my second contact") {
                val matchingContact = primeContacts.firstOrNull { it.first.lowercase().contains(name) }
                if (matchingContact != null) {
                    return CommandResult(true, CommandActionType.CALL_CONTACT, "Calling ${matchingContact.first}, Sir.", matchingContact.second)
                }
                return CommandResult(true, CommandActionType.CALL_CONTACT, "Initiating call to $name, Sir.", name)
            }
        }

        return CommandResult(false, CommandActionType.UNKNOWN, "")
    }

    fun getPrimeContacts(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val jsonStr = sharedPrefs.getString("prime_contacts_json", null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val phone = obj.optString("phone", "")
                    if (name.isNotEmpty() && phone.isNotEmpty()) {
                        result.add(Pair(name, phone))
                    }
                }
            } catch (e: Exception) {
                Log.e("CommandParser", "Error parsing prime contacts", e)
            }
        }

        if (result.isEmpty()) {
            val legacyName = sharedPrefs.getString("prime_name", null)
            val legacyNum = sharedPrefs.getString("prime_number", null)
            if (!legacyName.isNullOrEmpty() && !legacyNum.isNullOrEmpty()) {
                result.add(Pair(legacyName, legacyNum))
                savePrimeContacts(result)
            }
        }
        return result
    }

    fun savePrimeContacts(contacts: List<Pair<String, String>>) {
        try {
            val array = JSONArray()
            for (c in contacts) {
                val obj = JSONObject().apply {
                    put("name", c.first)
                    put("phone", c.second)
                }
                array.put(obj)
            }
            sharedPrefs.edit().putString("prime_contacts_json", array.toString()).apply()
        } catch (e: Exception) {
            Log.e("CommandParser", "Save failed", e)
        }
    }
}

package com.junior.assistant.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var editUserName: EditText
    private lateinit var spinnerVoice: Spinner
    private lateinit var radioGroupPersona: RadioGroup
    private lateinit var editApiKey: EditText
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button

    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPrefs = getSharedPreferences("JuniorPrefs", Context.MODE_PRIVATE)

        editUserName = findViewById(R.id.edit_user_name)
        spinnerVoice = findViewById(R.id.spinner_voice)
        radioGroupPersona = findViewById(R.id.radio_group_persona)
        editApiKey = findViewById(R.id.edit_api_key)
        btnCancel = findViewById(R.id.btn_cancel_settings)
        btnSave = findViewById(R.id.btn_save_settings)

        val density = resources.displayMetrics.density

        // Beautiful rounded input field shape for Elegant Dark UI
        val fieldBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(0xFF151515.toInt()) // Sleek zinc-950 solid background
            setStroke((1 * density).toInt(), 0x33A1A1AA.toInt()) // Zinc border
        }

        editUserName.apply {
            background = fieldBg
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }
        editApiKey.apply {
            background = fieldBg
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }

        val spinnerBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(0xFF151515.toInt())
            setStroke((1 * density).toInt(), 0x33A1A1AA.toInt())
        }
        spinnerVoice.apply {
            background = spinnerBg
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }

        val saveBtnBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f * density // Fully pill rounded
            setColor(0xFFFF1744.toInt()) // Crimson Red
        }
        btnSave.apply {
            background = saveBtnBg
            setTextColor(0xFFFFFFFF.toInt())
        }

        val voices = arrayOf("Charon", "Fenrir", "Puck", "Orus", "Aoede", "Kore", "Leda", "Zephyr")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoice.adapter = adapter

        val savedName = sharedPrefs.getString("user_custom_name", "Sir")
        val savedVoice = sharedPrefs.getString("voice_preset", "Charon")
        val savedMode = sharedPrefs.getString("persona_mode", "Companion")
        val savedKey = sharedPrefs.getString("custom_api_key", "")

        editUserName.setText(savedName)
        editApiKey.setText(savedKey)

        val voiceIndex = voices.indexOf(savedVoice).coerceAtLeast(0)
        spinnerVoice.setSelection(voiceIndex)

        when (savedMode) {
            "Professional" -> findViewById<RadioButton>(R.id.radio_professional).isChecked = true
            "Assistant" -> findViewById<RadioButton>(R.id.radio_normal).isChecked = true
            else -> findViewById<RadioButton>(R.id.radio_companion).isChecked = true
        }

        btnCancel.setOnClickListener {
            finish()
        }

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val name = editUserName.text.toString().trim()
        val voice = spinnerVoice.selectedItem.toString()
        val key = editApiKey.text.toString().trim()

        val selectedRadioId = radioGroupPersona.checkedRadioButtonId
        val mode = when (selectedRadioId) {
            R.id.radio_professional -> "Professional"
            R.id.radio_normal -> "Assistant"
            else -> "Companion"
        }

        sharedPrefs.edit().apply {
            putString("user_custom_name", if (name.isEmpty()) "Sir" else name)
            putString("voice_preset", voice)
            putString("persona_mode", mode)
            putString("custom_api_key", key)
            apply()
        }

        Toast.makeText(this, "Settings saved, Sir.", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}

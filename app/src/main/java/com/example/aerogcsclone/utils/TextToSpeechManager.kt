package com.example.aerogcsclone.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.*

/**
 * Manages Text-to-Speech functionality for the application.
 * Provides voice feedback for connection status and calibration events.
 */
class TextToSpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isReady = false
    private var currentLanguage: String = "te" // Default to Telugu

    companion object {
        private const val TAG = "TextToSpeechManager"

        // --- Shared deduplication state so repeated TTS is suppressed across instances ---
        private val dedupeLock = Any()
        @JvmStatic
        private var lastSpokenText: String? = null
        @JvmStatic
        private var lastSpokenAt: Long = 0L
        @JvmStatic
        private val dedupeWindowMillis: Long = 2000L // 2 seconds cooldown for the same message

        // Per-key tracking to guarantee a message is spoken only once per logical key
        @JvmStatic
        private val spokenKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }

    init {
        initializeTTS()
    }

    private fun initializeTTS() {
        try {
            textToSpeech = TextToSpeech(context, this)
        } catch (e: Exception) {
            // Failed to initialize TextToSpeech
        }
    }

    /**
     * Set the language for TTS output
     * @param languageCode "en" for English, "te" for Telugu
     */
    fun setLanguage(languageCode: String) {
        currentLanguage = languageCode
        textToSpeech?.let { tts ->
            val locale = if (languageCode == "en") {
                Locale.US
            } else {
                Locale.forLanguageTag("te-IN")
            }

            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
                currentLanguage = "en"
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                // Start with Telugu by default
                val telugu = Locale.forLanguageTag("te-IN")
                var result = tts.setLanguage(telugu)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    result = tts.setLanguage(Locale.US)
                    currentLanguage = "en"
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        isReady = false
                        return
                    }
                }

                isReady = true

                // Configure TTS settings
                tts.setSpeechRate(1.0f)
                tts.setPitch(1.0f)
            }
        } else {
            isReady = false
        }
    }

    /**
     * Speaks the given text if TTS is ready. Prevents repeating the exact same message within a short window.
     */
    fun speak(text: String) {
        // Quick ready check
        if (!isReady || textToSpeech == null) {
            return
        }

        val now = System.currentTimeMillis()
        synchronized(dedupeLock) {
            if (text == lastSpokenText && (now - lastSpokenAt) < dedupeWindowMillis) {
                // Ignore repeated request within cooldown
                return
            }

            lastSpokenText = text
            lastSpokenAt = now
        }

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
    }

    /**
     * Speak the given text only once per key. Subsequent calls with the same key will be ignored
     * until the key is reset via resetSpokenKey or resetAllSpoken.
     */
    fun speakOnce(key: String, text: String) {
        if (!isReady || textToSpeech == null) {
            return
        }

        synchronized(dedupeLock) {
            if (spokenKeys.contains(key)) {
                return
            }
            spokenKeys.add(key)
        }

        speak(text)
    }

    /**
     * Reset a specific spoken key so speakOnce can be used again for that key.
     */
    fun resetSpokenKey(key: String) {
        synchronized(dedupeLock) {
            spokenKeys.remove(key)
        }
    }

    /**
     * Clears all spoken keys so speakOnce can be used again for any key.
     */
    fun resetAllSpoken() {
        synchronized(dedupeLock) {
            spokenKeys.clear()
        }
    }

    /**
     * Allows callers to reset the dedupe state so the same message can be spoken again immediately.
     * Useful when calibration finishes or different calibration steps should re-announce the same phrase.
     */
    fun resetLastSpoken() {
        synchronized(dedupeLock) {
            lastSpokenText = null
            lastSpokenAt = 0L
        }
    }

    /**
     * Announces connection status
     */
    fun announceConnectionStatus(isConnected: Boolean) {
        val message = if (isConnected) "connected" else "disconnected"
        speak(getMessage(message))
    }

    /**
     * Announces calibration started
     */
    fun announceCalibrationStarted() {
        // Use immediate speak for calibration start to guarantee audible feedback even if dedupe
        // would otherwise suppress it (e.g., UI pressed multiple times).
        speakImmediate(getMessage("calibration_started"))
    }

    /**
     * Announces calibration finished with success/failure status
     */
    fun announceCalibrationFinished(isSuccess: Boolean) {
        val message = if (isSuccess) "calibration_success" else "calibration_failed"
        speak(getMessage(message))
    }

    /**
     * Announces general calibration finished message
     */
    fun announceCalibrationFinished() {
        speak(getMessage("calibration_finished"))
    }

    /**
     * Announces connection failed status
     */
    fun announceConnectionFailed() {
        speak(getMessage("connection_failed"))
    }

    /**
     * Announces automatic mode selection
     */
    fun announceSelectedAutomatic() {
        speak(getMessage("selected_automatic"))
    }

    /**
     * Announces manual mode selection
     */
    fun announceSelectedManual() {
        speak(getMessage("selected_manual"))
    }

    /**
     * Announces drone armed status
     */
    fun announceDroneArmed() {
        speak(getMessage("drone_armed"))
    }

    /**
     * Announces drone disarmed status
     */
    fun announceDroneDisarmed() {
        speak(getMessage("drone_disarmed"))
    }

    /**
     * Announces compass calibration started
     */
    fun announceCompassCalibrationStarted() {
        speakImmediate(getMessage("compass_calibration_started"))
    }

    /**
     * Announces compass calibration completed successfully
     */
    fun announceCompassCalibrationCompleted() {
        speak(getMessage("compass_calibration_completed"))
    }

    /**
     * Announces compass calibration failed
     */
    fun announceCompassCalibrationFailed() {
        speak(getMessage("compass_calibration_failed"))
    }

    /**
     * Announces reboot drone message
     */
    fun announceRebootDrone() {
        speak(getMessage("reboot_drone"))
    }

    /**
     * Announces calibration type when entering calibration screens
     */
    fun announceCalibration(calibrationType: String) {
        // Speak in Telugu: append the translated word for "calibration" (కేలిబ్రేషన్)
        // Use grammatically correct Telugu: "<type> కేలిబ్రేషన్ ప్రారంభమైంది"
        speak("$calibrationType కేలిబ్రేషన్ ప్రారంభమైంది")
    }

    /**
     * Announces IMU calibration position
     * Converts position names to natural speech (e.g., LEVEL -> Telugu for "Level")
     */
    fun announceIMUPosition(position: String) {
        val spokenText = when (position.uppercase(Locale.US)) {
            "LEVEL" -> "అన్ని వైపులా సమానంగా పెంటండి" // Level
            "LEFT" -> "ఎడమ" // Left
            "RIGHT" -> "కుడి" // Right
            "NOSEDOWN", "NOSE_DOWN" -> "నోస్ కిందకి పెంటండి" // Nose down
            "NOSEUP", "NOSE_UP" -> "నోస్ పైకి పెంటండి" // Nose up
            "BACK" -> "వెనక్కి తిప్పండి" // Inverted down/back
            else -> position.replace("_", " ")
                .lowercase(Locale.US)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        speak(spokenText)
    }

    /**
     * Announces IMU calibration position only once per position key.
     * Useful to avoid repeated announcements when user clicks Next/Start multiple times.
     */
    fun announceIMUPositionOnce(position: String) {
        val spokenText = when (position.uppercase(Locale.US)) {
            "LEVEL" -> AppStrings.imuLevel
            "LEFT" -> AppStrings.imuLeft
            "RIGHT" -> AppStrings.imuRight
            "NOSEDOWN", "NOSE_DOWN" -> AppStrings.imuNoseDown
            "NOSEUP", "NOSE_UP" -> AppStrings.imuNoseUp
            "BACK" -> AppStrings.imuBack
            else -> position.replace("_", " ")
                .lowercase(Locale.US)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        // Use a stable key per position so repeated UI actions won't replay the same phrase
        val key = "IMU_POS_${position.uppercase(Locale.US)}"
        speakOnce(key, spokenText)
    }

    /**
     * Announces mission paused at waypoint
     */
    fun announceMissionPaused(waypoint: Int) {
        val message = if (currentLanguage == "en") {
            "Mission paused at waypoint $waypoint"
        } else {
            "మిషన్ వేపాయింట్ $waypoint వద్ద పాజ్ చేయబడింది"
        }
        speak(message)
    }

    /**
     * Announces mission resumed
     */
    fun announceMissionResumed() {
        speak(getMessage("mission_resumed"))
    }

    /**
     * Announces language selection
     * @param languageCode "en" for English, "te" for Telugu
     */
    fun announceLanguageSelected(languageCode: String) {
        val message = if (languageCode == "en") {
            "English is selected"
        } else {
            "తెలుగు ని ఎంచుకున్నారు"
        }
        speakImmediate(message)
    }

    /**
     * Stops any ongoing speech
     */
    fun stop() {
        textToSpeech?.stop()
    }

    /**
     * Releases TTS resources
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isReady = false
    }

    /**
     * Checks if TTS is ready to use
     */
    fun isReady(): Boolean = isReady

    /**
     * Speak immediately bypassing the time-based and key-based dedupe logic.
     * This still updates the dedupe state so subsequent calls in the short cooldown
     * won't cause another immediate playback.
     */
    fun speakImmediate(text: String) {
        if (!isReady || textToSpeech == null) {
            return
        }

        val now = System.currentTimeMillis()
        // Update dedupe state to reflect this utterance, so normal speak() won't replay it
        synchronized(dedupeLock) {
            lastSpokenText = text
            lastSpokenAt = now
            // Also mark a generic spoken key so speakOnce won't replay same logical key
            try {
                spokenKeys.add(text)
            } catch (_: Exception) {
            }
        }

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
    }

    private fun getMessage(key: String): String {
        return when (key) {
            "connected" -> if (currentLanguage == "en") "Connected" else "కనెక్ట్ అయింది"
            "disconnected" -> if (currentLanguage == "en") "Disconnected" else "డిస్కనెక్ట్ అయింది"
            "connection_failed" -> if (currentLanguage == "en") "Connection failed" else "కనెక్షన్ విఫలమైంది"
            "calibration_started" -> if (currentLanguage == "en") "Calibration started" else "కేలిబ్రేషన్ ప్రారంభమైంది"
            "calibration_finished" -> if (currentLanguage == "en") "Calibration finished" else "కేలిబ్రేషన్ ముగిసింది"
            "calibration_success" -> if (currentLanguage == "en") "Calibration completed successfully" else "కేలిబ్రేషన్ విజయవంతంగా పూర్తయింది"
            "calibration_failed" -> if (currentLanguage == "en") "Calibration failed" else "కేలిబ్రేష్ విఫలమైంది"
            "selected_automatic" -> if (currentLanguage == "en") "Selected automatic" else "ఆటోమేటిక్ ఎంచుకున్నారు"
            "selected_manual" -> if (currentLanguage == "en") "Selected manual" else "మాన్యువల్ ఎంచుకున్నారు"
            "drone_armed" -> if (currentLanguage == "en") "Drone armed" else "డ్రోన్ ఆర్మ్ అయింది"
            "drone_disarmed" -> if (currentLanguage == "en") "Drone disarmed" else "డ్రోన్ డిసార్మ్ అయింది"
            "compass_calibration_started" -> if (currentLanguage == "en") "Compass calibration started" else "కంపాస్ కేలిబ్రేషన్ ప్రారంభమైంది"
            "compass_calibration_completed" -> if (currentLanguage == "en") "Compass calibration completed successfully" else "కంపాస్ కేలిబ్రేషన్ విజయవంతంగా పూర్తయింది"
            "compass_calibration_failed" -> if (currentLanguage == "en") "Compass calibration failed" else "కంపాస్ కేలిబ్రేషన్ విఫలమైంది"
            "reboot_drone" -> if (currentLanguage == "en") "Please reboot your drone" else "దయచేసి మీ డ్రోన్ రీబూట్ చేయండి"
            "mission_paused" -> if (currentLanguage == "en") "Mission paused" else "మిషన్ పాజ్ అయింది"
            "mission_resumed" -> if (currentLanguage == "en") "Mission resumed" else "మిషన్ రిజ్యూమ్ అయింది"
            else -> key
        }
    }
}

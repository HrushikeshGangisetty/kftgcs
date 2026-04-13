package com.example.kftgcs.utils

import android.content.Context
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.*

/**
 * Manages Text-to-Speech functionality for the application.
 * Provides voice feedback for connection status and calibration events.
 */
class TextToSpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isReady = false
    private var currentLanguage: String = "te" // Default to Telugu
    private var initRetryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object RetryConfig {
        private const val MAX_INIT_RETRIES = 3
        private const val RETRY_DELAY_MS = 1500L

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
            Timber.e(e, "Failed to initialize TextToSpeech")
            scheduleRetryInit()
        }
    }

    /**
     * Safely shut down the current TTS instance (if any) and schedule a fresh
     * initialization after a short delay.
     */
    private fun scheduleRetryInit() {
        if (initRetryCount >= MAX_INIT_RETRIES) {
            Timber.w("Exceeded max TTS init retries ($MAX_INIT_RETRIES), giving up")
            return
        }
        initRetryCount++
        Timber.i("Scheduling TTS re-init (attempt $initRetryCount/$MAX_INIT_RETRIES) in ${RETRY_DELAY_MS}ms")

        // Make sure we tear down the dead instance first
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        isReady = false

        mainHandler.postDelayed({ initializeTTS() }, RETRY_DELAY_MS)
    }

    /**
     * Set the language for TTS output
     * @param languageCode "en" for English, "te" for Telugu
     */
    fun setLanguage(languageCode: String) {
        currentLanguage = languageCode
        textToSpeech?.let { tts ->
            val locale = when (languageCode) {
                "en" -> Locale.US
                "te" -> Locale.forLanguageTag("te-IN")
                "hi" -> Locale.forLanguageTag("hi-IN")
                "mr" -> Locale.forLanguageTag("mr-IN")
                "ta" -> Locale.forLanguageTag("ta-IN")
                "kn" -> Locale.forLanguageTag("kn-IN")
                "ml" -> Locale.forLanguageTag("ml-IN")
                "gu" -> Locale.forLanguageTag("gu-IN")
                "as" -> Locale.forLanguageTag("as-IN")
                "bn" -> Locale.forLanguageTag("bn-IN")
                "pa" -> Locale.forLanguageTag("pa-IN")
                else -> Locale.US
            }

            try {
                val result = tts.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US)
                    currentLanguage = "en"
                }
            } catch (e: Exception) {
                Timber.e(e, "setLanguage failed")
                if (e is DeadObjectException || e.cause is DeadObjectException) {
                    scheduleRetryInit()
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                try {
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
                    initRetryCount = 0 // reset on success

                    // Configure TTS settings
                    tts.setSpeechRate(1.0f)
                    tts.setPitch(1.0f)
                } catch (e: Exception) {
                    Timber.e(e, "onInit: TTS engine died during setup")
                    isReady = false
                    if (e is DeadObjectException || e.cause is DeadObjectException) {
                        scheduleRetryInit()
                    }
                }
            }
        } else {
            Timber.w("TTS onInit failed with status=$status")
            isReady = false
            // Engine might have crashed; try re-init
            scheduleRetryInit()
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

        try {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Timber.e(e, "speak failed")
            isReady = false
            if (e is DeadObjectException || e.cause is DeadObjectException) {
                scheduleRetryInit()
            }
        }
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
        val message = when (currentLanguage) {
            "en" -> "$calibrationType calibration started"
            "te" -> "$calibrationType కేలిబ్రేషన్ ప్రారంభమైంది"
            "hi" -> "$calibrationType कैलिब्रेशन शुरू हो गया"
            "mr" -> "$calibrationType कॅलिब्रेशन सुरू झाले"
            "ta" -> "$calibrationType அளவுத்திருத்தம் தொடங்கியது"
            "kn" -> "$calibrationType ಮಾಪನಾಂಕನ ಪ್ರಾರಂಭವಾಗಿದೆ"
            "ml" -> "$calibrationType കാലിബ്രേഷൻ ആരംഭിച്ചു"
            "gu" -> "$calibrationType કેલિબ્રેશન શરૂ થયું"
            "as" -> "$calibrationType কেলিব্ৰেচন আৰম্ভ হৈছে"
            "bn" -> "$calibrationType ক্যালিব্রেশন শুরু হয়েছে"
            "pa" -> "$calibrationType ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਸ਼ੁਰੂ ਹੋ ਗਈ"
            else -> "$calibrationType calibration started"
        }
        speak(message)
    }

    /**
     * Announces IMU calibration position
     * Converts position names to natural speech (e.g., LEVEL -> Telugu for "Level")
     */
    fun announceIMUPosition(position: String) {
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
        val message = when (currentLanguage) {
            "en" -> "Mission paused at waypoint $waypoint"
            "te" -> "మిషన్ వేపాయింట్ $waypoint వద్ద పాజ్ చేయబడింది"
            "hi" -> "मिशन वेपॉइंट $waypoint पर रुका हुआ है"
            "mr" -> "मिशन वेपॉइंट $waypoint वर थांबले आहे"
            "ta" -> "பணி வேபாயிண்ட் $waypoint இல் இடைநிறுத்தப்பட்டது"
            "kn" -> "ಮಿಷನ್ ವೇಪಾಯಿಂಟ್ $waypoint ನಲ್ಲಿ ವಿರಾಮಗೊಂಡಿದೆ"
            "ml" -> "മിഷൻ വേപോയിന്റ് $waypoint ൽ താൽക്കാലികമായി നിർത്തി"
            "gu" -> "મિશન વેપોઈન્ટ $waypoint પર રોકાયેલ છે"
            "as" -> "মিছন ৱেপইণ্ট $waypoint ত বিৰতি হৈছে"
            "bn" -> "মিশন ওয়েপয়েন্ট $waypoint এ বিরতি দেওয়া হয়েছে"
            "pa" -> "ਮਿਸ਼ਨ ਵੇਪੁਆਇੰਟ $waypoint ਤੇ ਰੁਕਿਆ ਹੋਇਆ ਹੈ"
            else -> "Mission paused at waypoint $waypoint"
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
     * @param languageCode language code like "en", "te", "hi", etc.
     */
    fun announceLanguageSelected(languageCode: String) {
        val message = when (languageCode) {
            "en" -> "English is selected"
            "te" -> "తెలుగు ఎంచుకున్నారు"
            "hi" -> "हिन्दी चुनी गई है"
            "mr" -> "मराठी निवडली आहे"
            "ta" -> "தமிழ் தேர்ந்தெடுக்கப்பட்டது"
            "kn" -> "ಕನ್ನಡ ಆಯ್ಕೆಯಾಗಿದೆ"
            "ml" -> "മലയാളം തിരഞ്ഞെടുത്തു"
            "gu" -> "ગુજરાતી પસંદ કરવામાં આવી છે"
            "as" -> "অসমীয়া বাছনি কৰা হৈছে"
            "bn" -> "বাংলা নির্বাচন করা হয়েছে"
            "pa" -> "ਪੰਜਾਬੀ ਚੁਣੀ ਗਈ ਹੈ"
            else -> "English is selected"
        }
        speakImmediate(message)
    }

    /**
     * Stops any ongoing speech
     */
    fun stop() {
        try {
            textToSpeech?.stop()
        } catch (e: Exception) {
            Timber.e(e, "stop failed")
        }
    }

    /**
     * Releases TTS resources
     */
    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Timber.e(e, "shutdown failed")
        }
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

        try {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Timber.e(e, "speakImmediate failed")
            isReady = false
            if (e is DeadObjectException || e.cause is DeadObjectException) {
                scheduleRetryInit()
            }
        }
    }

    private fun getMessage(key: String): String {
        val translations: Map<String, Map<String, String>> = mapOf(
            "connected" to mapOf(
                "en" to "Connected", "te" to "కనెక్ట్ అయింది", "hi" to "कनेक्ट हो गया",
                "mr" to "कनेक्ट झाले", "ta" to "இணைக்கப்பட்டது", "kn" to "ಸಂಪರ್ಕಗೊಂಡಿದೆ",
                "ml" to "കണക്ട് ചെയ്തു", "gu" to "કનેક્ટ થયું", "as" to "সংযোগ হৈছে",
                "bn" to "সংযুক্ত হয়েছে", "pa" to "ਕਨੈਕਟ ਹੋ ਗਿਆ"
            ),
            "disconnected" to mapOf(
                "en" to "Disconnected", "te" to "డిస్కనెక్ట్ అయింది", "hi" to "डिस्कनेक्ट हो गया",
                "mr" to "डिस्कनेक्ट झाले", "ta" to "துண்டிக்கப்பட்டது", "kn" to "ಸಂಪರ್ಕ ಕಡಿತಗೊಂಡಿದೆ",
                "ml" to "വിച്ഛേദിച്ചു", "gu" to "ડિસ્કનેક્ટ થયું", "as" to "বিচ্ছিন্ন হৈছে",
                "bn" to "সংযোগ বিচ্ছিন্ন হয়েছে", "pa" to "ਡਿਸਕਨੈਕਟ ਹੋ ਗਿਆ"
            ),
            "connection_failed" to mapOf(
                "en" to "Connection failed", "te" to "కనెక్షన్ విఫలమైంది", "hi" to "कनेक्शन विफल हो गया",
                "mr" to "कनेक्शन अयशस्वी", "ta" to "இணைப்பு தோல்வி", "kn" to "ಸಂಪರ್ಕ ವಿಫಲವಾಗಿದೆ",
                "ml" to "കണക്ഷൻ പരാജയപ്പെട്ടു", "gu" to "કનેક્શન નિષ્ફળ", "as" to "সংযোগ বিফল হৈছে",
                "bn" to "সংযোগ ব্যর্থ হয়েছে", "pa" to "ਕਨੈਕਸ਼ਨ ਅਸਫਲ ਹੋਇਆ"
            ),
            "calibration_started" to mapOf(
                "en" to "Calibration started", "te" to "కేలిబ్రేషన్ ప్రారంభమైంది", "hi" to "कैलिब्रेशन शुरू हो गया",
                "mr" to "कॅलिब्रेशन सुरू झाले", "ta" to "அளவுத்திருத்தம் தொடங்கியது", "kn" to "ಮಾಪನಾಂಕನ ಪ್ರಾರಂಭವಾಗಿದೆ",
                "ml" to "കാലിബ്രേഷൻ ആരംഭിച്ചു", "gu" to "કેલિબ્રેશન શરૂ થયું", "as" to "কেলিব্ৰেচন আৰম্ভ হৈছে",
                "bn" to "ক্যালিব্রেশন শুরু হয়েছে", "pa" to "ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਸ਼ੁਰੂ ਹੋ ਗਈ"
            ),
            "calibration_finished" to mapOf(
                "en" to "Calibration finished", "te" to "కేలిబ్రేషన్ ముగిసింది", "hi" to "कैलिब्रेशन समाप्त हो गया",
                "mr" to "कॅलिब्रेशन पूर्ण झाले", "ta" to "அளவுத்திருத்தம் முடிந்தது", "kn" to "ಮಾಪನಾಂಕನ ಮುಗಿದಿದೆ",
                "ml" to "കാലിബ്രേഷൻ പൂർത്തിയായി", "gu" to "કેલિબ્રેશન પૂર્ણ થયું", "as" to "কেলিব্ৰেচন সমাপ্ত হৈছে",
                "bn" to "ক্যালিব্রেশন সম্পন্ন হয়েছে", "pa" to "ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਪੂਰੀ ਹੋ ਗਈ"
            ),
            "calibration_success" to mapOf(
                "en" to "Calibration completed successfully", "te" to "కేలిబ్రేషన్ విజయవంతంగా పూర్తయింది",
                "hi" to "कैलिब्रेशन सफलतापूर्वक पूरा हुआ", "mr" to "कॅलिब्रेशन यशस्वीपणे पूर्ण झाले",
                "ta" to "அளவுத்திருத்தம் வெற்றிகரமாக முடிந்தது", "kn" to "ಮಾಪನಾಂಕನ ಯಶಸ್ವಿಯಾಗಿ ಪೂರ್ಣಗೊಂಡಿದೆ",
                "ml" to "കാലിബ്രേഷൻ വിജയകരമായി പൂർത്തിയായി", "gu" to "કેલિબ્રેશન સફળતાપૂર્વક પૂર્ણ થયું",
                "as" to "কেলিব্ৰেচন সফলভাৱে সম্পন্ন হৈছে", "bn" to "ক্যালিব্রেশন সফলভাবে সম্পন্ন হয়েছে",
                "pa" to "ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਸਫਲਤਾਪੂਰਵਕ ਪੂਰੀ ਹੋ ਗਈ"
            ),
            "calibration_failed" to mapOf(
                "en" to "Calibration failed", "te" to "కేలిబ్రేషన్ విఫలమైంది", "hi" to "कैलिब्रेशन विफल हो गया",
                "mr" to "कॅलिब्रेशन अयशस्वी", "ta" to "அளவுத்திருத்தம் தோல்வி", "kn" to "ಮಾಪನಾಂಕನ ವಿಫಲವಾಗಿದೆ",
                "ml" to "കാലിബ്രേഷൻ പരാജയപ്പെട്ടു", "gu" to "કેલિબ્રેશન નિષ્ફળ", "as" to "কেলিব্ৰেচন বিফল হৈছে",
                "bn" to "ক্যালিব্রেশন ব্যর্থ হয়েছে", "pa" to "ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਅਸਫਲ ਹੋ ਗਈ"
            ),
            "selected_automatic" to mapOf(
                "en" to "Selected automatic", "te" to "ఆటోమేటిక్ ఎంచుకున్నారు", "hi" to "ऑटोमैटिक चुना गया",
                "mr" to "ऑटोमॅटिक निवडले", "ta" to "தானியங்கி தேர்ந்தெடுக்கப்பட்டது", "kn" to "ಸ್ವಯಂಚಾಲಿತ ಆಯ್ಕೆಯಾಗಿದೆ",
                "ml" to "ഓട്ടോമാറ്റിക് തിരഞ്ഞെടുത്തു", "gu" to "ઓટોમેટિક પસંદ કરાયું", "as" to "স্বয়ংক্ৰিয় বাছনি কৰা হৈছে",
                "bn" to "স্বয়ংক্রিয় নির্বাচিত", "pa" to "ਆਟੋਮੈਟਿਕ ਚੁਣਿਆ ਗਿਆ"
            ),
            "selected_manual" to mapOf(
                "en" to "Selected manual", "te" to "మాన్యువల్ ఎంచుకున్నారు", "hi" to "मैनुअल चुना गया",
                "mr" to "मॅन्युअल निवडले", "ta" to "கைமுறை தேர்ந்தெடுக்கப்பட்டது", "kn" to "ಕೈಯಿಂದ ಆಯ್ಕೆಯಾಗಿದೆ",
                "ml" to "മാനുവൽ തിരഞ്ഞെടുത്തു", "gu" to "મેન્યુઅલ પસંદ કરાયું", "as" to "মেনুৱেল বাছনি কৰা হৈছে",
                "bn" to "ম্যানুয়াল নির্বাচিত", "pa" to "ਮੈਨੁਅਲ ਚੁਣਿਆ ਗਿਆ"
            ),
            "drone_armed" to mapOf(
                "en" to "Drone armed", "te" to "డ్రోన్ ఆర్మ్ అయింది", "hi" to "ड्रोन आर्म हो गया",
                "mr" to "ड्रोन आर्म झाले", "ta" to "ட்ரோன் ஆர்ம் ஆனது", "kn" to "ಡ್ರೋನ್ ಆರ್ಮ್ ಆಗಿದೆ",
                "ml" to "ഡ്രോൺ ആർമ്ഡ് ആയി", "gu" to "ડ્રોન આર્મ થયું", "as" to "ড্ৰোন আৰ্ম হৈছে",
                "bn" to "ড্রোন আর্মড হয়েছে", "pa" to "ਡ੍ਰੋਨ ਆਰਮ ਹੋ ਗਿਆ"
            ),
            "drone_disarmed" to mapOf(
                "en" to "Drone disarmed", "te" to "డ్రోన్ డిసార్మ్ అయింది", "hi" to "ड्रोन डिसआर्म हो गया",
                "mr" to "ड्रोन डिसआर्म झाले", "ta" to "ட்ரோன் டிசார்ம் ஆனது", "kn" to "ಡ್ರೋನ್ ಡಿಸಾರ್ಮ್ ಆಗಿದೆ",
                "ml" to "ഡ്രോൺ ഡിസ്ആർമ്ഡ് ആയി", "gu" to "ડ્રોન ડિસઆર્મ થયું", "as" to "ড্ৰোন ডিচআৰ্ম হৈছে",
                "bn" to "ড্রোন ডিসআর্মড হয়েছে", "pa" to "ਡ੍ਰੋਨ ਡਿਸਆਰਮ ਹੋ ਗਿਆ"
            ),
            "compass_calibration_started" to mapOf(
                "en" to "Compass calibration started", "te" to "కంపాస్ కేలిబ్రేషన్ ప్రారంభమైంది",
                "hi" to "कम्पास कैलिब्रेशन शुरू हो गया", "mr" to "कंपास कॅलिब्रेशन सुरू झाले",
                "ta" to "திசைகாட்டி அளவுத்திருத்தம் தொடங்கியது", "kn" to "ಕಂಪಾಸ್ ಮಾಪನಾಂಕನ ಪ್ರಾರಂಭವಾಗಿದೆ",
                "ml" to "കോമ്പസ് കാലിബ്രേഷൻ ആരംഭിച്ചു", "gu" to "કંપાસ કેલિબ્રેશન શરૂ થયું",
                "as" to "কম্পাছ কেলিব্ৰেচন আৰম্ভ হৈছে", "bn" to "কম্পাস ক্যালিব্রেশন শুরু হয়েছে",
                "pa" to "ਕੰਪਾਸ ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਸ਼ੁਰੂ ਹੋ ਗਈ"
            ),
            "compass_calibration_completed" to mapOf(
                "en" to "Compass calibration completed successfully", "te" to "కంపాస్ కేలిబ్రేషన్ విజయవంతంగా పూర్తయింది",
                "hi" to "कम्पास कैलिब्रेशन सफलतापूर्वक पूरा हुआ", "mr" to "कंपास कॅलिब्रेशन यशस्वीपणे पूर्ण झाले",
                "ta" to "திசைகாட்டி அளவுத்திருத்தம் வெற்றிகரமாக முடிந்தது", "kn" to "ಕಂಪಾಸ್ ಮಾಪನಾಂಕನ ಯಶಸ್ವಿಯಾಗಿ ಪೂರ್ಣಗೊಂಡಿದೆ",
                "ml" to "കോമ്പസ് കാലിബ്രേഷൻ വിജയകരമായി പൂർത്തിയായി", "gu" to "કંપાસ કેલિબ્રેશન સફળતાપૂર્વક પૂર્ણ થયું",
                "as" to "কম্পাছ কেলিব্ৰেচন সফলভাৱে সম্পন্ন হৈছে", "bn" to "কম্পাস ক্যালিব্রেশন সফলভাবে সম্পন্ন হয়েছে",
                "pa" to "ਕੰਪਾਸ ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਸਫਲਤਾਪੂਰਵਕ ਪੂਰੀ ਹੋ ਗਈ"
            ),
            "compass_calibration_failed" to mapOf(
                "en" to "Compass calibration failed", "te" to "కంపాస్ కేలిబ్రేషన్ విఫలమైంది",
                "hi" to "कम्पास कैलिब्रेशन विफल हो गया", "mr" to "कंपास कॅलिब्रेशन अयशस्वी",
                "ta" to "திசைகாட்டி அளவுத்திருத்தம் தோல்வி", "kn" to "ಕಂಪಾಸ್ ಮಾಪನಾಂಕನ ವಿಫಲವಾಗಿದೆ",
                "ml" to "കോമ്പസ് കാലിബ്രേഷൻ പരാജയപ്പെട്ടു", "gu" to "કંપાસ કેલિબ્રેશન નિષ્ફળ",
                "as" to "কম্পাছ কেলিব্ৰেচন বিফল হৈছে", "bn" to "কম্পাস ক্যালিব্রেশন ব্যর্থ হয়েছে",
                "pa" to "ਕੰਪਾਸ ਕੈਲੀਬ੍ਰੇਸ਼ਨ ਅਸਫਲ ਹੋ ਗਈ"
            ),
            "reboot_drone" to mapOf(
                "en" to "Please reboot your drone", "te" to "దయచేసి మీ డ్రోన్ రీబూట్ చేయండి",
                "hi" to "कृपया अपना ड्रोन रीबूट करें", "mr" to "कृपया आपले ड्रोन रीबूट करा",
                "ta" to "உங்கள் ட்ரோனை மறுதொடக்கம் செய்யவும்", "kn" to "ದಯವಿಟ್ಟು ನಿಮ್ಮ ಡ್ರೋನ್ ಅನ್ನು ರೀಬೂಟ್ ಮಾಡಿ",
                "ml" to "ദയവായി നിങ്ങളുടെ ഡ്രോൺ റീബൂട്ട് ചെയ്യുക", "gu" to "કૃપા કરીને તમારું ડ્રોન રીબૂટ કરો",
                "as" to "অনুগ্ৰহ কৰি আপোনাৰ ড্ৰোন ৰিবুট কৰক", "bn" to "অনুগ্রহ করে আপনার ড্রোন রিবুট করুন",
                "pa" to "ਕਿਰਪਾ ਕਰਕੇ ਆਪਣਾ ਡ੍ਰੋਨ ਰੀਬੂਟ ਕਰੋ"
            ),
            "mission_paused" to mapOf(
                "en" to "Mission paused", "te" to "మిషన్ పాజ్ అయింది", "hi" to "मिशन रुका हुआ है",
                "mr" to "मिशन थांबले आहे", "ta" to "பணி இடைநிறுத்தப்பட்டது", "kn" to "ಮಿಷನ್ ವಿರಾಮಗೊಂಡಿದೆ",
                "ml" to "മിഷൻ താൽക്കാലികമായി നിർത്തി", "gu" to "મિશન રોકાયેલ છે", "as" to "মিছন বিৰতি হৈছে",
                "bn" to "মিশন বিরতি দেওয়া হয়েছে", "pa" to "ਮਿਸ਼ਨ ਰੁਕਿਆ ਹੋਇਆ ਹੈ"
            ),
            "mission_resumed" to mapOf(
                "en" to "Mission resumed", "te" to "మిషన్ రిజ్యూమ్ అయింది", "hi" to "मिशन फिर से शुरू हुआ",
                "mr" to "मिशन पुन्हा सुरू झाले", "ta" to "பணி மீண்டும் தொடங்கியது", "kn" to "ಮಿಷನ್ ಪುನರಾರಂಭವಾಗಿದೆ",
                "ml" to "മിഷൻ പുനരാരംഭിച്ചു", "gu" to "મિશન ફરી શરૂ થયું", "as" to "মিছন পুনৰাৰম্ভ হৈছে",
                "bn" to "মিশন পুনরায় শুরু হয়েছে", "pa" to "ਮਿਸ਼ਨ ਦੁਬਾਰਾ ਸ਼ੁਰੂ ਹੋ ਗਿਆ"
            )
        )

        val langMap = translations[key] ?: return key
        return langMap[currentLanguage] ?: langMap["en"] ?: key
    }
}

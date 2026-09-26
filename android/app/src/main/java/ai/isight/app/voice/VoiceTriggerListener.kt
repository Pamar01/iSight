package ai.isight.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous hands-free voice trigger listener for "Hey Ice" (and aliases "Ice", "Hey iSight").
 *
 * Runs a continuous, low-latency SpeechRecognizer loop in the background while the app is
 * in the foreground. When a wake word is detected, [onTrigger] is invoked with the raw utterance
 * (which may contain just the wake phrase, e.g. "hey ice", or a combined utterance, e.g.
 * "hey ice find my keys").
 *
 * Respects [isMuted] to avoid self-triggering while TTS is speaking.
 */
class VoiceTriggerListener(
    private val context: Context,
    private val isMuted: () -> Boolean = { false },
    private val onTrigger: (rawUtterance: String) -> Unit,
) {
    companion object {
        private const val TAG = "iSight/voiceTrigger"

        private val WAKE_PATTERNS = listOf(
            Regex("""\b(hey|hi|ok)\s+(ice|eyes|isight|i\s*sight)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(ice|isight)\b""", RegexOption.IGNORE_CASE),
        )

        fun containsWakeWord(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val t = text.lowercase().trim()
            return WAKE_PATTERNS.any { it.containsMatchIn(t) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile private var isListening = false
    private var lastTriggerMs = 0L

    val isActive: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        mainHandler.post { initAndListen() }
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { destroyRecognizer() }
    }

    private fun initAndListen() {
        if (!running) return
        try {
            destroyRecognizer()
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "SpeechRecognizer not available on this device")
                return
            }

            val rec = if (Build.VERSION.SDK_INT >= 33 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    Log.d(TAG, "SpeechRecognizer onError: $error")
                    if (running) {
                        val delay = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 600L
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                running = false
                                return
                            }
                            else -> 400L
                        }
                        scheduleRestart(delay)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    checkMatches(matches)
                    if (running) {
                        scheduleRestart(200L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    checkMatches(partials)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer = rec
            startListeningInternal()
        } catch (t: Throwable) {
            Log.w(TAG, "initAndListen failed: ${t.message}")
            scheduleRestart(1000L)
        }
    }

    private fun startListeningInternal() {
        if (!running) return
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            rec.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListeningInternal failed: ${t.message}")
            scheduleRestart(500L)
        }
    }

    private fun checkMatches(matches: List<String>?) {
        if (isMuted() || matches.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < 1500L) return

        for (candidate in matches) {
            if (containsWakeWord(candidate)) {
                lastTriggerMs = now
                Log.i(TAG, "Wake word triggered with: \"$candidate\"")
                onTrigger(candidate)
                break
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (running) {
                if (recognizer == null) {
                    initAndListen()
                } else {
                    startListeningInternal()
                }
            }
        }, delayMs)
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Throwable) {}
        recognizer = null
        isListening = false
    }
}

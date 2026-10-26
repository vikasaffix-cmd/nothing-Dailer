package com.example.allinonedailer.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Speech-to-text for voicemail files with Android's on-device recogniser
 * (Android 13+): the file is decoded to 16 kHz mono PCM and fed to
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer] as its audio source.
 * Nothing leaves the phone and the microphone isn't used, though Android
 * still asks for the microphone permission.
 */
class OnDeviceTranscriber(private val context: Context) {

    sealed interface Outcome {
        data class Text(val text: String, val language: String) : Outcome
        data object NoSpeech : Outcome
        data object Unsupported : Outcome
        data class NoLanguagePack(val language: String) : Outcome
        data object NeedsPermission : Outcome
        data object Failed : Outcome
    }

    /** [language] is "en", "hi", "mr", or null to detect (Android 14+) or use the app's language. */
    suspend fun transcribe(uri: Uri, language: String?): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isSupported(context)) return Outcome.Unsupported
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Outcome.NeedsPermission
        }
        // One recognition at a time: the batch and a retry may overlap.
        return LOCK.withLock {
            val pcm = File(context.cacheDir, "voicemail_transcribe.pcm")
            try {
                val ok = withContext(Dispatchers.IO) { PcmDecoder.decode(context, uri, pcm, SAMPLE_RATE, MAX_SECONDS) }
                when {
                    !ok -> Outcome.Failed
                    pcm.length() < SAMPLE_RATE -> Outcome.NoSpeech // under half a second
                    else -> withContext(Dispatchers.Main) { recognize(pcm, language) }
                }
            } finally {
                pcm.delete()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognize(pcm: File, language: String?): Outcome {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val primary = tagFor(language ?: appLanguage())
            val probe = baseIntent(primary)
            val support = checkSupport(recognizer, probe)
            val installed = support?.installedOnDeviceLanguages.orEmpty()
            val listed = installed + support?.supportedOnDeviceLanguages.orEmpty() + support?.pendingOnDeviceLanguages.orEmpty()

            var tag = primary
            var detect = emptyList<String>()
            // Some recognisers report nothing; then just try.
            if (listed.isNotEmpty()) {
                if (language == null) {
                    val ready = CANDIDATES.filter { c -> installed.any { sameLanguage(it, c) } }
                    if (ready.isEmpty()) {
                        recognizer.triggerModelDownload(probe)
                        return Outcome.NoLanguagePack(languageOf(primary))
                    }
                    if (ready.none { sameLanguage(it, primary) }) tag = ready.first()
                    detect = ready
                } else if (installed.none { sameLanguage(it, primary) }) {
                    if (listed.any { sameLanguage(it, primary) }) recognizer.triggerModelDownload(probe)
                    return Outcome.NoLanguagePack(languageOf(primary))
                }
            }

            val intent = baseIntent(tag).apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                // Keep going through pauses until the file ends.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && detect.size > 1) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(detect))
                }
            }

            val text = StringBuilder()
            var detected: String? = null
            val seconds = pcm.length() / (2L * SAMPLE_RATE)
            val error = withTimeoutOrNull((seconds * 2 + 30) * 1000) {
                listen(recognizer, intent, onText = { segment ->
                    if (segment.isNotBlank()) {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(segment.trim())
                    }
                }, onLanguage = { detected = it })
            } ?: TIMED_OUT

            val spoken = languageOf(detected ?: tag)
            return when {
                text.isNotEmpty() -> Outcome.Text(text.toString(), spoken)
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Outcome.NeedsPermission
                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Outcome.NoLanguagePack(spoken)
                error == NO_ERROR || error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Outcome.NoSpeech
                else -> Outcome.Failed
            }
        } finally {
            runCatching { recognizer.destroy() }
            runCatching { pfd.close() }
        }
    }

    /** Waits for the session to end; returns the error code, or [NO_ERROR]. */
    private suspend fun listen(
        recognizer: SpeechRecognizer,
        intent: Intent,
        onText: (String) -> Unit,
        onLanguage: (String) -> Unit
    ): Int = suspendCancellableCoroutine { cont ->
        fun finish(code: Int) {
            if (cont.isActive) cont.resume(code)
        }
        fun first(results: Bundle?) =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segmentResults: Bundle) {
                first(segmentResults)?.let(onText)
            }
            override fun onEndOfSegmentedSession() = finish(NO_ERROR)
            override fun onResults(results: Bundle?) {
                first(results)?.let(onText)
                finish(NO_ERROR)
            }
            override fun onError(error: Int) = finish(error)
            override fun onLanguageDetection(results: Bundle) {
                results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let(onLanguage)
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(intent)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkSupport(recognizer: SpeechRecognizer, intent: Intent): RecognitionSupport? =
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (cont.isActive) cont.resume(recognitionSupport)
                    }
                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }

    private fun baseIntent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** The app's language when it's Hindi or Marathi, else English. */
    private fun appLanguage(): String {
        val lang = context.resources.configuration.locales[0]?.language ?: Locale.getDefault().language
        return if (lang == "hi" || lang == "mr") lang else "en"
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SECONDS = 600
        private const val NO_ERROR = 0
        private const val TIMED_OUT = -1
        private val LOCK = Mutex()
        private val CANDIDATES = listOf("en-IN", "hi-IN", "mr-IN")

        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        fun tagFor(language: String): String = when (language) {
            "hi" -> "hi-IN"
            "mr" -> "mr-IN"
            else -> "en-IN"
        }

        private fun languageOf(tag: String) = Locale.forLanguageTag(tag).language.ifEmpty { "en" }

        private fun sameLanguage(a: String, b: String) = languageOf(a) == languageOf(b)
    }
}

/** Decodes an audio file to raw 16-bit little-endian mono PCM at a fixed rate. */
internal object PcmDecoder {

    fun decode(context: Context, uri: Uri, out: File, rate: Int, maxSeconds: Int): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), rate)
            val limit = rate.toLong() * maxSeconds
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            BufferedOutputStream(FileOutputStream(out)).use { os ->
                val sink = PcmSink(os)
                while (sink.samples < limit) {
                    if (!inputDone) {
                        val i = decoder.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = decoder.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = decoder.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        resampler = Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), rate)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (o >= 0) {
                        val buf = decoder.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mixDown(buf.slice().order(ByteOrder.nativeOrder()), channels, encoding) { resampler.push(it, sink::write) }
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                sink.flush()
            }
            out.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Averages the channels of each frame to one 16-bit sample. */
    private inline fun mixDown(buf: ByteBuffer, channels: Int, encoding: Int, emit: (Int) -> Unit) {
        val ch = channels.coerceAtLeast(1)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val f = buf.asFloatBuffer()
            while (f.remaining() >= ch) {
                var sum = 0f
                repeat(ch) { sum += f.get() }
                emit(((sum / ch).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt())
            }
        } else {
            val s = buf.asShortBuffer()
            while (s.remaining() >= ch) {
                var sum = 0
                repeat(ch) { sum += s.get() }
                emit(sum / ch)
            }
        }
    }

    /** Streaming rate change: box average when going down, linear when going up. */
    private class Resampler(inRate: Int, outRate: Int) {
        private val step = inRate.toDouble() / outRate
        private var next = 0.0
        private var index = 0L
        private var prev = 0
        private var sum = 0L
        private var count = 0

        fun push(sample: Int, emit: (Int) -> Unit) {
            if (step > 1.0) {
                sum += sample
                count++
                if (index + 1 >= next) {
                    emit((sum / count).toInt())
                    sum = 0
                    count = 0
                    next += step
                }
            } else {
                while (next <= index) {
                    val frac = next - (index - 1)
                    emit(if (index == 0L) sample else (prev + (sample - prev) * frac).toInt())
                    next += step
                }
            }
            prev = sample
            index++
        }
    }

    private class PcmSink(private val os: OutputStream) {
        private val bytes = ByteArray(8192)
        private var n = 0
        var samples = 0L
            private set

        fun write(sample: Int) {
            val v = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[n++] = (v and 0xFF).toByte()
            bytes[n++] = (v shr 8 and 0xFF).toByte()
            samples++
            if (n == bytes.size) flush()
        }

        fun flush() {
            os.write(bytes, 0, n)
            n = 0
        }
    }
}

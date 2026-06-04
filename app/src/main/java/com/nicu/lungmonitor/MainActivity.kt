package com.nicu.lungmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false

    companion object {
        private const val SAMPLE_RATE  = 48000
        private const val CHUNK_FRAMES = 4096   // frames per chunk (× 2 = stereo samples)
        private const val PERM_CODE    = 1
    }

    // ── JavascriptInterface bridge ────────────────────────────────────────────

    inner class AudioBridge {

        /** Called from JS on setup screen — returns USB device name or "none" */
        @JavascriptInterface
        fun getUsbDeviceName(): String {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val usbTypes = setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY
            )
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type in usbTypes }
                ?.productName?.toString()
                ?: "none"
        }

        /** Called from JS — starts AudioRecord and streams PCM to WebView */
        @JavascriptInterface
        fun startRecording() {
            if (isRecording) return

            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val usbTypes = setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY
            )
            val usbDev = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type in usbTypes }

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufBytes = maxOf(minBuf, CHUNK_FRAMES * 2 * 4 * 8) // 8× safety

            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .build()

            // Route to USB device if available; otherwise use default input
            usbDev?.let { rec.preferredDevice = it }

            rec.startRecording()
            audioRecord = rec
            isRecording = true

            Thread {
                val pcm     = FloatArray(CHUNK_FRAMES * 2) // interleaved L,R,L,R,...
                val byteBuf = ByteBuffer.allocate(pcm.size * 4).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }

                while (isRecording) {
                    val read = rec.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        // Encode as Base64 for efficient JS string transfer
                        byteBuf.clear()
                        for (i in 0 until read) byteBuf.putFloat(pcm[i])
                        val b64 = Base64.encodeToString(
                            byteBuf.array(), 0, read * 4, Base64.NO_WRAP
                        )
                        // Deliver to JS: window.onNativeAudio(base64String, sampleCount)
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.onNativeAudio('$b64',$read)", null
                            )
                        }
                    }
                }
            }.start()
        }

        /** Called from JS — stops the AudioRecord thread */
        @JavascriptInterface
        fun stopRecording() {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(true)   // remove for production

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled              = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess                = true
                allowContentAccess             = true
                domStorageEnabled              = true
            }
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            webViewClient = WebViewClient()
            addJavascriptInterface(AudioBridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)

        // Request RECORD_AUDIO permission at startup
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            // Notify JS so the setup screen can refresh USB status
            webView.evaluateJavascript("window.onPermissionResult && window.onPermissionResult($granted)", null)
        }
    }

    override fun onDestroy() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        super.onDestroy()
    }
}

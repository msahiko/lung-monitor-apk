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
import android.util.Log
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
        private const val CHUNK_FRAMES = 4096
        private const val PERM_CODE    = 1
        private const val TAG          = "LungMonitor"
    }

    inner class AudioBridge {

        /** 内蔵マイク以外の入力デバイスを検出（USB・有線問わず） */
        @JavascriptInterface
        fun getUsbDeviceName(): String {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val builtIn = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            )
            val all = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            Log.d(TAG, "All inputs: ${all.map { "${it.productName}(type=${it.type})" }}")
            val ext = all.firstOrNull { it.type !in builtIn }
            return ext?.productName?.toString() ?: "none"
        }

        /** AudioRecordを開始（setPreferredDeviceなし→Androidが自動ルーティング） */
        @JavascriptInterface
        fun startRecording() {
            if (isRecording) return

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufBytes = maxOf(minBuf, CHUNK_FRAMES * 2 * 4 * 8)

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

            Log.d(TAG, "AudioRecord state=${rec.state}, routedDevice=${rec.routedDevice?.productName}")

            rec.startRecording()
            audioRecord = rec
            isRecording = true

            Thread {
                val pcm    = FloatArray(CHUNK_FRAMES * 2)
                val bbuf   = ByteBuffer.allocate(pcm.size * 4).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                while (isRecording) {
                    val read = rec.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        bbuf.clear()
                        for (i in 0 until read) bbuf.putFloat(pcm[i])
                        val b64 = Base64.encodeToString(
                            bbuf.array(), 0, read * 4, Base64.NO_WRAP
                        )
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.onNativeAudio('$b64',$read)", null
                            )
                        }
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun stopRecording() {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled               = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess                 = true
                allowContentAccess              = true
                domStorageEnabled               = true
            }
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            webViewClient = WebViewClient()
            addJavascriptInterface(AudioBridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)

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
            webView.evaluateJavascript(
                "window.onPermissionResult && window.onPermissionResult($granted)", null
            )
        }
    }

    override fun onDestroy() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        super.onDestroy()
    }
}


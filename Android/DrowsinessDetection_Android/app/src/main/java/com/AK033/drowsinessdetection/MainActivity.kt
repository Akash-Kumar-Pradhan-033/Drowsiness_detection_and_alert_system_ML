package com.AK033.drowsinessdetection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.AK033.drowsinessdetection.databinding.ActivityMainBinding
import com.google.common.util.concurrent.ListenableFuture
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import timber.log.Timber
import android.util.Size
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var ortSession: OrtSession
    private lateinit var ortEnvironment: OrtEnvironment
    private lateinit var toneGenerator: ToneGenerator
    private var beepThread: Thread? = null
    private var stopBeep = false

    companion object {
        private const val ALERT_THRESHOLD = 1.3
        private const val CALL_THRESHOLD = 5.0
        private const val BEEP_FREQUENCY = 1000
        private const val BEEP_DURATION = 500
        private const val MODEL_INPUT_SIZE = 64
        private const val PUSHBULLET_API_KEY = "o.ISl7vFswIo0No5HaWk4OJN0WXYtpXNSs"
        private const val TELEGRAM_BOT_TOKEN = "7906985562:AAHxuXOT6lS2i_ipuuJ9AuLIupe0mmvTfQw"
        private const val TELEGRAM_CHAT_ID = "5036175022"
    }

    private var drowsyStartTime: Long = 0
    private var alertTriggered = false
    private var telegramAlertSent = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeModelAndCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Timber.plant(Timber.DebugTree())
        cameraExecutor = Executors.newSingleThreadExecutor()
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

        binding.stopButton.setOnClickListener {
            stopEverythingAndClose()
        }

        if (checkCameraPermission()) {
            initializeModelAndCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun stopEverythingAndClose() {
        stopContinuousBeep()
        toneGenerator.release()
        cameraExecutor.shutdown()
        try {
            ortSession.close()
            ortEnvironment.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing ONNX resources")
        }
        finish()
    }

    private fun checkCameraPermission() =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun initializeModelAndCamera() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ortEnvironment = OrtEnvironment.getEnvironment()
                val modelFile = assets.open("drowsiness_model.onnx").readBytes()
                ortSession = ortEnvironment.createSession(modelFile)

                withContext(Dispatchers.Main) {
                    startCamera()
                }
            } catch (e: Exception) {
                Timber.e(e, "Model initialization failed")
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to initialize model",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            analyzeImage(imageProxy)
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalyzer
                )

            } catch (exc: Exception) {
                Timber.e(exc, "Camera initialization failed")
                Toast.makeText(this@MainActivity, "Camera initialization failed", Toast.LENGTH_LONG)
                    .show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val inputArray = preprocessImage(bitmap)
            val output = runInference(inputArray)
            val isDrowsy = output[1] > 0.5
            updateUI(isDrowsy, output[1])

            if (isDrowsy) {
                handleDrowsyState()
            } else {
                resetDrowsyState()
            }
        } catch (e: Exception) {
            Timber.e(e, "Image analysis failed")
        } finally {
            imageProxy.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer: ByteBuffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun preprocessImage(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        val floatArray = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)

        for (y in 0 until MODEL_INPUT_SIZE) {
            for (x in 0 until MODEL_INPUT_SIZE) {
                val pixel = resized.getPixel(x, y)
                floatArray[y * MODEL_INPUT_SIZE + x] = (
                        Color.red(pixel) * 0.299f +
                                Color.green(pixel) * 0.587f +
                                Color.blue(pixel) * 0.114f
                        ) / 255.0f
            }
        }
        resized.recycle()
        return floatArray
    }

    private fun runInference(inputArray: FloatArray): FloatArray {
        return try {
            // Create a FloatBuffer from the array
            val floatBuffer = FloatBuffer.wrap(inputArray)

            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                floatBuffer,
                longArrayOf(1, 1, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
            )

            ortSession.run(mapOf("input" to inputTensor)).use { results ->
                (results[0].value as Array<FloatArray>)[0]
            }
        } catch (e: Exception) {
            Timber.e(e, "Inference failed")
            floatArrayOf(0f, 1f) // Fallback values
        }
    }

    private fun updateUI(isDrowsy: Boolean, confidence: Float) {
        runOnUiThread {
            if (isDrowsy) {
                binding.statusText.text = "DROWSY (${(confidence * 100).toInt()}%)"
                binding.statusText.setTextColor(Color.RED)
            } else {
                binding.statusText.text = "ALERT (${(confidence * 100).toInt()}%)"
                binding.statusText.setTextColor(Color.GREEN)
            }
        }
    }

    private fun handleDrowsyState() {
        val currentTime = System.currentTimeMillis()
        if (drowsyStartTime == 0L) {
            drowsyStartTime = currentTime
        }

        val duration = (currentTime - drowsyStartTime) / 1000.0
        runOnUiThread {
            binding.durationText.text = "Duration: %.1fs".format(duration)
        }

        if (duration >= ALERT_THRESHOLD && !alertTriggered) {
            alertTriggered = true
            startContinuousBeep()
            sendPushNotification()
        }

        if (duration >= CALL_THRESHOLD && !telegramAlertSent) {
            telegramAlertSent = true
            sendTelegramAlert()
        }
    }

    private fun resetDrowsyState() {
        drowsyStartTime = 0
        if (alertTriggered || telegramAlertSent) {
            stopContinuousBeep()
            alertTriggered = false
            telegramAlertSent = false
        }
        runOnUiThread {
            binding.durationText.text = ""
        }
    }

    private fun startContinuousBeep() {
        stopBeep = false
        beepThread = Thread {
            while (!stopBeep) {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_DURATION)
                Thread.sleep((BEEP_DURATION + 500).toLong())
            }
        }.also { it.start() }
    }

    private fun stopContinuousBeep() {
        stopBeep = true
        beepThread?.join()
        beepThread = null
    }

    private fun sendPushNotification() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val location = getGpsLocation()
                val message = if (location != "Unknown location") {
                    "Driver is drowsy! Location: $location"
                } else {
                    // Fallback to IP-based approximate location
                    val ipLocation = getApproximateLocationFromIP()
                    "Driver is drowsy! Location: $ipLocation"
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val json = """
                {
                    "type": "note",
                    "title": "Drowsiness Alert!",
                    "body": "$message"
                }
            """.trimIndent()

                val request = Request.Builder()
                    .url("https://api.pushbullet.com/v2/pushes")
                    .addHeader("Access-Token", PUSHBULLET_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.e("Pushbullet API error: ${response.code}")
                    } else {
                        Timber.d("Notification sent with location: $message")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send push notification")
            }
        }
    }

    private suspend fun getGpsLocation(): String = withContext(Dispatchers.IO) {
        try {
            // First try to get precise location if permissions are available
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                location?.let {
                    return@withContext "https://maps.google.com/?q=${it.latitude},${it.longitude}"
                }
            }
            return@withContext "Unknown location"
        } catch (e: Exception) {
            Timber.e(e, "Error getting GPS location")
            return@withContext "Unknown location"
        }
    }



    private fun sendTelegramAlert() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val location = getGpsLocation()
                val message = """
                    🚨 *DROWSINESS ALERT!* 🚨
                    
                    ⚠️ Driver has been drowsy for **${CALL_THRESHOLD} seconds!**
                    ${if (location != "Unknown location") "📍 Location: [Google Maps]($location)" else ""}
                    
                    Please check on them immediately!
                """.trimIndent()

                val client = OkHttpClient()
                val url = "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage"
                val json = """
                    {
                        "chat_id": "$TELEGRAM_CHAT_ID",
                        "text": "$message",
                        "parse_mode": "Markdown"
                    }
                """.trimIndent()

                val request = Request.Builder()
                    .url(url)
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.e("Telegram API error: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Telegram alert failed")
            }
        }
    }

    private suspend fun getApproximateLocationFromIP(): String = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://ip-api.com/json/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    if (json != null && json.contains("\"status\":\"success\"")) {
                        val lat = json.substringAfter("\"lat\":").substringBefore(",").trim()
                        val lon = json.substringAfter("\"lon\":").substringBefore(",").trim()
                        val city = json.substringAfter("\"city\":\"").substringBefore("\"").trim()
                        val country = json.substringAfter("\"country\":\"").substringBefore("\"").trim()

                        return@withContext if (lat.isNotEmpty() && lon.isNotEmpty()) {
                            "https://maps.google.com/?q=$lat,$lon (Approximate - $city, $country)"
                        } else {
                            "Approximate location: $city, $country"
                        }
                    }
                }
                return@withContext "Location unavailable"
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting IP-based location")
            return@withContext "Location unavailable"
        }
    }
}
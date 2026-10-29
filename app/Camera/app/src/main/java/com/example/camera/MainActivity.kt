package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Serializable
data class PhotoRecord(
    val image_url: String,
    val latitude: Double,
    val longitude: Double,
    val capture_time: String
)

val supabase = createSupabaseClient(
    supabaseUrl = "https://qjitjsfzllsrsxgkktnq.supabase.co",
    supabaseKey = "sb_publishable_fF1gCg_kqtka_Rkie4lrRg_jvvsyoGt"
) {
    install(Storage)
    install(Postgrest) // Added Postgrest
}

class MainActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private lateinit var menuLayout: View
    private lateinit var cameraLayout: View
    private lateinit var guideLayout: View

    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.example.camera.USB_PERMISSION"

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false || 
                              permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (cameraGranted && locationGranted) { 
            showCamera()
        } else { 
            Toast.makeText(this, "No permission to operate the Camera or Location!", Toast.LENGTH_SHORT).show() 
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            // Permission granted, let's try connecting again
                            checkEsp32Moisture()
                        }
                    } else {
                        Toast.makeText(context, "USB Permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        menuLayout = findViewById(R.id.menuLayout)
        cameraLayout = findViewById(R.id.cameraLayout)
        guideLayout = findViewById(R.id.guideLayout)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // USB Setup
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Setup Main Menu Buttons
        findViewById<Button>(R.id.btnOpenCamera).setOnClickListener {
            // Check permissions
            val permissionsToRequest = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
    
            if (permissionsToRequest.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                showCamera()
            } else {
                requestPermissionLauncher.launch(permissionsToRequest)
            }
        }
        
        findViewById<Button>(R.id.btnConnectEsp32).setOnClickListener {
            showGuide()
        }

        // Setup Guide Buttons
        findViewById<Button>(R.id.btnGuideBack).setOnClickListener {
            guideLayout.visibility = View.GONE
            menuLayout.visibility = View.VISIBLE
        }
        
        findViewById<Button>(R.id.btnGuideContinue).setOnClickListener {
            // Trigger the photo taking behavior of "care a tree" as requested
            showCamera()
            
            // Note: We also trigger the ESP32 check in the background just in case it's still needed
            checkEsp32Moisture()
        }

        // Setup Camera Buttons
        findViewById<Button>(R.id.captureButton).setOnClickListener { getMetadataAndShoot() }
        findViewById<Button>(R.id.btnBackToMenu).setOnClickListener {
            cameraLayout.visibility = View.GONE
            menuLayout.visibility = View.VISIBLE
            // Note: In a production app, you might want to unbind the camera lifecycle here to save battery
        }
    }

    private fun showGuide() {
        menuLayout.visibility = View.GONE
        guideLayout.visibility = View.VISIBLE
    }

    private fun showCamera() {
        menuLayout.visibility = View.GONE
        cameraLayout.visibility = View.VISIBLE
        startCamera()
    }
    
    private fun checkEsp32Moisture() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "No ESP32 connected via USB!", Toast.LENGTH_SHORT).show()
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        // Request permission if not already granted
        if (!usbManager.hasPermission(device)) {
            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Toast.makeText(this, "Failed to open connection to USB device", Toast.LENGTH_SHORT).show()
            return
        }

        val port = driver.ports[0] // Most devices only have one port

        CoroutineScope(Dispatchers.IO).launch {
            try {
                port.open(connection)
                // Set parameters (115200 baud, 8 data bits, 1 stop bit, no parity)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                
                // IMPORTANT: Setting DTR and RTS is required for most ESP32 boards to communicate over USB
                port.dtr = true
                port.rts = true

                // Give the ESP32 time to reboot and finish its setup(). 
                // Opening the USB connection causes the ESP32 to reset. 
                // Since your ESP32 setup() has a delay(1000), we must wait longer than that!
                delay(2000)

                // Send a request command over USB
                val requestCommand = "GET\n".toByteArray()
                port.write(requestCommand, 1000) // 1000ms timeout

                // Read the response from the ESP32
                delay(100) // Give the ESP32 time to respond
                val buffer = ByteArray(256)
                val numBytesRead = port.read(buffer, 2000) // 2000ms timeout
                val response = String(buffer, 0, numBytesRead).trim()

                val moisture = response.toFloatOrNull() ?: 0f
                
                withContext(Dispatchers.Main) {
                    if (moisture > 80f) {
                        Toast.makeText(this@MainActivity, "WARNING: Soil moisture is at ${moisture}%!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Soil moisture is at ${moisture}%", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("USB", "Error communicating with ESP32 via USB", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to read from ESP32. Check USB connection.", Toast.LENGTH_LONG).show()
                }
            } finally {
                try { port.close() } catch (e: Exception) {}
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("CameraX", "Failure in binding the Camera", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission") // Ensuring user has granted location permission before execution
    private fun getMetadataAndShoot() {
        // 1. Get system time
        val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        // 2. Get GPS coordinates
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            val lat = location?.latitude ?: 0.0
            val lng = location?.longitude ?: 0.0

            // 3. Bring these data to take a photo or upload
            takePhoto(lat, lng, timeStamp)
        }
    }

    private fun takePhoto(lat: Double, lng: Double, timeStamp: String) {
        val imageCapture = imageCapture ?: return
        
        // Create a temp file to hold the photo
        val photoFile = File(externalMediaDirs.firstOrNull(), "${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions, 
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Failure in taking photos: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Action Completed！Uploading to the server...", Toast.LENGTH_SHORT).show()
                    
                    // Switch to Background upload, so you can immediately take the next one
                    CoroutineScope(Dispatchers.IO).launch {
                        uploadToSupabase(photoFile, lat, lng, timeStamp)
                    }
                }
            }
        )
    }

    private suspend fun uploadToSupabase(file: File, lat: Double, lng: Double, timeStamp: String) {
        withContext(Dispatchers.IO) {
            try {
                val bucket = supabase.storage.from("photos") // Fill in the bucket name you created earlier
                val fileName = "uploads/${file.name}" // Storage path, e.g. uploads/1234567.jpg
                
                // 1. Upload the File to Storage
                bucket.upload(fileName, file.readBytes()) { 
                    upsert = false 
                }
                
                // 2. Get the Public URL back
                val publicUrl = bucket.publicUrl(fileName)
                
                // 3. Prepare the data to be written to Database
                val newRecord = PhotoRecord(
                    image_url = publicUrl,
                    latitude = lat,
                    longitude = lng,
                    capture_time = timeStamp
                )
                
                // 4. Formally insert into photo_records table
                supabase.postgrest["photo_records"].insert(newRecord)
                
                Log.d("Supabase", "Successfully uploaded and written to Database！ URL: $publicUrl")
                
            } catch (e: Exception) {
                Log.e("Supabase", "Upload failed", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        unregisterReceiver(usbReceiver)
    }
}
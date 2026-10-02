package com.example.photoenhancer

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var original: Bitmap? = null
    private var result: Bitmap? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var btnEnhance: Button
    private lateinit var btnSave: Button

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.openInputStream(uri)?.use { original = BitmapFactory.decodeStream(it) }
            result = null
            image.setImageBitmap(original)
            btnEnhance.isEnabled = true
            btnSave.isEnabled = false
            status.text = "Photo ready. Tap Enhance."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "Choose a photo"; gravity = Gravity.CENTER; setPadding(0, 16, 0, 16) }
        root.addView(status)
        val pick = Button(this).apply { text = "Choose photo"; setOnClickListener { picker.launch("image/*") } }
        btnEnhance = Button(this).apply { text = "Enhance"; isEnabled = false; setOnClickListener { enhance() } }
        btnSave = Button(this).apply { text = "Save to gallery"; isEnabled = false; setOnClickListener { save() } }
        root.addView(pick); root.addView(btnEnhance); root.addView(btnSave)
        setContentView(root)
    }

    private fun enhance() {
        val src = original ?: return
        btnEnhance.isEnabled = false
        status.text = "Enhancing... 0%"
        thread {
            try {
                val out = Enhancer(this).enhance(src) { p -> ui.post { status.text = "Enhancing... $p%" } }
                result = out
                ui.post {
                    image.setImageBitmap(out)
                    status.text = "Done!"
                    btnSave.isEnabled = true
                    btnEnhance.isEnabled = true
                }
            } catch (e: Throwable) {
                ui.post { status.text = "Error: ${e.message}"; btnEnhance.isEnabled = true }
            }
        }
    }

    private fun save() {
        val bmp = result ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "enhanced_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoEnhancer")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, "Saved to Pictures/PhotoEnhancer", Toast.LENGTH_LONG).show()
        }
    }
}

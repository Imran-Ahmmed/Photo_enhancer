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
                val enhanced = Enhancer(this).enhance(src) { p -> ui.post { status.text = "Enhancing... $p%" } }
                ui.post { status.text = "Finishing colors..." }
                val lifted = shadowLift(enhanced)
                val out = postProcess(lifted, autoGamma(lifted))
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

// অন্ধকার অংশ বেশি উজ্জ্বল করে, উজ্জ্বল অংশ প্রায় একই রাখে (মুখ আলো করার জন্য)
fun shadowLift(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height

    // ছোট আকারের luminance map (এলাকাভিত্তিক গড় আলো)
    val sw = maxOf(2, w / 32)
    val sh = maxOf(2, h / 32)
    val small = Bitmap.createScaledBitmap(src, sw, sh, true)
    val sp = IntArray(sw * sh)
    small.getPixels(sp, 0, sw, 0, 0, sw, sh)
    val lm = FloatArray(sw * sh) { i ->
        val p = sp[i]
        (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
    }
    small.recycle()

    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val rowsPerChunk = maxOf(1, 1_000_000 / w)
    val buf = IntArray(w * rowsPerChunk)
    var y = 0
    while (y < h) {
        val rows = minOf(rowsPerChunk, h - y)
        out.getPixels(buf, 0, w, 0, y, w, rows)
        for (ry in 0 until rows) {
            val fy = ((y + ry) * (sh - 1).toFloat() / (h - 1).coerceAtLeast(1))
            val y0 = fy.toInt().coerceIn(0, sh - 2)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = x * (sw - 1).toFloat() / (w - 1).coerceAtLeast(1)
                val x0 = fx.toInt().coerceIn(0, sw - 2)
                val tx = fx - x0
                val a = lm[y0 * sw + x0] * (1 - tx) + lm[y0 * sw + x0 + 1] * tx
                val b = lm[(y0 + 1) * sw + x0] * (1 - tx) + lm[(y0 + 1) * sw + x0 + 1] * tx
                val local = (a * (1 - ty) + b * ty).coerceAtLeast(0.03f)

                val gain = Math.pow((0.5 / local).toDouble(), 0.75).toFloat().coerceIn(1f, 2.5f)

                val idx = ry * w + x
                val p = buf[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val bl = p and 0xFF
                val pl = (0.299f * r + 0.587f * g + 0.114f * bl) / 255f
                val eff = 1f + (gain - 1f) * (1f - pl)

                val nr = (r * eff).toInt().coerceIn(0, 255)
                val ng = (g * eff).toInt().coerceIn(0, 255)
                val nb = (bl * eff).toInt().coerceIn(0, 255)
                buf[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        out.setPixels(buf, 0, w, 0, y, w, rows)
        y += rows
    }
    return out
}

// ছবির গড় আলো দেখে gamma ঠিক করে (অন্ধকার ছবি বেশি উজ্জ্বল হবে, ভালো ছবি প্রায় একই থাকবে)
fun autoGamma(src: Bitmap): Float {
    var sum = 0.0
    var n = 0
    val step = maxOf(8, minOf(src.width, src.height) / 200)
    var y = 0
    while (y < src.height) {
        var x = 0
        while (x < src.width) {
            val p = src.getPixel(x, y)
            val l = 0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)
            sum += l; n++
            x += step
        }
        y += step
    }
    val mean = (sum / n / 255.0).coerceIn(0.05, 0.95)
    return (Math.log(0.45) / Math.log(mean)).toFloat().coerceIn(0.55f, 1.0f)
}

// shadow lift (gamma) + saturation + contrast + warm tone, সব এক ধাপে, ছোট অংশে ভাগ করে
fun postProcess(src: Bitmap, gamma: Float): Bitmap {
    val w = src.width
    val h = src.height
    val out = src.copy(Bitmap.Config.ARGB_8888, true)

    val lut = IntArray(256) { i ->
        (255.0 * Math.pow(i / 255.0, gamma.toDouble())).toInt().coerceIn(0, 255)
    }
    val sat = 1.15f
    val contrast = 1.12f
    val bright = 8f
    val rGain = contrast * 1.04f   // একটু warm
    val gGain = contrast
    val bGain = contrast * 0.96f

    val rowsPerChunk = maxOf(1, 1_000_000 / w)
    val buf = IntArray(w * rowsPerChunk)
    var y = 0
    while (y < h) {
        val rows = minOf(rowsPerChunk, h - y)
        out.getPixels(buf, 0, w, 0, y, w, rows)
        for (i in 0 until w * rows) {
            val p = buf[i]
            val a = p ushr 24
            var r = lut[(p shr 16) and 0xFF].toFloat()
            var g = lut[(p shr 8) and 0xFF].toFloat()
            var b = lut[p and 0xFF].toFloat()

            val lum = 0.213f * r + 0.715f * g + 0.072f * b
            r = lum + (r - lum) * sat
            g = lum + (g - lum) * sat
            b = lum + (b - lum) * sat

            val ri = (r * rGain + bright).toInt().coerceIn(0, 255)
            val gi = (g * gGain + bright).toInt().coerceIn(0, 255)
            val bi = (b * bGain + bright).toInt().coerceIn(0, 255)
            buf[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        out.setPixels(buf, 0, w, 0, y, w, rows)
        y += rows
    }
    return out
}

package com.example.photoenhancer

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private lateinit var seek: SeekBar
    private lateinit var seekLabel: TextView

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
        status = TextView(this).apply {
            text = "Choose a photo"; gravity = Gravity.CENTER; setPadding(0, 16, 0, 16)
        }
        root.addView(status)

        seekLabel = TextView(this).apply { text = "Face brightness: 33" }
        seek = SeekBar(this).apply {
            max = 100
            progress = 33
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    seekLabel.text = "Face brightness: $p"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seekLabel)
        root.addView(seek)

        val pick = Button(this).apply { text = "Choose photo"; setOnClickListener { picker.launch("image/*") } }
        btnEnhance = Button(this).apply { text = "Enhance"; isEnabled = false; setOnClickListener { enhance() } }
        btnSave = Button(this).apply { text = "Save to gallery"; isEnabled = false; setOnClickListener { save() } }
        root.addView(pick); root.addView(btnEnhance); root.addView(btnSave)
        setContentView(root)
    }

    private fun enhance() {
        val src = original ?: return
        val strength = seek.progress / 100f
        btnEnhance.isEnabled = false
        status.text = "Enhancing... 0%"
        thread {
            try {
                val enhanced = Enhancer(this).enhance(src) { p ->
                    ui.post { status.text = "Enhancing... $p%" }
                }
                ui.post { status.text = "Finishing..." }
                val blended = blendWithOriginal(enhanced, src, 0.22f)
                val lifted = if (strength > 0f) faceLift(blended, strength) else blended
                val out = postProcess(lifted, 1.0f)
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

// Model-এর ফলের ওপর আসল ছবির কিছুটা মিশিয়ে halo ও "painting" ভাব কমায়
fun blendWithOriginal(enh: Bitmap, orig: Bitmap, a: Float): Bitmap {
    val out = enh.copy(Bitmap.Config.ARGB_8888, true)
    val up = Bitmap.createScaledBitmap(orig, out.width, out.height, true)
    val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (a * 255).toInt() }
    Canvas(out).drawBitmap(up, 0f, 0f, p)
    up.recycle()
    return out
}

private fun bandWeight(v: Float, lo: Float, hi: Float, margin: Float): Float {
    return when {
        v < lo -> maxOf(0f, 1f - (lo - v) / margin)
        v > hi -> maxOf(0f, 1f - (v - hi) / margin)
        else -> 1f
    }
}

// ত্বকের রং (YCbCr) কতটা মিলছে, ০..১
private fun skinWeight(r: Int, g: Int, b: Int): Float {
    val y = 0.299f * r + 0.587f * g + 0.114f * b
    if (y < 8f) return 0f
    val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
    val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
    return bandWeight(cb, 77f, 127f, 8f) * bandWeight(cr, 133f, 173f, 4f)
}

// শুধু ত্বকের জায়গায় আলো বাড়ায়। রঙের অনুপাত ঠিক থাকে।
fun faceLift(src: Bitmap, strength: Float): Bitmap {
    val w = src.width
    val h = src.height
    val cell = 16
    val cw = (w + cell - 1) / cell
    val chh = (h + cell - 1) / cell
    val sum = FloatArray(cw * chh)
    val cnt = FloatArray(cw * chh)

    val rowsPerChunk = maxOf(1, 1_000_000 / w)
    val buf = IntArray(w * rowsPerChunk)

    // ধাপ ১: প্রতিটা ১৬x১৬ ঘরে ত্বকের গড় পরিমাণ
    var y = 0
    while (y < h) {
        val rows = minOf(rowsPerChunk, h - y)
        src.getPixels(buf, 0, w, 0, y, w, rows)
        for (ry in 0 until rows) {
            val cy = (y + ry) / cell
            for (x in 0 until w) {
                val p = buf[ry * w + x]
                val wt = skinWeight((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                val ci = cy * cw + x / cell
                sum[ci] += wt
                cnt[ci] += 1f
            }
        }
        y += rows
    }

    var mask = FloatArray(cw * chh) { i -> minOf(1f, (sum[i] / cnt[i].coerceAtLeast(1f)) * 1.5f) }

    // ধাপ ২: mask নরম (feather) করা, ২ বার 3x3 blur
    repeat(2) {
        val nm = FloatArray(cw * chh)
        for (j in 0 until chh) for (i in 0 until cw) {
            var s = 0f
            var c = 0
            for (dj in -1..1) for (di in -1..1) {
                val jj = j + dj
                val ii = i + di
                if (jj in 0 until chh && ii in 0 until cw) { s += mask[jj * cw + ii]; c++ }
            }
            nm[j * cw + i] = s / c
        }
        mask = nm
    }

    // আলো বাড়ার lookup: গাঢ় অংশে বেশি, উজ্জ্বল অংশে প্রায় নেই
    val g = 1f - 0.5f * strength
    val kLut = FloatArray(256) { i ->
        val yy = maxOf(i, 3) / 255f
        val k = Math.pow(yy.toDouble(), g.toDouble()).toFloat() / yy
        val protect = 1f - yy * yy
        (1f + (minOf(k, 2.2f) - 1f) * protect)
    }

    val out = src.copy(Bitmap.Config.ARGB_8888, true)

    // ধাপ ৩: প্রয়োগ
    y = 0
    while (y < h) {
        val rows = minOf(rowsPerChunk, h - y)
        out.getPixels(buf, 0, w, 0, y, w, rows)
        for (ry in 0 until rows) {
            val fy = (y + ry + 0.5f) / cell - 0.5f
            val j0 = Math.floor(fy.toDouble()).toInt().coerceIn(0, chh - 1)
            val j1 = (j0 + 1).coerceAtMost(chh - 1)
            val ty = (fy - j0).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val fx = (x + 0.5f) / cell - 0.5f
                val i0 = Math.floor(fx.toDouble()).toInt().coerceIn(0, cw - 1)
                val i1 = (i0 + 1).coerceAtMost(cw - 1)
                val tx = (fx - i0).coerceIn(0f, 1f)
                val m0 = mask[j0 * cw + i0] * (1 - tx) + mask[j0 * cw + i1] * tx
                val m1 = mask[j1 * cw + i0] * (1 - tx) + mask[j1 * cw + i1] * tx
                val region = m0 * (1 - ty) + m1 * ty
                if (region < 0.01f) continue

                val idx = ry * w + x
                val p = buf[idx]
                val r = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val b = p and 0xFF
                val pw = skinWeight(r, gg, b)
                val m = region * (0.4f + 0.6f * pw)

                val yi = (0.299f * r + 0.587f * gg + 0.114f * b).toInt().coerceIn(0, 255)
                val k = 1f + (kLut[yi] - 1f) * m

                val nr = (r * k).toInt().coerceIn(0, 255)
                val ng = (gg * k).toInt().coerceIn(0, 255)
                val nb = (b * k).toInt().coerceIn(0, 255)
                buf[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        out.setPixels(buf, 0, w, 0, y, w, rows)
        y += rows
    }
    return out
}

// হালকা color grading
fun postProcess(src: Bitmap, gamma: Float): Bitmap {
    val w = src.width
    val h = src.height
    val out = src.copy(Bitmap.Config.ARGB_8888, true)

    val lut = IntArray(256) { i ->
        (255.0 * Math.pow(i / 255.0, gamma.toDouble())).toInt().coerceIn(0, 255)
    }
    val sat = 1.03f
    val contrast = 1.03f
    val rGain = contrast * 1.01f
    val gGain = contrast
    val bGain = contrast * 0.99f

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

            val ri = (r * rGain).toInt().coerceIn(0, 255)
            val gi = (g * gGain).toInt().coerceIn(0, 255)
            val bi = (b * bGain).toInt().coerceIn(0, 255)
            buf[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        out.setPixels(buf, 0, w, 0, y, w, rows)
        y += rows
    }
    return out
}
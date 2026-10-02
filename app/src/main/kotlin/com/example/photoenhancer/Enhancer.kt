package com.example.photoenhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

class Enhancer(ctx: Context) {
    private val interp: Interpreter
    private val tile: Int
    private val scale: Int

    init {
        val fd = ctx.assets.openFd("model.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interp = Interpreter(buf, Interpreter.Options().setNumThreads(4))
        tile = interp.getInputTensor(0).shape()[1]
        scale = interp.getOutputTensor(0).shape()[1] / tile
    }

    fun enhance(input: Bitmap, onProgress: (Int) -> Unit): Bitmap {
        // Limit size so phone memory/time stays reasonable
        val maxSide = 800
        val ratio = maxSide.toFloat() / maxOf(input.width, input.height)
        val src = if (ratio < 1f)
            Bitmap.createScaledBitmap(input, (input.width * ratio).toInt(), (input.height * ratio).toInt(), true)
        else input.copy(Bitmap.Config.ARGB_8888, false)

        val w = src.width
        val h = src.height
        val pad = 16
        val step = tile - 2 * pad
        val outT = tile * scale
        val out = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)

        val inBuf = ByteBuffer.allocateDirect(tile * tile * 3 * 4).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(outT * outT * 3 * 4).order(ByteOrder.nativeOrder())
        val fa = FloatArray(outT * outT * 3)

        val cols = (w + step - 1) / step
        val rows = (h + step - 1) / step
        var done = 0

        var y0 = 0
        while (y0 < h) {
            var x0 = 0
            while (x0 < w) {
                inBuf.rewind()
                for (ty in 0 until tile) {
                    val sy = (y0 + ty - pad).coerceIn(0, h - 1)
                    for (tx in 0 until tile) {
                        val sx = (x0 + tx - pad).coerceIn(0, w - 1)
                        val p = src.getPixel(sx, sy)
                        inBuf.putFloat(Color.red(p) / 255f)
                        inBuf.putFloat(Color.green(p) / 255f)
                        inBuf.putFloat(Color.blue(p) / 255f)
                    }
                }
                outBuf.rewind()
                interp.run(inBuf, outBuf)
                outBuf.rewind()
                outBuf.asFloatBuffer().get(fa)

                val cw = min(step, w - x0)
                val ch = min(step, h - y0)
                val ow = cw * scale
                val oh = ch * scale
                val px = IntArray(ow * oh)
                for (oy in 0 until oh) {
                    for (ox in 0 until ow) {
                        val i = ((pad * scale + oy) * outT + (pad * scale + ox)) * 3
                        val r = (fa[i] * 255f).toInt().coerceIn(0, 255)
                        val g = (fa[i + 1] * 255f).toInt().coerceIn(0, 255)
                        val b = (fa[i + 2] * 255f).toInt().coerceIn(0, 255)
                        px[oy * ow + ox] = Color.rgb(r, g, b)
                    }
                }
                out.setPixels(px, 0, ow, x0 * scale, y0 * scale, ow, oh)
                done++
                onProgress(done * 100 / (cols * rows))
                x0 += step
            }
            y0 += step
        }
        return out
    }
}
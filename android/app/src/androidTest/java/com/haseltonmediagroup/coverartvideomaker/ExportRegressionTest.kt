package com.haseltonmediagroup.coverartvideomaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.BufferedOutputStream
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ExportRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory: File get() = File(context.getExternalFilesDir(null), "regression").apply { mkdirs() }

    @Test fun stereo44100() { exportCase("stereo_44100", 44100, 2, 16, 3240, 1920, 1080) }
    @Test fun stereo48000() { exportCase("stereo_48000", 48000, 2, 16, 3240, 1920, 1080) }
    @Test fun mono48000Portrait() { exportCase("mono_48000", 48000, 1, 16, 3240, 1080, 1920) }
    @Test fun pcm24Square() { exportCase("pcm24_48000", 48000, 2, 24, 3240, 1080, 1080) }
    @Test fun fullEndingAcrossImageLoop() { exportCase("long_48000", 48000, 2, 16, 61240, 320, 240) }
    @Test fun compatibleAacCopy() {
        val source = exportCase("aac_source", 44100, 2, 16, 3240, 640, 360)
        val target = File(directory, "aac_copy.mp4").apply { delete() }
        CoverVideoExporter(context).export(Uri.fromFile(artwork()), Uri.fromFile(source), 640, 360, target)
    }

    private fun exportCase(name: String, rate: Int, channels: Int, bits: Int,
                           durationMs: Int, width: Int, height: Int): File {
        val source = File(directory, "$name.wav")
        writeWave(source, rate, channels, bits, durationMs)
        val output = File(directory, "$name.mp4").apply { delete() }
        CoverVideoExporter(context).export(Uri.fromFile(artwork()), Uri.fromFile(source), width, height, output)
        return output
    }

    private fun artwork(): File {
        val file = File(directory, "test_cover.png")
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(0f, 0f, 160f, 160f, Paint().apply { color = Color.RED })
            canvas.drawRect(160f, 160f, 320f, 320f, Paint().apply { color = Color.BLUE })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        return file
    }

    /** Known pitch throughout; a distinctive tone at the very end detects truncation. */
    private fun writeWave(file: File, rate: Int, channels: Int, bits: Int, durationMs: Int) {
        val frames = rate.toLong() * durationMs / 1000
        val bytesPerFrame = channels * bits / 8
        val dataBytes = (frames * bytesPerFrame).toInt()
        BufferedOutputStream(file.outputStream()).use { out ->
            fun le(value: Int, bytes: Int) { repeat(bytes) { out.write(value ushr (it * 8) and 255) } }
            out.write("RIFF".toByteArray(Charsets.US_ASCII)); le(dataBytes + 36, 4)
            out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); le(16, 4)
            le(1, 2); le(channels, 2); le(rate, 4); le(rate * bytesPerFrame, 4)
            le(bytesPerFrame, 2); le(bits, 2)
            out.write("data".toByteArray(Charsets.US_ASCII)); le(dataBytes, 4)
            for (i in 0 until frames) {
                val t = i.toDouble() / rate
                val atEnd = t >= durationMs / 1000.0 - 0.25
                repeat(channels) { channel ->
                    val frequency = if (atEnd) { if (channel == 0) 1200.0 else 1600.0 }
                                    else { if (channel == 0) 440.0 else 880.0 }
                    val max = if (bits == 24) 8388607 else 32767
                    val sample = (sin(2 * PI * frequency * t) * max * 0.45).toInt()
                    le(sample, bits / 8)
                }
            }
        }
    }
}

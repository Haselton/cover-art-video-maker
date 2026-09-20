package com.haseltonmediagroup.coverartvideomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** One export per instance. Run export() on a worker thread; cancel() is thread safe. */
@androidx.annotation.OptIn(UnstableApi::class)
class CoverVideoExporter(context: Context) {
    private val context = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    @Volatile private var cancelAction: (() -> Unit)? = null

    fun cancel() {
        cancelled.set(true)
        cancelAction?.invoke()
    }

    fun export(art: Uri, audio: Uri, width: Int, height: Int, output: File,
               onProgress: (Int) -> Unit = {}) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Export must run off the UI thread" }
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        check(!output.exists()) { "Refusing to overwrite an existing file" }
        val workspace = File(context.cacheDir, "cover_export_${java.util.UUID.randomUUID()}")
        check(workspace.mkdirs()) { "Cannot create export workspace" }
        val thread = HandlerThread("CoverVideoExport").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        var transformer: Transformer? = null
        var succeeded = false
        cancelAction = {
            handler.post {
                transformer?.cancel()
                finished.countDown()
            }
        }
        try {
            val image = File(workspace, "cover.png")
            prepareArtwork(art, image, width, height)
            val copyAudio = canCopyAudio(audio)
            val processors = if (copyAudio) emptyList() else listOf(
                ToInt16PcmAudioProcessor(),
                ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 2))
                    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2))
                },
                // REAL resampling, never relabeling 48 kHz samples as 44.1 kHz.
                SonicAudioProcessor().apply {
                    setOutputSampleRateHz(SAMPLE_RATE)
                    setSpeed(1f)
                    setPitch(1f)
                }
            )
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audio))
                .setRemoveVideo(true)
                .setEffects(Effects(processors, emptyList()))
                .build()
            val imageItem = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(Uri.fromFile(image))
                    .setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(60_000).build()
            ).setRemoveAudio(true).setFrameRate(FRAME_RATE).build()
            // Audio is the only non-looping sequence. Its real end-of-stream controls
            // duration, NOT rounded metadata and NOT a hand-written packet cutoff.
            val composition = Composition.Builder(listOf(
                EditedMediaItemSequence.Builder(imageItem).setIsLooping(true).build(),
                EditedMediaItemSequence.Builder(audioItem).build()
            )).setTransmuxAudio(copyAudio).build()
            handler.post {
                try {
                    if (cancelled.get()) { finished.countDown(); return@post }
                    val factory = DefaultEncoderFactory.Builder(context)
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder()
                            .setBitrate(320_000)
                            .setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC).build())
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                            .setBitrate(4_000_000).setiFrameIntervalSeconds(2f).build())
                        .setEnableFallback(false).build()
                    transformer = Transformer.Builder(context)
                        .setLooper(thread.looper)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(factory)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                finished.countDown()
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult,
                                                 exportException: ExportException) {
                                failure.set(exportException)
                                finished.countDown()
                            }
                        }).build()
                    transformer!!.start(composition, output.absolutePath)
                    val holder = ProgressHolder()
                    handler.post(object : Runnable {
                        override fun run() {
                            if (finished.count == 0L) return
                            if (transformer!!.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(holder.progress.coerceIn(0, 99))
                            }
                            handler.postDelayed(this, 300)
                        }
                    })
                } catch (e: Exception) {
                    failure.set(e)
                    finished.countDown()
                }
            }
            finished.await()
            if (cancelled.get()) throw IOException("Export cancelled")
            failure.get()?.let { throw IOException("MP4 export failed: ${it.message}", it) }
            validateOutput(output)
            succeeded = true
            onProgress(100)
        } finally {
            cancelAction = null
            handler.post {
                transformer?.cancel()
                thread.quitSafely()
            }
            thread.join(5_000)
            workspace.deleteRecursively()
            if (!succeeded) output.delete()
        }
    }

    private fun prepareArtwork(uri: Uri, file: File, width: Int, height: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read the cover image" }
        var sample = 1
        while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: throw IOException("Cannot decode the cover image")
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.BLACK)
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            canvas.drawBitmap(bitmap, null,
                RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            file.outputStream().use { check(canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
            canvasBitmap.recycle()
        }
    }

    /** Only already-compatible AAC-LC is copied without another lossy encode. */
    private fun canCopyAudio(uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val f = extractor.getTrackFormat(track)
            val profile = if (f.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
                f.getInteger(MediaFormat.KEY_AAC_PROFILE)
            } else {
                val asc = f.getByteBuffer("csd-0")
                if (asc != null && asc.hasRemaining()) (asc.get(asc.position()).toInt() and 255) ushr 3 else -1
            }
            f.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC &&
                f.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE &&
                f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2 &&
                profile == MediaCodecInfo.CodecProfileLevel.AACObjectLC
        } catch (_: Exception) {
            false // Let Media3 parse/transcode formats not recognized by MediaExtractor.
        } finally {
            extractor.release()
        }
    }

    companion object {
        const val FRAME_RATE = 25
        const val SAMPLE_RATE = 44_100
        private const val FRAME_US = 1_000_000L / FRAME_RATE

        /** Reject malformed output before it becomes visible in the Gallery. */
        fun validateOutput(file: File) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val video = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC
                } ?: throw IOException("Export has no H.264 video track")
                val audio = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                } ?: throw IOException("Export has no AAC audio track")
                val af = extractor.getTrackFormat(audio)
                check(af.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE &&
                    af.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2) { "Unexpected audio output format" }
                extractor.selectTrack(video)
                val times = ArrayList<Long>()
                while (extractor.sampleTime >= 0) { times.add(extractor.sampleTime); extractor.advance() }
                check(times.isNotEmpty()) { "No video frames were encoded" }
                // B-frames may be stored in decode order. Validate DISPLAY order.
                times.sort()
                for (i in 1 until times.size) {
                    check(abs(times[i] - times[i-1] - FRAME_US) <= 2) {
                        "Invalid video timing at frame $i"
                    }
                }
                extractor.unselectTrack(video)
                extractor.selectTrack(audio)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var first = -1L
                var last = -1L
                while (extractor.sampleTime >= 0) {
                    val t = extractor.sampleTime
                    check(last < 0 || t > last) { "Duplicate or reversed audio timestamps" }
                    if (first < 0) first = t
                    last = t
                    extractor.advance()
                }
                check(first >= 0 && last >= first) { "No audio packets were encoded" }
                val audioDuration = last - first + 1024L * 1_000_000 / SAMPLE_RATE
                val videoDuration = times.last() - times.first() + FRAME_US
                check(abs(audioDuration - videoDuration) < 200_000) { "Audio/video duration mismatch" }
            } finally {
                extractor.release()
            }
        }
    }
}

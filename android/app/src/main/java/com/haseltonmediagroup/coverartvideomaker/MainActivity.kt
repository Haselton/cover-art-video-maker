package com.haseltonmediagroup.coverartvideomaker

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {
    private var artUri: Uri? = null
    private var audioUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var createButton: Button
    private lateinit var preset: Spinner

    private val artPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { artUri = uri; status.text = "Cover art selected"; updateReadyState() }
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { audioUri = uri; status.text = "Audio selected"; updateReadyState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,70,40,40); setBackgroundColor(Color.rgb(17,19,24)) }
        root.addView(label("COVER ART VIDEO MAKER",25f))
        root.addView(label("Turn your artwork + song into an MP4 — entirely on your phone.",15f))
        root.addView(Button(this).apply { text="SELECT COVER ART"; setOnClickListener { artPicker.launch("image/*") } })
        root.addView(Button(this).apply { text="SELECT AUDIO"; setOnClickListener { audioPicker.launch("audio/*") } })
        preset = Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("YouTube 1920 × 1080","Square 1080 × 1080","Vertical 1080 × 1920")) }
        root.addView(preset)
        createButton=Button(this).apply { text="CREATE MP4"; isEnabled=false; setOnClickListener { startRender() } }
        root.addView(createButton)
        status=label("Choose cover art and audio to begin.",14f); root.addView(status); setContentView(root)
    }

    private fun label(value:String,size:Float)=TextView(this).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(0,12,0,12) }
    private fun updateReadyState(){ createButton.isEnabled=artUri!=null&&audioUri!=null }

    private fun startRender(){
        createButton.isEnabled=false; status.text="Rendering MP4… keep the app open."
        Thread { try { renderVideo() } catch(e:Exception){ runOnUiThread { status.text="Render failed: ${e.message}"; createButton.isEnabled=true } } }.start()
    }

    private fun renderVideo(){
        val art=artUri ?: error("No cover art")
        val audio=audioUri ?: error("No audio")
        val dims=arrayOf(1920 to 1080,1080 to 1080,1080 to 1920)[preset.selectedItemPosition]
        val width=dims.first; val height=dims.second
        val retriever=MediaMetadataRetriever(); retriever.setDataSource(this,audio)
        val durationUs=(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Could not read audio duration"))*1000L; retriever.release()
        val bitmap=contentResolver.openInputStream(art).use { input -> requireNotNull(BitmapFactory.decodeStream(input)){"Could not decode cover art"} }

        val extractor=MediaExtractor()
        val audioFd=contentResolver.openFileDescriptor(audio,"r") ?: error("Could not open audio")
        extractor.setDataSource(audioFd.fileDescriptor)
        var audioIndex=-1
        for(i in 0 until extractor.trackCount){ val mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME); if(mime?.startsWith("audio/")==true){ audioIndex=i; break } }
        require(audioIndex>=0){"No audio track found"}; extractor.selectTrack(audioIndex)
        val audioFormat=extractor.getTrackFormat(audioIndex)

        val fileName="CoverArtVideo_${System.currentTimeMillis()}.mp4"
        val values=ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME,fileName); put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Cover Art Video Maker") }
        val outputUri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values) ?: error("Could not create output")
        val outputFd=contentResolver.openFileDescriptor(outputUri,"w") ?: error("Could not open output")
        val muxer=MediaMuxer(outputFd.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val audioTrack=muxer.addTrack(audioFormat)

        val videoFormat=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply { setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_BIT_RATE,4_000_000); setInteger(MediaFormat.KEY_FRAME_RATE,1); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,5) }
        val encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); encoder.configure(videoFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface=encoder.createInputSurface(); encoder.start()
        val info=MediaCodec.BufferInfo(); var videoTrack=-1; var muxerStarted=false
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        fun drain(end:Boolean){
            while(true){
                val index=encoder.dequeueOutputBuffer(info,if(end) 10_000 else 0)
                if(index==MediaCodec.INFO_TRY_AGAIN_LATER){ if(!end) return }
                else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){ if(!muxerStarted){ videoTrack=muxer.addTrack(encoder.outputFormat); muxer.start(); muxerStarted=true } }
                else if(index>=0){ val buffer=encoder.getOutputBuffer(index); if(buffer!=null&&info.size>0&&muxerStarted){ buffer.position(info.offset); buffer.limit(info.offset+info.size); muxer.writeSampleData(videoTrack,buffer,info) }; val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0; encoder.releaseOutputBuffer(index,false); if(eos)return }
            }
        }

        var second=0L; val seconds=(durationUs+999_999L)/1_000_000L
        while(second<=seconds){ val canvas=surface.lockCanvas(null); try { canvas.drawColor(Color.BLACK); val scale=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height); val dw=bitmap.width*scale; val dh=bitmap.height*scale; canvas.drawBitmap(bitmap,null,RectF((width-dw)/2f,(height-dh)/2f,(width+dw)/2f,(height+dh)/2f),paint) } finally { surface.unlockCanvasAndPost(canvas) }; drain(false); second++ }
        encoder.signalEndOfInputStream(); drain(true)

        require(muxerStarted){"Video encoder produced no output"}
        val buffer=ByteBuffer.allocate(1024*1024); val audioInfo=MediaCodec.BufferInfo()
        while(true){ buffer.clear(); val size=extractor.readSampleData(buffer,0); if(size<0)break; audioInfo.set(0,size,extractor.sampleTime,extractor.sampleFlags); muxer.writeSampleData(audioTrack,buffer,audioInfo); extractor.advance() }

        extractor.release(); audioFd.close(); encoder.stop(); encoder.release(); surface.release(); muxer.stop(); muxer.release(); outputFd.close(); bitmap.recycle()
        runOnUiThread { status.text="Saved: Movies/Cover Art Video Maker/$fileName"; createButton.isEnabled=true; Toast.makeText(this,"MP4 created",Toast.LENGTH_LONG).show() }
    }
}

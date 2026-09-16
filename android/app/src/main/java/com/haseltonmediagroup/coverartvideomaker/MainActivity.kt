package com.haseltonmediagroup.coverartvideomaker

import android.os.*
import android.content.*
import android.graphics.*
import android.media.*
import android.net.Uri
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {
 private var art:Uri?=null; private var audio:Uri?=null; private lateinit var status:TextView; private lateinit var create:Button; private lateinit var preset:Spinner
 private val artPick=registerForActivityResult(ActivityResultContracts.GetContent()){if(it!=null){art=it;status.text="Cover art selected";ready()}}
 private val audioPick=registerForActivityResult(ActivityResultContracts.GetContent()){if(it!=null){audio=it;status.text="Audio selected";ready()}}
 override fun onCreate(b:Bundle?){super.onCreate(b);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,70,40,40);setBackgroundColor(Color.rgb(17,19,24))};fun t(s:String,z:Float)=TextView(this).apply{text=s;textSize=z;setTextColor(Color.WHITE);setPadding(0,12,0,12)};root.addView(t("COVER ART VIDEO MAKER",25f));root.addView(t("Turn your artwork + song into an MP4 — entirely on your phone.",15f));root.addView(Button(this).apply{text="SELECT COVER ART";setOnClickListener{artPick.launch("image/*")}});root.addView(Button(this).apply{text="SELECT AUDIO";setOnClickListener{audioPick.launch("audio/*")}});preset=Spinner(this);preset.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("YouTube 1920 × 1080","Square 1080 × 1080","Vertical 1080 × 1920"));root.addView(preset);create=Button(this).apply{text="CREATE MP4";isEnabled=false;setOnClickListener{render()}};root.addView(create);status=t("Choose cover art and audio to begin.",14f);root.addView(status);setContentView(root)}
 private fun ready(){create.isEnabled=art!=null&&audio!=null}
 private fun render(){create.isEnabled=false;status.text="Rendering… keep the app open";Thread{try{encode()}catch(e:Exception){runOnUiThread{status.text="Render failed: ${e.message}";create.isEnabled=true}}}.start()}
 private fun encode(){val (w,h)=arrayOf(1920 to 1080,1080 to 1080,1080 to 1920)[preset.selectedItemPosition];val mmr=MediaMetadataRetriever();mmr.setDataSource(this,audio);val duration=(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?:0)*1000;mmr.release();val image=contentResolver.openInputStream(art!!).use{BitmapFactory.decodeStream(it)}
  val ex=MediaExtractor();contentResolver.openFileDescriptor(audio!!,"r")!!.use{ex.setDataSource(it.fileDescriptor)}
  var ai=-1
  for(i in 0 until ex.trackCount){val mime=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime?.startsWith("audio/")==true){ai=i;break}}
  require(ai>=0){"No audio track found"};ex.selectTrack(ai)
  val name="CoverArtVideo_${System.currentTimeMillis()}.mp4";val cv=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Cover Art Video Maker")};val out=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cv)!!;val pfd=contentResolver.openFileDescriptor(out,"w")!!;val mux=MediaMuxer(pfd.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);val at=mux.addTrack(ex.getTrackFormat(ai))
  val vf=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h).apply{setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);setInteger(MediaFormat.KEY_BIT_RATE,5_000_000);setInteger(MediaFormat.KEY_FRAME_RATE,1);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,10)};val vc=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);vc.configure(vf,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);val surface=vc.createInputSurface();vc.start();var vt=-1;var started=false;val info=MediaCodec.BufferInfo();var elapsed=0L
  while(elapsed<=duration){val c=surface.lockCanvas(null);c.drawColor(Color.BLACK);val s=minOf(w.toFloat()/image.width,h.toFloat()/image.height);val dw=image.width*s;val dh=image.height*s;c.drawBitmap(image,null,RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),Paint(Paint.ANTI_ALIAS_FLAG));surface.unlockCanvasAndPost(c);Thread.sleep(1000);elapsed+=1_000_000;var ix=vc.dequeueOutputBuffer(info,10000);if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED&&!started){vt=mux.addTrack(vc.outputFormat);mux.start();started=true;ix=vc.dequeueOutputBuffer(info,0)};while(ix>=0){if(started&&info.size>0)mux.writeSampleData(vt,vc.getOutputBuffer(ix)!!,info);vc.releaseOutputBuffer(ix,false);ix=vc.dequeueOutputBuffer(info,0)}}
  vc.signalEndOfInputStream();while(true){val ix=vc.dequeueOutputBuffer(info,10000);if(ix>=0){if(info.size>0&&started)mux.writeSampleData(vt,vc.getOutputBuffer(ix)!!,info);vc.releaseOutputBuffer(ix,false);if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)break}}
  val buf=ByteBuffer.allocate(1024*1024);val ainfo=MediaCodec.BufferInfo();while(true){val n=ex.readSampleData(buf,0);if(n<0)break;ainfo.set(0,n,ex.sampleTime,ex.sampleFlags);mux.writeSampleData(at,buf,ainfo);ex.advance()};ex.release();vc.stop();vc.release();surface.release();mux.stop();mux.release();pfd.close();image.recycle();runOnUiThread{status.text="Video saved to Movies/Cover Art Video Maker\n$name";create.isEnabled=true;Toast.makeText(this,"MP4 created",Toast.LENGTH_LONG).show()}}
}

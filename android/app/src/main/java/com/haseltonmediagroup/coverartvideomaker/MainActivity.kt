package com.haseltonmediagroup.coverartvideomaker

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {
 private var artUri:Uri?=null; private var audioUri:Uri?=null
 private lateinit var status:TextView; private lateinit var createButton:Button; private lateinit var preset:Spinner
 private val artPicker=registerForActivityResult(ActivityResultContracts.GetContent()){if(it!=null){artUri=it;status.text="Cover art selected";ready()}}
 private val audioPicker=registerForActivityResult(ActivityResultContracts.GetContent()){if(it!=null){audioUri=it;status.text="Audio selected";ready()}}
 override fun onCreate(b:Bundle?){super.onCreate(b);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,70,40,40);setBackgroundColor(Color.rgb(17,19,24))};root.addView(label("COVER ART VIDEO MAKER",25f));root.addView(label("Turn your artwork + song into an MP4 — entirely on your phone.",15f));root.addView(Button(this).apply{text="SELECT COVER ART";setOnClickListener{artPicker.launch("image/*")}});root.addView(Button(this).apply{text="SELECT AUDIO";setOnClickListener{audioPicker.launch("audio/*")}});preset=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("YouTube 1920 × 1080","Square 1080 × 1080","Vertical 1080 × 1920"))};root.addView(preset);createButton=Button(this).apply{text="CREATE MP4";isEnabled=false;setOnClickListener{startRender()}};root.addView(createButton);status=label("Choose cover art and audio to begin.",14f);root.addView(status);setContentView(root)}
 private fun label(v:String,s:Float)=TextView(this).apply{text=v;textSize=s;setTextColor(Color.WHITE);setPadding(0,12,0,12)}
 private fun ready(){createButton.isEnabled=artUri!=null&&audioUri!=null}
 private fun progress(v:String){runOnUiThread{status.text=v}}
 private fun startRender(){createButton.isEnabled=false;progress("Preparing media… 5%") ;Thread{try{render()}catch(e:Exception){runOnUiThread{status.text="Render failed: ${e.message}";createButton.isEnabled=true}}}.start()}
 private fun drawFrame(surface:android.view.Surface,bitmap:android.graphics.Bitmap,w:Int,h:Int,paint:Paint){val c=surface.lockCanvas(null);try{c.drawColor(Color.BLACK);val s=minOf(w.toFloat()/bitmap.width,h.toFloat()/bitmap.height);val dw=bitmap.width*s;val dh=bitmap.height*s;c.drawBitmap(bitmap,null,RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),paint)}finally{surface.unlockCanvasAndPost(c)}}
 private fun render(){
  val art=artUri?:error("No cover art");val audio=audioUri?:error("No audio");val (w,h)=arrayOf(1920 to 1080,1080 to 1080,1080 to 1920)[preset.selectedItemPosition]
  val mmr=MediaMetadataRetriever();mmr.setDataSource(this,audio);val durationUs=(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:error("Could not read duration"))*1000L;mmr.release();val bitmap=contentResolver.openInputStream(art).use{requireNotNull(BitmapFactory.decodeStream(it)){"Could not decode artwork"}}
  progress("Starting video encoder… 15%")
  val name="CoverArtVideo_${System.currentTimeMillis()}.mp4";val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Cover Art Video Maker")};val out=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:error("Output failed");val ofd=contentResolver.openFileDescriptor(out,"w")?:error("Output open failed");val mux=MediaMuxer(ofd.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  val vf=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h).apply{setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);setInteger(MediaFormat.KEY_BIT_RATE,4_000_000);setInteger(MediaFormat.KEY_FRAME_RATE,1);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,5)};val venc=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);venc.configure(vf,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);val surface=venc.createInputSurface();venc.start();val vi=MediaCodec.BufferInfo();var vt=-1
  val af=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,44100,2).apply{setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);setInteger(MediaFormat.KEY_BIT_RATE,192000);setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)};val aenc=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);aenc.configure(af,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);aenc.start();val ai=MediaCodec.BufferInfo();var at=-1
  val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  drawFrame(surface,bitmap,w,h,paint)
  val seed=aenc.dequeueInputBuffer(10000);if(seed>=0)aenc.queueInputBuffer(seed,0,0,0,0)
  var attempts=0
  while((vt<0||at<0)&&attempts<200){if(vt<0){val x=venc.dequeueOutputBuffer(vi,10000);if(x==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)vt=mux.addTrack(venc.outputFormat);else if(x>=0)venc.releaseOutputBuffer(x,false)};if(at<0){val x=aenc.dequeueOutputBuffer(ai,10000);if(x==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)at=mux.addTrack(aenc.outputFormat);else if(x>=0)aenc.releaseOutputBuffer(x,false)};attempts++}
  require(vt>=0&&at>=0){"Encoder did not initialize"};mux.start();progress("Creating cover video… 30%")
  var sec=0L;val total=(durationUs+999999)/1000000
  while(sec<=total){drawFrame(surface,bitmap,w,h,paint);drainVideo(venc,mux,vt,vi,false);sec++;if(sec%10L==0L){val pct=30+((sec.coerceAtMost(total)*20)/(total.coerceAtLeast(1))).toInt();progress("Creating cover video… $pct%")}}
  venc.signalEndOfInputStream();drainVideo(venc,mux,vt,vi,true);progress("Converting audio to AAC… 55%")
  transcodeAudio(audio,aenc,mux,at,durationUs)
  progress("Finalizing MP4… 95%")
  venc.stop();venc.release();surface.release();aenc.stop();aenc.release();mux.stop();mux.release();ofd.close();bitmap.recycle();runOnUiThread{status.text="Complete — saved to Movies/Cover Art Video Maker/$name";createButton.isEnabled=true;Toast.makeText(this,"MP4 created",Toast.LENGTH_LONG).show()}
 }
 private fun drainVideo(enc:MediaCodec,mux:MediaMuxer,track:Int,info:MediaCodec.BufferInfo,end:Boolean){var idle=0;while(true){val x=enc.dequeueOutputBuffer(info,if(end)10000 else 0);if(x==MediaCodec.INFO_TRY_AGAIN_LATER){if(!end)return;idle++;if(idle>100)error("Video encoder timed out")}else if(x>=0){idle=0;val b=enc.getOutputBuffer(x);if(b!=null&&info.size>0){b.position(info.offset);b.limit(info.offset+info.size);mux.writeSampleData(track,b,info)};val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;enc.releaseOutputBuffer(x,false);if(eos)return}}}
 private fun transcodeAudio(uri:Uri,encoder:MediaCodec,mux:MediaMuxer,track:Int,durationUs:Long){val ex=MediaExtractor();val fd=contentResolver.openFileDescriptor(uri,"r")?:error("Audio open failed");ex.setDataSource(fd.fileDescriptor);var idx=-1;for(i in 0 until ex.trackCount){if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true){idx=i;break}};require(idx>=0){"No audio track"};ex.selectTrack(idx);val inf=ex.getTrackFormat(idx);val mime=inf.getString(MediaFormat.KEY_MIME)?:error("Audio format missing");val dec=MediaCodec.createDecoderByType(mime);dec.configure(inf,null,null,0);dec.start();val di=MediaCodec.BufferInfo();val ei=MediaCodec.BufferInfo();var inputDone=false;var decoderDone=false;var lastPct=-1
  while(!decoderDone){if(!inputDone){val ix=dec.dequeueInputBuffer(10000);if(ix>=0){val b=dec.getInputBuffer(ix)!!;val n=ex.readSampleData(b,0);if(n<0){dec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}else{dec.queueInputBuffer(ix,0,n,ex.sampleTime,0);ex.advance()}}};val ox=dec.dequeueOutputBuffer(di,10000);if(ox>=0){val pcm=dec.getOutputBuffer(ox);if(pcm!=null&&di.size>0){pcm.position(di.offset);pcm.limit(di.offset+di.size);var remaining=di.size;while(remaining>0){val ix=encoder.dequeueInputBuffer(10000);if(ix>=0){val ib=encoder.getInputBuffer(ix)!!;ib.clear();val n=minOf(remaining,ib.remaining());val old=pcm.limit();pcm.limit(pcm.position()+n);ib.put(pcm);pcm.limit(old);encoder.queueInputBuffer(ix,0,n,di.presentationTimeUs,0);remaining-=n;drainAudio(encoder,mux,track,ei,false)}};val pct=55+((di.presentationTimeUs.coerceAtLeast(0)*35)/durationUs.coerceAtLeast(1)).toInt().coerceIn(0,35);if(pct!=lastPct){lastPct=pct;progress("Converting audio to AAC… $pct%")}};decoderDone=di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;dec.releaseOutputBuffer(ox,false)}};var queued=false;repeat(100){if(!queued){val ix=encoder.dequeueInputBuffer(10000);if(ix>=0){encoder.queueInputBuffer(ix,0,0,durationUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);queued=true}}};require(queued){"Audio encoder timed out"};drainAudio(encoder,mux,track,ei,true);dec.stop();dec.release();ex.release();fd.close()}
 private fun drainAudio(enc:MediaCodec,mux:MediaMuxer,track:Int,info:MediaCodec.BufferInfo,end:Boolean){var idle=0;while(true){val x=enc.dequeueOutputBuffer(info,if(end)10000 else 0);if(x==MediaCodec.INFO_TRY_AGAIN_LATER){if(!end)return;idle++;if(idle>100)error("Audio encoder timed out")}else if(x>=0){idle=0;val b=enc.getOutputBuffer(x);if(b!=null&&info.size>0){b.position(info.offset);b.limit(info.offset+info.size);mux.writeSampleData(track,b,info)};val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;enc.releaseOutputBuffer(x,false);if(eos)return}}}
}

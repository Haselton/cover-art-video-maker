package com.haseltonmediagroup.coverartvideomaker

import android.content.ContentValues
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private val cyan=Color.rgb(0,229,255)
    private val bg=Color.rgb(5,9,13)
    private val panel=Color.rgb(14,23,30)
    private val muted=Color.rgb(151,171,184)
    private var artUri: Uri? = null
    private var audioUri: Uri? = null
    private var rendering = false
    @Volatile private var activeExporter: CoverVideoExporter? = null
    private lateinit var status: TextView
    private lateinit var createButton: Button
    private lateinit var preset: Spinner
    private lateinit var artPreview: ImageView
    private lateinit var artState: TextView
    private lateinit var audioState: TextView
    private lateinit var progressBar: ProgressBar

    private val artPicker=registerForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) {
            artUri=it; artPreview.setImageURI(it)
            artPreview.scaleType=ImageView.ScaleType.CENTER_CROP
            artState.text="✓ ARTWORK READY"; artState.setTextColor(cyan); ready()
        }
    }
    private val audioPicker=registerForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) {
            audioUri=it
            val m=MediaMetadataRetriever()
            try {
                m.setDataSource(this,it)
                val ms=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                audioState.text="✓ AUDIO READY  •  ${ms/60000}:${((ms/1000)%60).toString().padStart(2,'0')}"
            } catch (_: Exception) {
                audioState.text="✓ AUDIO SELECTED"
            } finally { m.release() }
            audioState.setTextColor(cyan); ready()
        }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor=bg; window.navigationBarColor=bg
        val scroll=ScrollView(this).apply { setBackgroundColor(bg) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(34,24,34,42) }
        scroll.addView(root); root.addView(header())
        root.addView(text("MUSIC IN • VIDEO OUT",13f,cyan,true).apply { gravity=Gravity.CENTER; setPadding(0,0,0,22) })
        root.addView(card())
        root.addView(text("OUTPUT FORMAT",12f,muted,true).apply { setPadding(2,22,0,8) })
        val labels=arrayOf("YouTube  •  1920 × 1080  •  16:9","Square  •  1080 × 1080  •  1:1","TikTok / Shorts / Reels  •  1080 × 1920  •  9:16")
        val formatAdapter=object : ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels) {
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View =
                (super.getView(position,convertView,parent) as TextView).apply {
                    setTextColor(Color.WHITE); textSize=14f; gravity=Gravity.CENTER_VERTICAL
                    setPadding(dp(12),0,dp(12),0); setSingleLine(false); maxLines=2
                }
            override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View =
                (super.getDropDownView(position,convertView,parent) as TextView).apply {
                    setTextColor(Color.WHITE); setBackgroundColor(panel); textSize=14f
                    gravity=Gravity.CENTER_VERTICAL; minHeight=dp(56)
                    setPadding(dp(12),dp(8),dp(12),dp(8)); setSingleLine(false); maxLines=2
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        preset=Spinner(this).apply { adapter=formatAdapter; setBackgroundColor(panel) }
        root.addView(preset,LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(text("MP4 • H.264 • 25 FPS\nAAC-LC • 44.1 kHz stereo • full-length audio",11f,muted,false).apply {
            gravity=Gravity.CENTER; setPadding(0,12,0,0)
        })
        createButton=Button(this).apply {
            text="CREATE VIDEO  ▶"; textSize=17f; setTextColor(Color.BLACK)
            isAllCaps=false; isEnabled=false; background=round(cyan,18f)
            // Theme button padding must not consume a fixed pixel-height control.
            setPadding(dp(12),0,dp(12),0); minHeight=0; minimumHeight=0; gravity=Gravity.CENTER
            setOnClickListener { startRender() }
        }
        root.addView(createButton,LinearLayout.LayoutParams(-1,dp(56)).apply { topMargin=22 })
        progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; progress=0; progressTintList=android.content.res.ColorStateList.valueOf(cyan)
        }
        root.addView(progressBar,LinearLayout.LayoutParams(-1,7).apply { topMargin=18 })
        status=text("Select your artwork and audio to begin.",13f,muted,false).apply {
            gravity=Gravity.CENTER; setPadding(0,12,0,8)
        }
        root.addView(status)
        root.addView(text("PRIVATE BY DESIGN  •  YOUR MEDIA STAYS ON YOUR DEVICE",10f,Color.rgb(88,117,130),true).apply {
            gravity=Gravity.CENTER; setPadding(0,20,0,0)
        })
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun header(): LinearLayout {
        val box=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,0,0,8) }
        val mark=TextView(this).apply { text="◉"; textSize=44f; setTextColor(cyan); gravity=Gravity.CENTER; includeFontPadding=false }
        box.addView(mark,LinearLayout.LayoutParams(dp(48),dp(68)))
        val words=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        words.addView(text("COVER ART",28f,Color.WHITE,true)); words.addView(text("VIDEO MAKER",28f,cyan,true))
        box.addView(words); return box
    }
    private fun card(): LinearLayout {
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18,18,18,18); background=round(panel,22f) }
        artPreview=ImageView(this).apply { setBackgroundColor(Color.rgb(9,15,20)) }
        c.addView(artPreview,LinearLayout.LayoutParams(-1,420))
        artState=text("＋  SELECT COVER ART",14f,Color.WHITE,true).apply {
            gravity=Gravity.CENTER; background=round(Color.rgb(20,34,43),14f)
            setOnClickListener { if (!rendering) artPicker.launch("image/*") }
        }
        c.addView(artState,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=14 })
        audioState=text("♫  SELECT AUDIO",14f,Color.WHITE,true).apply {
            gravity=Gravity.CENTER; background=round(Color.rgb(20,34,43),14f)
            setOnClickListener { if (!rendering) audioPicker.launch("audio/*") }
        }
        c.addView(audioState,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=10 }); return c
    }
    private fun text(v:String,s:Float,color:Int,bold:Boolean)=TextView(this).apply {
        text=v; textSize=s; setTextColor(color); if(bold)setTypeface(typeface,Typeface.BOLD); letterSpacing=.05f
    }
    private fun round(color:Int,r:Float)=GradientDrawable().apply {
        setColor(color); cornerRadius=r; setStroke(if(color==panel)1 else 0,Color.rgb(25,56,68))
    }
    private fun ready() {
        createButton.isEnabled=!rendering && artUri!=null && audioUri!=null
        createButton.alpha=if(createButton.isEnabled)1f else .65f
    }
    private fun progress(message:String,percent:Int) {
        runOnUiThread { if (!isDestroyed) { status.text=message; progressBar.progress=percent } }
    }
    private fun startRender() {
        if (rendering) return
        val art=artUri ?: return
        val audio=audioUri ?: return
        // Capture all UI state before entering the worker thread.
        val (width,height)=arrayOf(1920 to 1080,1080 to 1080,1080 to 1920)[preset.selectedItemPosition]
        rendering=true; ready(); preset.isEnabled=false
        status.setTextColor(muted); progress("Preparing compatible MP4…",0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val exporter=CoverVideoExporter(applicationContext)
        activeExporter=exporter
        Thread {
            val file=File(cacheDir,"CoverArtVideo_${java.util.UUID.randomUUID()}.mp4")
            try {
                exporter.export(art,audio,width,height,file) { p ->
                    progress("Creating 25 fps video with full-length audio… ${(p * 94) / 100}%",(p * 94) / 100)
                }
                if (isDestroyed) return@Thread
                progress("Checks passed — saving video…",96)
                saveToGallery(file)
                runOnUiThread {
                    if (!isDestroyed) {
                        progressBar.progress=100
                        status.text="✓ COMPLETE — saved to Movies/Cover Art Video Maker"
                        status.setTextColor(cyan)
                        Toast.makeText(this,"Video created",Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                progress("Render failed: ${e.message}",0)
            } finally {
                file.delete(); activeExporter=null
                runOnUiThread {
                    if (!isDestroyed) {
                        rendering=false; preset.isEnabled=true; ready()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }.start()
    }
    private fun saveToGallery(file: File) {
        val values=ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME,"CoverArtVideo_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Cover Art Video Maker")
            put(MediaStore.Video.Media.IS_PENDING,1)
        }
        val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)
            ?: error("Cannot create Gallery entry")
        try {
            val destination=contentResolver.openOutputStream(uri,"w") ?: error("Cannot open Gallery output")
            destination.use { out -> file.inputStream().use { it.copyTo(out) } }
            check(contentResolver.update(uri,ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING,0)
            },null,null) == 1) { "Cannot finalize Gallery entry" }
        } catch (e: Exception) {
            contentResolver.delete(uri,null,null)
            throw e
        }
    }
    override fun onDestroy() {
        activeExporter?.cancel()
        super.onDestroy()
    }
}

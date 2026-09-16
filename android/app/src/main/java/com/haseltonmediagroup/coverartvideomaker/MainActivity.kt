package com.haseltonmediagroup.coverartvideomaker

import android.content.ContentValues
import android.graphics.Color
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

class MainActivity : AppCompatActivity() {
    private var artUri: Uri? = null
    private var audioUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var createButton: Button
    private lateinit var preset: Spinner

    private val artPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            artUri = uri
            status.text = "Cover art selected"
            updateReadyState()
        }
    }

    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            audioUri = uri
            status.text = "Audio selected"
            updateReadyState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 70, 40, 40)
            setBackgroundColor(Color.rgb(17, 19, 24))
        }
        root.addView(label("COVER ART VIDEO MAKER", 25f))
        root.addView(label("Turn your artwork + song into an MP4 — entirely on your phone.", 15f))
        root.addView(Button(this).apply {
            text = "SELECT COVER ART"
            setOnClickListener { artPicker.launch("image/*") }
        })
        root.addView(Button(this).apply {
            text = "SELECT AUDIO"
            setOnClickListener { audioPicker.launch("audio/*") }
        })
        preset = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("YouTube 1920 × 1080", "Square 1080 × 1080", "Vertical 1080 × 1920"))
        }
        root.addView(preset)
        createButton = Button(this).apply {
            text = "CREATE MP4"
            isEnabled = false
            setOnClickListener { createVideoPlaceholder() }
        }
        root.addView(createButton)
        status = label("Choose cover art and audio to begin.", 14f)
        root.addView(status)
        setContentView(root)
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, 12, 0, 12)
    }

    private fun updateReadyState() {
        createButton.isEnabled = artUri != null && audioUri != null
    }

    private fun createVideoPlaceholder() {
        val art = artUri ?: return
        val audio = audioUri ?: return
        status.text = "Files selected. Android renderer ready for next integration step."
        Toast.makeText(this, "Cover and audio loaded", Toast.LENGTH_LONG).show()
    }
}

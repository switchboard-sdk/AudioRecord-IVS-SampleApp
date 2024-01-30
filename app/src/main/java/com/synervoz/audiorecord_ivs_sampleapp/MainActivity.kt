package com.synervoz.audiorecord_ivs_sampleapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amazonaws.ivs.broadcast.AudioDevice
import com.amazonaws.ivs.broadcast.AudioLocalStageStream
import com.amazonaws.ivs.broadcast.BroadcastConfiguration
import com.amazonaws.ivs.broadcast.DeviceDiscovery
import com.amazonaws.ivs.broadcast.LocalStageStream
import com.amazonaws.ivs.broadcast.ParticipantInfo
import com.amazonaws.ivs.broadcast.Stage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer


class MainActivity : AppCompatActivity() {

    private var bufferSizeRecorder: Int = 0

    // recording
    private val sampleRate = 48000
    private lateinit var recorder: AudioRecord
    private var isRecording = false
    private lateinit var recordingThread: Thread
    private lateinit var recordButton: Button

    // ivs
    val TOKEN = "eyJhbGciOiJLTVMiLCJ0eXAiOiJKV1QifQ.eyJleHAiOjE3MDc4MzQzMjksImlhdCI6MTcwNjYyNDcyOSwianRpIjoiTklDSG5YcGlFUnVVIiwicmVzb3VyY2UiOiJhcm46YXdzOml2czpldS1jZW50cmFsLTE6MTQ1NzIzNjg2MjQ2OnN0YWdlL1F2dEJ3SVRTOVhKMyIsInRvcGljIjoiUXZ0QndJVFM5WEozIiwiZXZlbnRzX3VybCI6IndzczovL2dsb2JhbC5lZXZlZS5ldmVudHMubGl2ZS12aWRlby5uZXQiLCJ3aGlwX3VybCI6Imh0dHBzOi8vNzhmYzU2ZmVkYjI0Lmdsb2JhbC1ibS53aGlwLmxpdmUtdmlkZW8ubmV0IiwidXNlcl9pZCI6IkJhbGF6cyIsImNhcGFiaWxpdGllcyI6eyJhbGxvd19wdWJsaXNoIjp0cnVlLCJhbGxvd19zdWJzY3JpYmUiOnRydWV9LCJ2ZXJzaW9uIjoiMC4wIn0.MGQCMCToYOUcmym68_U30PQmsCcxfAS2G5_Mbl0mKftmg7qvTiKkGAYyQ-r4qejUMhSemQIwGYjErUP8oOilHLazAJEXWSgpKsUcWTGL59saWDWLnDZQqmNUZ5o-dBFEdy6uaSh7"
    var audioDevice: AudioDevice? = null
    lateinit var deviceDiscovery: DeviceDiscovery
    var publishStreams: ArrayList<LocalStageStream> = ArrayList()
    private var stage: Stage? = null

//    private val NUMBER_OF_CHANNELS = 1
//    private val AUDIO_FORMAT_NR_OF_BYTES = 2

    private val stageStrategy = object : Stage.Strategy {
        override fun stageStreamsToPublishForParticipant(
            stage: Stage,
            participantInfo: ParticipantInfo,
        ): List<LocalStageStream> {
            return publishStreams
        }

        override fun shouldPublishFromParticipant(
            stage: Stage,
            participantInfo: ParticipantInfo,
        ): Boolean {
            return true
        }

        override fun shouldSubscribeToParticipant(
            stage: Stage,
            participantInfo: ParticipantInfo,
        ): Stage.SubscribeType {
            return Stage.SubscribeType.NONE
        }
    }

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var presentationTimeInUs:Double = 0.0

    lateinit var byteBuffer: ByteBuffer

    // Amazon IVS Requires to use the thread where the BroadcastSession was created
    private fun launchMain(block: suspend CoroutineScope.() -> Unit) = mainScope.launch(
        context = CoroutineExceptionHandler { _, e -> Log.d("Launch", "Coroutine failed ${e.localizedMessage}") },
        block = block
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.btnRecord)
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        checkPermissions()
        initializeRecorder()
        initIVS()
    }

    private fun initIVS() {
        val sampleRate = when (sampleRate) {
            8000 -> BroadcastConfiguration.AudioSampleRate.RATE_8000
            16000 -> BroadcastConfiguration.AudioSampleRate.RATE_16000
            22050 -> BroadcastConfiguration.AudioSampleRate.RATE_22050
            44100 -> BroadcastConfiguration.AudioSampleRate.RATE_44100
            48000 -> BroadcastConfiguration.AudioSampleRate.RATE_48000
            else -> BroadcastConfiguration.AudioSampleRate.RATE_44100
        }

        deviceDiscovery = DeviceDiscovery(this)
        audioDevice =
            deviceDiscovery.createAudioInputSource(1, sampleRate, AudioDevice.Format.INT16)

        val microphoneStream = AudioLocalStageStream(audioDevice!!)
        publishStreams.add(microphoneStream)

        stage = Stage(this, TOKEN.trim(), stageStrategy)
        stage?.join()
    }

    fun sendAudioToIVS(data: ByteArray?, nrOfBytes: Int) {
        launchMain {
            byteBuffer.put(data!!)

            val success = audioDevice!!.appendBuffer(byteBuffer, nrOfBytes.toLong(), presentationTimeInUs.toLong())
            if (success < 0) {
                Log.e("AmazonIVS", "Error appending to audio device buffer: ${byteBuffer.isDirect}")
            }

            val numberOfSamples = nrOfBytes / 2
            presentationTimeInUs += numberOfSamples * 1000000 / sampleRate
            byteBuffer.clear()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun initializeRecorder() {
        bufferSizeRecorder = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        byteBuffer = ByteBuffer.allocateDirect(bufferSizeRecorder)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeRecorder)
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { // check for proper initialization
            Log.e("MainActivity", "error initializing " + recorder.getState().toString());
            return;
        }
    }

    private fun startRecording() {
        recorder.startRecording()
        isRecording = true
        recordingThread = Thread { sendAudioToIVS() }
        recordingThread.start()
        recordButton.text = "Stop Recording"
    }

    private fun stopRecording() {
        isRecording = false
        recorder.stop()
        stage?.leave()
        try {
            recordingThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        recordButton.text = "Start Recording"
    }

    private fun sendAudioToIVS() {
        val byteArray = ByteArray(bufferSizeRecorder / 2)
        while (isRecording) {
            val readBytes = recorder.read(byteArray, 0, byteArray.size)
            if (readBytes > 0) {
                sendAudioToIVS(byteArray, readBytes)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::recorder.isInitialized) {
            recorder.release()
        }
    }
}

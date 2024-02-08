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
import com.synervoz.switchboard.sdk.audiograph.AudioBuffer
import com.synervoz.switchboard.sdk.audiograph.AudioBus
import com.synervoz.switchboard.sdk.audiograph.AudioBusList
import com.synervoz.switchboard.sdk.audiograph.AudioData
import com.synervoz.switchboard.sdk.audiograph.AudioGraph
import com.synervoz.switchboard.sdk.audiographnodes.MonoToMultiChannelNode
import com.synervoz.switchboard.sdk.audiographnodes.MultiChannelToMonoNode
import com.synervoz.switchboardsuperpowered.audiographnodes.EchoNode
import com.synervoz.switchboardsuperpowered.audiographnodes.ReverbNode
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
    val TOKEN = ""
    var audioDevice: AudioDevice? = null
    lateinit var deviceDiscovery: DeviceDiscovery
    var publishStreams: ArrayList<LocalStageStream> = ArrayList()
    private var stage: Stage? = null

    private val NUMBER_OF_CHANNELS = 1
    private val AUDIO_FORMAT_NR_OF_BYTES = 2

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

    // SwitchboardSDK
    val audioGraph = AudioGraph()
    val reverbNode = ReverbNode()
    val monoToMultiChannelNode = MonoToMultiChannelNode()
    val multiChannelToMonoNode = MultiChannelToMonoNode()


    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var presentationTimeInUs:Double = 0.0

    lateinit var byteBuffer: ByteBuffer

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
    }

    private fun initAudioGraph() {
        reverbNode.isEnabled = true

        audioGraph.addNode(reverbNode)
        audioGraph.addNode(monoToMultiChannelNode)
        audioGraph.addNode(multiChannelToMonoNode)

        audioGraph.connect(audioGraph.inputNode, monoToMultiChannelNode)
        audioGraph.connect(monoToMultiChannelNode, reverbNode)
        audioGraph.connect(reverbNode, multiChannelToMonoNode)
        audioGraph.connect(multiChannelToMonoNode, audioGraph.outputNode)

        audioGraph.start()
    }

    fun processByteArray(inByteArray: ByteArray, outByteArray: ByteArray, nrOfBytesToProcess: Int) {

        val inFloatArray = convertInt16ByteArrayToFloat(inByteArray)
        val numberOfFramesToProcess = nrOfBytesToProcess / (NUMBER_OF_CHANNELS * AUDIO_FORMAT_NR_OF_BYTES)

        val inAudioData = AudioData(NUMBER_OF_CHANNELS, numberOfFramesToProcess)
        val outAudioData = AudioData(NUMBER_OF_CHANNELS, numberOfFramesToProcess)
        val inAudioBuffer = AudioBuffer(NUMBER_OF_CHANNELS, numberOfFramesToProcess, false, sampleRate, inAudioData)
        val outAudioBuffer = AudioBuffer(NUMBER_OF_CHANNELS, numberOfFramesToProcess, false, sampleRate, outAudioData)

        inAudioBuffer.copyFrom(inFloatArray, numberOfFramesToProcess * NUMBER_OF_CHANNELS)
        processAudioBuffer(inAudioBuffer, outAudioBuffer)

        for (i in inFloatArray.indices) {
            // TODO: handle stereo if needed
            val floatSample = outAudioBuffer.getSample(0, i)
            val int16Sample = (floatSample * 32767.0f).toInt()
            outByteArray[i * 2] = (int16Sample and 0xFF).toByte()              // Lower byte
            outByteArray[i * 2 + 1] = ((int16Sample shr 8) and 0xFF).toByte()  // Higher byte
        }
    }

    fun convertInt16ByteArrayToFloat(byteArray: ByteArray): FloatArray {
        val floatArray = FloatArray(byteArray.size / 2)
        for (i in byteArray.indices step 2) {
            val int16Value = (byteArray[i + 1].toInt() shl 8) or (byteArray[i].toInt() and 0xFF)
            floatArray[i / 2] = int16Value / 32768.0f
        }
        return floatArray
    }



    fun processAudioBuffer(inAudioBuffer: AudioBuffer, outAudioBuffer: AudioBuffer) {
        val inAudioBus = AudioBus(inAudioBuffer)
        val inAudioBusList = AudioBusList(inAudioBus)
        val outAudioBus = AudioBus(outAudioBuffer)
        val outAudioBusList = AudioBusList(outAudioBus)
        audioGraph.process(inAudioBusList, outAudioBusList)
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
        } else {
            // Permission already granted, initialize everything here
            initializeRecorder()
            initIVS()
            initAudioGraph()
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
        recordingThread = Thread { processRecording() }
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

    private fun processRecording() {
        // assign size so that bytes are read in in chunks inferior to AudioRecord internal buffer size
        val byteArray = ByteArray(bufferSizeRecorder)
        val processedByteArray = ByteArray(bufferSizeRecorder )
        while (isRecording) {
            val readBytes = recorder.read(byteArray, 0, byteArray.size)
            if (readBytes > 0) {
                processByteArray(byteArray, processedByteArray, readBytes)
                sendAudioToIVS(processedByteArray, readBytes)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::recorder.isInitialized) {
            recorder.release()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // RECORD_AUDIO permission was granted, initialize everything here
            initializeRecorder()
            initIVS()
            initAudioGraph()
        } else {
            // Handle the case where the user denies the permission
            // You might want to close the app or disable certain features
        }
    }
}

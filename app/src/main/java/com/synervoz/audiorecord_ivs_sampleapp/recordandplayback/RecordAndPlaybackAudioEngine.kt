package com.synervoz.audiorecord_ivs_sampleapp.recordandplayback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.synervoz.switchboard.sdk.Codec
import com.synervoz.switchboard.sdk.SwitchboardSDK
import com.synervoz.switchboard.sdk.audioengine.AudioEngine
import com.synervoz.switchboard.sdk.audiograph.AudioBuffer
import com.synervoz.switchboard.sdk.audiograph.AudioBus
import com.synervoz.switchboard.sdk.audiograph.AudioBusList
import com.synervoz.switchboard.sdk.audiograph.AudioData
import com.synervoz.switchboard.sdk.audiograph.AudioGraph
import com.synervoz.switchboard.sdk.audiographnodes.AudioPlayerNode
import com.synervoz.switchboard.sdk.audiographnodes.MonoToMultiChannelNode
import com.synervoz.switchboard.sdk.audiographnodes.MultiChannelToMonoNode
import com.synervoz.switchboard.sdk.audiographnodes.RecorderNode
import com.synervoz.switchboard.sdk.logger.Logger
import com.synervoz.switchboardsuperpowered.audiographnodes.ReverbNode
import java.nio.ByteBuffer

class RecordAndPlaybackAudioEngine(val context: Context) {

    private var bufferSizeRecorder: Int = 0

    private val sampleRate = 48000
    private val numberOfChannels = 1
    private val audioFormatNrOfBytes = 2
    private lateinit var recorder: AudioRecord

    @Volatile
    private var isRecording = false
    private lateinit var recordingThread: Thread
    lateinit var recordingFilePath : String
    var currentFormat: Codec = Codec.WAV

    val audioGraph = AudioGraph()
    val audioPlaybackGraph = AudioGraph()
    val reverbNode = ReverbNode()
    val monoToMultiChannelNode = MonoToMultiChannelNode()
    val multiChannelToMonoNode = MultiChannelToMonoNode()
    val audioPlayerNode = AudioPlayerNode()
    val recorderNode = RecorderNode(sampleRate = sampleRate, numberOfChannels = numberOfChannels)

    lateinit var byteBuffer: ByteBuffer

    private val audioEngine = AudioEngine(context = context)


    init {
        reverbNode.isEnabled = true

        audioGraph.addNode(reverbNode)
        audioGraph.addNode(monoToMultiChannelNode)
        audioGraph.addNode(multiChannelToMonoNode)
        audioGraph.addNode(recorderNode)

        audioGraph.connect(audioGraph.inputNode, monoToMultiChannelNode)
        audioGraph.connect(monoToMultiChannelNode, reverbNode)
        audioGraph.connect(reverbNode, multiChannelToMonoNode)
        audioGraph.connect(multiChannelToMonoNode, recorderNode)

        recorderNode.start()
        audioGraph.start()

        audioPlaybackGraph.addNode(audioPlayerNode)
        audioPlaybackGraph.connect(audioPlayerNode, audioPlaybackGraph.outputNode)

        initializeAudioRecord()
    }

    fun isRecording(): Boolean = isRecording
    fun isPlaying(): Boolean = audioPlayerNode.isPlaying

    @SuppressLint("MissingPermission")
    private fun initializeAudioRecord() {
        bufferSizeRecorder = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        byteBuffer = ByteBuffer.allocateDirect(bufferSizeRecorder)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeRecorder
        )
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { // check for proper initialization
            Log.e(
                "RecordAndPlaybackAudioEngine",
                "error initializing " + recorder.getState().toString()
            );
            return;
        }
    }


    fun startRecording() {
        recorder.startRecording()
        isRecording = true
        recordingThread = Thread { processRecording() }
        recordingThread.start()
    }

    fun stopRecording() {
        isRecording = false
        recorder.stop()
        try {
            recordingThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        recordingFilePath = context.getExternalFilesDir(null)?.absolutePath +  "/test_recording"+ "." + currentFormat.fileExtension
        recorderNode.stop(recordingFilePath, currentFormat)
        val loaded = audioPlayerNode.load(recordingFilePath, currentFormat)
        Logger.debug("Recorded file loaded: $loaded")
    }

    private fun processRecording() {
        // assign size so that bytes are read in in chunks inferior to AudioRecord internal buffer size
        val byteArray = ByteArray(bufferSizeRecorder)
        val processedByteArray = ByteArray(bufferSizeRecorder)
        while (isRecording) {
            val readBytes = recorder.read(byteArray, 0, byteArray.size)
            if (readBytes > 0) {
                processByteArray(byteArray, processedByteArray, readBytes)
            }
        }
    }

    fun processByteArray(inByteArray: ByteArray, outByteArray: ByteArray, nrOfBytesToProcess: Int) {

        val inFloatArray = convertInt16ByteArrayToFloat(inByteArray)
        val numberOfFramesToProcess =
            nrOfBytesToProcess / (numberOfChannels * audioFormatNrOfBytes)

        val inAudioData = AudioData(numberOfChannels, numberOfFramesToProcess)
        val outAudioData = AudioData(numberOfChannels, numberOfFramesToProcess)
        val inAudioBuffer =
            AudioBuffer(numberOfChannels, numberOfFramesToProcess, false, sampleRate, inAudioData)
        val outAudioBuffer = AudioBuffer(
            numberOfChannels,
            numberOfFramesToProcess,
            false,
            sampleRate,
            outAudioData
        )

        inAudioBuffer.copyFrom(inFloatArray, numberOfFramesToProcess * numberOfChannels)
        processAudioBuffer(inAudioBuffer, outAudioBuffer)
    }

    fun processAudioBuffer(inAudioBuffer: AudioBuffer, outAudioBuffer: AudioBuffer) {
        val inAudioBus = AudioBus(inAudioBuffer)
        val inAudioBusList = AudioBusList(inAudioBus)
        val outAudioBusList = AudioBusList(numberOfBuses = 0)
        audioGraph.process(inAudioBusList, outAudioBusList)
    }

    fun convertInt16ByteArrayToFloat(byteArray: ByteArray): FloatArray {
        val floatArray = FloatArray(byteArray.size / 2)
        for (i in byteArray.indices step 2) {
            val int16Value = (byteArray[i + 1].toInt() shl 8) or (byteArray[i].toInt() and 0xFF)
            floatArray[i / 2] = int16Value / 32768.0f
        }
        return floatArray
    }


    fun startPlayback() {
        audioPlayerNode.play()
        audioEngine.start(audioPlaybackGraph)
    }

    fun stopPlayback() {
        audioPlayerNode.stop()
        audioEngine.stop()
    }
}
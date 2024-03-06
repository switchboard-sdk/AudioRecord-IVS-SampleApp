package com.synervoz.audiorecord_ivs_sampleapp.recordandplayback

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.synervoz.audiorecord_ivs_sampleapp.databinding.FragmentRecordAndPlaybackBinding

class RecordAndPlaybackFragment: Fragment() {
    lateinit var binding: FragmentRecordAndPlaybackBinding
    lateinit var audioEngine: RecordAndPlaybackAudioEngine

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRecordAndPlaybackBinding.inflate(inflater, container, false)
        audioEngine = RecordAndPlaybackAudioEngine(requireActivity())

        binding.btnRecord.setOnClickListener {
            if (audioEngine.isRecording()) {
                binding.btnRecord.text = "Start recording"
                audioEngine.stopRecording()
            } else {
                binding.btnRecord.text = "Stop recording"
                audioEngine.startRecording()
            }
        }

        binding.btnPlayback.setOnClickListener {
            if (audioEngine.isPlaying()) {
                binding.btnPlayback.text = "Start playback"
                audioEngine.stopPlayback()
            } else {
                audioEngine.startPlayback()
                binding.btnPlayback.text = "Stop playback"
            }
        }

        return binding.root
    }
}
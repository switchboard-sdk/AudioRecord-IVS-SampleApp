package com.synervoz.audiorecord_ivs_sampleapp.utils

import android.content.Context
import androidx.fragment.app.Fragment
import com.synervoz.audiorecord_ivs_sampleapp.AudioRecordWithAmazonIVSFragment
import com.synervoz.switchboard.sdk.SwitchboardSDK
import com.synervoz.switchboardsuperpowered.SuperpoweredExtension

object ExampleProvider {
    fun initialize(context: Context) {
        SwitchboardSDK.initialize(context, switchboardClientID, switchboardClientSecret)
        SuperpoweredExtension.initialize(superpoweredLicenseKey)
    }

    fun examples(): List<Example> {
        return listOf(
            Example("AudioRecordWithAmazonIVSFragment", AudioRecordWithAmazonIVSFragment::class.java as Class<Fragment>),
        )
    }
}

data class Example (
    val title: String,
    var fragment: Class<Fragment>
)
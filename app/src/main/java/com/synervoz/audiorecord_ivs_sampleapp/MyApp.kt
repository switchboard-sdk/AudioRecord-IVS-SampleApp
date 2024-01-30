package com.synervoz.audiorecord_ivs_sampleapp

import android.app.Application
import com.synervoz.switchboard.sdk.SwitchboardSDK
import com.synervoz.switchboardsuperpowered.SuperpoweredExtension
import java.util.Collections.singleton




class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SwitchboardSDK.initialize(this, "clientId", "clientSecret")
        SuperpoweredExtension.initialize("ExampleLicenseKey-WillExpire-OnNextUpdate")
    }
}
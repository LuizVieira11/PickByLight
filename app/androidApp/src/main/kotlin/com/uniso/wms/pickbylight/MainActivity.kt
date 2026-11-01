package com.uniso.wms.pickbylight

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.uniso.wms.pickbylight.nfc.NfcController
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.FeedbackController

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppBuildConfig.setDynamicBrokerUrl(BuildConfig.MQTT_BROKER_URL)
        FeedbackController.init(this)
        NfcController.init(this)

        setContent {
            App(onCloseApp = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        NfcController.enableReaderMode(this, this)
    }

    override fun onPause() {
        super.onPause()
        NfcController.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag?) {
        val idBytes = tag?.id ?: return
        val uidFormatted = idBytes.joinToString(":") { "%02X".format(it) }
        runOnUiThread {
            NfcManager.onTagScanned(uidFormatted)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
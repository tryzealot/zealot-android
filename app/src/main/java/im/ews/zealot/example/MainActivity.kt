package im.ews.zealot.example

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import im.ews.zealot.Zealot

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Zealot.create(this)
            .setEndpoint("http://172.16.217.29:3000")
            .setChannelKey("3bc04a64f80e42053ebe0a108265a3bb")
            .setBuildType(BuildConfig.BUILD_TYPE)
            .setAlertMaxHeight(Zealot.ScreenHeight.HALFSCREEN)
            .launch()
    }
}

package com.transmiti2.transmiti2

import com.transmiti2.transmiti2.streaming.StreamChannelHandler
import com.transmiti2.transmiti2.streaming.StreamContract
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : FlutterActivity() {

    private val streamingScope = CoroutineScope(SupervisorJob())
    private var streamingChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            StreamContract.METHOD_CHANNEL,
        )
        channel.setMethodCallHandler(
            StreamChannelHandler(applicationContext, streamingScope),
        )
        streamingChannel = channel
    }

    override fun onDestroy() {
        streamingChannel?.setMethodCallHandler(null)
        streamingScope.cancel()
        super.onDestroy()
    }
}

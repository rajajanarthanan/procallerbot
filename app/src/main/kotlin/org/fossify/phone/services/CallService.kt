package org.fossify.phone.services

import android.app.KeyguardManager
import android.content.Context
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import okhttp3.WebSocket
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.helpers.WebSocketRegistry
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus

object ActiveCall{
    val calls = mutableSetOf<Call>()
    fun add(call: Call) {
        this.calls.add(call)
    }
    fun endActiveCall() {
        calls.forEach { it.disconnect() }
        calls.clear()
    }
}


class CallService : InCallService() {

    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            val number = call.details.handle?.schemeSpecificPart ?: "unknown"
            var updateState = ""
            var updateWs = false
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                updateState = "call_disconnected"
                updateWs=true
                callNotificationManager.cancelNotification()
            } else {
                if(state == Call.STATE_ACTIVE){
                    updateState = "call_connected"
                    updateWs=true
                }else if(state == Call.STATE_HOLDING){
                    updateState = "call_holing"
                    updateWs=true
                }
                callNotificationManager.setupNotification()
            }
            if(updateWs){
                val message = """
                                {
                                    "type": "call_state_changed",
                                    "event": "$updateState",
                                    "number": "$number"
                                }
                            """.trimIndent()
                WebSocketRegistry.current?.send(message)
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        ActiveCall.add(call)
        call.registerCallback(callListener)

        val isScreenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        if (!powerManager.isInteractive || call.isOutgoing() || isScreenLocked || config.alwaysShowFullscreen) {
            try {
                callNotificationManager.setupNotification(true)
                startActivity(CallActivity.getStartIntent(this))
            } catch (e: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        } else {
            callNotificationManager.setupNotification()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }
}

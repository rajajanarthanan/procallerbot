package org.fossify.phone.helpers

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.Request
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class ProfinixManager(private val context: Context) {
    private var auth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null
    private var webSocket: WebSocket? = null

    companion object {
        private const val TAG = "ProfinixManager"
        private const val wssDocPath = "procaller_servers/1"
        private var wssUrl: String? = null
        private var documentListener: ListenerRegistration? = null
    }

    init {
        setupFirebase()
    }

    private fun setupFirebase() {
        try{
            val app = FirebaseApp.initializeApp(context)
            auth = app?.let { FirebaseAuth.getInstance(it) }
            firestore = app?.let{ FirebaseFirestore.getInstance() }
            when {
                this.auth == null || this.firestore == null -> {
                    Log.e(TAG, "Firebase not initialized")
                }else -> {
                Log.d(TAG, "Setting up Firebase")
                if (auth!!.currentUser == null) {
                    firebaseLogin()
                }else{
                    startFirestoreListener()
                }
            }
            }

        }catch(e: Exception){
            Log.e(TAG,"setUpFirebase Error: $e")
        }

    }

    private fun startFirestoreListener() {
        this.firestore!!.document(wssDocPath).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to document", error)
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                Log.d(TAG, "Document data: ${snapshot.data}")
                val data = snapshot.data
                val wssUrlValue = data?.get("wssUrl") as? String

                if (wssUrlValue != null) {
                    Log.d(TAG, "WebSocket URL: $wssUrlValue")
                    wssUrl = wssUrlValue
                    connectToServer(wssUrl!!)
                } else {
                    Log.d(TAG, "wssUrl not found in document")
                }
            }
        }
    }

    private fun firebaseLogin() {
        CoroutineScope(Dispatchers.IO).launch {
            val userId = "dbuser@profinix.tech"
            val password = "password123"
            Log.d(TAG, "Firebase login initiated with username: $userId, $password")
            if (userId != null && password != null) {
                val authRes = auth!!.signInWithEmailAndPassword(userId, password).await()
                Log.d(TAG, "Firebase login initiated")
                if (authRes.user != null) {
                    Log.d(TAG, "Firebase login successful")
                    startFirestoreListener()
                } else {
                    Log.d(TAG, "Firebase login failed")
                }
            } else {
                Log.e(TAG, "Invalid UserId or Password: $userId, $password")
            }
        }
    }

    private fun connectToServer(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                Log.d("WebSocket", "Connected to $url")
                webSocket = ws
                val jsonPayload = """
                                {
                                    "type": "register",
                                    "id": "android1"
                                }
                            """.trimIndent()

                // Send the payload
                ws.send(jsonPayload)

            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("WebSocket", "Received message: $text")

                // You can parse and trigger call here, e.g.
                if (text.contains("\"dial\"")) {
                    val number = extractPhoneNumber(text)
                    Log.d("DialNumber",number)
                    dialNumber(number)
                }
            }



            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("WebSocket", "Received bytes")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e("WebSocket", "Error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocket", "Closed: $reason")
            }
        }

        client.newWebSocket(request, listener)
//        client.dispatcher.executorService.shutdown()
    }

    fun extractPhoneNumber(json: String): String {
        Log.d("JSONString", json)
        var number = JSONObject(JSONObject(json).optString("payload", "") as String).optString("dial", "")
        Log.d("extractedPhoneNumber","$number")
        return number
    }

}

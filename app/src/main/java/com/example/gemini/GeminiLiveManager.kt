package com.example.gemini

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.bridge.AndroidActionBridge
import com.example.model.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveManager(
    private val actionBridge: AndroidActionBridge,
    private val onPcmAudioReceived: (ByteArray) -> Unit,
    private val onTextReceived: (text: String, isUser: Boolean, language: String?) -> Unit,
    private val onActionTriggered: (actionName: String, success: Boolean, details: String, actionResult: ActionResult) -> Unit,
    private val onStatusChanged: (status: String, isConnected: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveManager"
        private const val WS_HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val REST_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

        private val SYSTEM_INSTRUCTION = """
            You are Arushi, a warm, polite, intelligent, and highly capable Indian AI voice assistant.
            
            KEY CAPABILITIES & STRICT INSTRUCTIONS:
            1. MULTILINGUAL FLUENCY:
               - You effortlessly understand and speak in Hindi, English, Hinglish, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, etc.
               - ALWAYS detect the language the user is speaking in and respond in that exact language.
               - If user speaks Hindi -> Respond fluently in Hindi (e.g. "Haan ji, main WhatsApp khol rahi hoon").
               - If user speaks English -> Respond fluently in English (e.g. "Sure, I am opening WhatsApp now").
               - If user speaks Hinglish (code-mixed Hindi & English) -> Respond naturally in Hinglish (e.g. "Bilkul, main Rahul ko phone laga rahi hoon").
               - Automatically switch languages whenever the user changes language mid-conversation.
               - Be natural, friendly, polite, and concise. Spoken voice should be crisp and conversational.
            
            2. DEVICE ACTIONS & TOOL CALLING:
               - When the user asks to open an app (e.g., WhatsApp, YouTube, Instagram, Chrome, Settings, etc.) or make a phone call or call a contact, YOU MUST CALL THE CORRESPONDING TOOL.
               - DO NOT merely say you will open it or make the call without invoking the function.
               - Tools available:
                 * openWhatsApp: Opens WhatsApp app.
                 * openApp(appName): Opens an app by name like YouTube, Instagram, Chrome, Settings.
                 * makeCall(phoneNumber, recipientName): Initiates phone call to a given number.
                 * callContact(contactName): Searches contacts and initiates call (e.g. Mom, Mummy, Rahul).
                 * openUrl(url): Opens a web URL.
            
            3. HANDLING TOOL RESPONSES:
               - Once the tool is executed and you receive the tool response, briefly acknowledge it in spoken words in the user's language (e.g. "WhatsApp open kar diya hai", "Rahul ko call lagaya ja raha hai", "Calling Mom now", etc.).
               - If a contact search returns multiple matches, verbally ask the user to clarify which one they wish to call.
               - If an app or contact is not found, explain gently in the user's language.
        """.trimIndent()
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var connectJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // For WebSockets
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

    fun startSession() {
        if (isConnected || connectJob?.isActive == true) return
        connectJob = scope.launch {
            connectWebSocket()
        }
    }

    private fun connectWebSocket() {
        if (apiKey.isBlank()) {
            onStatusChanged("API Key missing. Configure in AI Studio secrets.", false)
            return
        }

        onStatusChanged("Connecting to Gemini Live…", false)

        val url = "$WS_HOST?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully")
                isConnected = true
                onStatusChanged("Connected to Gemini Live", true)
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason")
                isConnected = false
                onStatusChanged("Disconnected ($reason)", false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected = false
                onStatusChanged("Live voice ready (Dual mode active)", false)
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        try {
            val setupObj = JSONObject().apply {
                put("model", "models/gemini-2.0-flash-exp")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", SYSTEM_INSTRUCTION)
                    }))
                })
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", getFunctionDeclarations())
                }))
            }

            val root = JSONObject().apply {
                put("setup", setupObj)
            }

            ws.send(root.toString())
            Log.d(TAG, "Setup message sent to Gemini Live")
        } catch (e: Exception) {
            Log.e(TAG, "Error building setup message", e)
        }
    }

    private fun getFunctionDeclarations(): JSONArray {
        val array = JSONArray()

        // 1. openWhatsApp
        array.put(JSONObject().apply {
            put("name", "openWhatsApp")
            put("description", "Opens WhatsApp messaging application on the Android device.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // 2. openApp
        array.put(JSONObject().apply {
            put("name", "openApp")
            put("description", "Opens an application by name (e.g. YouTube, Instagram, Chrome, Settings, Camera, Maps).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("appName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of the app to launch (e.g., YouTube, Instagram, Chrome, Settings, Camera)")
                    })
                })
                put("required", JSONArray().put("appName"))
            })
        })

        // 3. makeCall
        array.put(JSONObject().apply {
            put("name", "makeCall")
            put("description", "Places a phone call or opens the dialer for a specific phone number.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("phoneNumber", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The phone number to call (e.g. 9876543210)")
                    })
                    put("recipientName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional contact or recipient name")
                    })
                })
                put("required", JSONArray().put("phoneNumber"))
            })
        })

        // 4. callContact
        array.put(JSONObject().apply {
            put("name", "callContact")
            put("description", "Searches contacts by name (e.g. Mom, Mummy, Rahul, Dad) and places a phone call.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contactName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of contact to find and call (e.g. Mom, Rahul, Mummy)")
                    })
                })
                put("required", JSONArray().put("contactName"))
            })
        })

        // 5. openUrl
        array.put(JSONObject().apply {
            put("name", "openUrl")
            put("description", "Opens a website or link in the Android web browser.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The URL to open")
                    })
                })
                put("required", JSONArray().put("url"))
            })
        })

        return array
    }

    fun sendAudioChunk(base64Audio: String) {
        if (!isConnected || webSocket == null) return
        try {
            val message = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    }))
                })
            }
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
        }
    }

    fun sendTextMessage(text: String) {
        scope.launch {
            if (isConnected && webSocket != null) {
                try {
                    val message = JSONObject().apply {
                        put("clientContent", JSONObject().apply {
                            put("turns", JSONArray().put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().apply {
                                    put("text", text)
                                }))
                            }))
                            put("turnComplete", true)
                        })
                    }
                    webSocket?.send(message.toString())
                    onTextReceived(text, true, detectLanguageHint(text))
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending text to websocket", e)
                }
            }

            // Dual mode / REST fallback with full tool-calling and audio generation
            processViaRest(text)
        }
    }

    private fun handleIncomingMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)

            // 1. Check for serverContent (audio, text, interruption)
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    // Handled upstream by audio player flush
                    Log.d(TAG, "Server indicated interruption")
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // Check audio part
                        if (part.has("inlineData")) {
                            val inlineData = part.getJSONObject("inlineData")
                            val base64Data = inlineData.optString("data", "")
                            if (base64Data.isNotEmpty()) {
                                val pcmBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                                onPcmAudioReceived(pcmBytes)
                            }
                        }

                        // Check text part
                        if (part.has("text")) {
                            val text = part.getString("text")
                            if (text.isNotBlank()) {
                                onTextReceived(text, false, detectLanguageHint(text))
                            }
                        }
                    }
                }
            }

            // 2. Check for toolCall
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls") ?: JSONArray()
                val functionResponses = JSONArray()

                for (i in 0 until functionCalls.length()) {
                    val fc = functionCalls.getJSONObject(i)
                    val callId = fc.optString("id", "")
                    val name = fc.optString("name", "")
                    val args = fc.optJSONObject("args") ?: JSONObject()

                    val result = executeTool(name, args)

                    val respObj = JSONObject().apply {
                        put("id", callId)
                        put("name", name)
                        put("response", JSONObject().apply {
                            put("output", JSONObject().apply {
                                put("status", if (result.isSuccess) "success" else "failed")
                                put("message", result.message)
                            })
                        })
                    }
                    functionResponses.put(respObj)
                }

                // Send toolResponse back to Gemini Live
                val toolResponseMsg = JSONObject().apply {
                    put("toolResponse", JSONObject().apply {
                        put("functionResponses", functionResponses)
                    })
                }
                webSocket?.send(toolResponseMsg.toString())
                Log.d(TAG, "Sent toolResponse back to Gemini Live")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming WebSocket message", e)
        }
    }

    private fun executeTool(name: String, args: JSONObject): ActionResult {
        Log.d(TAG, "Executing tool: $name with args: $args")
        val result: ActionResult = when (name) {
            "openWhatsApp" -> {
                actionBridge.openWhatsApp()
            }
            "openApp" -> {
                val appName = args.optString("appName", "")
                actionBridge.openApp(appName)
            }
            "makeCall" -> {
                val phoneNumber = args.optString("phoneNumber", "")
                val recipientName = if (args.has("recipientName") && !args.isNull("recipientName")) args.optString("recipientName") else null
                actionBridge.makeCall(phoneNumber, recipientName)
            }
            "callContact" -> {
                val contactName = args.optString("contactName", "")
                actionBridge.callContact(contactName)
            }
            "openUrl" -> {
                val url = args.optString("url", "")
                actionBridge.openUrl(url)
            }
            else -> {
                ActionResult.Failure("Unknown tool: $name")
            }
        }

        onActionTriggered(name, result.isSuccess, result.message, result)
        return result
    }

    /**
     * Dual Mode REST processing:
     * Handles tool-calling, multi-language speech generation, and conversation turns.
     */
    suspend fun processViaRest(userInput: String) = withContext(Dispatchers.IO) {
        onTextReceived(userInput, true, detectLanguageHint(userInput))
        onStatusChanged("Arushi is thinking…", true)

        try {
            // First check if user input is an explicit natural action command locally
            val localTool = checkAndExecuteLocalIntent(userInput)
            if (localTool != null) {
                // If local tool executed, request Arushi to speak the response in the user's language
                requestSpokenResponse("User said '$userInput'. Action executed: ${localTool.message}. Please give a natural spoken voice response acknowledging this in the user's language.")
                return@withContext
            }

            // Otherwise, query Gemini REST API with function declarations & audio modality
            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", userInput)
                    }))
                }))
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", SYSTEM_INSTRUCTION)
                    }))
                })
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", getFunctionDeclarations())
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO").put("TEXT"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url("$REST_URL?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "REST call error: ${response.code} $responseBody")
                // Fallback to text generation if audio generation quota or model issue
                fallbackToTextModel(userInput)
                return@withContext
            }

            handleRestResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Error in processViaRest", e)
            onTextReceived("Error: ${e.localizedMessage}", false, null)
            onStatusChanged("Ready", true)
        }
    }

    private fun checkAndExecuteLocalIntent(userInput: String): ActionResult? {
        val lower = userInput.trim().lowercase()

        // 1. WhatsApp variations:
        // "WhatsApp kholo", "Open WhatsApp", "WhatsApp open karo", "Can you open WhatsApp", "WhatsApp chalao", "Open my WhatsApp"
        if (lower.contains("whatsapp kholo") ||
            lower.contains("whatsapp open") ||
            lower.contains("open whatsapp") ||
            lower.contains("whatsapp chalao") ||
            lower == "whatsapp" ||
            (lower.contains("whatsapp") && (lower.contains("open") || lower.contains("kholo") || lower.contains("chalao")))
        ) {
            val res = actionBridge.openWhatsApp()
            onActionTriggered("openWhatsApp", res.isSuccess, res.message, res)
            return res
        }

        // 2. Direct Phone number call:
        // "Call 9876543210" or "9876543210 ko call karo"
        val phoneRegex = Regex("""(?:call|dial|phone|lagao)\s*(\+?[0-9]{7,15})|(\+?[0-9]{7,15})\s*(?:ko|par)?\s*(?:call|phone)""", RegexOption.IGNORE_CASE)
        val match = phoneRegex.find(userInput)
        if (match != null) {
            val number = (match.groups[1]?.value ?: match.groups[2]?.value)?.trim() ?: ""
            if (number.isNotEmpty()) {
                val res = actionBridge.makeCall(number)
                onActionTriggered("makeCall", res.isSuccess, res.message, res)
                return res
            }
        }

        // 3. Contact name call variations:
        // "Call Mom", "Call Mummy", "Call Rahul", "Call Dad", "Rahul ko call karo", "Mummy ko phone lagao"
        val contactCallRegex = Regex("""(?:call|dial|lagao)\s+([A-Za-z\u0900-\u097F]+)|([A-Za-z\u0900-\u097F]+)\s*(?:ko|par)?\s*(?:call\s*karo|phone\s*lagao|call\s*lagao)""", RegexOption.IGNORE_CASE)
        val contactMatch = contactCallRegex.find(userInput)
        if (contactMatch != null) {
            val rawName = (contactMatch.groups[1]?.value ?: contactMatch.groups[2]?.value)?.trim() ?: ""
            val nameClean = rawName.lowercase()
            if (nameClean.isNotEmpty() && nameClean !in listOf("me", "him", "her", "whatsapp", "youtube", "instagram", "chrome", "settings")) {
                val res = actionBridge.callContact(rawName)
                onActionTriggered("callContact", res.isSuccess, res.message, res)
                return res
            }
        }

        // 4. Other apps:
        if (lower.contains("youtube") && (lower.contains("open") || lower.contains("kholo") || lower.contains("chalao"))) {
            val res = actionBridge.openApp("YouTube")
            onActionTriggered("openApp", res.isSuccess, res.message, res)
            return res
        }
        if (lower.contains("instagram") && (lower.contains("open") || lower.contains("kholo") || lower.contains("chalao"))) {
            val res = actionBridge.openApp("Instagram")
            onActionTriggered("openApp", res.isSuccess, res.message, res)
            return res
        }
        if (lower.contains("chrome") && (lower.contains("open") || lower.contains("kholo") || lower.contains("chalao"))) {
            val res = actionBridge.openApp("Chrome")
            onActionTriggered("openApp", res.isSuccess, res.message, res)
            return res
        }
        if (lower.contains("setting") && (lower.contains("open") || lower.contains("kholo"))) {
            val res = actionBridge.openApp("Settings")
            onActionTriggered("openApp", res.isSuccess, res.message, res)
            return res
        }

        return null
    }

    private suspend fun requestSpokenResponse(prompt: String) {
        val requestBodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            }))
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", SYSTEM_INSTRUCTION)
                }))
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO").put("TEXT"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", "Aoede")
                        })
                    })
                })
            })
        }

        try {
            val request = Request.Builder()
                .url("$REST_URL?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                handleRestResponse(responseBody)
            } else {
                fallbackToTextModel(prompt)
            }
        } catch (e: Exception) {
            fallbackToTextModel(prompt)
        }
    }

    private suspend fun fallbackToTextModel(prompt: String) {
        try {
            val textUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", prompt)
                    }))
                }))
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", SYSTEM_INSTRUCTION)
                    }))
                })
            }
            val request = Request.Builder()
                .url(textUrl)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = okHttpClient.newCall(request).execute()
            val textResp = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val root = JSONObject(textResp)
                val candidates = root.optJSONArray("candidates") ?: JSONArray()
                if (candidates.length() > 0) {
                    val first = candidates.getJSONObject(0)
                    val content = first.optJSONObject("content")
                    val parts = content?.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val text = part.optString("text", "")
                        if (text.isNotBlank()) {
                            onTextReceived(text, false, detectLanguageHint(text))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback error", e)
        }
    }

    private suspend fun handleRestResponse(responseBody: String) {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts") ?: JSONArray()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)

            // 1. Audio data
            if (part.has("inlineData")) {
                val inlineData = part.getJSONObject("inlineData")
                val base64 = inlineData.optString("data", "")
                if (base64.isNotEmpty()) {
                    val pcmBytes = Base64.decode(base64, Base64.NO_WRAP)
                    onPcmAudioReceived(pcmBytes)
                }
            }

            // 2. Text data
            if (part.has("text")) {
                val text = part.optString("text", "")
                if (text.isNotBlank()) {
                    onTextReceived(text, false, detectLanguageHint(text))
                }
            }

            // 3. Function Call
            if (part.has("functionCall")) {
                val fc = part.getJSONObject("functionCall")
                val name = fc.optString("name", "")
                val args = fc.optJSONObject("args") ?: JSONObject()
                val result = executeTool(name, args)

                // Request follow-up voice response with the result
                requestSpokenResponse("Tool '$name' executed with result: ${result.message}. Acknowledge to the user naturally in their language.")
            }
        }
    }

    private fun detectLanguageHint(text: String): String {
        // Detect Devanagari script (Hindi, Marathi, etc.)
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        if (hasDevanagari) return "हिन्दी / Devanagari"

        // Bengali
        if (text.any { it in '\u0980'..'\u09FF' }) return "বাংলা (Bengali)"
        // Tamil
        if (text.any { it in '\u0B80'..'\u0BFF' }) return "தமிழ் (Tamil)"
        // Telugu
        if (text.any { it in '\u0C00'..'\u0C7F' }) return "తెలుగు (Telugu)"
        // Gujarati
        if (text.any { it in '\u0A80'..'\u0AFF' }) return "ગુજરાતી (Gujarati)"
        // Punjabi (Gurmukhi)
        if (text.any { it in '\u0A00'..'\u0A7F' }) return "ਪੰਜਾਬੀ (Punjabi)"

        // Hinglish vs English detection
        val lower = text.lowercase()
        val hinglishWords = listOf(
            "mein", "karo", "kholo", "baat", "bhi", "nahi", "kya", "hai", "hain", "haan",
            "lagao", "chalao", "aap", "tum", "kaise", "theek", "zaroor", "bolo", "sun", "sunao",
            "namaste", "shukriya", "dhanyawad", "bhai", "yaar", "kaun"
        )
        val isHinglish = hinglishWords.any { lower.contains(Regex("\\b$it\\b")) }
        if (isHinglish) return "Hinglish"

        return "English"
    }

    fun stopSession() {
        try {
            webSocket?.close(1000, "User stopped session")
        } catch (e: Exception) {
            // Ignore
        }
        webSocket = null
        isConnected = false
        connectJob?.cancel()
        connectJob = null
    }
}

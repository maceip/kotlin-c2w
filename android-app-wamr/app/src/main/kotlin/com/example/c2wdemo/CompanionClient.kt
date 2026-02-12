package com.example.c2wdemo

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Client for the friscy companion app running on LAN.
 *
 * Discovery: mDNS service type `_friscy._tcp`
 * Protocol: HTTP REST
 *   - GET  /snapshots              -> JSON list of snapshot names
 *   - GET  /snapshots/{name}       -> snapshot binary
 *   - PUT  /snapshots/{name}       -> upload snapshot binary
 *   - DELETE /snapshots/{name}     -> delete snapshot
 */
class CompanionClient(private val context: Context) {

    companion object {
        private const val TAG = "CompanionClient"
        private const val SERVICE_TYPE = "_friscy._tcp."
        private const val CONNECT_TIMEOUT = 5_000
        private const val READ_TIMEOUT = 30_000
    }

    /** Discovered companion host + port. Null if not discovered. */
    var companionUrl: String? = null
        private set

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Start mDNS discovery for companion app. */
    fun startDiscovery() {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = mgr

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "Discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "Discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i(TAG, "Discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Found: ${serviceInfo.serviceName}")
                mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "Resolve failed: $errorCode")
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val port = info.port
                        companionUrl = "http://$host:$port"
                        Log.i(TAG, "Companion resolved: $companionUrl")
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Lost: ${serviceInfo.serviceName}")
                companionUrl = null
            }
        }

        discoveryListener = listener
        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Stop mDNS discovery. */
    fun stopDiscovery() {
        discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        discoveryListener = null
    }

    /** Set companion URL manually (fallback when mDNS doesn't work). */
    fun setManualUrl(url: String) {
        companionUrl = url
    }

    /** Upload a snapshot file to the companion. */
    suspend fun uploadSnapshot(snapshotFile: File): Boolean = withContext(Dispatchers.IO) {
        val base = companionUrl ?: return@withContext false
        val name = snapshotFile.nameWithoutExtension
        try {
            val conn = URL("$base/snapshots/$name").openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(snapshotFile.length())

            conn.outputStream.use { out ->
                snapshotFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            false
        }
    }

    /** Download a snapshot from the companion. */
    suspend fun downloadSnapshot(name: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        val base = companionUrl ?: return@withContext false
        try {
            val conn = URL("$base/snapshots/$name").openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return@withContext false
            }

            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }

    /** List snapshots available on the companion. */
    suspend fun listRemoteSnapshots(): List<String> = withContext(Dispatchers.IO) {
        val base = companionUrl ?: return@withContext emptyList()
        try {
            val conn = URL("$base/snapshots").openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return@withContext emptyList()
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Simple JSON array parsing: ["name1", "name2"]
            body.trim()
                .removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "List remote failed", e)
            emptyList()
        }
    }
}

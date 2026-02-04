package com.example.c2wdemo.registry

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * OCI Distribution Spec v2 registry client.
 *
 * Supports:
 *   - Pulling manifests (image index + image manifest)
 *   - Pulling config blobs
 *   - Pulling layer blobs (full download and Range requests)
 *   - OCI Image Layout over HTTP
 *   - Docker Hub token authentication (anonymous)
 *   - CORS-enabled registries
 */
class RegistryClient(
    private val onProgress: ((String) -> Unit)? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Token cache: registry -> token
    private val tokenCache = mutableMapOf<String, String>()

    /**
     * Resolve an image reference to its manifest, config, and layer descriptors.
     */
    suspend fun resolve(ref: ImageReference): ResolvedImage {
        return if (ref.isHttpLayout) {
            resolveHttpLayout(ref)
        } else {
            resolveRegistry(ref)
        }
    }

    // --- Registry-based resolution ---

    private suspend fun resolveRegistry(ref: ImageReference): ResolvedImage {
        log("Resolving ${ref}...")

        val (manifestBytes, contentType) = fetchManifest(ref)
        val mediaType = contentType ?: OciMediaTypes.IMAGE_MANIFEST

        return if (OciMediaTypes.isManifestList(mediaType)) {
            val index = json.decodeFromString<OciImageIndex>(manifestBytes.decodeToString())
            log("Image index with ${index.manifests.size} platforms")

            val platformDesc = selectPlatform(index)
                ?: throw RegistryException("No compatible platform found in image index")

            log("Selected platform: ${platformDesc.platform?.os}/${platformDesc.platform?.architecture}")

            val platformRef = ref.copy(digest = platformDesc.digest, tag = null)
            val (platManifestBytes, _) = fetchManifest(platformRef)
            val manifest = json.decodeFromString<OciImageManifest>(platManifestBytes.decodeToString())

            val config = fetchConfig(ref, manifest.config)

            ResolvedImage(
                reference = ref,
                manifest = manifest,
                config = config,
                source = ImageSource.Registry(ref),
            )
        } else {
            val manifest = json.decodeFromString<OciImageManifest>(manifestBytes.decodeToString())
            log("Manifest: ${manifest.layers.size} layers, config ${manifest.config.shortDigest}")

            val config = fetchConfig(ref, manifest.config)

            ResolvedImage(
                reference = ref,
                manifest = manifest,
                config = config,
                source = ImageSource.Registry(ref),
            )
        }
    }

    private fun fetchManifest(ref: ImageReference): Pair<ByteArray, String?> {
        val url = "${ref.registryBaseUrl}/v2/${ref.repository}/manifests/${ref.reference}"
        val acceptHeader = OciMediaTypes.MANIFEST_ACCEPT_TYPES.joinToString(", ")

        val request = Request.Builder()
            .url(url)
            .header("Accept", acceptHeader)
            .apply { addAuthHeader(ref, this) }
            .build()

        val response = executeWithAuth(ref, request)
        val body = response.body?.bytes()
            ?: throw RegistryException("Empty manifest response from $url")
        val contentType = response.header("Content-Type")
        return body to contentType
    }

    private fun fetchConfig(ref: ImageReference, descriptor: OciDescriptor): OciImageConfig {
        log("Fetching config ${descriptor.shortDigest}...")
        val bytes = fetchBlob(ref, descriptor.digest)
        return json.decodeFromString<OciImageConfig>(bytes.decodeToString())
    }

    /**
     * Fetch a complete blob by digest from a registry.
     */
    fun fetchBlob(ref: ImageReference, digest: String): ByteArray {
        val url = "${ref.registryBaseUrl}/v2/${ref.repository}/blobs/$digest"

        val request = Request.Builder()
            .url(url)
            .apply { addAuthHeader(ref, this) }
            .build()

        val response = executeWithAuth(ref, request)
        return response.body?.bytes()
            ?: throw RegistryException("Empty blob response for $digest")
    }

    /**
     * Fetch a byte range from a blob (for lazy pulling).
     */
    fun fetchBlobRange(ref: ImageReference, digest: String, offset: Long, length: Long): ByteArray {
        val url = "${ref.registryBaseUrl}/v2/${ref.repository}/blobs/$digest"
        val rangeEnd = offset + length - 1

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-$rangeEnd")
            .apply { addAuthHeader(ref, this) }
            .build()

        val response = executeWithAuth(ref, request)

        if (response.code != 206 && response.code != 200) {
            throw RegistryException("Range request failed: HTTP ${response.code}")
        }

        return response.body?.bytes()
            ?: throw RegistryException("Empty range response for $digest")
    }

    /**
     * Open a streaming connection to a blob.
     */
    fun streamBlob(ref: ImageReference, digest: String): InputStream {
        val url = "${ref.registryBaseUrl}/v2/${ref.repository}/blobs/$digest"

        val request = Request.Builder()
            .url(url)
            .apply { addAuthHeader(ref, this) }
            .build()

        val response = executeWithAuth(ref, request)
        return response.body?.byteStream()
            ?: throw RegistryException("Empty stream response for $digest")
    }

    // --- HTTP OCI Image Layout resolution ---

    private suspend fun resolveHttpLayout(ref: ImageReference): ResolvedImage {
        val baseUrl = ref.httpUrl ?: throw RegistryException("No HTTP URL for layout")
        log("Fetching OCI layout from $baseUrl...")

        val indexBytes = httpGet("$baseUrl/index.json")
        val layoutIndex = json.decodeFromString<OciLayoutIndex>(indexBytes.decodeToString())

        if (layoutIndex.manifests.isEmpty()) {
            throw RegistryException("Empty OCI layout index")
        }

        val manifestDesc = if (layoutIndex.manifests.size == 1) {
            layoutIndex.manifests[0]
        } else {
            layoutIndex.manifests.find { desc ->
                desc.platform?.let { p -> p.os == "linux" && p.architecture == "amd64" } == true
            } ?: layoutIndex.manifests[0]
        }

        val digestPath = manifestDesc.digest.replace(":", "/")
        val manifestBytes = httpGet("$baseUrl/blobs/$digestPath")

        val mediaType = manifestDesc.mediaType

        val manifest = if (OciMediaTypes.isManifestList(mediaType)) {
            val index = json.decodeFromString<OciImageIndex>(manifestBytes.decodeToString())
            val platDesc = selectPlatform(index)
                ?: throw RegistryException("No compatible platform in HTTP layout index")
            val platDigestPath = platDesc.digest.replace(":", "/")
            val platManifestBytes = httpGet("$baseUrl/blobs/$platDigestPath")
            json.decodeFromString<OciImageManifest>(platManifestBytes.decodeToString())
        } else {
            json.decodeFromString<OciImageManifest>(manifestBytes.decodeToString())
        }

        val configDigestPath = manifest.config.digest.replace(":", "/")
        val configBytes = httpGet("$baseUrl/blobs/$configDigestPath")
        val config = json.decodeFromString<OciImageConfig>(configBytes.decodeToString())

        log("Resolved: ${manifest.layers.size} layers")

        return ResolvedImage(
            reference = ref,
            manifest = manifest,
            config = config,
            source = ImageSource.HttpLayout(baseUrl),
        )
    }

    /**
     * Fetch a blob from an HTTP OCI Layout.
     */
    fun fetchHttpLayoutBlob(baseUrl: String, digest: String): ByteArray {
        val digestPath = digest.replace(":", "/")
        return httpGet("$baseUrl/blobs/$digestPath")
    }

    /**
     * Fetch a byte range from an HTTP OCI Layout blob.
     */
    fun fetchHttpLayoutBlobRange(
        baseUrl: String,
        digest: String,
        offset: Long,
        length: Long,
    ): ByteArray {
        val digestPath = digest.replace(":", "/")
        val url = "$baseUrl/blobs/$digestPath"
        val rangeEnd = offset + length - 1

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-$rangeEnd")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw RegistryException("HTTP layout range request failed: ${response.code}")
        }
        return response.body?.bytes()
            ?: throw RegistryException("Empty HTTP layout range response")
    }

    // --- Authentication ---

    private fun executeWithAuth(ref: ImageReference, request: Request): Response {
        val response = httpClient.newCall(request).execute()

        if (response.code == 401) {
            val authHeader = response.header("Www-Authenticate") ?: return response
            response.close()

            val token = fetchToken(ref, authHeader)
            tokenCache[ref.registry] = token

            val authedRequest = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()

            return httpClient.newCall(authedRequest).execute()
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw RegistryException(
                "Registry request failed: HTTP ${response.code} ${response.message} " +
                    "URL: ${request.url} Body: ${errorBody.take(500)}"
            )
        }

        return response
    }

    private fun addAuthHeader(ref: ImageReference, builder: Request.Builder) {
        tokenCache[ref.registry]?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }
    }

    private fun fetchToken(ref: ImageReference, wwwAuth: String): String {
        val params = parseWwwAuthenticate(wwwAuth)
        val realm = params["realm"] ?: throw RegistryException("No realm in WWW-Authenticate")
        val service = params["service"] ?: ""
        val scope = params["scope"] ?: "repository:${ref.repository}:pull"

        val tokenUrl = "$realm?service=$service&scope=$scope"
        log("Authenticating with $realm...")

        val request = Request.Builder().url(tokenUrl).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RegistryException("Token request failed: HTTP ${response.code}")
        }

        val body = response.body?.string()
            ?: throw RegistryException("Empty token response")

        val tokenJson = json.decodeFromString<TokenResponse>(body)
        return tokenJson.token ?: tokenJson.accessToken
            ?: throw RegistryException("No token in auth response")
    }

    private fun parseWwwAuthenticate(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val paramStr = header.removePrefix("Bearer ").removePrefix("bearer ")
        val regex = Regex("""(\w+)="([^"]*?)"""")
        regex.findAll(paramStr).forEach { match ->
            params[match.groupValues[1]] = match.groupValues[2]
        }
        return params
    }

    // --- Utility ---

    private fun httpGet(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RegistryException("HTTP GET failed: ${response.code} for $url")
        }
        return response.body?.bytes() ?: throw RegistryException("Empty response from $url")
    }

    private fun selectPlatform(index: OciImageIndex): OciDescriptor? {
        val amd64 = index.manifests.find { desc ->
            desc.platform?.let { it.os == "linux" && it.architecture == "amd64" } == true
        }
        if (amd64 != null) return amd64

        val arm64 = index.manifests.find { desc ->
            desc.platform?.let { it.os == "linux" && it.architecture == "arm64" } == true
        }
        if (arm64 != null) return arm64

        val linux = index.manifests.find { desc ->
            desc.platform?.os == "linux"
        }
        if (linux != null) return linux

        return index.manifests.firstOrNull()
    }

    private fun log(msg: String) {
        onProgress?.invoke(msg)
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

// --- Supporting types ---

@kotlinx.serialization.Serializable
private data class TokenResponse(
    val token: String? = null,
    @kotlinx.serialization.SerialName("access_token")
    val accessToken: String? = null,
    @kotlinx.serialization.SerialName("expires_in")
    val expiresIn: Long? = null,
)

sealed class ImageSource {
    data class Registry(val ref: ImageReference) : ImageSource()
    data class HttpLayout(val baseUrl: String) : ImageSource()
}

data class ResolvedImage(
    val reference: ImageReference,
    val manifest: OciImageManifest,
    val config: OciImageConfig,
    val source: ImageSource,
) {
    val layerCount: Int get() = manifest.layers.size

    val totalLayerSize: Long get() = manifest.layers.sumOf { it.size }

    val hasEstargzLayers: Boolean
        get() = manifest.layers.any { it.hasEstargzToc }

    fun layerSummary(): String = buildString {
        append("${manifest.layers.size} layers, ")
        append("total ${totalLayerSize / 1024 / 1024} MB")
        if (hasEstargzLayers) {
            val esgzCount = manifest.layers.count { it.hasEstargzToc }
            append(", $esgzCount eStargz (lazy-pullable)")
        }
    }
}

class RegistryException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

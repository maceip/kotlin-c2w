package com.example.c2wdemo.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OCI Distribution Spec and Image Spec data models.
 *
 * Implements:
 *   - OCI Image Index (fat manifest / multi-platform)
 *   - OCI Image Manifest
 *   - OCI Image Configuration
 *   - OCI Content Descriptors
 *   - eStargz TOC (Table of Contents)
 */

// --- OCI Media Types ---

object OciMediaTypes {
    const val IMAGE_INDEX = "application/vnd.oci.image.index.v1+json"
    const val IMAGE_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
    const val IMAGE_CONFIG = "application/vnd.oci.image.config.v1+json"
    const val LAYER_TAR_GZIP = "application/vnd.oci.image.layer.v1.tar+gzip"
    const val LAYER_TAR = "application/vnd.oci.image.layer.v1.tar"
    const val LAYER_TAR_ZSTD = "application/vnd.oci.image.layer.v1.tar+zstd"

    // Docker manifest types (for compatibility)
    const val DOCKER_MANIFEST_V2 = "application/vnd.docker.distribution.manifest.v2+json"
    const val DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json"
    const val DOCKER_LAYER_GZIP = "application/vnd.docker.image.rootfs.diff.tar.gzip"
    const val DOCKER_CONFIG = "application/vnd.docker.container.image.v1+json"

    /** All accepted manifest media types for Accept header. */
    val MANIFEST_ACCEPT_TYPES = listOf(
        IMAGE_MANIFEST,
        IMAGE_INDEX,
        DOCKER_MANIFEST_V2,
        DOCKER_MANIFEST_LIST,
    )

    fun isManifestList(mediaType: String): Boolean =
        mediaType == IMAGE_INDEX || mediaType == DOCKER_MANIFEST_LIST

    fun isGzipLayer(mediaType: String): Boolean =
        mediaType == LAYER_TAR_GZIP || mediaType == DOCKER_LAYER_GZIP
}

// --- OCI Descriptor ---

@Serializable
data class OciDescriptor(
    val mediaType: String = "",
    val digest: String = "",
    val size: Long = 0,
    val annotations: Map<String, String>? = null,
    val platform: OciPlatform? = null,
    val urls: List<String>? = null,
) {
    /** Short digest for display (first 12 chars). */
    val shortDigest: String
        get() = digest.substringAfter("sha256:").take(12)

    /** Check if this layer has eStargz TOC digest annotation. */
    val hasEstargzToc: Boolean
        get() = annotations?.containsKey(ESTARGZ_TOC_DIGEST_ANNOTATION) == true

    /** Get the eStargz TOC digest from annotations. */
    val estargzTocDigest: String?
        get() = annotations?.get(ESTARGZ_TOC_DIGEST_ANNOTATION)

    companion object {
        const val ESTARGZ_TOC_DIGEST_ANNOTATION =
            "containerd.io/snapshot/stargz/toc.digest"
    }
}

// --- OCI Platform ---

@Serializable
data class OciPlatform(
    val architecture: String = "",
    val os: String = "",
    @SerialName("os.version")
    val osVersion: String? = null,
    @SerialName("os.features")
    val osFeatures: List<String>? = null,
    val variant: String? = null,
    val features: List<String>? = null,
)

// --- OCI Image Index (fat manifest) ---

@Serializable
data class OciImageIndex(
    val schemaVersion: Int = 2,
    val mediaType: String? = null,
    val manifests: List<OciDescriptor> = emptyList(),
    val annotations: Map<String, String>? = null,
)

// --- OCI Image Manifest ---

@Serializable
data class OciImageManifest(
    val schemaVersion: Int = 2,
    val mediaType: String? = null,
    val config: OciDescriptor = OciDescriptor(),
    val layers: List<OciDescriptor> = emptyList(),
    val annotations: Map<String, String>? = null,
)

// --- OCI Image Configuration ---

@Serializable
data class OciImageConfig(
    val architecture: String = "",
    val os: String = "",
    val config: ContainerConfig? = null,
    val rootfs: RootFs? = null,
    val history: List<HistoryEntry>? = null,
)

@Serializable
data class ContainerConfig(
    @SerialName("Env")
    val env: List<String>? = null,
    @SerialName("Cmd")
    val cmd: List<String>? = null,
    @SerialName("Entrypoint")
    val entrypoint: List<String>? = null,
    @SerialName("WorkingDir")
    val workingDir: String? = null,
    @SerialName("User")
    val user: String? = null,
    @SerialName("ExposedPorts")
    val exposedPorts: Map<String, @Serializable EmptyObject>? = null,
    @SerialName("Volumes")
    val volumes: Map<String, @Serializable EmptyObject>? = null,
    @SerialName("Labels")
    val labels: Map<String, String>? = null,
)

@Serializable
class EmptyObject

@Serializable
data class RootFs(
    val type: String = "",
    @SerialName("diff_ids")
    val diffIds: List<String> = emptyList(),
)

@Serializable
data class HistoryEntry(
    val created: String? = null,
    @SerialName("created_by")
    val createdBy: String? = null,
    @SerialName("empty_layer")
    val emptyLayer: Boolean? = null,
    val comment: String? = null,
)

// --- OCI Image Layout (for HTTP serving) ---

@Serializable
data class OciLayout(
    val imageLayoutVersion: String = "",
)

@Serializable
data class OciLayoutIndex(
    val schemaVersion: Int = 2,
    val manifests: List<OciDescriptor> = emptyList(),
    val mediaType: String? = null,
)

// --- eStargz TOC (Table of Contents) ---

@Serializable
data class EstargzToc(
    val version: Int = 1,
    val entries: List<EstargzTocEntry> = emptyList(),
) {
    /** Find all regular file entries. */
    fun files(): List<EstargzTocEntry> =
        entries.filter { it.type == "reg" }

    /** Find the prefetch landmark entry. */
    fun prefetchLandmark(): EstargzTocEntry? =
        entries.find { it.name == PrefetchLandmark }

    /** Get entries that should be prefetched (before the landmark). */
    fun prefetchEntries(): List<EstargzTocEntry> {
        val landmark = prefetchLandmark() ?: return emptyList()
        val landmarkIdx = entries.indexOf(landmark)
        return entries.subList(0, landmarkIdx).filter { it.type == "reg" || it.type == "chunk" }
    }

    companion object {
        const val PrefetchLandmark = ".prefetch.landmark"
    }
}

@Serializable
data class EstargzTocEntry(
    val name: String = "",
    val type: String = "",                  // dir, reg, symlink, hardlink, char, block, fifo, chunk
    val size: Long = 0,
    val offset: Long = 0,                   // Offset of gzip header in compressed blob
    val linkName: String? = null,
    @SerialName("modtime")
    val modTime: String? = null,
    val mode: Long? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val uname: String? = null,
    val gname: String? = null,
    val digest: String? = null,             // SHA256 of uncompressed file content
    val chunkOffset: Long = 0,              // Offset within the file for chunk entries
    val chunkSize: Long = 0,                // Size of this chunk (uncompressed)
    val chunkDigest: String? = null,        // SHA256 of chunk data
    @SerialName("xattrs")
    val xAttrs: Map<String, String>? = null,
    @SerialName("numLink")
    val numLink: Int = 0,
    val devMajor: Int? = null,
    val devMinor: Int? = null,
) {
    val isDirectory: Boolean get() = type == "dir"
    val isRegularFile: Boolean get() = type == "reg"
    val isSymlink: Boolean get() = type == "symlink"
    val isChunk: Boolean get() = type == "chunk"
}

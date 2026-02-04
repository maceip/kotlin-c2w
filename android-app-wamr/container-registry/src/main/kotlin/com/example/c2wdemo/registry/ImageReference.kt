package com.example.c2wdemo.registry

/**
 * Parses and represents an OCI container image reference.
 *
 * Supported formats:
 *   - registry.example.com/repo:tag
 *   - registry.example.com/repo@sha256:digest
 *   - registry.example.com:5000/repo:tag
 *   - library/alpine:3.18 (defaults to docker.io)
 *   - alpine:3.18 (defaults to docker.io/library/)
 *   - http://host/path (OCI Image Layout over HTTP)
 */
data class ImageReference(
    val registry: String,
    val repository: String,
    val tag: String?,
    val digest: String?,
    val isHttpLayout: Boolean = false,
    val httpUrl: String? = null,
) {
    /** The reference string used in registry API calls (tag or digest). */
    val reference: String
        get() = digest ?: tag ?: "latest"

    /** Whether this references a specific digest. */
    val isDigestReference: Boolean
        get() = digest != null

    /** Base URL for registry API v2 calls. */
    val registryBaseUrl: String
        get() {
            val scheme = if (registry.startsWith("localhost") ||
                registry.contains("localhost:") ||
                registry.matches(Regex("""^\d+\.\d+\.\d+\.\d+.*"""))
            ) "http" else "https"
            return "$scheme://$registry"
        }

    /** Full image name without tag/digest. */
    val fullName: String
        get() = "$registry/$repository"

    override fun toString(): String = when {
        isHttpLayout -> httpUrl ?: "$registry/$repository"
        digest != null -> "$registry/$repository@$digest"
        else -> "$registry/$repository:${tag ?: "latest"}"
    }

    companion object {
        /**
         * Parse an image reference string into its components.
         */
        fun parse(ref: String): ImageReference {
            // Handle HTTP(S) URLs (OCI Image Layout)
            if (ref.startsWith("http://") || ref.startsWith("https://")) {
                return ImageReference(
                    registry = "",
                    repository = "",
                    tag = null,
                    digest = null,
                    isHttpLayout = true,
                    httpUrl = ref.trimEnd('/')
                )
            }

            var remaining = ref

            // Split off digest
            var digest: String? = null
            val atIdx = remaining.indexOf('@')
            if (atIdx >= 0) {
                digest = remaining.substring(atIdx + 1)
                remaining = remaining.substring(0, atIdx)
            }

            // Split off tag
            var tag: String? = null
            if (digest == null) {
                val colonIdx = remaining.lastIndexOf(':')
                // Make sure this colon is in the name part, not a port
                val slashIdx = remaining.lastIndexOf('/')
                if (colonIdx > slashIdx) {
                    tag = remaining.substring(colonIdx + 1)
                    remaining = remaining.substring(0, colonIdx)
                }
            }

            // Determine registry and repository
            val parts = remaining.split('/')
            val registry: String
            val repository: String

            when {
                // Single component: e.g. "alpine" -> docker.io/library/alpine
                parts.size == 1 -> {
                    registry = "registry-1.docker.io"
                    repository = "library/${parts[0]}"
                }
                // Two components: could be registry/repo or namespace/repo
                parts.size == 2 -> {
                    if (parts[0].contains('.') || parts[0].contains(':')) {
                        // Looks like a registry hostname
                        registry = parts[0]
                        repository = parts[1]
                    } else {
                        // Docker Hub namespace/repo
                        registry = "registry-1.docker.io"
                        repository = "${parts[0]}/${parts[1]}"
                    }
                }
                // Three or more: first is registry
                else -> {
                    registry = parts[0]
                    repository = parts.drop(1).joinToString("/")
                }
            }

            if (tag == null && digest == null) {
                tag = "latest"
            }

            return ImageReference(
                registry = registry,
                repository = repository,
                tag = tag,
                digest = digest,
            )
        }
    }
}

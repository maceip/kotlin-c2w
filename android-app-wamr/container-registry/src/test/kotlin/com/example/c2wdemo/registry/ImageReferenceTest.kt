package com.example.c2wdemo.registry

import org.junit.Assert.*
import org.junit.Test

class ImageReferenceTest {

    @Test
    fun `parse simple image name defaults to docker hub`() {
        val ref = ImageReference.parse("alpine")
        assertEquals("registry-1.docker.io", ref.registry)
        assertEquals("library/alpine", ref.repository)
        assertEquals("latest", ref.tag)
        assertNull(ref.digest)
        assertFalse(ref.isHttpLayout)
    }

    @Test
    fun `parse image with tag`() {
        val ref = ImageReference.parse("alpine:3.18")
        assertEquals("registry-1.docker.io", ref.registry)
        assertEquals("library/alpine", ref.repository)
        assertEquals("3.18", ref.tag)
    }

    @Test
    fun `parse namespace and repo defaults to docker hub`() {
        val ref = ImageReference.parse("library/ubuntu:22.04")
        assertEquals("registry-1.docker.io", ref.registry)
        assertEquals("library/ubuntu", ref.repository)
        assertEquals("22.04", ref.tag)
    }

    @Test
    fun `parse custom registry with port`() {
        val ref = ImageReference.parse("localhost:5000/myimage:v1")
        assertEquals("localhost:5000", ref.registry)
        assertEquals("myimage", ref.repository)
        assertEquals("v1", ref.tag)
    }

    @Test
    fun `parse registry with domain`() {
        val ref = ImageReference.parse("ghcr.io/owner/repo:latest")
        assertEquals("ghcr.io", ref.registry)
        assertEquals("owner/repo", ref.repository)
        assertEquals("latest", ref.tag)
    }

    @Test
    fun `parse digest reference`() {
        val digest = "sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val ref = ImageReference.parse("alpine@$digest")
        assertEquals("registry-1.docker.io", ref.registry)
        assertEquals("library/alpine", ref.repository)
        assertNull(ref.tag)
        assertEquals(digest, ref.digest)
        assertTrue(ref.isDigestReference)
    }

    @Test
    fun `parse http url as OCI layout`() {
        val ref = ImageReference.parse("http://example.com/images/myapp")
        assertTrue(ref.isHttpLayout)
        assertEquals("http://example.com/images/myapp", ref.httpUrl)
    }

    @Test
    fun `parse https url as OCI layout`() {
        val ref = ImageReference.parse("https://example.com/oci/layout/")
        assertTrue(ref.isHttpLayout)
        assertEquals("https://example.com/oci/layout", ref.httpUrl) // trailing slash removed
    }

    @Test
    fun `registryBaseUrl uses https for public registries`() {
        val ref = ImageReference.parse("ghcr.io/owner/repo:v1")
        assertEquals("https://ghcr.io", ref.registryBaseUrl)
    }

    @Test
    fun `registryBaseUrl uses http for localhost`() {
        val ref = ImageReference.parse("localhost:5000/repo:v1")
        assertEquals("http://localhost:5000", ref.registryBaseUrl)
    }

    @Test
    fun `registryBaseUrl uses http for IP addresses`() {
        val ref = ImageReference.parse("192.168.1.100:5000/repo:v1")
        assertEquals("http://192.168.1.100:5000", ref.registryBaseUrl)
    }

    @Test
    fun `reference returns digest when present`() {
        val ref = ImageReference.parse("alpine@sha256:abc123")
        assertEquals("sha256:abc123", ref.reference)
    }

    @Test
    fun `reference returns tag when no digest`() {
        val ref = ImageReference.parse("alpine:3.18")
        assertEquals("3.18", ref.reference)
    }

    @Test
    fun `reference returns latest when neither tag nor digest`() {
        // parse always defaults tag to latest if no digest
        val ref = ImageReference.parse("alpine")
        assertEquals("latest", ref.reference)
    }

    @Test
    fun `toString formats registry image with tag`() {
        val ref = ImageReference.parse("ghcr.io/owner/repo:v2")
        assertEquals("ghcr.io/owner/repo:v2", ref.toString())
    }

    @Test
    fun `toString formats digest reference`() {
        val ref = ImageReference.parse("alpine@sha256:abc123")
        assertEquals("registry-1.docker.io/library/alpine@sha256:abc123", ref.toString())
    }

    @Test
    fun `toString formats http layout`() {
        val ref = ImageReference.parse("http://example.com/oci")
        assertEquals("http://example.com/oci", ref.toString())
    }

    @Test
    fun `fullName combines registry and repository`() {
        val ref = ImageReference.parse("ghcr.io/owner/repo:v1")
        assertEquals("ghcr.io/owner/repo", ref.fullName)
    }

    @Test
    fun `parse multi-level repository path`() {
        val ref = ImageReference.parse("ghcr.io/org/team/project:latest")
        assertEquals("ghcr.io", ref.registry)
        assertEquals("org/team/project", ref.repository)
        assertEquals("latest", ref.tag)
    }

    @Test
    fun `parse image without tag defaults to latest`() {
        val ref = ImageReference.parse("ghcr.io/owner/repo")
        assertEquals("latest", ref.tag)
    }
}

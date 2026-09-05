package dev.ide.agent.impl.opencode

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class OpenCodePhase1Test {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `1 - Registry resolution rejects pending_artifact status`() {
        val model = OpenCodeRegistryModel(
            agents = listOf(
                OpenCodeAgentEntry(
                    id = "opencode",
                    name = "OpenCode",
                    version = "1.18.18",
                    distribution = OpenCodeDistribution(
                        binary = mapOf(
                            "linux-aarch64" to OpenCodeBinaryDist(
                                archiveUrl = "https://example.com/opencode.tar.gz",
                                sha256 = "1234567890123456789012345678901234567890123456789012345678901234",
                                verified = false,
                                verificationStatus = "pending_artifact"
                            )
                        )
                    )
                )
            )
        )

        val res = AgentRegistry.resolveForHostAbi(model)
        assertFalse(res.isValid)
        assertTrue(res.reason.contains("not verified"))
    }

    @Test
    fun `2 - Registry resolution rejects missing checksum`() {
        val model = OpenCodeRegistryModel(
            agents = listOf(
                OpenCodeAgentEntry(
                    id = "opencode",
                    name = "OpenCode",
                    version = "1.18.18",
                    distribution = OpenCodeDistribution(
                        binary = mapOf(
                            "linux-aarch64" to OpenCodeBinaryDist(
                                archiveUrl = "https://example.com/opencode.tar.gz",
                                sha256 = "",
                                verified = true,
                                verificationStatus = "verified"
                            )
                        )
                    )
                )
            )
        )

        val res = AgentRegistry.resolveForHostAbi(model)
        assertFalse(res.isValid)
        assertTrue(res.reason.contains("Invalid or missing SHA-256"))
    }

    @Test
    fun `3 - AgentDownloader rejects extraction when SHA-256 mismatches`() {
        val content = "dummy file content".toByteArray()
        val dummyTarGz = createDummyTarGz("test.txt", content)

        val staging = File(tempDir, "staging")
        val result = AgentDownloader.extractTarGzToStaging(
            archiveStream = ByteArrayInputStream(dummyTarGz),
            expectedSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            expectedSizeBytes = dummyTarGz.size.toLong(),
            stagingDir = staging
        )

        assertFalse(result.success)
        assertTrue(result.reason.contains("Checksum mismatch"))
        assertFalse(File(staging, "test.txt").exists())
    }

    @Test
    fun `4 - AgentDownloader detects path traversal and cleans staging`() {
        val content = "malicious content".toByteArray()
        val maliciousTarGz = createDummyTarGz("../evil.txt", content)

        val staging = File(tempDir, "staging")
        val (hash, _) = AgentDownloader.computeStreamSha256(ByteArrayInputStream(maliciousTarGz))

        val result = AgentDownloader.extractTarGzToStaging(
            archiveStream = ByteArrayInputStream(maliciousTarGz),
            expectedSha256 = hash,
            expectedSizeBytes = maliciousTarGz.size.toLong(),
            stagingDir = staging,
            requirePreVerification = false
        )

        assertFalse(result.success)
        assertTrue(result.reason.contains("Path traversal"))
        assertFalse(File(staging, "../evil.txt").exists())
    }

    @Test
    fun `5 - RootFSManager isolates subdirectories under files opencode`() {
        val dirs = RootFSManager.validateAndEnsureIsolatedDirectories(tempDir)
        assertTrue(dirs["staging"]!!.absolutePath.contains("opencode"))
        assertTrue(dirs["root"]!!.exists())
    }

    @Test
    fun `6 - AgentLauncher builds inert ProcessSpec without starting process`() {
        val spec = AgentLauncher.buildInertProcessSpec(tempDir, "proj_1", 4098)
        assertEquals(4098, spec.targetPort)
        assertEquals("127.0.0.1", spec.commandArgs.last())
        assertTrue(spec.executable.endsWith("opencode"))
        assertTrue(spec.bindMounts.isEmpty())
    }

    @Test
    fun `6b - AgentLauncher optionally resolves a persisted port when target port is unset`() {
        val spec = AgentLauncher.buildInertProcessSpec(tempDir, "proj_stable_launch", 0)
        val paths = OpenCodePaths(tempDir)
        val portFile = File(paths.stateProjectsDir, "proj_stable_launch.port")

        assertTrue(portFile.exists())
        assertEquals(spec.targetPort, portFile.readText().trim().toInt())
        assertTrue(spec.commandArgs.contains("--port"))
        assertEquals(spec.targetPort.toString(), spec.commandArgs[spec.commandArgs.indexOf("--port") + 1])
        assertEquals(spec.targetPort.toString(), spec.environment["OPENCODE_PORT"])
    }

    @Test
    fun `6c - AgentLauncher runs opencode directly without proot sandbox`() {
        val spec = AgentLauncher.buildInertProcessSpec(tempDir, "proj_1", 4098)
        assertFalse("-r" in spec.commandArgs)
        assertFalse("-b" in spec.commandArgs)
        assertTrue(spec.bindMounts.isEmpty())
    }

    @Test
    fun `7 - ScriptValidator detects hardcoded secret`() {
        val script = File(tempDir, "test.sh").apply {
            writeText("#!/bin/sh\nexport API_KEY=\"sk-12345678901234567890\"\n")
        }
        val res = ScriptValidator.validateScriptFile(script)
        assertFalse(res.isValid)
        assertTrue(res.reason.contains("sensitive secrets"))
    }

    @Test
    fun `8 - ScriptValidator permits clean script`() {
        val script = File(tempDir, "clean.sh").apply {
            writeText("#!/bin/sh\necho \"Starting server on 127.0.0.1\"\n")
        }
        val res = ScriptValidator.validateScriptFile(script)
        assertTrue(res.isValid)
    }

    private fun createDummyTarGz(entryName: String, content: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            val header = ByteArray(512)
            val nameBytes = entryName.toByteArray(Charsets.US_ASCII)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

            val sizeOctal = "%011o".format(content.size).toByteArray(Charsets.US_ASCII)
            System.arraycopy(sizeOctal, 0, header, 124, sizeOctal.size)

            header[156] = '0'.code.toByte() // Regular file

            gzos.write(header)
            gzos.write(content)

            val padding = (512 - (content.size % 512)) % 512
            if (padding > 0) {
                gzos.write(ByteArray(padding))
            }

            // End of archive zero blocks
            gzos.write(ByteArray(1024))
        }
        return baos.toByteArray()
    }
}

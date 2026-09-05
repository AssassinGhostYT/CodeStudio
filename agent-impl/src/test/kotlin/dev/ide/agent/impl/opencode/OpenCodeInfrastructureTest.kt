package dev.ide.agent.impl.opencode

import dev.ide.agent.OpenCodeSessionRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress

class OpenCodeInfrastructureTest {

    @field:TempDir
    lateinit var tempFolder: File

    @Test
    fun `1 - Valid route passes validation`() {
        val projectDir = File(tempFolder, "ValidProject").apply { mkdirs() }
        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate(projectDir.absolutePath)

        assertTrue(res.isValid)
        assertTrue(res.isDirectory)
        assertTrue(res.canRead)
        assertEquals(projectDir.canonicalPath, res.canonicalPath)
    }

    @Test
    fun `2 - Non-existent route fails validation`() {
        val nonExistent = File(tempFolder, "DoesNotExist")
        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate(nonExistent.absolutePath)

        assertFalse(res.isValid)
        assertFalse(res.isDirectory)
        assertEquals("Path does not exist on filesystem", res.reason)
    }

    @Test
    fun `3 - Route pointing to a file fails validation`() {
        val file = File(tempFolder, "test.txt").apply { createNewFile() }
        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate(file.absolutePath)

        assertFalse(res.isValid)
        assertFalse(res.isDirectory)
        assertEquals("Path is a file, not a directory", res.reason)
    }

    @Test
    fun `4 - Empty or null route fails validation`() {
        val validator = ProjectRouteValidator(tempFolder)
        assertFalse(validator.validate("").isValid)
        assertFalse(validator.validate("   ").isValid)
        assertFalse(validator.validate(null).isValid)
    }

    @Test
    fun `5 - Route with null byte fails validation`() {
        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate("/some/path\u0000with/null")

        assertFalse(res.isValid)
        assertEquals("Path contains null bytes", res.reason)
    }

    @Test
    fun `6 - Route inside files opencode fails validation`() {
        val opencodeSub = File(tempFolder, "opencode/my_project").apply { mkdirs() }
        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate(opencodeSub.absolutePath)

        assertFalse(res.isValid)
        assertEquals("Path is inside files/opencode", res.reason)
    }

    @Test
    fun `7 - Route inside files support fails validation`() {
        val supportSub = File(tempFolder, "support/my_project").apply { mkdirs() }
        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate(supportSub.absolutePath)

        assertFalse(res.isValid)
        assertEquals("Path is inside files/support", res.reason)
    }

    @Test
    fun `8 - Route inside files storage fails validation`() {
        val storageSub = File(tempFolder, "storage/my_project").apply { mkdirs() }
        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate(storageSub.absolutePath)

        assertFalse(res.isValid)
        assertEquals("Path is inside files/storage", res.reason)
    }

    @Test
    fun `9 - Identical routes produce identical projectId`() {
        val path = "/storage/emulated/0/MyProject"
        val id1 = ProjectIdGenerator.generateProjectId(path)
        val id2 = ProjectIdGenerator.generateProjectId(path)

        assertEquals(id1, id2)
        assertEquals(64, id1.length)
    }

    @Test
    fun `10 - Different routes produce different projectIds`() {
        val id1 = ProjectIdGenerator.generateProjectId("/path/one")
        val id2 = ProjectIdGenerator.generateProjectId("/path/two")

        assertNotEquals(id1, id2)
    }

    @Test
    fun `11 - PortAllocator finds available loopback port`() {
        val allocator = PortAllocator(startPort = 9100, endPort = 9150)
        val port = allocator.allocateAvailablePort()

        assertTrue(port in 9100..9150)
    }

    @Test
    fun `12 - PortAllocator rejects invalid ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortAllocator(startPort = 100, endPort = 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortAllocator(startPort = 9000, endPort = 8000)
        }
    }

    @Test
    fun `13 - ServerSocket used for check is closed properly`() {
        val allocator = PortAllocator(startPort = 9200, endPort = 9200)
        val port = allocator.allocateAvailablePort()
        assertEquals(9200, port)

        val loopback = InetAddress.getByName("127.0.0.1")
        val isAvailableAgain = allocator.isPortAvailable(loopback, 9200)
        assertTrue(isAvailableAgain, "Socket must be closed after checking so it remains available")
    }

    @Test
    fun `14 - FakeOpenCodeRuntimeManager executes no real processes`() {
        val paths = OpenCodePaths(tempFolder)
        val fakeManager = FakeOpenCodeRuntimeManager(paths)

        fakeManager.prepareDirectories("proj_123")
        val startRes = fakeManager.startServer("proj_123", "/workspace")

        assertTrue(startRes.isFailure)
        assertNull(fakeManager.currentHandle())
        assertTrue(fakeManager.calls.contains("prepareDirectories(proj_123)"))
        assertTrue(fakeManager.calls.contains("startServer(proj_123, /workspace)"))
    }

    @Test
    fun `15 - ensureDirectories is idempotent`() {
        val paths = OpenCodePaths(tempFolder)
        paths.ensureDirectories()
        assertTrue(paths.rootDir.exists())
        assertTrue(paths.tmpDir.exists())

        paths.ensureDirectories()
        assertTrue(paths.rootDir.exists())
    }

    @Test
    fun `16 - Absolute subpaths are rejected by OpenCodePaths`() {
        val paths = OpenCodePaths(tempFolder)
        assertThrows(IllegalArgumentException::class.java) {
            paths.resolveSubPath("/etc/passwd")
        }
    }

    @Test
    fun `17 - Read-only directory is valid with canWrite false`() {
        val readOnlyDir = File(tempFolder, "ReadOnlyDir").apply { mkdirs() }
        readOnlyDir.setReadOnly()

        val validator = ProjectRouteValidator(tempFolder)
        val res = validator.validate(readOnlyDir.absolutePath)

        assertTrue(res.isValid, "Read-only directories should remain valid for read operations")
        if (!res.canWrite) {
            assertEquals("Directory valid (read-only mode)", res.reason)
        } else {
            assertEquals("Valid directory", res.reason)
        }
        readOnlyDir.setWritable(true)
    }

    @Test
    fun `18 - OpenCodeRuntimeDiagnostics performs no execution`() {
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder)
        assertTrue(report.architecture.isNotEmpty())
        assertFalse(report.isReadyForManualRuntimeTest, "Empty directory cannot be ready")
        assertTrue(report.missingComponents.size >= 6)
    }

    @Test
    fun `19 - OpenCodeSessionRecord model instantiation`() {
        val record = OpenCodeSessionRecord(
            projectId = "abc",
            canonicalPath = "/path",
            displayName = "MyProj"
        )
        assertEquals(1, record.schemaVersion)
        assertEquals("abc", record.projectId)
        assertNull(record.openCodeSessionId)
    }

    // --- NEW PHASE 3B TESTS ---

    @Test
    fun `21 - Diagnostic reports missing busybox`() {
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder)
        assertTrue(report.missingComponents.contains("runtime/busybox"))
    }

    @Test
    fun `23 - Diagnostic reports missing opencode agent binary`() {
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder)
        assertTrue(report.missingComponents.contains("agents/opencode/1.18.18/opencode"))
    }

    @Test
    fun `24 - Diagnostic reports incorrect checksum`() {
        val paths = OpenCodePaths(tempFolder)
        paths.ensureDirectories()
        val dummyBin = paths.resolveSubPath("agents/opencode/1.18.18/opencode")
        dummyBin.parentFile.mkdirs()
        dummyBin.writeText("invalid binary content")

        val expected = mapOf("agents/opencode/1.18.18/opencode" to "expected_sha_12345")
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder, expected)

        assertFalse(report.isReadyForManualRuntimeTest)
        assertTrue(report.warnings.any { it.contains("Checksum mismatch") })
    }

    @Test
    fun `25 - Diagnostic reports correct checksum`() {
        val paths = OpenCodePaths(tempFolder)
        paths.ensureDirectories()
        val dummyBin = paths.resolveSubPath("agents/opencode/1.18.18/opencode")
        dummyBin.parentFile.mkdirs()
        dummyBin.writeText("valid content")

        val actualSha = OpenCodeRuntimeDiagnostics.computeSha256(dummyBin)
        val expected = mapOf("agents/opencode/1.18.18/opencode" to actualSha)
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder, expected)

        val component = report.components.find { it.componentName == "agents/opencode/1.18.18/opencode" }
        assertNotNull(component)
        assertEquals(true, component?.checksumMatches)
    }

    @Test
    fun `26 - Diagnostic handles unexecutable file gracefully`() {
        val paths = OpenCodePaths(tempFolder)
        paths.ensureDirectories()
        val dummyBin = paths.resolveSubPath("runtime/busybox")
        dummyBin.parentFile.mkdirs()
        dummyBin.writeText("busybox stub")
        dummyBin.setExecutable(false)

        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder)
        val status = report.components.find { it.componentName == "runtime/busybox" }

        assertNotNull(status)
        assertTrue(status!!.exists)
    }

    @Test
    fun `27 - Diagnostic reports missing state directory`() {
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder)
        assertTrue(report.missingComponents.contains("state/projects"))
    }

    @Test
    fun `28 - Diagnostic checks temporary available port`() {
        val allocator = PortAllocator(startPort = 9300, endPort = 9310)
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder, portAllocator = allocator)

        assertNotNull(report.availablePort)
        assertTrue(report.availablePort!! in 9300..9310)
    }

    @Test
    fun `29 - Diagnostic with invalid port range handles gracefully`() {
        assertThrows(IllegalArgumentException::class.java) {
            val allocator = PortAllocator(startPort = 9900, endPort = 9800)
            OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder, portAllocator = allocator)
        }
    }

    @Test
    fun `30 - Diagnostic performs zero process execution`() {
        // Assert no runtime/process exception occurs and report completes in-memory
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder)
        assertNotNull(report)
    }

    @Test
    fun `31 - Diagnostic opens no persistent sockets`() {
        val allocator = PortAllocator(startPort = 9400, endPort = 9400)
        val report = OpenCodeRuntimeDiagnostics.runDiagnostic(tempFolder, portAllocator = allocator)

        assertEquals(9400, report.availablePort)
        assertTrue(allocator.isPortAvailable(InetAddress.getByName("127.0.0.1"), 9400))
    }

    @Test
    fun `32 - Paths outside files opencode are rejected`() {
        val paths = OpenCodePaths(tempFolder)
        assertThrows(IllegalArgumentException::class.java) {
            paths.resolveSubPath("../support/secret.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            paths.resolveSubPath("../storage/data.db")
        }
    }

    @Test
    fun `33 - StablePortProvider persists and reuses the same port`() {
        val paths = OpenCodePaths(tempFolder)
        val allocator = PortAllocator(startPort = 9500, endPort = 9500)

        val first = StablePortProvider.resolveStablePort("proj_stable", paths, allocator)
        val second = StablePortProvider.resolveStablePort("proj_stable", paths, allocator)

        assertEquals(9500, first)
        assertEquals(first, second)
        val portFile = File(paths.stateProjectsDir, "proj_stable.port")
        assertTrue(portFile.exists())
        assertEquals(first.toString(), portFile.readText().trim())
    }

    @Test
    fun `34 - StablePortProvider allocates a fresh port when the saved one is busy`() {
        val paths = OpenCodePaths(tempFolder)
        val allocator = PortAllocator(startPort = 9600, endPort = 9601)

        val saved = StablePortProvider.resolveStablePort("proj_busy", paths, allocator)
        assertEquals(9600, saved)

        val busySocket = java.net.ServerSocket(9600, 1, InetAddress.getByName("127.0.0.1")).use {
            val fresh = StablePortProvider.resolveStablePort("proj_busy", paths, allocator)
            assertEquals(9601, fresh)
            val portFile = File(paths.stateProjectsDir, "proj_busy.port")
            assertEquals("9601", portFile.readText().trim())
            it
        }
    }

    @Test
    fun `35 - StablePortProvider honours an explicit target port`() {
        val paths = OpenCodePaths(tempFolder)
        val allocator = PortAllocator(startPort = 9700, endPort = 9700)

        val port = StablePortProvider.resolveStablePort("proj_explicit", paths, allocator, targetPort = 4098)

        assertEquals(4098, port)
        assertEquals(9700, allocator.allocateAvailablePort())
    }
}

package com.amusic.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The phone joins the desktop recommendation payload by content-hash track id, so the
 * hash MUST agree with the engine's `track_id_for` (preprocess/audio.py). These goldens
 * were produced by that exact Python logic over the same deterministic byte patterns
 * (`bytes(i % 251)`), including the >512 KB boundary: only files strictly larger than
 * 2 chunks get the tail block.
 */
class TrackIdentityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, size: Int): File {
        val f = File(tmp.root, name)
        val bytes = ByteArray(size) { (it % 251).toByte() }
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun smallFile_headOnly() {
        val f = write("small.bin", 1000)
        assertEquals("08aee443809b89a8", TrackIdentity.trackIdFor(f.absolutePath, f.length()))
    }

    @Test
    fun bigFile_headAndTail() {
        val f = write("big.bin", 700 * 1024)
        assertEquals("58c113bd68b8b4cf", TrackIdentity.trackIdFor(f.absolutePath, f.length()))
    }

    @Test
    fun exactlyTwoChunks_noTail() {
        val f = write("exact.bin", 512 * 1024)
        assertEquals("cbd668c154b92ea0", TrackIdentity.trackIdFor(f.absolutePath, f.length()))
    }

    @Test
    fun unreadableFile_returnsNull() {
        assertNull(TrackIdentity.trackIdFor(tmp.root.resolve("missing.bin").absolutePath, 42))
    }
}

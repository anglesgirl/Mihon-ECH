package eu.kanade.tachiyomi.network

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KatHttp3SourceTest {
    @Test
    fun `read honors byteCount and keeps the remainder of each chunk`() {
        val queue = LinkedBlockingQueue<ByteArray>(8).apply {
            put(byteArrayOf(1, 2, 3, 4))
            put(byteArrayOf(5, 6))
            put(ByteArray(0))
        }
        val source = KatHttp3Source(
            queue = queue,
            finished = AtomicBoolean(true),
            errorRef = AtomicReference(null),
            onClose = {},
        )
        val sink = Buffer()

        assertEquals(0L, source.read(sink, 0L))
        assertEquals(2L, source.read(sink, 2L))
        assertArrayEquals(byteArrayOf(1, 2), sink.readByteArray())
        assertEquals(2L, source.read(sink, 2L))
        assertArrayEquals(byteArrayOf(3, 4), sink.readByteArray())
        assertEquals(2L, source.read(sink, 2L))
        assertArrayEquals(byteArrayOf(5, 6), sink.readByteArray())
        assertEquals(-1L, source.read(sink, 2L))
    }

    @Test
    fun `mid-stream failure is propagated instead of being reported as EOF`() {
        val failure = IOException("test body transfer failure")
        val queue = LinkedBlockingQueue<ByteArray>(8).apply {
            put(byteArrayOf(1, 2))
            put(ByteArray(0))
        }
        val source = KatHttp3Source(
            queue = queue,
            finished = AtomicBoolean(true),
            errorRef = AtomicReference(failure),
            onClose = {},
        )
        val sink = Buffer()

        assertEquals(2L, source.read(sink, 2L))
        assertArrayEquals(byteArrayOf(1, 2), sink.readByteArray())
        assertSame(failure, assertThrows(IOException::class.java) { source.read(sink, 1L) })
    }

    @Test
    fun `read after close fails instead of returning EOF`() {
        val source = KatHttp3Source(
            queue = LinkedBlockingQueue(8),
            finished = AtomicBoolean(false),
            errorRef = AtomicReference(null),
            onClose = {},
        )
        source.close()

        assertThrows(IOException::class.java) { source.read(Buffer(), 1L) }
    }
}

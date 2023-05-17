import Util.Companion.formatC
import Util.Companion.safeClose
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

data class Pipe(
    val targetHost: String,
    val src: SocketChannel,
    val dest: SocketChannel,
    val data: DataBuffer,
    val srcKey: SelectionKey,
    val destKey: SelectionKey
) {
    companion object {
        val logger = LoggerFactory.getLogger(Pipe::class.java)
        val ID = AtomicLong(0L)

        fun generateId(): Long {
            return ID.addAndGet(1L)
        }
    }

    private var isSrcEOF = false
    private var isDestEOF = false
    val id = generateId()

    val bytesUp = AtomicLong(data.srcBuffer.remaining().toLong())
    val bytesDown = AtomicLong(0L)
    val startTime = System.currentTimeMillis()

    init {
        logger.info("Connection started. Id: ${id}")
    }

    /**
     * Still has unwritten data
     */
    fun hasData(): Boolean {
        val hasData = data.hasData()
        return hasData
    }

    /**
     * Did either party close the connection
     */
    private fun isEOF(): Boolean {
        val eof = isSrcEOF || isDestEOF
        if(eof) {
            logger.debug("${id} EOF: src=$isSrcEOF dest=$isDestEOF")
        }
        return eof
    }

    /**
     * Should we abondon this pipe
     *
     * Case1
     */
    fun shouldClose(): Boolean {
        return isEOF() && !hasData()
    }

    fun findPeer(key: SelectionKey): SelectionKey {
        if (key === srcKey) {
            return destKey
        } else if (key === destKey) {
            return srcKey
        } else {
            throw IllegalArgumentException("Not key for this Pipe!")
        }
    }

    fun findBuffer(ch: SocketChannel): ByteBuffer {
        if (ch === src) {
            return data.srcBuffer
        } else if (ch === dest) {
            return data.destBuffer
        } else {
            throw IllegalArgumentException("Not channel for this Pipe")
        }
    }

    fun findOtherBuffer(ch: SocketChannel): ByteBuffer {
        if (ch === src) {
            return data.destBuffer
        } else if (ch === dest) {
            return data.srcBuffer
        } else {
            throw IllegalArgumentException("Not channel for this Pipe")
        }
    }

    fun markEOF(ch: SocketChannel) {
        if (ch === src) {
            isSrcEOF = true
        } else if (ch === dest) {
            isDestEOF = true
        } else {
            throw IllegalArgumentException("Not channel for this Pipe")
        }
    }

    fun handleCanRead(ch: SocketChannel) {
        val buffer = findBuffer(ch)
        val myKey = findMyKey(ch)
        val otherKey = findPeer(myKey)
        buffer.clear()
        val isSrc = isSource(ch)
        val isDest = isDestination(ch)
        val TAG = if(isSrc) {
            "SRC"
        } else {
            "DEST"
        }
        if((isSrc && data.srcHasData())||(isDest && data.destHasData())) {
            // can't read when data is on hold
            // pause my read
            myKey.interestOps(myKey.interestOps() and SelectionKey.OP_READ.inv())
            // wake up other write
            // can't read
            return
        }
        var nread: Int
        try {
            nread = ch.read(buffer)
        } catch (e: IOException) {
            logger.error("Failed to read from $TAG channel ${formatC(ch)} ${e.javaClass}: ${e.message}")
            cleanup()
            return
        }
        if (nread == -1) {
            // clean the data set
            logger.debug("${id} $TAG EOF")
            markEOF(ch)
            // No more reading SRC
            if (shouldClose()) {
                logger.debug("${id} Should close socket")
                cleanup()
            }
        } else if(nread > 0) {
            if(isSrc) {
                bytesUp.addAndGet(nread.toLong())
            } else {
                bytesDown.addAndGet(nread.toLong())
            }
            buffer.flip()
            logger.debug("${id} $TAG -> buffer: Read $nread bytes")
            data.markHasData(buffer)
            // pause my read
            // wake up write intention
            myKey.interestOps(myKey.interestOps() and SelectionKey.OP_READ.inv())
            otherKey.interestOps(otherKey.interestOps() or SelectionKey.OP_WRITE)
        }
    }

    fun handleCanWrite(ch: SocketChannel) {
        val buffer = findOtherBuffer(ch)
        val myKey = findMyKey(ch)
        val otherKey = findPeer(myKey)
        val isSrc = isSource(ch)
        val isDest = isDestination(ch)
        val TAG = if (isSrc) {
            "SRC"
        } else {
            "DEST"
        }
        if((isSrc && !data.destHasData())||(isDest && !data.srcHasData())) {
            // can't read when data is on hold
            myKey.interestOps(myKey.interestOps() and SelectionKey.OP_WRITE.inv())
            // can't read
            if(shouldClose()) {
                cleanup()
            }
            return
        }
        try {
            val size = ch.write(buffer)
            logger.debug("${id} buffer -> $TAG: Written $size (remaining ${buffer.remaining()} bytes)")
            if (!buffer.hasRemaining()) {
                // enable read for other key
                otherKey.interestOps(otherKey.interestOps() or SelectionKey.OP_READ)
                myKey.interestOps(myKey.interestOps() and SelectionKey.OP_WRITE.inv())
                // data not available
                // Enable read on my key again
                data.markHasNoData(buffer)
            }
        } catch (e: IOException) {
            logger.error("Failed to write to $TAG: ${formatC(ch)}")
            cleanup()
            return
        }
        if (shouldClose()) {
            logger.debug("${id} Should close")
            cleanup()
        }
    }

    fun isSelfEOF(ch:SocketChannel):Boolean {
        if(ch === src) {
            return isSrcEOF
        } else if(ch === dest) {
            return isDestEOF
        } else {
            throw IllegalArgumentException("Not channel for this Pipe!")
        }
    }
    fun isOtherEOF(ch:SocketChannel):Boolean {
        if(ch === src) {
            return isDestEOF
        } else if(ch === dest) {
            return isSrcEOF
        } else {
            throw IllegalArgumentException("Not channel for this Pipe!")
        }
    }
    fun findPeer(ch: SocketChannel): SocketChannel {
        if (ch === src) {
            return dest
        } else if (ch === dest) {
            return src
        } else {
            throw IllegalArgumentException("Not source or destination socket!")
        }
    }

    private fun findMyKey(ch: SocketChannel): SelectionKey {
        if (ch === src) {
            return srcKey
        } else if (ch === dest) {
            return destKey
        } else {
            throw IllegalArgumentException("Not source of dest socket!")
        }
    }

    private fun isSource(what: SocketChannel): Boolean {
        return what === src
    }

    fun isDestination(what: SocketChannel): Boolean {
        return what === dest
    }

    private fun uptime(): Long {
        return System.currentTimeMillis() - startTime
    }

    fun cleanup() {
        safeClose(src)
        safeClose(dest)
        srcKey.cancel()
        destKey.cancel()
        logger.info(
            "Connection closed:\n" +
                    "  Id: $id\n" +
                    "  Upload:   ${this.bytesUp} bytes\n" +
                    "  Download: ${this.bytesDown} bytes\n" +
                    "  Uptime: ${uptime()} ms"
        )
    }
}
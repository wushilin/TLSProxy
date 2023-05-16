import Util.Companion.formatC
import Util.Companion.safeClose
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

data class Pipe(val targetHost:String, val src: SocketChannel, val dest:SocketChannel, var clientHello: ByteBuffer?, val data:DataBuffer, val srcKey:SelectionKey, val destKey:SelectionKey) {
    companion object {
        val logger = LoggerFactory.getLogger(Pipe::class.java)
        val ID = AtomicLong(0L)

        fun generateId():Long {
            return ID.addAndGet(1L)
        }
    }
    private var isSrcEOF = false
    private var isDestEOF = false
    val id = generateId()

    val bytesUp = AtomicLong(1L)
    val bytesDown = AtomicLong(1L)
    val startTime = System.currentTimeMillis()
    init {
        logger.info("Connection started. Id: ${id}")
    }
    /**
     * Still has unwritten data
     */
    fun hasData():Boolean {
        return data.destHasData() || data.srcHasData()
    }

    /**
     * Did either party close the connection
     */
    private fun isEOF():Boolean {
        return isSrcEOF || isDestEOF
    }

    /**
     * Should we abondon this pipe
     *
     * Case1
     */
    fun shouldClose():Boolean {
        return isEOF() && !hasData()
    }

    fun handleCanRead(ch:SocketChannel) {
        if(isSource(ch)) {
            if(data.srcHasData()) {
                // buffer is full
                // mute read first
                srcKey.interestOps(srcKey.interestOps() and SelectionKey.OP_READ.inv())
            } else {
                val buffer = data.borrowSrcForProducing()
                buffer.clear()
                var nread:Int
                try {
                    nread = ch.read(buffer)
                } catch(e:IOException) {
                    logger.error("Failed to read from source channel ${formatC(ch)} ${e.javaClass}: ${e.message}")
                    cleanup()
                    return
                }
                if(nread == -1) {
                    // clean the data set
                    data.borrowSrcForConsuming()
                    logger.debug("${id} src EOF")
                    isSrcEOF = true
                    // No more reading SRC
                    srcKey.interestOps(srcKey.interestOps() and SelectionKey.OP_READ.inv())
                    if(shouldClose()) {
                        logger.debug("${id} Should close socket")
                        cleanup()
                    }
                } else {
                    bytesUp.addAndGet(nread.toLong())
                    buffer.flip()
                    logger.debug("${id} src -> buffer: Read $nread bytes")
                    // wake up write intention
                    destKey.interestOps(destKey.interestOps() or SelectionKey.OP_WRITE)
                }
            }
        } else if(isDestination(ch)) {
            if(data.destHasData()) {
                destKey.interestOps(destKey.interestOps() and SelectionKey.OP_READ.inv())
            } else {
                val buffer = data.borrowDestForProducing()
                buffer.clear()
                var nread:Int
                try {
                    nread = ch.read(buffer)
                } catch(e:IOException) {
                    logger.error("Failed to read from dest channel ${formatC(ch)} ${e.javaClass}: ${e.message}")
                    cleanup()
                    return
                }
                if(nread == -1) {
                    data.borrowDestForConsuming()
                    logger.debug("${id} dest EOF")
                    isSrcEOF = true
                    // No more reading SRC
                    destKey.interestOps(destKey.interestOps() and SelectionKey.OP_READ.inv())
                    if(shouldClose()) {
                        logger.debug("${id} Should close socket")
                        cleanup()
                    }
                } else {
                    bytesDown.addAndGet(nread.toLong())
                    buffer.flip()
                    logger.debug("${id} dest -> buffer: Read $nread bytes")
                    // wake up write intention
                    srcKey.interestOps(srcKey.interestOps() or SelectionKey.OP_WRITE)
                }
            }
        } else throw IllegalArgumentException("Unknown channel to handle")
    }

    fun handleCanWrite(ch:SocketChannel) {
        if(isSource(ch)) {
            if(!data.destHasData()) {
                // buffer is full
                // mute read first
                srcKey.interestOps(srcKey.interestOps() and SelectionKey.OP_WRITE.inv())
            } else {
                val buffer = data.borrowDestForConsuming()
                val toWrite = buffer.remaining()
                try {
                    while (buffer.hasRemaining()) {
                        ch.write(buffer)
                    }
                } catch(e: IOException) {
                    logger.error("Failed to write to src: ${formatC(ch)}")
                    cleanup()
                    return
                }
                logger.debug("${id} buffer -> src: Written $toWrite bytes")
                if(!isDestEOF) {
                    destKey.interestOps(destKey.interestOps() or SelectionKey.OP_READ)
                }
                if(shouldClose()) {
                    logger.debug("${id} Should close")
                    cleanup()
                }
            }
        } else if(isDestination(ch)) {
            if(clientHello != null) {
                val size = clientHello!!.remaining()
                while(clientHello!!.hasRemaining()) {
                    ch.write(clientHello)
                }
                logger.debug("${id} clientHello -> dest: Written $size bytes")
                clientHello = null
                return
            }
            if(!data.srcHasData()) {
                // buffer is full
                // mute read first
                destKey.interestOps(destKey.interestOps() and SelectionKey.OP_WRITE.inv())
            } else {
                val buffer = data.borrowSrcForConsuming()
                val toWrite = buffer.remaining()
                try {
                    while (buffer.hasRemaining()) {
                        ch.write(buffer)
                    }
                } catch(e: IOException) {
                    logger.error("Failed to write to dest: ${formatC(ch)}")
                    cleanup()
                    return
                }
                logger.debug("${id} buffer -> dest: Written $toWrite bytes")
                if(!isSrcEOF) {
                    srcKey.interestOps(srcKey.interestOps() or SelectionKey.OP_READ)
                }
                if(shouldClose()) {
                    logger.debug("${id} Should close")
                    cleanup()
                }
            }
        } else throw IllegalArgumentException("Unknown channel to handle")
    }

    fun getPeerSocket(ch:SocketChannel):SocketChannel {
        if(isSource(ch)) {
            return dest
        }
        if(isDestination(ch)) {
            return src
        }
        throw IllegalArgumentException("Not source or destination socket!")
    }

    fun getPeerKey(ch:SocketChannel):SelectionKey? {
        if(isSource(ch)) {
            return destKey
        }

        if(isDestination(ch)) {
            return srcKey
        }
        throw IllegalArgumentException("Not source or destination socket!")
    }
    fun isSource(what:SocketChannel):Boolean {
        return what == src
    }

    fun isDestination(what:SocketChannel):Boolean {
        return what == dest
    }

    fun uptime():Long {
        return System.currentTimeMillis() - startTime
    }
    fun cleanup() {
        safeClose(src)
        safeClose(dest)
        srcKey.cancel()
        destKey.cancel()
        logger.info("Connection closed:\n" +
                "  Id: $id\n" +
                "  Upload:   ${this.bytesUp} bytes\n" +
                "  Download: ${this.bytesDown} bytes\n" +
                "  Uptime: ${uptime()} ms")
    }
}
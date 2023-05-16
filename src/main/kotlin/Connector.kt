import Util.Companion.formatC
import Util.Companion.safeClose
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * This class is dedicated to connect to remote hosts and establish pipes for pipeline worker
 */
class Connector(val listenPort: Int, val port: Int) {
    companion object {
        val logger = LoggerFactory.getLogger(Connector::class.java)
    }

    val selector = Selector.open()

    fun start() {
        logger.info("Starting listener on port $listenPort forwarding to SNI $port")
        var nselected:Int
        var buffer = ByteBuffer.allocate(4096)
        var nread:Int
        val serverChannel = ServerSocketChannel.open()
        serverChannel.bind(InetSocketAddress(listenPort))
        serverChannel.configureBlocking(false)
        serverChannel.register(selector, SelectionKey.OP_ACCEPT)

        while (true) {
            nselected = selector.select(5000)
            if (nselected > 0) {
                logger.debug("Connector selected $nselected sockets (${selector.keys().size} keys in management)")
            } else {
                continue
            }
            val selectedKeys = selector.selectedKeys().iterator()

            while (selectedKeys.hasNext()) {
                val key = selectedKeys.next()
                if(!key.isValid) {
                    selectedKeys.remove()
                    key.cancel()
                    continue
                }
                if (key.isValid && key.isAcceptable) {
                    // New Incoming connection
                    val clientChannel = (key.channel() as ServerSocketChannel).accept()
                    clientChannel.configureBlocking(false)
                    clientChannel.register(selector, SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                    selectedKeys.remove()
                    logger.info("Accepted new connection ${formatC(clientChannel, true)}")
                    continue
                }
                val channel = key.channel() as SocketChannel
                if (key.isValid && key.isReadable) {
                    val pipeNull = key.attachment() as Pipe?
                    if (pipeNull != null) {
                        pipeNull.handleCanRead(channel)
                    } else {
                        // initial connection!
                        buffer.clear()
                        nread = channel.read(buffer)
                        if (nread == -1) {
                            safeClose(channel)
                            key.cancel()
                            selectedKeys.remove()
                            //to close
                            continue
                        }
                        val sniHostName = TLSUtil.extractSNI(buffer)

                        val clientHello = ByteBuffer.allocate(nread)
                        buffer.flip()
                        clientHello.put(buffer)
                        clientHello.flip()
                        buffer.flip()
                        if (sniHostName == null || "" == sniHostName.trim()) {
                            logger.warn("Closing connection ${formatC(channel, true)} because no SNI info")
                            // not good. Close it
                            key.cancel()
                            safeClose(channel)
                            selectedKeys.remove()
                            continue
                        }
                        val remoteChannel = SocketChannel.open()
                        remoteChannel.configureBlocking(false)
                        try {
                            remoteChannel.connect(InetSocketAddress(sniHostName, port))
                            val remoteKey = remoteChannel.register(selector, SelectionKey.OP_CONNECT)
                            val newPipe = Pipe(sniHostName, channel, remoteChannel, clientHello, DataBuffer(), key, remoteKey)
                            remoteKey.attach(newPipe)
                            key.attach(newPipe)
                        } catch (t: Throwable) {
                            logger.error("Failed to connect socket to $sniHostName. Cause ${t.javaClass}: ${t.message}")
                            safeClose(remoteChannel)
                            safeClose(channel)
                            key.cancel()
                            selectedKeys.remove()
                            continue
                        }
                    }
                }
                if (key.isValid && key.isConnectable) {
                    val pipeNull = key.attachment() as Pipe
                    try {
                        // remote channel connected. Now can construct pipe
                        if (channel.finishConnect()) {
                            key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                            logger.info("Established outbound connection ${formatC(channel, false)}")
                        } else {
                            key.cancel()
                            pipeNull.srcKey.cancel()
                            safeClose(pipeNull.src)
                            safeClose(channel)
                            logger.debug("Failed to connect to ${pipeNull.targetHost}. finishConnect() returned false")
                            selectedKeys.remove()
                            continue
                        }
                    } catch (t: Throwable) {
                        logger.debug("Failed to connect to ${pipeNull.targetHost}. Cause ${t.javaClass} : ${t.message} ")
                        key.cancel()
                        pipeNull.srcKey.cancel()
                        safeClose(pipeNull.src)
                        safeClose(channel)
                        selectedKeys.remove()
                        continue
                    }
                }
                if (key.isValid && key.isWritable) {
                    val pipeNull = key.attachment() as Pipe?
                    if(pipeNull == null) {
                        selectedKeys.remove()
                        continue
                    }
                    pipeNull.handleCanWrite(channel)
                }
                selectedKeys.remove()
            }
        }
    }
}
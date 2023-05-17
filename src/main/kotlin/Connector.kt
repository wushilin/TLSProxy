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
class Connector(val listenPort: Int, val port: Int, val acl:RuleSet?) {
    companion object {
        val logger = LoggerFactory.getLogger(Connector::class.java)
    }

    val selector = Selector.open()

    fun start() {
        logger.info("Starting listener on port $listenPort forwarding to SNI $port")
        var nselected:Int
        var nread:Int
        val serverChannel = ServerSocketChannel.open()
        serverChannel.bind(InetSocketAddress(listenPort))
        serverChannel.configureBlocking(false)
        serverChannel.register(selector, SelectionKey.OP_ACCEPT)

        while (true) {
            nselected = selector.select(10000)
            if (nselected >= 0) {
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
                    clientChannel.register(selector, SelectionKey.OP_READ)
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
                        val clientHello = ByteBuffer.allocate(1024)
                        // temporarily don't read from the channel
                        key.interestOps(key.interestOps() and SelectionKey.OP_READ.inv())
                        nread = channel.read(clientHello)
                        if (nread == -1) {
                            logger.warn("Closing connection ${formatC(channel, true)} because client closed connection")
                            safeClose(channel)
                            key.cancel()
                            selectedKeys.remove()
                            //to close
                            continue
                        }
                        var sniHostName = ""
                        try {
                            sniHostName = TLSUtil.extractSNI(clientHello)!!
                        } catch(ex:Exception) {
                            logger.warn("Unable to parse SNI hostname ${ex.javaClass} : ${ex.message}")
                        }
                        clientHello.flip()
                        if ("" == sniHostName.trim()) {
                            logger.warn("Closing connection ${formatC(channel, true)} because no SNI info")
                            // not good. Close it
                            key.cancel()
                            safeClose(channel)
                            selectedKeys.remove()
                            continue
                        }
                        if(acl != null) {
                            val clientAddressInet = channel.remoteAddress as InetSocketAddress
                            val clientAddress = clientAddressInet.address
                            val decision = acl.decide(clientAddress, sniHostName)
                            if(decision != Decision.ALLOW) {
                                logger.info("Rejected client ${formatC(channel)} trying to connect to ${sniHostName} due to ACL")
                                key.cancel()
                                safeClose(channel)
                                selectedKeys.remove()
                                continue
                            } else {
                                logger.info("Allowing client ${formatC(channel)} connect to ${sniHostName}")
                            }
                        }
                        val remoteChannel = SocketChannel.open()
                        remoteChannel.configureBlocking(false)
                        try {
                            remoteChannel.connect(InetSocketAddress(sniHostName, port))
                            val remoteKey = remoteChannel.register(selector, SelectionKey.OP_CONNECT)
                            val newPipe = Pipe(sniHostName, channel, remoteChannel, DataBuffer(clientHello, ByteBuffer.allocate(1024), true), key, remoteKey)
                            logger.info("My Key ops: ${key.interestOps()} other Key ops: ${remoteKey.interestOps()}")
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
                    val peerKey = pipeNull.findPeer(key)
                    try {
                        // remote channel connected. Now can construct pipe
                        if (channel.finishConnect()) {
                            // dest must be read write
                            key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                            peerKey.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                            logger.info("Established outbound connection ${formatC(channel, false)}")
                        } else {
                            pipeNull.cleanup()
                            selectedKeys.remove()
                            logger.debug("Failed to connect to ${pipeNull.targetHost}. finishConnect() returned false")
                            continue
                        }
                    } catch (t: Throwable) {
                        pipeNull.cleanup()
                        selectedKeys.remove()
                        logger.debug("Failed to connect to ${pipeNull.targetHost}. Cause ${t.javaClass} : ${t.message} ")
                        continue
                    }
                }
                if (key.isValid && key.isWritable) {
                    val pipeNull = key.attachment() as Pipe
                    pipeNull.handleCanWrite(channel)
                }
                selectedKeys.remove()
            }
        }
    }
}
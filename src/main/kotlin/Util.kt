import java.nio.channels.SocketChannel

class Util {
    companion object {
        fun safeClose(c: SocketChannel?) {
            if(c != null) {
                try {
                    c.close()
                } catch(t:Throwable) {
                    println("Failed to close socket: ${t.message}")
                }
            }
        }

        fun formatC(channel: SocketChannel, incoming:Boolean = true):String {
            if(incoming) {
                return "${channel.remoteAddress} -> ${channel.localAddress}"
            } else {
                return "${channel.localAddress} -> ${channel.remoteAddress}"
            }
        }
    }
}
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class Main : CliktCommand() {
    val source: List<String> by option("-p", "--port", help="Port Forwarding Mapping rule in `-p listen_port[:connect_port]`").multiple()
    val rulesFile: String by option("-a", "--acl", help="ACL file in JSON").default("")
    override fun run() {
        if(source.isEmpty()) {
            println("Please specify port mapping using `-p <port>` or `-p <listen_port>:<connect_port>`")
            exitProcess(1)
        }

        var acl:RuleSet? = null
        if(rulesFile != "") {
            println("Loading ACL rules file $rulesFile")
            acl = parseRuleSet(rulesFile)
        } else {
            println("Not loading ACL files")
        }

        val threads = mutableListOf<Thread>()
        for(next in source) {
            val pm = PortMapping(next)
            threads.add(thread {
                Connector(pm.getListenPort(), pm.getConnectPort(), acl).start()
            })
        }
        for(next in threads) {
            next.join()
        }
    }
}

class PortMapping(spec:String) {
    private var listenPort = -1
    private var connectPort = -1
    init {
        val idx = spec.indexOf(":")
        if(idx == -1) {
            listenPort = spec.toInt()
            connectPort = spec.toInt()
        } else {
            val listenString = spec.substring(0, idx)
            val connectString = spec.substring(idx + 1)
            listenPort = listenString.toInt()
            connectPort = connectString.toInt()
        }
    }

    fun getListenPort():Int {
        return listenPort
    }

    fun getConnectPort():Int {
        return connectPort
    }
}

fun main(args: Array<String>) = Main().main(args)

// fun main() {
//     // Create a KeyManagerFactory with a keystore containing the server's private key and certificate chain
//     //val copier = BidirectionalCopier()
//     //val connector = Connector(22222, 22)
//     connector.start()
// }


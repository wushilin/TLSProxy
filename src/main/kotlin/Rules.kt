import com.jayway.jsonpath.JsonPath
import net.minidev.json.JSONArray
import org.apache.commons.net.util.SubnetUtils
import org.apache.commons.net.util.SubnetUtils.SubnetInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
class RuleEngine {
}
val logger = LoggerFactory.getLogger(RuleEngine::class.java)
enum class Decision {
    ALLOW,
    DENY,
    NONE
}
data class ClientGroups(val groups:List<ClientGroup>) {
    fun match(address:InetAddress):Boolean {
        for(next in groups) {
            if(next.match(address)) {
                return true
            }
        }
        return false
    }
}

data class HostGroups(val groups:List<HostGroup>) {
    fun match(hostName:String):Boolean {
        for(next in groups) {
            if(next.match(hostName)) {
                return true
            }
        }
        return false
    }
}

data class Rule(val clients:ClientGroups, val hosts:HostGroups, val decision:String) {
    fun decide(client:InetAddress, target:String): Decision {
        if(clients.match(client)) {
            if(hosts.match(target)) {
                if("allow".equals(decision, true)) {
                    return Decision.ALLOW
                } else if("deny".equals(decision, true)) {
                    return Decision.DENY
                } else if("none".equals(decision, true)) {
                    return Decision.NONE
                } else {
                    throw IllegalArgumentException("Unknown decision: $decision")
                }
            }
        }
        return Decision.NONE
    }
}

data class RuleSet(val rules:List<Rule>) {
    fun decide(client:InetAddress, target:String):Decision {
        for(next in rules) {
            val decision = next.decide(client, target)
            logger.debug("$next -> $decision")
            if(decision != Decision.NONE) {
                return decision
            }
        }
        return Decision.DENY
    }
}
data class ClientGroup(val rules:List<String>) {
    /**
     * Supports
     */
    private val hosts = mutableSetOf<String>()
    private val cidrs = mutableListOf<SubnetInfo>()
    private val patterns = mutableListOf<Regex>()
    private var alwaysTrue = false
    init {
        for(rule in rules) {
            if("pattern:.*".equals(rule, true)) {
                alwaysTrue = true
            }
            if(rule.startsWith("host:")) {
                hosts.add(rule.substring("host:".length).uppercase(Locale.getDefault()))
            } else if(rule.startsWith("cidr:")) {
                cidrs.add(SubnetUtils(rule.substring("cidr:".length)).info)
            } else if(rule.startsWith("pattern:")) {
                patterns.add(Regex(rule.substring("pattern:".length)))
            } else {
                throw IllegalArgumentException("Invalid host notation: $rule")
            }
        }
    }
    fun match(who: InetAddress):Boolean {
        if(alwaysTrue) {
            return true
        }
        val hostName = who.hostName
        val address = who.address
        val ipLiteral = ipAddressToString(address)
        val isIpV4 = who is Inet4Address
        if(hostName != null) {
            val hostNameUpper = hostName.uppercase(Locale.getDefault())
            if(hosts.contains(hostNameUpper)) {
                return true
            }

            for(next in patterns) {
                if(next.matches(hostName)) {
                    return true
                }
            }
        }
        if(hosts.contains(ipLiteral)) {
            return true
        }

        for(next in patterns) {
            if(next.matches(ipLiteral)) {
                return true
            }
        }
        if(isIpV4) {
            val ipv4 = who as Inet4Address
            for(next in cidrs) {
                if(next.isInRange(ipv4.hostAddress)) {
                    return true
                }
            }
        }
        return false
    }
}

data class HostGroup(val rules:List<String>) {
    private val hosts = mutableSetOf<String>()
    private val patterns = mutableListOf<Regex>()
    private var alwaysTrue = false
    init {
        for(rule in rules) {
            if("pattern:.*".equals(rule, true)) {
                alwaysTrue = true
            }
            if(rule.startsWith("host:")) {
                hosts.add(rule.substring("host:".length).uppercase(Locale.getDefault()))
            } else if(rule.startsWith("pattern:")) {
                patterns.add(Regex(rule.substring("pattern:".length)))
            } else {
                throw IllegalArgumentException("Invalid host notation: $rule")
            }
        }
    }
    fun match(who:String):Boolean {
        if(alwaysTrue) {
            return true
        }
        if(hosts.contains(who.uppercase(Locale.getDefault()))) {
            return true
        }
        for(next in patterns) {
            if(next.matches(who)) {
                return true
            }
        }
        return false
    }
}

val ANY_CLIENT = ClientGroup(listOf("pattern:.*"))
val ANY_HOST = HostGroup(listOf("pattern:.*"))
fun parseRuleSet(fileName:String): RuleSet {
    val jsonString = readFile(fileName)
    val ctx  = JsonPath.parse(jsonString)
    val clients:LinkedHashMap<String, List<String>> = ctx.read("\$.clients")
    val hosts:LinkedHashMap<String, List<String>> = ctx.read("\$.targets")
    val rules:List<LinkedHashMap<String, Any>> = ctx.read("\$.rules")
    val clientByName = mutableMapOf<String, ClientGroup>()
    val hostByName = mutableMapOf<String, HostGroup>()
    for((clientName, hostPatterns) in clients) {
        if(clientName.startsWith("$")) {
            throw java.lang.IllegalArgumentException("Can't use reserved client group name $clientName")
        }
        val newGroup = ClientGroup(hostPatterns)
        clientByName[clientName] = newGroup
    }

    for((hostGroupName, hostPatterns) in hosts) {
        if(hostGroupName.startsWith("$")) {
            throw IllegalArgumentException("Can't use reserved host group name $hostGroupName")
        }
        val newGroup = HostGroup(hostPatterns)
        hostByName[hostGroupName] = newGroup
    }

    clientByName["\$any"] = ANY_CLIENT
    hostByName["\$any"] = ANY_HOST

    val finalRules = mutableListOf<Rule>()
    for(nextRule in rules) {
        val client = nextRule.get("client") as JSONArray?
        val target = nextRule.get("target") as JSONArray?
        val decision = nextRule.get("decision") as String? ?: throw IllegalArgumentException("Decision required")

        val clientGroups = mutableListOf<ClientGroup>()
        val targetGroups = mutableListOf<HostGroup>()
        if(client != null) {
            for (i in 0 until client.size) {
                val nextName = client.get(i) as String
                val toAdd = clientByName[nextName]
                if(toAdd != null) {
                    clientGroups.add(toAdd)
                } else {
                    throw IllegalArgumentException("Group $nextName not found")
                }
            }
        }
        if(target != null) {
            for(i in 0 until target.size) {
                val nextName = target.get(i) as String
                val toAdd = hostByName[nextName]
                if(toAdd != null) {
                    targetGroups.add(toAdd)
                } else {
                    throw IllegalArgumentException("Group $nextName not found")
                }
            }
        }
        if(clientGroups.isEmpty()) {
            clientGroups.add(ANY_CLIENT);
        }
        if(targetGroups.isEmpty()) {
            targetGroups.add(ANY_HOST)
        }
        val newRule = Rule(ClientGroups(clientGroups), HostGroups(targetGroups), decision)
        finalRules.add(newRule)
    }
    return RuleSet(finalRules)
}

fun readFile(fileName: String):String {
    val file = File(fileName)
    file.inputStream().use {
        return it.readBytes().toString(Charsets.UTF_8)
    }
}


private fun ipAddressToString(rawBytes: ByteArray): String {
    var i = 4
    val ipAddress = StringBuilder()
    for (raw in rawBytes) {
        ipAddress.append(raw.toInt() and 0xFF)
        if (--i > 0) {
            ipAddress.append(".")
        }
    }
    return ipAddress.toString()
}
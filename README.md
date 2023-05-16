# TLSProxy
Proxy TLS Port to the client intended hosts.

It works like nginx `tlspreread` but it is more dynamic.

One possible use case is that your target service has no static IP address.

Use case example:

Google Server is accessible at www.google.com:443
You can't whitelist to www.google.com:443 because too many possible IP addresses

You run this on host 123.45.67.89:443

You configure your internal DNS to point www.google.com to 123.45.67.89

Your clients now access www.google.com normally, through SNI based proxy to www.google.com


# How does it work
This service listens on `LISTEN PORT`, and accepts client requests.

Upon client connects, client will send TLS `clientHello`, which identifies the intended hostname.
This service establish a connection to the intended hostname on the `CONNECT PORT` (connect port
defaults to the `LISTEN PORT`), and start a bi-directional tunneling between the client Socket and
the remote socket.


NOTE: It only support TLS with SNI enabled (e.g. most of the TLS protocols)

# Does it breach the security TLS?
No. It decodes the `clientHello` message, and the whole traffic of the actual protocol is still confidential.

# Building
Build this program by:

```bash
$ ./gradlew clean shadowJar
```

The built artifact is in `build/libs/tlsproxy-1.0-SNAPSHOT-all.jar`

# Executing
```bash
$ java -jar tlsproxy-1.0-SNAPSHOT-all.jar -p 1443:443 -p 9092
```
The above command let the program to:
* Listen on port 1443, and forward to client intended host port 443
* Listen on port 9092, and forward to client indented host port 9092

You will have to configure /etc/hosts, or DNS to point all original hosts to this host's IP address

Getting help:

```bash
$ java -jar tlsproxy-1.0-SNAPSHOT-all.jar --help
```

# Logging
You can specify the log4j configuration file location by command line.

By default info is used. you can enable DEBUG if you want to.

```bash
$ java -Dlog4j.configuration=file:./log4j.properties -jar tlsproxy-1.0-SNAPSHOT-all.jar -p 1443:443 -p 9092
```


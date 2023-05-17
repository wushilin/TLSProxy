#!/bin/sh

java -Dlog4j.configuration=file:./log4j.properties -jar *.jar -a rules.json -p 1443:443 -p 9092

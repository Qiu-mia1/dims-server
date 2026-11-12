@echo off
set JAVA_HOME=D:\JDK17
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d E:\project\code\isim-dims\dims\isim-dims\dims-server
D:\Tools\AA\apache-maven-3.9.16\bin\mvn.cmd compile -o 2>&1

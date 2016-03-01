Modified version of the thrift maven plugin as distributed by Apache Thrift
Binaries of the Thrift compiler for Windows and Centos/RHEL included
Use at your own risk!

Debug maven plugins:


    *From the command line, run maven goal with mvnDebug instead of mvn. E.g. mvnDebug clean
    *Open the source of the maven plugin you want to debug in intelliJ and set a breakPoint
    *In IDEA, add a Remote Configuration:
        Under Settings, set Transport: Socket, Debugger Mode: Attach, Host: localhost, Port: 8000 (default port of mvnDebug).
        Run the Configuration in Debug mode. It should connect to the waiting mvnDebug jvm.
    *To debug local tests run maven test goal with commandline: -DforkCount=0 install

# Starts the backend with the sim profile and GC logging / 2GB heap
Set-Location $PSScriptRoot
mvn spring-boot:run "-Dspring-boot.run.profiles=sim" "-Dspring-boot.run.jvmArguments=-Xlog:gc*:file=gc-sim.log:time,uptime -Xmx2g"

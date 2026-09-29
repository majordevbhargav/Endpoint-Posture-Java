# Starts the backend with the dev profile (values come from application-dev.yml).
Set-Location $PSScriptRoot
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
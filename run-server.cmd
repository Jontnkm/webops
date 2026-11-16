@echo off
rem Start WebOps server at http://127.0.0.1:8080
rem Uses the JDK in WebOps\.tools\jdk-17 for this window only (no system settings changed).
rem To also scan local test pages (127.0.0.1):
rem   run-server.cmd --webops.scan.allow-private-targets=true
set "JAVA_HOME=%~dp0.tools\jdk-17"
cd /d "%~dp0server"
if "%~1"=="" (
  call "%~dp0server\mvnw.cmd" spring-boot:run
) else (
  call "%~dp0server\mvnw.cmd" spring-boot:run "-Dspring-boot.run.arguments=%*"
)

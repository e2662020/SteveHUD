@echo off
setlocal
set DIR=%~dp0
set WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
    echo BoardcastMod: gradle-wrapper.jar is missing, trying to find/download it...
    if not exist "%DIR%gradle\wrapper" mkdir "%DIR%gradle\wrapper"
    if exist "%DIR%..\MOBmod\gradle\wrapper\gradle-wrapper.jar" (
        copy /Y "%DIR%..\MOBmod\gradle\wrapper\gradle-wrapper.jar" "%WRAPPER_JAR%" >nul
    )
)

if not exist "%WRAPPER_JAR%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ErrorActionPreference='Stop';" ^
      "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
      "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v9.4.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER_JAR%';"
    if errorlevel 1 (
        echo Failed to download gradle-wrapper.jar. Open this folder in IntelliJ IDEA and let IDEA import the Gradle project, or install Gradle 9.4 and run: gradle build
        exit /b 1
    )
)

java -classpath "%WRAPPER_JAR%" "-Dorg.gradle.appname=gradlew" org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%

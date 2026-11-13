@echo off
setlocal
set "MAVEN_PROJECTBASEDIR=%~dp0"
set "WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar"
for /f "tokens=1,* delims==" %%a in ('findstr "^wrapperUrl=" "%~dp0.mvn\wrapper\maven-wrapper.properties"') do set "WRAPPER_URL=%%b"
if not exist "%WRAPPER_JAR%" (
  powershell -NoProfile -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
  if errorlevel 1 exit /b 1
)
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%." -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %ERRORLEVEL%

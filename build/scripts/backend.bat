@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  backend startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Add default JVM options here. You can also use JAVA_OPTS and BACKEND_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:init
@rem Get command-line arguments, handling Windows variants

if not "%OS%" == "Windows_NT" goto win9xME_args

:win9xME_args
@rem Slurp the command line arguments.
set CMD_LINE_ARGS=
set _SKIP=2

:win9xME_args_slurp
if "x%~1" == "x" goto execute

set CMD_LINE_ARGS=%*

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\backend-1.0.0-SNAPSHOT.jar;%APP_HOME%\lib\vertx-web-client-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-auth-jwt-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-health-check-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-web-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-auth-mongo-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-hazelcast-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-micrometer-metrics-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-sql-client-templates-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-pg-client-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-lang-kotlin-coroutines-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-kafka-client-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-mongo-client-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-lang-kotlin-4.0.0.CR1.jar;%APP_HOME%\lib\kotlin-stdlib-jdk8-1.4.10.jar;%APP_HOME%\lib\vertx-web-common-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-auth-common-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-sql-client-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-core-4.0.0.CR1.jar;%APP_HOME%\lib\vertx-bridge-common-4.0.0.CR1.jar;%APP_HOME%\lib\hazelcast-4.0.2.jar;%APP_HOME%\lib\micrometer-core-1.5.2.jar;%APP_HOME%\lib\HdrHistogram-2.1.10.jar;%APP_HOME%\lib\kotlinx-coroutines-core-1.3.9.jar;%APP_HOME%\lib\kafka-clients-2.6.0.jar;%APP_HOME%\lib\jackson-databind-2.11.3.jar;%APP_HOME%\lib\slf4j-api-1.7.30.jar;%APP_HOME%\lib\mongodb-driver-reactivestreams-4.1.0.jar;%APP_HOME%\lib\reactive-streams-1.0.3.jar;%APP_HOME%\lib\commons-collections4-4.2.jar;%APP_HOME%\lib\netty-handler-proxy-4.1.49.Final.jar;%APP_HOME%\lib\netty-codec-http2-4.1.49.Final.jar;%APP_HOME%\lib\netty-codec-http-4.1.49.Final.jar;%APP_HOME%\lib\netty-handler-4.1.49.Final.jar;%APP_HOME%\lib\netty-resolver-dns-4.1.49.Final.jar;%APP_HOME%\lib\netty-codec-socks-4.1.49.Final.jar;%APP_HOME%\lib\netty-codec-dns-4.1.49.Final.jar;%APP_HOME%\lib\netty-codec-4.1.49.Final.jar;%APP_HOME%\lib\netty-transport-4.1.49.Final.jar;%APP_HOME%\lib\netty-buffer-4.1.49.Final.jar;%APP_HOME%\lib\netty-resolver-4.1.49.Final.jar;%APP_HOME%\lib\netty-common-4.1.49.Final.jar;%APP_HOME%\lib\jackson-core-2.11.3.jar;%APP_HOME%\lib\LatencyUtils-2.0.3.jar;%APP_HOME%\lib\kotlin-stdlib-jdk7-1.4.10.jar;%APP_HOME%\lib\kotlin-stdlib-1.4.10.jar;%APP_HOME%\lib\zstd-jni-1.4.4-7.jar;%APP_HOME%\lib\lz4-java-1.7.1.jar;%APP_HOME%\lib\snappy-java-1.1.7.3.jar;%APP_HOME%\lib\jackson-annotations-2.11.3.jar;%APP_HOME%\lib\mongodb-driver-core-4.1.0.jar;%APP_HOME%\lib\bson-4.1.0.jar;%APP_HOME%\lib\kotlin-stdlib-common-1.4.10.jar;%APP_HOME%\lib\annotations-13.0.jar

@rem Execute backend
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %BACKEND_OPTS%  -classpath "%CLASSPATH%" io.vertx.core.Launcher %CMD_LINE_ARGS%

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable BACKEND_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if  not "" == "%BACKEND_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega

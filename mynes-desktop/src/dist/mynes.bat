@echo off
rem
rem Runs MyNES out of the directory this was unpacked into: the companion of the mynes script beside
rem it, under the same rules. No arguments opens the window, anything else goes through to the
rem emulator, and --headless works from here too.
rem
rem java and not javaw, which would run without a console. The window has no use for one, but this
rem is also how --headless is reached, and a headless run's entire output is printed to it.
rem
rem There is no Java version check here, and there is one in the shell script. Parsing a version out
rem of `java -version` in batch is a `for /f` and two string splits that nobody working on this can
rem run before shipping them, and a launcher that is wrong in that way is worse for a Windows player
rem than the UnsupportedClassVersionError it was meant to spare them. This form cannot be wrong.
rem
setlocal

set "HERE=%~dp0"
set "JAR=%HERE%mynes.jar"

if not exist "%JAR%" (
    echo mynes.jar is not next to this script -- expected it at "%JAR%". 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java"
)

"%JAVA%" -jar "%JAR%" %*

rem cmd has no exec, so the code has to be carried the last step by hand. The headless mode tells
rem six of them apart and a script on the other end is entitled to read them.
exit /b %ERRORLEVEL%

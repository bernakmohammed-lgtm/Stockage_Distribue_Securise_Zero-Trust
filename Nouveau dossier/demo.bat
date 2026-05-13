@echo off
setlocal

echo Building fat jar...
mvn -q package -DskipTests
if errorlevel 1 (
    echo Build failed
    exit /b 1
)

set JAR=target\stockage-distribue-1.0.0-SNAPSHOT-all.jar
set SERVER_PORT=9000
set NODE1_PORT=9101
set NODE2_PORT=9102
set NODE3_PORT=9103

mkdir data\node1 2>nul
mkdir data\node2 2>nul
mkdir data\node3 2>nul

echo Starting storage nodes...
start "Node1" cmd /k "java -cp %JAR% com.stockage.storage.StorageNode %NODE1_PORT% data\node1 127.0.0.1:%NODE2_PORT% 127.0.0.1:%NODE3_PORT%"
start "Node2" cmd /k "java -cp %JAR% com.stockage.storage.StorageNode %NODE2_PORT% data\node2 127.0.0.1:%NODE1_PORT% 127.0.0.1:%NODE3_PORT%"
start "Node3" cmd /k "java -cp %JAR% com.stockage.storage.StorageNode %NODE3_PORT% data\node3 127.0.0.1:%NODE1_PORT% 127.0.0.1:%NODE2_PORT%"

timeout /t 2 >nul

echo Starting central server...
start "Server" cmd /k "java -cp %JAR% com.stockage.server.Server %SERVER_PORT% 127.0.0.1:%NODE1_PORT% 127.0.0.1:%NODE2_PORT% 127.0.0.1:%NODE3_PORT%"

timeout /t 2 >nul

echo Creating test file...
echo This is a demo file for secure distributed storage. > demo-input.txt

echo Uploading file as alice...
java -cp %JAR% com.stockage.client.Client upload 127.0.0.1 %SERVER_PORT% alice alice demo-input.txt client.keystore password > demo-upload.log 2>&1
type demo-upload.log

timeout /t 1 >nul

REM Extract CID from log file (last line contains "CID=")
for /f "tokens=2 delims==" %%a in ('findstr /C:"CID=" demo-upload.log') do set CID=%%a
set CID=%CID: =%

echo.
echo Downloading file as alice with CID=%CID%...
java -cp %JAR% com.stockage.client.Client download 127.0.0.1 %SERVER_PORT% alice alice %CID% demo-output.txt client.keystore password

echo.
echo Comparing files...
fc demo-input.txt demo-output.txt >nul
if errorlevel 1 (
    echo FILES DIFFER
) else (
    echo FILES MATCH - Demo OK
)

echo.
echo Demo complete. Press any key to terminate all demo windows...
pause >nul

taskkill /FI "WindowTitle eq Node1" /T /F >nul 2>&1
taskkill /FI "WindowTitle eq Node2" /T /F >nul 2>&1
taskkill /FI "WindowTitle eq Node3" /T /F >nul 2>&1
taskkill /FI "WindowTitle eq Server" /T /F >nul 2>&1

endlocal

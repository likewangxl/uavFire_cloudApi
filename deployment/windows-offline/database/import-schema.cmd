@echo off
"%~dp0..\runtime\mysql\bin\mysql.exe" --protocol=tcp -h127.0.0.1 -P%~1 -uroot --default-character-set=utf8mb4 < "%~dp0schema.sql"
exit /b %errorlevel%

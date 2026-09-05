UAVFire Windows Redis AOF MISCONF repair

Confirmed symptom in backend-stdout.log:
  RedisCommandExecutionException: MISCONF Errors writing to the AOF file

Steps:
  1. Copy all files from this ZIP into C:\UAVFire and replace existing files.
  2. Right-click C:\UAVFire\REPAIR-REDIS-AOF.bat and run as Administrator.
  3. Do not run START.bat separately.
  4. Wait for green Redis, backend captcha, and Nginx captcha checks.
  5. Open http://192.168.1.2:81 and press Ctrl+F5.

Data handling:
  MySQL and MinIO business data are not modified. Existing appendonly.aof is
  preserved in place for support/recovery. Windows Redis is switched to RDB
  persistence because its AOF writer entered a permanent MISCONF state.

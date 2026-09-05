UAVFire duplicate-install automatic repair

Use when an older instance such as F:\UAVFire still owns Redis TCP 6379 while
the intended installation is C:\UAVFire.

Steps:
  1. Copy every file from this ZIP into C:\UAVFire and replace existing files.
  2. Right-click C:\UAVFire\FIX-DUPLICATE-UAVFIRE.bat and run as Administrator.
  3. Do not run STOP, START, or another repair script at the same time.
  4. Wait for the final green messages, then open http://192.168.1.2:81.

The script stops known UAVFire runtime processes launched from other drive
letters, but does not delete or modify files on those drives. It repairs Redis,
restarts and verifies the C:\UAVFire stack, and registers the startup task to
C:\UAVFire\scripts\start.ps1.

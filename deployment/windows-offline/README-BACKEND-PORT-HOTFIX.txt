UAVFire Java backend port hotfix (192.168.1.2)

Symptom:
  The login captcha and demo login return HTTP 504, while backend logs report
  that TCP port 6790 is already occupied.

Steps:
  1. Extract this ZIP.
  2. Copy all extracted files into C:\UAVFire and allow Windows to replace files.
  3. To switch and synchronize the backend port to 6789, right-click
     C:\UAVFire\SET-BACKEND-PORT-6789.bat and run it as Administrator.
     If the configuration is already synchronized and only an orphaned backend
     must be restarted, run REPAIR-BACKEND-PORT.bat instead.
  4. Wait for the green message confirming that TCP 6790 and captcha HTTP are healthy.
  5. Refresh http://192.168.1.2:81 with Ctrl+F5.

Safety:
  The 6789 switch temporarily pauses UAVFire-AutoStart and terminates only Java
  processes whose command line identifies uavfire-1.10.0.jar. An unrelated
  listener is reported with PID, executable, and command line and is left
  running. The auto-start task is restored after the repair attempt.

Logs:
  C:\UAVFire\data\logs\backend-stdout.log
  C:\UAVFire\data\logs\backend-stderr.log
  Previous logs are retained with a timestamped .bak suffix.

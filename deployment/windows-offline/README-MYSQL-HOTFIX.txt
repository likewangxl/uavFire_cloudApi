UAVFire Windows MySQL installation hotfix

Applicable error:
  Unknown suffix '$' used for variable 'port'
  Database account initialization failed

Usage:
1. Close the failed INSTALL.bat window.
2. Extract this hotfix directly into C:\UAVFire and allow it to merge/overwrite the scripts directory.
3. Right-click C:\UAVFire\RECOVER-MYSQL.bat and run it as Administrator.
4. The recovery verifies the existing cloud_sample schema, creates the application account,
   writes the installation marker, and then continues INSTALL.bat automatically.
5. Run STATUS.bat after installation finishes.

Do not rename or delete C:\UAVFire\data\mysql for this error. MySQL initialization and schema import
already completed successfully before the bad port argument was reached.

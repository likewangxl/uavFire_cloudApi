UAVFire Windows server address and AI health repair

This repair performs the following actions without deleting database or media data:
1. Stops only UAVFire-managed processes.
2. Sets publicHost to 192.168.1.2 while preserving all other customized ports.
3. Moves UAVFire AI away from the conflicting port 9000 to the first free port from 9002 through 9010.
4. Regenerates backend, Nginx, AI and media configuration.
5. Restarts all UAVFire processes and verifies /healthz plus the complete STATUS checks.

Usage:
1. Extract this ZIP directly into C:\UAVFire and allow scripts/config files to be overwritten.
2. Right-click FIX-WINDOWS-SERVER.bat and choose Run as administrator.
3. Wait for STATUS to finish. The final access URL must show http://192.168.1.2:81.

The repair preserves the existing MySQL port (for example 3307), database files, credentials,
fire images and other generated data.

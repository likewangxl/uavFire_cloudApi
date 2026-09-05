UAVFire cockpit WebRTC hotfix for 192.168.0.100

Observed root cause:
- The browser can load the cockpit and the FLV stream is healthy.
- ZLMediaKit WebRTC signaling succeeds, but its SDP answer advertises the old
  address 192.168.1.2:19586.
- The browser therefore reports zlm-connection-failed.

Run as follows:
1. Copy this extracted folder to the Windows server or a USB drive.
2. Double-click FIX-WEBRTC-IP-192.168.0.100.bat.
3. Approve the administrator prompt.
4. Wait for both of these final lines:
   [OK] running ZLMediaKit rtc.externIP=192.168.0.100
   [OK] WebRTC repair complete.
5. Open http://192.168.0.100:81/leadership-cockpit and press Ctrl+F5.

The script always repairs C:\UAVFire. It does not reconfigure or restart
MySQL, Redis, the Java backend, the AI service, or Nginx. It restarts only the
UAVFire MediaServer and opens WebRTC TCP/UDP 19586 in Windows Firewall.

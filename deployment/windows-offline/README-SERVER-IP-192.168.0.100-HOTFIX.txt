UAVFire Windows server IP and WebRTC hotfix

1. Extract this ZIP anywhere, including the USB drive.
2. Right-click SET-SERVER-IP-192.168.0.100.bat and run it as administrator.
   The script always targets the real installation at C:\UAVFire.
3. Do not run the older V1 or V2 hotfix.
4. Wait until the script prints [OK].
5. Open http://192.168.0.100:81 and press Ctrl+F5.

The script updates the public host used by the backend, frontend and
ZLMediaKit, enables the authenticated MQTT listener for RC Plus OSD data,
opens TCP 81/1883/8089/19586 and UDP 19586, restarts UAVFire, and verifies
the generated WebRTC and MQTT addresses.

# ZLMediaKit Deployment For UAVFire

## Purpose

This folder is the repo-local deployment scaffold for the media hub defined in:

- [m_4_t双流直播与火情识别专项详细设计方案（评审修订版）.md](/Users/likewang/uavfire/m_4_t双流直播与火情识别专项详细设计方案（评审修订版）.md:121)

The agreed route is:

```text
RC Plus -> RTMP publish -> ZLMediaKit -> WebRTC playback -> cockpit
```

It is not:

```text
RC Plus -> LiveKit
```

## What This Scaffold Includes

- `docker-compose.yml`: local/private deployment entrypoint
- `.env.example`: host, port and stream naming template
- URL rules for the current single visible stream and future dual-stream variants

## What It Does Not Do

- It does not start on this machine automatically.
- It does not generate SSL certificates for public internet exposure.
- It does not create a true independent thermal stream. Current M4T/MSDK work uses one stream and explicit thermal degradation unless a future composite-slicing path is added.

## Expected Ports

Based on the official ZLMediaKit docker image examples:

- RTMP publish: `1935`
- HTTP API / static: `58925`
- HTTPS: `8443`
- RTSP: `8554`
- WebRTC TCP/UDP: `10000`
- STUN UDP: `8000`
- RTP proxy UDP: `9000`

Official references:

- ZLMediaKit GitHub README: https://github.com/ZLMediaKit/ZLMediaKit
- ZLMediaKit Guide: https://docs.zlmediakit.com/guide/
- ZLMediaKit WebRTC Guide: https://docs.zlmediakit.com/zh/guide/protocol/webrtc/

## Stream Naming

Current implementation uses one ZLM stream per aircraft/agent:

- stream group: `{droneSn}` or configured aircraft SN
- current visible/main stream: `{effectiveSn}-0`

`DjiLiveStreamController` computes `effectiveSn` as:

1. `BuildConfig.AGENT_AIRCRAFT_SN`, when non-empty.
2. The command/runtime `droneSn`, otherwise.

For the current LAN baseline:

- RTMP publish:
  `rtmp://172.20.10.7:1935/live/{effectiveSn}-0`
- WebRTC playback:
  `webrtc://172.20.10.7:58925/live/{effectiveSn}-0`
- RTSP pull for ai-service:
  `rtsp://172.20.10.7:8554/live/{effectiveSn}-0`

Historical docs mention `{droneSn}_visible` / `{droneSn}_thermal`. Treat those as the original design target, not the current implementation.

Future variants may add:

- `{effectiveSn}-visible-slice` and `{effectiveSn}-thermal-slice` if backend or ai-service exposes side-by-side composite slicing as separate outputs.
- Cloud SDK dual-stream names if the future `video-demand-aux-manual` PoC proves true dual streams.

## How To Start On A Real Host

1. Install Docker and Docker Compose plugin on the target host.
2. Copy `.env.example` to `.env` and replace `ZLM_PUBLIC_HOST`.
3. Run:

```bash
cd deployment/zlmediakit
cp .env.example .env
docker compose up -d
```

4. Verify container and ports:

```bash
docker compose ps
docker logs uavfire-zlmediakit --tail 100
```

## What You Need To Provide

To complete the end-to-end media path, you still need one reachable host:

- A Linux/macOS host or VM where Docker is available
- LAN IP or domain visible to both RC Plus and browser clients
- Open firewall for at least:
  - `1935/tcp`
  - `58925/tcp`
  - `10000/tcp`
  - `10000/udp`

## Next Repo Steps After ZLMediaKit Is Running

1. Confirm `rcplus-msdk-agent/gradle.properties` points at the active ZLM host.
2. Start the agent and confirm ZLM sees `live/{effectiveSn}-0`.
3. Confirm backend DualStream group exposes `visiblePlayUrl = webrtc://.../{effectiveSn}-0`.
4. Open cockpit live tab and verify `ZLMRTCClient.Endpoint` playback.
5. If thermal AI is required, choose and implement one of the current fallback paths in `docs/MSDK_V5_THERMAL_DUAL_STREAM_RESEARCH.md`.

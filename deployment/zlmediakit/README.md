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
- URL rules for visible/thermal push and playback

## What It Does Not Do

- It does not start on this machine automatically because `docker` is not installed here.
- It does not yet wire RC Plus publish code or cockpit player code.
- It does not generate SSL certificates for public internet exposure.

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

The project design already fixed the stream ids:

- stream group: `{droneSn}`
- visible stream: `{droneSn}_visible`
- thermal stream: `{droneSn}_thermal`

For the current default `droneSn = RC_PLUS_LOCAL`, the publish targets become:

- visible RTMP:
  `rtmp://{ZLM_PUBLIC_HOST}:{ZLM_RTMP_PORT}/{ZLM_STREAM_APP}/RC_PLUS_LOCAL_visible`
- thermal RTMP:
  `rtmp://{ZLM_PUBLIC_HOST}:{ZLM_RTMP_PORT}/{ZLM_STREAM_APP}/RC_PLUS_LOCAL_thermal`

Browser playback targets should follow ZLMediaKit WebRTC URL format:

- visible WebRTC:
  `webrtc://{ZLM_PUBLIC_HOST}:{ZLM_HTTP_PORT}/{ZLM_STREAM_APP}/RC_PLUS_LOCAL_visible`
- thermal WebRTC:
  `webrtc://{ZLM_PUBLIC_HOST}:{ZLM_HTTP_PORT}/{ZLM_STREAM_APP}/RC_PLUS_LOCAL_thermal`

If the frontend chooses to use HTTP-FLV as fallback:

- visible HTTP-FLV:
  `http://{ZLM_PUBLIC_HOST}:{ZLM_HTTP_PORT}/{ZLM_STREAM_APP}/RC_PLUS_LOCAL_visible.live.flv`
- thermal HTTP-FLV:
  `http://{ZLM_PUBLIC_HOST}:{ZLM_HTTP_PORT}/{ZLM_STREAM_APP}/RC_PLUS_LOCAL_thermal.live.flv`

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

1. Point RC Plus publish config at the visible/thermal RTMP URLs above.
2. Let `rcplus-msdk-agent` report:
   - `playbackStatus = ready`
   - `visiblePlayUrl = webrtc://.../RC_PLUS_LOCAL_visible`
   - `thermalPlayUrl = webrtc://.../RC_PLUS_LOCAL_thermal`
3. Replace the cockpit runtime placeholder with a real WebRTC player.

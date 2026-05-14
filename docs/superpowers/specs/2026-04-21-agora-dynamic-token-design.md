# Agora Dynamic Token Design

## Goal

把当前写死在后端配置中的 Agora RTC token 改为服务端动态生成，避免前端加入频道时因为临时 token 过期导致 `CAN_NOT_GET_GATEWAY_SERVER` / `dynamic key or token timeout`。

## Current Root Cause

- 当前前端通过 `/manage/api/v1/live/agora/config` 获取 `appid/channel/token`
- 后端 `LiveStreamServiceImpl#getAgoraFrontendConfig()` 直接把 `application.yml` 中的静态 token 返回给前端
- Agora RTC token 是短期有效的，过期后前端仍然会拿到旧 token，因此 join 失败

## Chosen Approach

使用 Agora 官方 Maven 依赖 `io.agora:authentication` 在后端动态生成 RTC token。

- 保持前端接口契约不变，仍返回 `appid/channel/token`
- 后端配置新增：
  - `livestream.url.agora.app-certificate`
  - `livestream.url.agora.token-expire-seconds`
- 后端每次请求 `/live/agora/config` 时使用：
  - `appid`
  - `appCertificate`
  - `channel`
  - 固定 `uid=0`
  - audience/subscriber 角色
  - 可配置过期时间
  生成一个新 token

## Why UID 0

当前前端在浏览器中为每次会话随机生成 Agora uid。为了不改前端接口和调用方式，服务端 token 使用 `uid=0` 生成，这样同一 channel 下前端可继续用自己的随机 uid 加入。

## Config Rules

- `app-certificate` 只允许存在后端配置中，不能下发给前端
- `token-expire-seconds` 默认建议 `3600`
- 配置缺失时，后端应明确返回空 token 或配置错误状态，而不是继续返回旧静态 token

## Verification

- 新增后端单元测试，验证：
  - 动态 token 会被生成
  - token 不再等于旧静态配置值
  - 配置 DTO 仍包含 `appid/channel/token`
- 启动后端后调用 `/manage/api/v1/live/agora/config`，确认返回 token 非空

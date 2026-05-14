# DRC-Assisted Takeoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace TSA official takeoff stage 1 with a DRC-assisted climb that keeps cloud control held while preserving the existing stage-2 `fly_to_point` flow.

**Architecture:** Add a small stage-1 policy module that decides when DRC climb may start, when it is complete, and when it must fail. Wire `tsa.vue` to use that policy, add a `climbing_stage1` phase, and reuse existing manual-control stick publishing rather than `takeoff_to_point`.

**Tech Stack:** Vue 3, Vite, Node test runner, existing DRC MQTT/manual-control helpers.

---

# Context

How this app was made (the "spatial app factory" line: research → design → build → QA → showcase).

- `design-package.json` — the binding design package (v1.1, gate B changed once: "cartoon audience"), states S00–S15 + M1–M8, hall geometry, gaze model, palette, copy.
- PRD and the clickable sample are private Claude artifacts (links in the factory state, not reproduced here).
- QA round 1: 1 finding (q1-1, user headset feedback "the panel's bottom-right goes into the 3D lectern"), fixed in v0.1.1: the lectern panel keeps its heading and only tilts towards the eyes (20–70°), the lectern sits under the panel with ≥ 7 cm clearance.
- Deviations from the design (all within tolerance) are listed in the factory state; the main ones: per-listener entities instead of mesh instancing, toon shading baked into palette UVs instead of a ShaderGraph material and IBL, seat tints above the heads, no seat highlight under the replay beam.
- v0.1.2 (user feedback at gate C: "use eye tracking, and visualise the gaze, e.g. with light"): gaze from the public `EyeTrackingProvider` with a head-direction fallback and a panel hint; a follow-spot + floor pool + lit listeners show where you are looking; the design package said head direction only (changed at the user's request).

# Tasks — Fraud Engine + Simulator

## TASK 2 (current): DrawIO full-system flow diagram
Output: docs/diagrams/system-flow.drawio (2 pages, embedded brand icons).

- [x] Map deployment/data/observability wiring (3 Explore agents)
- [x] Clarify: single master canvas + exploded data page; embedded logos; real+gaps legend; path docs/diagrams/
- [x] Fetch + embed 15 brand icons (devicon/simple-icons) as percent-encoded SVG data-URIs
- [x] Generate system-flow.drawio (2 pages, 122 nodes, 70 edges, 26 icons)
- [x] Validate: well-formed XML; all 26 icons decode to valid SVG; edges resolve; no off-canvas
- [x] Adversarial verification workflow (coverage / render / honesty) — 3/3 PASS, 0 high/med findings
- [x] No fixes needed — diagram verified complete, accurate, renderable, honest

## Review (Task 2)
- docs/diagrams/system-flow.drawio: 2 pages, 122 nodes, 70 edges, 15 brand icons.
- Icons embedded as percent-encoded SVG data-URIs (offline-safe; render-proven by
  decoding all 26 back to xmllint-valid SVG).
- Independent 3-verifier fan-out (coverage/render/honesty) all PASS.
- Generator kept in /tmp (repo stays clean); deliverable is the single .drawio.

## TASK 2b (done): low-crossing relayout of system-flow.drawio
- [x] Rewrote as layered (Sugiyama) flow: L→R spine, stores grouped, reserved
      channels for long edges, every edge explicitly orthogonally routed.
- [x] Built exact geometric crossing checker (edge-through-node, edge-edge,
      non-ortho, node-overlap) to measure + iterate without a renderer.
- [x] RESULT — Master: 0 edges-through-boxes, 8 distinct-colour crossings (top
      Redis/Kafka channel + bottom-right store cluster; irreducible on 1 canvas).
      Data plane: 0 through-boxes, 0 crossings. 0 component overlaps either page.
- [x] Coverage 100% retained (all inventory labels present), XML valid, 24 icons valid.
- Offer: split into per-subsystem pages for literally zero crossings if wanted.

## TASK 1 (done earlier): Interview Prep pack (SE L3)
- [x] simulator-walkthrough.md, gap-analysis.md, likely-questions.md +Q26–37
- [ ] Live mock-interview drill (was interrupted by Task 2) — resume on request

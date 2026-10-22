# Liquid experiment

A design mock for a "completely liquid" Oriel: every interaction morphs, stretches, merges and
splits like fluid, follows the finger, and settles on springs that keep the finger's speed. It is
an experiment to judge the feel before touching the Compose code, not a spec.

## Open it

Open `index.html` in a browser (double-click it, or `xdg-open design/liquid/index.html`). It is one
self-contained file: no network requests, no libraries, invented content only. It works with a mouse
and with touch (pointer events), so you can also open it on a phone. The panel beside the phone has
a light theme toggle, **Reduce motion** (liquid effects become plain fades) and a 4× slow motion
switch for studying the springs, plus a list of interactions with "Show" buttons.

## Interactions

| Interaction | How to trigger | What happens |
| --- | --- | --- |
| Tab bar | Tap a tab, or drag along the bar | The selected pill's two edges ride separate springs (leading stiff, trailing soft), so it stretches toward the target, necks in the middle and snaps in with overshoot. |
| Search droplet | Tap Search, or drag it left into the bar; ✕ to close | Bar and search are blobs under an SVG metaball filter. The search edge moves fast and the bar edge slow, so they fuse mid-way and split again when the gap reopens. |
| Squishy surfaces | Press and move on any button, chip or card | Scales about the touch point, leans toward it and lights under it; stretches after the finger; release gives a jelly wobble seeded with velocity. |
| Chips | Tap a chip on Home | Same two-edge blob as the tab bar; the inverted label is clipped to the blob's path. |
| Pull to refresh | Drag Home down from the top | A drop hangs on a thinning neck; past the threshold the neck snaps and the drop becomes a gooey spinner (satellites fusing with it). |
| Context menu | Press and hold a card | The card lifts out of a blurred page; the menu oozes out of its bottom edge through a goo bridge, then pinches off. |
| Mini player ↔ watch | Drag the mini player up (or tap); drag the video, or the page at its top, down | The player elongates along its travel, leaves a droplet at the dock tied by a goo tail that snaps, and morphs into the full player. Collapsing lands with a squash. |
| Sheets | Watch page: Chapters or ⋯ (or ⋯ on any card) | The sheet grows out of the button: same rect and radius at the start, height leading width, sides swelling mid-flight. Drag down to shrink it back in; drag up to rubber-band. |
| Switches, segmented | You tab, Settings | The knob/thumb is a blob that widens under the finger and stretches in its direction of travel; draggable. |
| Page push and back | Gear on You, avatar on Home; swipe right | The page's leading edge bulges as it pushes in and dents where the finger holds it during the back swipe. |
| Shorts | Swipe vertically; tap the heart | Pages snap on springs; the heart splashes droplets that detach and fall back in. |

## Taking it to Compose

- **Springs.** The mock's helper is a damped oscillator set by response and damping ratio, the
  same model as `spring(dampingRatio, stiffness)`. Use `Animatable.animateTo(target, spec,
  initialVelocity = velocityTracker.calculateVelocity())` so every release carries the finger's
  speed, and keep the existing `motion(...)` wrapper so Reduce motion turns them into fades.
- **Two-edge blobs** (tab pill, chips, switch knob, segmented thumb). Two `Animatable<Float>`s for
  the left and right edges with different specs; draw the capsule (with the neck) in `drawBehind`
  from a `Path`, reading the animatables in the draw phase so nothing recomposes per frame.
- **Metaballs** (search merge, dock droplet, menu bridge, spinner, like splash). On Android 13+
  use an AGSL `RuntimeShader` as a `RenderEffect` on a small `graphicsLayer`: blur plus an
  alpha threshold, or an analytic metaball field for a few circles and rounded rects. Below 13
  there is no cheap equivalent, so skip the fusion and animate the shapes as separate blobs.
- **Morphs** (sheet from button, page edge). Build the outline as a `Path` each frame (rounded rect
  with swelling sides, or the edge curve) and use it in `clipPath`/`Outline`; for shape-to-shape
  morphs between polygons, `androidx.graphics:graphics-shapes` `Morph` interpolates cleanly.
- **Cost.** Blur and runtime shaders are the expensive part: keep them on small layers, only
  while something is moving, and never on scrolling content. Path clips are cheap. Degrade in
  steps: no metaball shader below Android 13, no clips/edge bulges with Liquid glass off, fades
  only with Reduce motion. Every gesture still needs its accessible equivalent (buttons remain).

## Known rough edges

Everything is mock data; there is no video. Text clipping under the morphing sheet and the
chip label clip are approximations. Performance was checked only in desktop Chromium (headless);
Safari's SVG filters on HTML elements are slower and may stutter on the metaball layers.

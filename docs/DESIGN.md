# Design rules

Oriel looks and feels like an iOS app, modelled on [BitChord](https://github.com/kushagrasinghx/BitChord):
liquid glass, a floating tab bar, large titles, grouped lists, sheets and press-and-hold menus.
The bar is BitChord's feel and seamlessness: nothing jumps, nothing flashes, everything follows
the finger. A change that looks like Material, or like Android's YouTube app, isn't merged until
it looks like iOS.

Every visual change needs screenshots in the pull request, dark and light (see "Proof" below).

## Layout

- Page gutter is `PAGE_GUTTER` (16dp). Don't invent other side margins.
- Tab roots use a **large title** that collapses into the bar on scroll; pushed pages get a back
  chevron and a centered title. Use `PageScaffold`; don't build page chrome by hand.
- Lists of settings or options are **grouped** (`SettingsGroup`, `SettingsRow`, `ChoiceRow`,
  `SwitchRow`): rounded cards on `surfaceVariant`, hairline dividers, group header above and footer
  text below. Not Material list items, not cards with elevation.
- Choices that cover part of the screen are **sheets** (`OrielSheet`) with a grabber, a
  `SheetTitle`, and a close or Done on the right. Confirmations are iOS alerts (`AlertSpec`), not
  Material dialogs. Item actions are press-and-hold menus (`HeldContextMenu`) or the ⋮ sheet.
- Nothing scrolls under a status bar or the tab bar without the glass treatment; use the scaffold's insets.

## Touch

- Every tappable thing has a hit area of at least **44dp**, even when it's drawn smaller. Grow the
  touch area, not the visual (an outer `Box` with the clickable, or `minimumInteractiveComponentSize()`).
- Every tap gives feedback: a press scale or dim (as `FilterChip` and `ActionTile` do), and the
  matching haptic from `rememberHaptics()` (`Tap`, `Select`, `ToggleOn/Off`, `Expand`…). No Material
  ripples on iOS-style surfaces.
- Gestures follow the finger the whole way and settle with a spring at the finger's speed:
  dragging sheets, the player, panels, reorder handles. A gesture that does nothing until release,
  then jumps, is a bug.
- Every gesture has an accessible equivalent (a button, a custom accessibility action, or a menu item).

## Motion

- Use the springs in `ui/theme/Motion.kt` (`SheetSpring`, `PlayerSpring`, `GlassSpring`,
  `MenuSpring`) and `EaseOutCubic`. Don't introduce new curves without a reason in the PR.
- Wrap animation specs in `motion(...)` / `fade(...)` so **Reduce motion** turns them into snaps.
- Pushes slide like iOS (whole page from the right, the old page drifts left and dims); tab
  switches crossfade. Don't add Material shared-axis or fade-through transitions.
- Animate in the draw or layout phase where possible (`graphicsLayer`, layout lambdas), never by
  recomposing every frame. Read fast-changing state (scroll offsets, playback position, drag
  offsets) inside `graphicsLayer {}`, `drawBehind {}` or `Modifier.layout {}`.

## Type, colour, icons

- Inter (`Inter`, `InterDisplay`) through `MaterialTheme.typography`; don't set font families
  inline. Titles are heavier and tighter, as iOS's are.
- Colours come from the theme and `themePalette()`. Primary is white on dark and black on light,
  not a brand colour; red is for LIVE and destructive actions only. Support dark and light; test both.
- Icons: the SF-style line glyphs in `Glyphs.kt` first, then Material Rounded through `OrielIcons`.
  Never Material Filled/Outlined/Sharp mixed in, never YouTube's own icons.
- Glass: use the shared glass modifiers; it must degrade cleanly when Liquid glass is off and
  when Reduce transparency is on.

## Accessibility

- Every icon-only control has a content description. Selected and on/off states are announced
  (`selected`, `toggleable`, `Role`), not only drawn.
- Text respects the system font size; don't fix heights that clip larger text.
- Contrast: text on glass or on video gets a shade or scrim, as the Shorts overlay does.

## Proof

Run the screenshot tests (`./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true`); images
land in `app/build/screenshots/`. Attach the before and after for every screen you touched, in dark
and light, to the PR. Add a screenshot test for any new screen or state.

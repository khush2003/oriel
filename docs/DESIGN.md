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
  text below. Not Material list items, not cards with elevation. This is the look inside sheets.
- **Settings is a control pane of glass** (`GlassPanes.kt`), not a grouped list: the page has a
  colour wash in the accent's own family (`PageScaffold(wash = true)`), categories are
  `CategoryTile`s, a switch is a `ToggleTile` (glyph and switch above, name below; `wide` when it
  stands alone), a short choice is a `SegmentTile` with a sliding glass thumb, a choice of a
  handful is a `PickerRow` whose menu grows from its value, and plain rows sit in a `GlassPane`.
  One corner (28 dp) for panes and tiles, 18 dp for what nests in them; nothing is a capsule but
  a switch. A pane wears the bars' own rim light and shadow over a light veil
  (`glassPane`), the wash showing through it. It samples and blurs nothing: a page holds a
  dozen panes that redraw as it scrolls, and bending a smooth wash shows next to nothing.
  Never an even outline, which draws a page of boxes. Every glass surface follows the same
  three modes: Liquid glass on (lens, rim light), off (frosted: a plain blur and a hairline),
  and Reduce transparency (solid, no blur anywhere, the scrim under sheets included). Labels are medium weight, not bold. State is the switch alone: tiles don't change colour. Line glyphs only
  (`Glyphs.Line…`), each in a soft disc (`GlyphDisc`), no coloured icon tiles. Explanations go behind an `InfoTip` on the group's
  header, never as paragraphs on the page; write a tip as `Name: what it does` lines, one per
  control, and it's set as a short list. Rows in a `GlassPane` are parted by hairlines from where
  their text starts (`dividers = false` for a pane that holds one control, `glyphs = false` for
  rows that lead with their text). You is built the same way: the account as a pane, the library as tiles.
  A new setting goes on the page it belongs to (`SettingsSection`), not on the root.
- Choices that cover part of the screen are **sheets** (`OrielSheet`) with a grabber, a
  `SheetTitle`, and a close or Done on the right. A sheet floats: a card clear of the
  screen's edges made of the account card's own glass (`glassSurface(panel = true)`, sampling
  the page from the sheet's window), with the page blurred and dimmed behind it. Text on glass
  always sits on that panel tint, never on clear glass. Inside, short choices go straight on the sheet
  and forms are `PaneHeader` + `GlassPane`, with the actions that finish it as buttons at its foot. Inside a sheet or an alert
  `LocalOnGlass` is set, and `SettingsGroup`/`SettingsRow` take the same finish (glass group,
  glyphs in discs), so every sheet matches without restyling each; alerts, popup menus and the
  press-and-hold menu wear the same gloss. Confirmations are iOS alerts (`AlertSpec`), not
  Material dialogs. Item actions are press-and-hold menus (`HeldContextMenu`) or the ⋮ sheet.
- Nothing scrolls under a status bar or the tab bar without the glass treatment; use the scaffold's insets.
- The tab bar is icons only, a pill centred above the bottom edge with the search circle beside it;
  each tab's name is its content description. It draws in a little (scaled in its layer, nothing
  re-measured) while the reader scrolls on down a page, and comes back to full size as they scroll up. With liquid glass and motion on, search opens as a field
  in the bar: drag the search circle left to open it, drag the back circle right to close it.

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
- The **accent** (`accent()`, chosen in Settings, coral from the app icon by default) marks state:
  a switch that's on, the tick on a choice, watch progress, an unread dot, and it colours the
  wash behind glass pages. Buttons, chips and the tab bar stay primary;
  tiles and panes never take it. Over a video or artwork use `accentOnMedia()`. The player's scrubber stays
  white, since SponsorBlock's segment colours share its track.
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

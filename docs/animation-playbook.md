# Pip animation playbook

How to make Pip, our floating-head gnome, move with weight, appeal and readable emotion, written against our engine
(`ui/anim/Keyframes.kt` plus the scene DSL in `ui/show/ShowDsl.kt`). Every recipe below uses the properties Pip
actually has: `x/y`, `size`, `facing`, `squash(sx, sy)` (scaled from his feet), `rotation` (degrees, pivot at his
feet), `eyesOpen`, `blink`, `look(x, y)` (about -1..1), `hatSway` (-1..1, swings the floppy tip), `hatDroop` (0..1,
tips the whole hat), `blush`, the discrete `mood` (NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED) and `mouth` (NONE, SMILE,
FROWN, O, SHOUT, FLAT), and the two hands.

Conventions used in this document:

- Times are given as **ms** and as **frames at 60 fps** (1 f = 16.7 ms). Film references quoted at 24 fps are converted
  (1 film frame = 41.7 ms = 2.5 f at 60 fps).
- Keyframe percentages are of the animation's `duration`. A stop's ease governs the segment that *starts* at that stop
  (CSS rules, and how our `Keyframes.apply` works).
- Screen `y` grows downward, so "up" is a negative `y`. Android canvas rotation is clockwise for positive degrees.
- Values marked **(derived)** are my own calculations or recommendations; everything else links to a source.
- Recipes use two eases our `Easing` object doesn't have yet. Add them before using the snippets:
  `val easeInOutSine = Easing.cubicBezier(0.37f, 0f, 0.63f, 1f)` and
  `val easeOutSine = Easing.cubicBezier(0.61f, 1f, 0.88f, 1f)`.
- Use `Composite.ADD` for position, rotation and hat offsets so they stack on the idle; keep `squash` in REPLACE mode
  with one owner at a time (additive squash would sum offsets from 1).

---

## 1. Cheat sheet (the rules we apply to Pip)

**Body (the whole head is the body)**
- Squash on every landing and stretch on every launch; keep `sx * sy ≈ 1` so he never gains or loses volume.
- Cap squash at about ±20% for normal acting, ±30% for big comedy hits; beyond that his eyes distort and he stops reading as Pip.
- Squash and stretch along the path of travel: tall when moving up or down fast, round at the apex, wide on impact.
- Every jump, dash or big turn gets an anticipation in the opposite direction first (squash down before up, lean back before forward).
- Anticipate for 100-200 ms; the action itself can be 3-4 film frames (125-170 ms) if the anticipation was clear.
- Move on arcs. Combine `x` and `y` tracks with different eases so the path curves; never move him diagonally in a straight line at constant speed.
- Up is ease-out (decelerate), down is ease-in (accelerate). Gravity has no ease-in-out.

**Secondary motion**
- The hat tip lags the head by 60-100 ms (4-6 f) and overshoots when the head stops. It is our follow-through; never let it move in sync with the head.
- Hands lead or trail the head by 2-4 f and never move as mirrored twins; offset left and right by 1-2 f.
- Layer motion with `Composite.ADD` (idle bob, hat sway, hop offsets) so actions stack instead of fighting.

**Eyes and face**
- Eyes lead: change `look` 80-170 ms before the head turns or travels.
- Blink on every change of thought, on every big head turn (mid-turn or near the end), and every 2-6 s when idle.
- Close the lids faster than you open them (about 1:1.5). A symmetric linear blink looks mechanical.
- Swap the discrete `mood`/`mouth` while the eyes are shut or during a fast move, so the pop is hidden.
- Never leave him dead still. Holds are moving holds: a 1-3 px drift, a breath, a slow hat settle, a blink.

**Timing and staging**
- One idea at a time: a reaction finishes reading before the next one starts, but actions overlap at the seams.
- Give comedy a beat: hold the "realisation" pose 250-500 ms before the big reaction.
- Contrast is the point: a fast move reads fast only next to a hold or a slow move.
- Keep gestures in silhouette: hands outside the head outline, tilts clear of the hat.
- Mood changes speed: happy is quick and bouncy with short ground contact; sad is slow, low and long on the ground.
- Randomise idles: blink intervals, dart targets and loop periods should never line up into a visible cycle.
- Respect "Remove animations": when the system animator scale is 0, show key poses with cuts or short fades only.

---

## 2. Principles applied to a floating head

The 12 principles come from Frank Thomas and Ollie Johnston's *The Illusion of Life* (1981); see the summary on
[Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation). John Lasseter's 1987 SIGGRAPH paper
showed how they carry over to keyframed computer animation using Luxo Jr., a lamp with no face at all, which is the
closest well-documented precedent for Pip
([Lasseter 1987, PDF](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)).

With no body, Pip's head does the work of a whole figure: the head is torso, posture and face in one. Here is what
each principle becomes for him.

### 2.1 Squash and stretch, with volume preservation

The rule: an object's volume stays constant as it deforms, so stretching one axis narrows the other
([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). Lasseter applies this to keyframed
scale: when you scale up along one axis, scale down the others to keep the volume
([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)). Josh Comeau's squash demo
anchors the transform at the bottom centre so the object stays glued to the floor, which matches Pip's
feet pivot, and advises keeping the effect subtle in real UI
([Comeau, Squash and Stretch](https://www.joshwcomeau.com/animation/squash-and-stretch/)).

For a 2D blob, "volume" is area, so pick `sy` and set `sx = 1 / sy` **(derived)**:

| Pose | `squash(sx, sy)` | Use |
| --- | --- | --- |
| Gentle breath | (1.02, 0.98) | Idle |
| Anticipation | (1.16, 0.86) | Before a hop or dash |
| Takeoff stretch | (0.87, 1.15) | First frames of the jump |
| Impact | (1.22, 0.82) | Landing |
| Comedy splat | (1.43, 0.70) | Fall from height, slapstick only |

Kirby is the model to copy here: his body deforms freely, but the distance between his eyes, and between eyes and
mouth, does not stretch, to protect his cuteness
([Wikipedia, Kirby](https://en.wikipedia.org/wiki/Kirby_(character))). Our renderer scales the whole face with the
squash, so past about ±20% the eyes become slits or stilts. Two options **(derived)**: keep squash within the table
above, or have `PipRenderer` counter-scale the eye ovals by `1/sx, 1/sy` so the features stay round while the head
deforms.

**Recipe: landing squash with a damped wobble** (450 ms, REPLACE; values from the spring table in section 5):

```kotlin
animate(at = tLand, duration = 0.45, ease = Easing.linear) {
  at(0,  easeOutSine)    { squash(1, 1) }
  at(9,  easeInOutSine)  { squash(1.25, 0.80) }   // impact
  at(29, easeInOutSine)  { squash(0.92, 1.09) }   // rebound
  at(49, easeInOutSine)  { squash(1.04, 0.96) }
  at(70, easeInOutSine)  { squash(0.98, 1.02) }
  to                     { squash(1, 1) }
}
```

### 2.2 Anticipation

Anticipation prepares the audience for an action and directs their eye; Lasseter notes that the faster the action,
the more the anticipation must carry it. His example is a zip-off that lasts only 3-4 frames but is anticipated long
enough that the audience knows exactly what is coming
([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)).

For Pip: squash down before rising, lean (rotate) away before dashing, shrink the eyes slightly (`eyesOpen 0.8`)
before a big surprised pop, pull a hand back before a point.

**Recipe: dash right** (anticipation 160 ms, travel 220 ms, overshoot settle 260 ms; total 640 ms):

```kotlin
// Lean back on the feet pivot, then whip forward. ADD so it stacks on the idle.
animate(at = t, duration = 0.64, composite = Composite.ADD) {
  at(0,  Easing.easeOut)           { rotation(0);   x(0) }
  at(25, Easing.cubicBezier(0.5f, 0f, 0.75f, 0f)) { rotation(-10); x(-12) }  // wind up (lean away)
  at(59, Easing.cubicBezier(0.3f, 0.7f, 0.4f, 1.5f)) { rotation(8); x(dist) } // arrive leaning in
  to                               { rotation(0);   x(dist) }
}
// Eyes lead: look toward the target ~120 ms before the wind-up ends.
animate(at = t, duration = 0.12, ease = Easing.easeOut) { to { look(0.8, 0) } }
```

### 2.3 Staging

Present one idea at a time so it reads completely and unmistakably; the eye goes to movement in a still scene and to
stillness in a busy one. Luxo Jr. was staged to the side and in silhouette so every action read clearly
([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)).

For Pip:
- Put hands and props outside the head outline; a hand in front of his face reads as a blob.
- Use `facing` and a 3/4 look direction so a gesture plays across the screen, not toward the camera.
- When a speech bubble or prop is the point, freeze everything else into a moving hold so the eye goes there.
- Don't stack a mood change, a hop and a hand gesture on the same frame.

### 2.4 Straight ahead versus pose to pose

Our engine is pose to pose by nature: you set the key poses at percentages and the engine fills the in-betweens. Block
the extremes first (anticipation, contact, apex, impact, settle), check the timing with linear eases, then add the
per-stop eases. Use straight-ahead thinking only for noisy secondary layers (trembles, hat flutter), which are better
written as short additive loops.

### 2.5 Follow-through and overlapping action (the hat)

Loose parts keep moving after the main mass stops, different parts move at different rates, and "drag" means parts
lag a few frames behind ([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). In Luxo Jr.
the cord trails the hop; in Wally B.'s zip-off the feet lag so far they stay on screen after the body has gone, and
they leave 1-2 frames apart, which makes the action more interesting. Lasseter also insists that the next action
should start before the previous one completely stops
([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)).

Pip's floppy hat tip is his cord. Rules **(derived)**:
- **Lag**: the hat track is the head track shifted 60-100 ms (4-6 f) later. Use the same keyframes with `delay`.
- **Direction**: the tip swings opposite to the direction of travel while accelerating and in the direction of travel
  when the head stops.
- **Amplitude**: about `hatSway ±0.3` for a dash, `±0.6` for a whip-turn; `hatDroop +0.3 → 0` for a hop.
- **Settle**: overshoot once or twice with decreasing amplitude (use the ζ = 0.25 wobble in section 5).

**Recipe: hat follow-through for a dash** (ADD, starts 80 ms after the head):

```kotlin
animate(at = t, delay = 0.08, duration = 0.7, composite = Composite.ADD) {
  at(0,  easeInOutSine) { hatSway(0) }
  at(30, easeInOutSine) { hatSway(-0.35) }  // tip trails while the head accelerates
  at(55, easeInOutSine) { hatSway(0.30) }   // head has stopped, tip keeps going
  at(75, easeInOutSine) { hatSway(-0.10) }
  to                    { hatSway(0) }
}
```

### 2.6 Slow in and slow out

More in-betweens near the extremes and fewer in the middle give natural acceleration
([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). In our terms: per-stop eases.
Choose the ease by what the motion is (section 5), not by habit. Gravity is the exception: a ball or a hopping
Pip decelerates going up and accelerates coming down, and does not ease into the floor.

### 2.7 Arcs

Natural motion follows arcs; mechanical motion follows straight lines
([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). With separate `x` and `y` tracks
you get an arc for free by giving them different eases: `x` linear or gentle ease, `y` ease-out up and ease-in down.
For a swoop, offset the `y` keyframe so its extreme lands at a different percentage from `x`'s. Rotation on the feet
pivot already moves the top of the hat on an arc, which is why a small lean reads as natural.

### 2.8 Secondary action

Secondary actions support the main action without distracting from it
([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). Pip's are: blush rising during a
compliment, a hand rubbing the hat brim while thinking, the eyes glancing at a prop while he talks. Start them after
the main action has read, never on the same frame.

### 2.9 Timing

Timing alone can make two identical objects read as different weights; heavy things take longer to start, stop and
change direction ([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)). The same
paper reproduces Thomas and Johnston's head-turn table: the same two extreme head poses mean different things purely
by how many in-betweens connect them. Converted to our clock **(derived; assumes one drawing per 2 film frames, so
halve the times if the original was on ones)**:

| In-betweens | Approx. ms | Meaning |
| --- | --- | --- |
| 0 | 80 | Hit by a tremendous force |
| 1 | 170 | Hit by a brick or frying pan |
| 2 | 250 | Nervous tic, twitch |
| 3 | 330 | Dodging a brick |
| 4 | 420 | Crisp order: "Move it!" |
| 5 | 500 | Friendly: "Over here" |
| 6 | 580 | Sees something he has always wanted |
| 7 | 670 | Tries to get a better look |
| 8 | 750 | Searches a shelf |
| 9 | 830 | Appraises, considering thoughtfully |
| 10 | 920 | Stretches a sore muscle |

Use this table directly for Pip's look-and-tilt changes: the same `look` + `rotation` change at 170 ms is a flinch,
at 500 ms a friendly glance, at 830 ms a considered look.

Mood also lives in timing. Luxo Jr. hops fast with little ground time while chasing the ball, and after he pops it
each hop is slower with more time on the ground and the head down
([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)).

### 2.10 Exaggeration

Push poses past realism so they read, but keep the scene balanced
([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). For Pip, exaggerate in time and
pose, not in deformation: a longer anticipation, a bigger lean, a longer hold, larger hands. Keep deformation in the
section 2.1 limits. Duolingo's Lily team took the opposite lesson for a deadpan character, deliberately toning down
wide eyes to protect her personality ([Rive blog](https://rive.app/blog/duolingo-s-ai-powered-video-call-brings-lily-to-life)).
Exaggeration should serve who Pip is.

### 2.11 Solid drawing (solid posing)

Thomas and Johnston warned against "twins", mirrored left and right sides
([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). For Pip: never pose both hands
symmetrically, tilt the head a few degrees in most held poses, and let one eye lead in a look by offsetting `lookY`
slightly against the head tilt.

### 2.12 Appeal

Appeal is the character's charisma; simple, rounded, baby-like proportions help likeable characters
([Wikipedia](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)). With minimal features, small changes
matter a lot: Nintendo spent months with Good-Feel adjusting Kirby's eye and mouth positions because tiny proportion
shifts change the impression ([Wikipedia, Kirby](https://en.wikipedia.org/wiki/Kirby_(character))). Treat Pip's eye
spacing, eye size and hat silhouette as brand assets, and test every new expression at phone size.

### Holds and moving holds

A moving hold keeps a pose alive with tiny motion; Animation Mentor's advice is that if the eyes are alive you can
nearly freeze the rest, and that overdoing the drift produces a floaty "CG" feel
([Animation Mentor](https://animationmentor.com/blog/why-all-animators-need-to-master-the-moving-hold)).

**Recipe: moving hold** (ADD, run under any held pose, 1.2-2 s):

```kotlin
animate(at = tHold, duration = 1.6, ease = easeInOutSine, composite = Composite.ADD) {
  from { y(0);  rotation(0) }
  to   { y(-2); rotation(0.6) }      // a 2 px settle and a slight lean further into the pose
}
// plus one blink somewhere in the hold, and an eye dart if the hold runs past ~2 s
```

### Recipe: the hop (all tracks)

A complete hop of 720 ms that stays in place (ADD on `y` so it composes with wherever he is; squash REPLACE).
Percentages are of 720 ms.

```kotlin
// Body squash/stretch
animate(at = t, duration = 0.72, ease = Easing.linear) {
  at(0,  Easing.easeOut)        { squash(1, 1) }
  at(15, Easing.cubicBezier(0.2f, 0.9f, 0.3f, 1f)) { squash(1.16, 0.86) } // 108 ms anticipation
  at(25, Easing.easeInOut)      { squash(0.87, 1.15) }  // takeoff stretch
  at(45, Easing.linear)         { squash(1, 1) }        // round at the apex
  at(66, Easing.easeIn)         { squash(1, 1) }
  at(74, Easing.linear)         { squash(0.9, 1.11) }   // falling stretch just before contact
  at(77, easeInOutSine)         { squash(1.22, 0.82) }  // impact
  at(88, easeInOutSine)         { squash(0.96, 1.04) }
  to                            { squash(1, 1) }
}
// Height: decelerate up, accelerate down (ballistic halves)
animate(at = t, duration = 0.72, composite = Composite.ADD) {
  at(0)                                            { y(0) }
  at(17, Easing.cubicBezier(0.25f, 0.6f, 0.5f, 1f)) { y(0) }      // leaves the ground after the anticipation
  at(47, Easing.cubicBezier(0.5f, 0f, 0.75f, 0.4f)) { y(-hop) }   // apex
  at(77)                                           { y(0) }       // contact
  to                                               { y(0) }
}
// Hat: lags 70 ms, droops on the way up, flips on the way down, flops on landing
animate(at = t, delay = 0.07, duration = 0.8, ease = easeInOutSine, composite = Composite.ADD) {
  at(0)  { hatDroop(0) }
  at(30) { hatDroop(0.35) }   // tip trails the takeoff
  at(55) { hatDroop(0) }      // floats at the apex
  at(80) { hatDroop(0.45) }   // keeps falling after the head lands
  at(90) { hatDroop(0.1) }
  to     { hatDroop(0) }
}
// Eyes: look up at the apex for a happy hop, down for a sad one; blink on landing for a heavy feel.
```

For a happy hop, cut ground time (anticipation 70 ms, impact settle 200 ms) and raise the apex; for a sad hop, double
the anticipation and settle, lower the apex and add `look(0, 0.6)` (head down), following Luxo Jr. **(derived)**.

---

## 3. Emotion with minimal features

### 3.1 What carries emotion when there's almost no face

- **The eyes do most of the acting.** Animation Mentor's moving-hold advice holds for Pip: keep the eyes alive and the
  rest can be nearly still ([Animation Mentor](https://animationmentor.com/blog/why-all-animators-need-to-master-the-moving-hold)).
  Duolingo's characters idle with blinks, head nods and brow movement
  ([Duolingo blog](https://blog.duolingo.com/world-character-visemes/)).
- **The whole head is posture.** Luxo Jr. has no face: emotion comes from where the "head" looks and how the hops are
  timed ([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)).
- **Movement can define emotion more than appearance**: speed tells you whether a character is lethargic, excited,
  nervous or relaxed ([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)).
- **Brows and lids read the emotion, the mouth confirms it.** In FACS terms, sadness raises the *inner* brow, anger
  lowers and draws the brows together, and surprise raises both brows and the upper lids
  ([Ekman-based FACS tutorial, arXiv](https://arxiv.org/pdf/2307.06396); [PMC review](https://pmc.ncbi.nlm.nih.gov/articles/PMC4157835)).
  Pip currently draws brows only for ANGRY and a heavy sloping lid for SAD. Adding inner-raised brows for SAD and
  raised brows for SURPRISED would cover the three biggest gaps **(derived)**.

### 3.2 Lessons from minimal-face characters

| Character | What to take for Pip |
| --- | --- |
| Pixar's Luxo Jr. | The head is the eye: aim it. Same action, different emotion through hop speed, ground time and head height. Stage in silhouette. ([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)) |
| Kirby | Body squashes, facial spacing doesn't. Surprise changes the eye shape itself (white circles with small pupils), not just the size. ([Wikipedia](https://en.wikipedia.org/wiki/Kirby_(character)); [WiKirby](https://wikirby.com/wiki/Kirby%27s_characteristics)) |
| WALL-E's EVE | Expressive eyes on a smooth form needed careful control of the relationship between head, body and arms plus stylised eyes; for Pip, the hands and tilt carry what a body would. ([Creative Bloq](https://creativebloq.com/computer-arts/walle-year-s-model-11089200)) |
| Baymax | The team "unimated" him, cutting motion until little more than a single blink remained; minimal faces invite viewers to project feelings onto them. Restraint makes each move count. ([HuffPost / Jim Hill](https://www.huffingtonpost.com/entry/how-a-droopy-diaper-and-a_b_6175576.html); [NPR](https://www.npr.org/blogs/alltechconsidered/2014/11/07/362030044/the-challenge-of-big-hero-6-how-to-make-a-huggable-robot)) |
| Pac-Man ghosts | Expressive eyes on a blob made them cute rather than scary, and the eyes alone persist as a state (an eaten ghost's eyes travel home). Eyes can carry the character even when the body is gone. ([Game Developer, Pac-Man Dossier](https://www.gamedeveloper.com/design/the-pac-man-dossier)) |
| Clippy | Its designer said it needed only a wire, a couple of eyes and some eyebrows, which is also why it was easy to meme. Brows are the cheapest high-value feature to add. ([Vice](https://www.vice.com/en/article/microsoft-clippy-creator-interview-kevin-atteberry)) |
| Duolingo's Duo and the World Characters | Rive state machines switch pose states and separate mouth states that run together; idles include blinks, nods and brow moves. For Lily, eight head and eight body idles combine into 64+ variations so the idle never visibly loops; thinking reads as a tilt or a furrowed brow. ([Duolingo blog](https://blog.duolingo.com/world-character-visemes/); [Rive blog](https://rive.app/blog/duolingo-s-ai-powered-video-call-brings-lily-to-life)) |
| Headspace | Built from rounded shapes with simple faces; Nexus Studios has made 150+ characters and environments for the app since 2016, and breathing exercises pace an expanding and contracting circle to the narration. For Pip: a calm breath loop is a tool, not just decoration. ([Nexus Studios](https://nexusstudios.com/work/headspace-case-study/); [Blake Crosley analysis](https://blakecrosley.com/guides/design/headspace)) |
| Google's Material motion | Emphasised easing for expressive moves, decelerate on entry, accelerate on exit; M3 Expressive moves spatial motion to springs. ([Android Compose docs](https://developer.android.com/develop/ui/compose/animation/customize); token values in section 5) |
| Stardew Valley's Junimos | Players say half their charm is the constant bounce; a modder's winter variant stops the bounce and has them loaf up and nap. An idle bounce *is* personality. (Community source: [Nexus Mods](https://www.nexusmods.com/stardewvalley/mods/3996)) |
| Slimes (Dragon Quest and others) | No source found; from observation, they rely on the same squash-bounce idle plus big eyes as Junimos. |

### 3.3 Eye mechanics

**Blinks.** Bloop Animation's frame recipes, which assume film timing (24 fps), converted to 60 fps:

| Blink | Close | Hold shut | Open | Total at 60 fps | Use |
| --- | --- | --- | --- | --- | --- |
| Regular | 2 | 1 | 3 | 15 f / 250 ms | Staring, head moves, staying alive |
| Fast | 2 | 0 | 3 | 12 f / 210 ms | Anxiety, anger, something flies past |
| Long | 3 | 2 | 4 | 22 f / 375 ms | Long head moves, change of thought |
| Bat (flirt) | ¾ closed in 1 | 0 | 2 | 8 f / 125 ms | Coy, smug |

([Bloop Animation](https://www.bloopanimation.com/blinking-animation/)). Real blinks last roughly 100-400 ms and adults
blink about 10-20 times a minute ([Kwon et al. 2013, J. R. Soc. Interface](https://discovery-pp.ucl.ac.uk/1396599/1/J._R._Soc._Interface-2013-Kwon-.pdf);
[PubMed 2008](https://pubmed.ncbi.nlm.nih.gov/18565090/)). For a mascot, every 2-6 s with jitter feels alive
without nervous energy **(derived)**.

Our current `blinkAt` helper in `ScenesMovement.kt` closes and opens symmetrically over 200 ms with linear easing.
Prefer this asymmetric version **(derived from Bloop's close:open ratios)**:

```kotlin
// Regular blink, 230 ms: snap shut, brief hold, softer open. fill = NONE so it leaves no trace.
animate(at = t, duration = 0.23, ease = Easing.linear, fill = Fill.NONE) {
  at(0,  Easing.easeIn)  { blink(0) }
  at(30, Easing.linear)  { blink(1) }   // ~70 ms to close
  at(43, Easing.easeOut) { blink(1) }   // ~30 ms shut
  to                     { blink(0) }   // ~130 ms to open
}
// Double blink (surprise, disbelief, "wait, what?"): two of these 60-80 ms apart, second slightly shorter.
```

**Blinks mark thought.** Blinks come with changes of thought, a realisation, or a shift of emotion, and fast head
turns almost always trigger one ([Bloop Animation](https://www.bloopanimation.com/blinking-animation/);
[Animation Mentor](https://www.animationmentor.com/blog/tutorial-animate-blinks-eye-movement/)). Place a blink:
- at the moment Pip "gets it", just before the new expression;
- in the middle or near the end of a big head turn ([Animation Mentor](https://www.animationmentor.com/blog/tutorial-animate-blinks-eye-movement/));
- on the frame where you swap `mood` or `mouth`, so the discrete pop happens behind closed lids **(derived)**.

**Eye darts.** A dart is about 3 film frames (start, one transition, end), so 100-130 ms at 60 fps; left-right darts
read as listening, up-down as sizing someone up or fear, and a left-right-then-up triangle as scanning a new space
([Bloop Animation](https://www.bloopanimation.com/blinking-animation/)). Use `Easing.easeOut` or a steps(2) ease for
a crisp saccade, never a slow ease-in-out.

**Look before you move.** The eyes usually lead the head in an action
([Lasseter 1987](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)), and animators sequence eyes,
then head, then body ([Animation Mentor](https://www.animationmentor.com/blog/tutorial-animate-blinks-eye-movement/)).
For Pip: `look` reaches its new target 80-170 ms before `rotation` or `x` starts, and the hands follow the head by
another 2-4 f.

### 3.4 Emotion table

Pip's eyes are drawn by `mood`: HAPPY draws closed upward arcs (so `eyesOpen` and `look` have no effect), SURPRISED
draws eyes 1.3× bigger, SAD adds a sloping heavy lid, ANGRY adds slanted brows. Tilt values are `rotation` degrees
on the feet pivot (sign depends on which way he faces). All timings **(derived)** from the sources above unless linked.

| Emotion | Eyes / lids | Brows (`mood`) | Mouth | Head tilt / posture | Squash | Timing | Hat |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Happy | HAPPY arcs; blush 0.3-0.6 | HAPPY | SMILE | Up, tilt 4-8° side to side | Bouncy: (0.9, 1.1) ↔ (1.1, 0.9) | Fast, light: 120-250 ms moves, short ground time | Lively sway ±0.3, lags 60 ms |
| Sad | `eyesOpen` 0.7, `look(±0.3, 0.6)` down | SAD (heavy lid) | FROWN or NONE | Head down: `y +10`, tilt 6-10° | Slumped (1.06, 0.94), held | Slow: 600-1000 ms moves, long holds, long blinks | `hatDroop` 0.5-0.8, barely sways |
| Disappointed | `eyesOpen` 0.6, look down then away | SAD or NEUTRAL | FLAT | Sigh: rise 2 px then sink 8 px | Inhale (0.97, 1.03), exhale (1.08, 0.93) | Beat (400 ms hold), slow sink 700 ms | Droops after the sink, 120 ms late |
| Angry | `eyesOpen` 0.75, locked on target, no darts | ANGRY brows | FLAT or SHOUT | Lean in 6-10° toward target | Compressed (1.1, 0.9) with 2-3 px tremble | Fast, hard stops; short anticipation | Stiff; small fast flicks ±0.15 |
| Surprised | SURPRISED (1.3×), `eyesOpen` 1, double blink after | SURPRISED | O | Pops up `y -12`, tilt back | Anticipation (1.1, 0.9) → stretch (0.85, 1.18) | 80 ms snap up, 300-500 ms hold, 250 ms settle | Springs up, wobbles (ζ 0.25) |
| Smug | `eyesOpen` 0.55 (half-lidded), look to the side | HAPPY or NEUTRAL | SMILE | Tilt back 6°, chin up | Slight stretch (0.97, 1.03) | Slow and deliberate, 500-800 ms; one slow blink | Cocked: `hatSway` +0.2 held |
| Sneaky | `eyesOpen` 0.55, quick darts left-right | NEUTRAL (or ANGRY for scheming) | NONE or FLAT | Low: `y +15`, lean forward 5° | Crouched (1.08, 0.93) | `steps()` tiptoe moves, pauses between, fast darts | Tucked: `hatDroop` 0.3, no sway |
| Scared | SURPRISED, `eyesOpen` 1, fast darts up-down | SURPRISED | O or FLAT | Recoil away from threat 8-12°, shrink `size` 0.95 | Shrunk (0.95, 1.03) + 30-40 ms tremble | Instant recoil (≤100 ms), fast blinks | Flattens: `hatDroop` 0.6, quivers ±0.08 |

### 3.5 Beat-by-beat examples

**Happy (receives good news), ~1.2 s**
1. 0 ms: eyes dart to the source (`look(0.6, 0)`, 100 ms, easeOut).
2. 150 ms: blink (thought change); swap to HAPPY + SMILE on the closed frame.
3. 300 ms: anticipation squash (1.12, 0.88) for 100 ms.
4. 400 ms: hop (section 2) at 0.75 scale, apex `y -24`, with `blush` rising 0 → 0.5 over 300 ms.
5. 900 ms: land with the ζ 0.25 wobble; hat lags and flips; settle into a moving hold with a 2 Hz tiny tilt (±3°) for joy.

**Sad (bad news), ~2 s**
1. 0 ms: hold neutral for a beat (300 ms); the news has to land.
2. 300 ms: long blink (375 ms); swap to SAD on the closed frame.
3. 500 ms: `look` slides down to (0.2, 0.6) over 400 ms, easeInOut.
4. 600 ms: head sinks `y +10`, tilts 8°, squash to (1.06, 0.94) over 800 ms with an emphasised-decelerate ease.
5. 800 ms: `hatDroop` 0 → 0.7 starting 150 ms after the head, slower than the head.
6. Hold: moving hold with a slow breath, no darts.

**Disappointed (expected a reward, got nothing), ~1.8 s**
1. Look at the thing, eyes bright (NEUTRAL, `eyesOpen` 1).
2. Beat: 400 ms of nothing.
3. Inhale: rise 2 px and stretch (0.97, 1.03), 250 ms.
4. Exhale: sink 8 px and squash (1.08, 0.93), 700 ms easeOut; swap to SAD + FLAT mid-exhale behind a long blink.
5. Look away to the side, slowly (500 ms); `hatDroop` follows 120 ms late.

**Angry (someone pokes him), ~1 s**
1. Poke: 1-frame hit, recoil 170 ms (the "brick" timing).
2. 200 ms: fast blink; swap to ANGRY + FLAT.
3. 250 ms: eyes lock on the poker; head turns after them, 250 ms, no overshoot.
4. 500 ms: lean in 8° with compressed squash (1.1, 0.9); 3 px tremble additive loop (40 ms period, 6 cycles).
5. Optional: SHOUT on a quick stretch (0.9, 1.12), 120 ms, then snap back.

**Surprised (a "take"), ~1.1 s**
1. 0 ms: sees it; eyes squeeze (`eyesOpen` 0.7) and squash (1.1, 0.9), 100 ms. This is the anticipation.
2. 100 ms: snap up `y -12`, stretch (0.85, 1.18), SURPRISED + O, 80 ms easeOut.
3. 180 ms: hold the take 300-500 ms with a 1-2 px moving hold.
4. Double blink at the start of the hold.
5. Settle 250 ms with the ζ 0.25 squash wobble; hat springs and wobbles 80 ms behind.

**Smug (he was right), ~1.5 s**
1. Slow half-lid: `eyesOpen` 1 → 0.55 over 300 ms.
2. Look to the side at the audience (500 ms friendly-glance timing).
3. Tilt back 6° and stretch slightly over 600 ms, emphasised decelerate.
4. One slow blink (long blink at 1.3× length).
5. Optional hand: PALM on the hat brim, or POINT at himself, arriving 3 f after the tilt.

**Sneaky (tiptoeing past), loop ~1.6 s per step**
1. Crouch: `y +15`, squash (1.08, 0.93), lean 5° forward, `eyesOpen` 0.55.
2. Step: `x +30` with `Easing.steps(3)` over 300 ms (stealthy, stop-start), tiny lift `y -4` on each step.
3. Pause 400 ms; dart left-right (100 ms each), then back to the path.
4. Hat tucked (`hatDroop` 0.3), no sway; hands as PALM held in front, staggered 2 f.

**Scared (a loud noise), ~1 s**
1. Instant recoil ≤100 ms: rotate away 10°, shrink `size` to 0.95, SURPRISED + O. No anticipation: fear has none.
2. Fast blink; eyes dart up-down twice (100 ms each).
3. Tremble: additive `x ±2` and `hatSway ±0.08` loop at 35-40 ms period for 500 ms.
4. Slowly peek back toward the noise: eyes first, head 150 ms later, 670 ms "better look" timing.

---

## 4. Timing reference

At 60 fps, 1 f = 16.7 ms. Film sources counted in 24 fps frames are converted at 2.5 f per film frame.

| Event | Frames @60 | ms | Source / note |
| --- | --- | --- | --- |
| Regular blink (close-hold-open) | 15 (5/2/8) | 250 | [Bloop](https://www.bloopanimation.com/blinking-animation/) (2/1/3 film frames) |
| Fast blink | 12 (5/0/7) | 210 | [Bloop](https://www.bloopanimation.com/blinking-animation/) |
| Long "thought change" blink | 22 (7/5/10) | 375 | [Bloop](https://www.bloopanimation.com/blinking-animation/) |
| Double blink | 2 × 12 with 4-5 f gap | 450-500 | (derived) |
| Idle blink interval | 120-360 | 2-6 s, jittered | (derived from 10-20 blinks/min, [PubMed](https://pubmed.ncbi.nlm.nih.gov/18565090/)) |
| Eye dart | 6-8 | 100-130 | [Bloop](https://www.bloopanimation.com/blinking-animation/) (3 film frames) |
| Eyes lead head by | 5-10 | 80-170 | (derived from [Lasseter](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)) |
| Hat lag behind head | 4-6 | 60-100 | (derived) |
| Hands lag / stagger | 1-4 | 17-70 | Lasseter's Wally B. feet leave 1-2 film frames apart |
| Button-press "contact" | 2 | 34 | [Comeau, 3D button](https://www.joshwcomeau.com/animation/3d-button/) |
| Small anticipation | 6-9 | 100-150 | (derived) |
| Big anticipation | 12-18 | 200-300 | (derived) |
| Fast action after clear anticipation | 7-10 | 125-170 | [Lasseter](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf) (3-4 film frames) |
| Squash on impact (held on contact) | 3-5 | 50-80 | Comeau holds the ball on the ground 5% of the bounce ([squash](https://www.joshwcomeau.com/animation/squash-and-stretch/)) |
| Reaction delay (noticing) | 9-18 | 150-300 | (derived) |
| Comedic beat before the reaction | 15-30 | 250-500 | Hold the realisation; tune by eye ([School of Motion](https://www.schoolofmotion.com/blog/how-to-animate-character-takes); [Fiveable](https://fiveable.me/2d-animation/unit-10/manipulating-timing-spacing-comedic-dramatic-effect/study-guide/VThqosnSzDJFFmO6)) |
| Take hold (surprise) | 18-30 | 300-500 | (derived) |
| Moving hold | 60-180 | 1-3 s | [Animation Mentor](https://animationmentor.com/blog/why-all-animators-need-to-master-the-moving-hold) |
| Boop (brief reaction then auto-return) | 9 | 150 | [Comeau, boop](https://www.joshwcomeau.com/react/boop/) |
| Idle bob period | 120-180 | 2-3 s | (derived; slower reads calmer) |

Frame-count rules for takes and beats are starting points; animation teachers agree there is no universal number, so
tune by playback against the surrounding motion ([School of Motion](https://www.schoolofmotion.com/blog/how-to-animate-character-takes)).

---

## 5. Spring and easing cookbook

### 5.1 Cubic-bezier library (use with `Easing.cubicBezier(x1, y1, x2, y2)`)

| Feel | Bezier | Where it comes from / use |
| --- | --- | --- |
| Snappy overshoot ("boing") | (0.3, 0.7, 0.4, 1.5) | Comeau's 3D button hover/release, 250 ms ([3D button](https://www.joshwcomeau.com/animation/3d-button/)) |
| Return to rest, no overshoot | (0.3, 0.7, 0.4, 1.0) | Same article, 600 ms "aggressive ease-out" |
| Classic back overshoot | (0.34, 1.56, 0.64, 1) | Our `Easing.outBack`; Penner's easeOutBack |
| Snappy, no overshoot | (0.2, 0.9, 0.3, 1) | Our `snappy` in `ScenesMovement.kt`; fast settle into a pose |
| Material emphasised | (0.2, 0, 0, 1) | M3 emphasised/standard; general-purpose expressive move |
| Material emphasised decelerate (enter, arrive) | (0.05, 0.7, 0.1, 1) | M3 token; Pip arriving on screen or settling into sadness |
| Material emphasised accelerate (exit, leave) | (0.3, 0, 0.8, 0.15) | M3 token; Pip leaving the screen |
| Floaty / breathing | (0.37, 0, 0.63, 1) | easeInOutSine; idles, bob, hat drift, between spring extremes |
| Floaty start from motion | (0.61, 1, 0.88, 1) | easeOutSine; first segment of an impact wobble |
| Rise (gravity, going up) | (0.25, 0.6, 0.5, 1) | Our `rise`; decelerating ballistic half |
| Fall (gravity, coming down) | (0.5, 0, 0.75, 0.4) | Our `fall`; accelerating ballistic half |
| Heavy start | (0.6, 0, 0.9, 0.4) | Slow to get going (heavy prop, sad sink) (derived) |
| Heavy stop | (0.1, 0.6, 0.3, 1) | Large mass arriving with a thud, pair with squash (derived) |
| Bounce-up (Comeau ball) | (0.123, 0.5, 0.5, 1) | Comeau's bouncing ball, 400 ms alternate ([squash](https://www.joshwcomeau.com/animation/squash-and-stretch/)) |

M3 token values are from third-party token tables, since Google's spec page didn't render for fetching
([EPA-WG token table](https://unpkg.com/@epa-wg/cem-theme@0.0.14/dist/lib/tokens/cem-timing.xhtml);
[bevy_material_ui](https://docs.rs/crate/bevy_material_ui/latest/source/src/tokens.rs)). Check them against
[m3.material.io](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs) before treating them as canon.

Which to pick, per Comeau: ease-out for things entering, ease-in only for things leaving or vanishing, ease-in-out for
loops, and `ease` for everything else; give enter and exit different speeds (fast in, relaxed out)
([CSS transitions](https://www.joshwcomeau.com/animation/css-transitions/)).

### 5.2 Spring parameters and what they mean

A spring is described by mass, stiffness (tension) and damping (friction): higher stiffness is snappier, more damping
is less bouncy, and most useful springs are not very bouncy at all
([Comeau, spring physics](https://www.joshwcomeau.com/animation/a-friendly-introduction-to-spring-physics/)). Springs
also handle interruption better than duration-based tweens because they keep velocity continuous
([Android Compose](https://developer.android.com/develop/ui/compose/animation/customize)).

Parameters seen in the wild:

| Source | Settings | Damping ratio ζ (derived) | First peak / settle (derived) |
| --- | --- | --- | --- |
| Comeau boop default (react-spring) | tension 300, friction 10, mass 1 | 0.29 | ~190 ms / ~800 ms |
| Comeau boop "snappier" | tension 300, friction 6 | 0.17 | ~180 ms / >1 s |
| Comeau squash micro-interaction (Motion) | stiffness 300, damping 12 | 0.35 | ~195 ms / ~660 ms |
| Comeau spring sandbox | mass 1.75, tension 200, friction 12 | 0.32 | ~310 ms / ~1.2 s |
| Compose `Spring.StiffnessMedium` + `DampingRatioMediumBouncy` | 1500, 0.5 | 0.5 | ~94 ms / ~210 ms |
| Compose `StiffnessLow` + `DampingRatioLowBouncy` | 200, 0.75 | 0.75 | ~340 ms / ~380 ms |

Sources: [boop](https://www.joshwcomeau.com/react/boop/), [squash](https://www.joshwcomeau.com/animation/squash-and-stretch/),
[spring intro](https://www.joshwcomeau.com/animation/a-friendly-introduction-to-spring-physics/),
[Compose Spring constants](https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Spring).
Compose names its presets StiffnessHigh 10000, Medium 1500, MediumLow 400, Low 200, VeryLow 50 and damping ratios
HighBouncy 0.2, MediumBouncy 0.5, LowBouncy 0.75, NoBouncy 1.

Conversions **(derived)**, for mass m, stiffness k, damping c:
- natural frequency ωn = √(k/m); damping ratio ζ = c / (2√(k·m));
- overshoot of a step = e^(−πζ/√(1−ζ²)): ζ 0.2 → 53%, 0.3 → 37%, 0.5 → 16%, 0.75 → 3%;
- first peak at π / (ωn√(1−ζ²)); settles within 2% after about 4 / (ζ·ωn).

For Pip **(derived)**: ζ ≈ 0.3 for playful arrivals, ζ ≈ 0.5 for normal UI-like moves, ζ ≈ 0.7-1 for calm or sad.

### 5.3 Faking springs with keyframes

CSS can now approximate springs with the `linear()` timing function, which draws straight lines between many points;
Comeau found about 11 hand-placed points looked robotic and ~50 computed points (or ~25 with explicit positions) looked
convincing ([Comeau, springs in native CSS](https://www.joshwcomeau.com/animation/linear-timing-function/)). Our
engine has a better tool: per-stop eases. Put a stop at each **extreme** of the spring (where velocity is zero) and
connect them with easeInOutSine. A sine segment between extremes is very close to the true damped cosine, so 4-6 stops
do what 50 linear points do **(derived)**.

**A. Step response (move from A to B and settle).** Value is a fraction of the distance from A (0) to B (1); duration
is the 2% settle time. Segment ease: `Easing.ease` for the first segment (starts at rest), easeInOutSine after.

| ζ | Stops (percent : value) |
| --- | --- |
| 0.3 (bouncy) | 0 : 0 → 25 : 1.37 → 49 : 0.86 → 74 : 1.05 → 100 : 1.0 |
| 0.5 (lively) | 0 : 0 → 45 : 1.16 → 91 : 0.97 → 100 : 1.0 |
| 0.7 (gentle) | 0 : 0 → 77 : 1.05 → 100 : 1.0 |

```kotlin
// Pip slides to a new spot and settles with a ζ 0.3 spring; d = distance, 650 ms.
animate(at = t, duration = 0.65, composite = Composite.ADD) {
  at(0,  Easing.ease)   { x(0) }
  at(25, easeInOutSine) { x(d * 1.37f) }
  at(49, easeInOutSine) { x(d * 0.86f) }
  at(74, easeInOutSine) { x(d * 1.05f) }
  to                    { x(d) }
}
```

**B. Impulse response (a kick that wobbles back to rest).** For impacts, pokes and hat flops. Values are multiples of
the first peak amplitude A. First segment ease: easeOutSine (starts at full speed); then easeInOutSine.

| ζ | Stops (percent : multiple of A) |
| --- | --- |
| 0.15 (jelly) | 0 : 0 → 6 : 1 → 18 : −0.62 → 31 : 0.39 → 43 : −0.24 → 56 : 0.15 → 69 : −0.09 → 81 : 0.06 → 100 : 0 |
| 0.25 (springy) | 0 : 0 → 9 : 1 → 29 : −0.44 → 49 : 0.20 → 70 : −0.09 → 90 : 0.04 → 100 : 0 |
| 0.4 (firm) | 0 : 0 → 10 : 1 → 35 : −0.25 → 61 : 0.06 → 100 : 0 |

To apply to squash, set `sy = 1 − A·w` and `sx = 1 / sy`; the landing recipe in 2.1 is the ζ 0.25 row with A = 0.2.
To apply to the hat, put the row straight into `hatSway` (A = 0.3-0.6) with a 60-100 ms delay.

`Easing.outElastic` in our engine is a fixed impulse-like curve with roughly ζ 0.1; it reads as cartoon jelly and is
too much for most of Pip's moves **(derived)**. `outBounce` is a hard-surface ball bounce; use it for things falling
onto a floor (a prop landing), not for Pip, who is soft.

### 5.4 Stagger and waves

Comeau's animated pride flag runs the same oscillation on every column, delays each by `index × step` (50-100 ms), and
uses negative delays so the wave is already in motion on the first frame; the amplitude also grows with the index to
make the flag billow ([Comeau, pride flags](https://www.joshwcomeau.com/animation/pride-flags/)). Our `Timing.delay` can
be negative (the animation simply began earlier), so the same trick works. Uses for Pip **(derived)**: hat sway as a
multi-segment wave, staggered hands (2-4 f), particles and confetti, and a row of icons reacting to Pip in sequence.

Partial keyframes, where you omit the 0% stop so the animation starts from the current value, let a reaction start
from wherever Pip already is ([Comeau, partial keyframes](https://www.joshwcomeau.com/animation/partial-keyframes/)).
Our engine does the same (a missing 0% uses the underlying value), so leave out `from {}` for reactions that can
interrupt anything.

### 5.5 Randomness and the "boop"

Comeau adds randomness to spawn timing, sizes and even sound samples so repeated effects never feel robotic, and he
treats novelty as essential to whimsy ([Whimsical animations](https://www.joshwcomeau.com/blog/whimsical-animations/);
[sparkles](https://www.joshwcomeau.com/react/animated-sparkles-in-react/)). His "boop" plays a short reaction on
hover that springs back after about 150 ms even if the trigger persists
([boop](https://www.joshwcomeau.com/react/boop/)). Translate to Pip as a poke reaction: 150 ms squash and tilt, then
spring home with the ζ 0.3 table.

For idles, run several loops with periods that don't share factors (for example bob 2.4 s, hat 3.1 s, breathing 4.3 s)
and schedule blinks and darts from a seeded random so the show stays deterministic but never visibly repeats
**(derived)**. Duolingo solved the same problem for Lily by combining 8 head × 8 body idles into 64+ variations
([Rive blog](https://rive.app/blog/duolingo-s-ai-powered-video-call-brings-lily-to-life)).

### 5.6 Reduced motion

The platform won't turn off animation for you; you have to opt in to the preference
([Comeau, prefers-reduced-motion](https://joshwcomeau.com/react/prefers-reduced-motion/)). Comeau's sparkles fall back
to a few static sparkles instead of none ([sparkles](https://www.joshwcomeau.com/react/animated-sparkles-in-react/)).
On Android, the equivalent is the "Remove animations" setting, which sets the animator duration scale to 0; check
`ValueAnimator.areAnimatorsEnabled()` (API 26+) ([Android reference](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())).
When it's off **(derived)**: jump straight to key poses (expressions, `mood`, `mouth`, props), keep at most short fades,
drop idle bob, squash, hat wobble and trembles, and keep speech bubbles so meaning survives.

---

## 6. Common mistakes that make motion feel cheap

| Mistake | Why it looks cheap | Fix |
| --- | --- | --- |
| Linear easing on body moves | Robotic start and stop | Pick an ease per segment (section 5.1); linear only for hold placeholders and `steps` |
| Ease-in-out on everything | Floaty, weightless "CG" feel | Gravity: ease-out up, ease-in down. Arrivals: decelerate. Contrast fast and slow |
| Everything moves on the same frame | Stiff, puppet-like | Overlap: eyes lead, head, hat 60-100 ms later, hands 2-4 f offset ([Lasseter](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)) |
| Hat moves in sync with the head | Hat looks glued on | Delay the hat track and let it overshoot when the head stops |
| No anticipation | Actions arrive out of nowhere | 100-300 ms squash, lean or eye squeeze in the opposite direction first |
| Anticipation that's too long | Audience gets ahead of you and loses interest | Spend enough time to set it up but no more ([Lasseter](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf)) |
| Squash that changes volume | Pip inflates and deflates | Keep `sx · sy ≈ 1` |
| Squash too strong | Eyes distort, Pip stops looking like Pip | Cap at about ±20%; counter-scale eyes if needed (Kirby rule) |
| Squash at the apex | Looks rubbery for no reason | Round at the apex, stretch only when moving fast, squash on contact |
| Dead holds | Character looks like a still image | Moving holds: 1-3 px drift, breath, blink ([Animation Mentor](https://animationmentor.com/blog/why-all-animators-need-to-master-the-moving-hold)) |
| Over-busy idles | Nervous, distracting, and fights with UI | Keep idle amplitude tiny; Baymax shows how restraint gives each move weight |
| Perfectly periodic idle | Brain spots the loop in seconds | Coprime loop periods, randomised blinks and darts |
| Symmetric linear blink | Mechanical | Fast close, short hold, slower open; blink on thought changes |
| Blinking on a fixed timer only | Eyes look like a screensaver | Tie most blinks to beats: realisations, turns, mood swaps |
| Discrete mood/mouth pops | A visible "snap" mid-pose | Swap on the shut frame of a blink, or mid fast move |
| Overshoot on every move | `outBack` fatigue; nothing lands | Reserve overshoot for arrivals and playful beats; calm moves get ζ ≥ 0.7 |
| Twinning (mirrored hands, level head) | Stiff and posed | Offset timing and angles; add a few degrees of tilt to held poses |
| Straight-line travel | Mechanical | Different eases on `x` and `y` so the path arcs |
| Reaction with no beat | Joke or emotion doesn't land | Hold the realisation 250-500 ms before the reaction |
| Too many ideas at once | Nothing reads | Stage one idea at a time; freeze everything else into a moving hold |
| Ignoring reduced-motion | Hurts motion-sensitive users | Section 5.6 |

---

## 7. Inspiration list

**Foundations**

| Who | Link | What to study |
| --- | --- | --- |
| Frank Thomas and Ollie Johnston | [12 principles summary](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation) | *The Illusion of Life*: the source of the principles, twins, appeal, and the head-turn timing table. |
| Richard Williams | [The Animator's Survival Kit](https://en.wikipedia.org/wiki/The_Animator%27s_Survival_Kit) | Timing and spacing charts, takes, accents, and overlapping action; the best desk reference for frame counts. |
| John Lasseter / Pixar | [Principles applied to 3D (PDF)](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf) | Luxo Jr.: emotion with no face, silhouette staging, overlap, mood through hop timing. Closest precedent for Pip. |
| Glen Keane | [Glen Keane Productions: Dear Basketball](https://glenkeaneproductions.com/dear-basketball) | Line of action and expressive arcs; how much feeling a single strong pose carries. |
| Aaron Blaise | [Creature Art Teacher](https://creatureartteacher.com/) | Courses such as *10 Essential Actions for Animators*: weight, anticipation and mechanics before performance. |
| Alan Becker | [12 Principles series (via BlenderNation)](https://www.blendernation.com/2017/09/12/non-blender-12-principles-animation/) | Each principle demonstrated in isolation on simple shapes; good to show the team. |
| Bloop Animation | [Blinks and eye movement](https://www.bloopanimation.com/blinking-animation/) | Blink and dart frame recipes. |
| Animation Mentor | [Moving holds](https://animationmentor.com/blog/why-all-animators-need-to-master-the-moving-hold) | Keeping holds alive, eye-led acting. |
| Josh W. Comeau | [Animation articles](https://www.joshwcomeau.com/animation/), [Whimsical Animations course](https://whimsy.joshwcomeau.com/) | Springs, squash, partial keyframes, linear() springs, staggered waves, restraint and novelty. |

**Independent animators**

| Who | Link | What to study |
| --- | --- | --- |
| Toniko Pantoja | [Cartoon Brew: Artist of the Day](https://www.cartoonbrew.com/artist-of-the-day/toniko-pantoja-141343.html) | Hand-drawn shorts (Tiny Nomad, Crayon Dragon): appeal and clear, readable acting on simple designs. |
| Felix Colgrave | [Wikipedia](https://en.wikipedia.org/wiki/Felix_Colgrave) (YouTube: FelixColgrave) | Double King: bouncy, rubbery After Effects motion and comic timing on odd, simple creatures. Note: felixcolgrave.com currently serves unrelated gambling content; don't link it. |
| Ian Worthington (Worthikids) | [Animation Obsessive interview](https://animationobsessive.substack.com/p/how-worthikids-makes-hit-animation) | Bigtop Burger: deadpan comedic timing, holds and beats; minimal motion that still lands jokes. |
| Allen Laseter | [It's Nice That profile](https://www.itsnicethat.com/articles/allen-laseter-animation-030817), [Dribbble](https://dribbble.com/AllenLaseter/about) | After Effects character loops with playful squash and snappy timing (TED-Ed, Netflix spots). |
| Jorge R. Canedo Estrada | [Behance](https://www.behance.net/Jrcanest), [Motion Array interview](https://motionarray.com/blog/interview-with-jorge-canedo-estrada) | Ex-Buck and Giant Ant: fluid frame-by-frame-feeling motion, smears and overlap. |

**Studios and teams**

| Who | Link | What to study |
| --- | --- | --- |
| Giant Ant | [giantant.ca](https://giantant.ca/) | Duolingo *Misunderstood* spots featuring the owl: a mascot acting in short, punchy beats. |
| Buck | [buck.co](https://buck.co/), [Headspace work](https://buck.co/work/headspace) | Brand characters (Dunkin' Munchkins), and calm, rounded character motion for Headspace. |
| Oddfellows | [oddfellows.tv](https://oddfellows.tv/) | Google's Weather Froggy (listed on their homepage): a mascot that lives in idle states and weather vignettes. |
| ManvsMachine | [mvsm.com](https://mvsm.com/), [EyeBuyGuy campaign](https://www.itsnicethat.com/news/manvsmachine-eye-buy-direct-advertising-animation-011118) | Polished 3D character spots; how a simple mascot carries a campaign. |
| Nexus Studios (Headspace) | [Headspace case study](https://nexusstudios.com/work/headspace-case-study/) | 150+ characters from simple shapes; calm timing and breathing as a design element. |
| Duolingo animation team | [World Characters visemes](https://blog.duolingo.com/world-character-visemes/), [Lily case study](https://rive.app/blog/duolingo-s-ai-powered-video-call-brings-lily-to-life) | Modular actor, state machines, idle variety, thinking poses, restraint. |
| Rive community | [Best community designs 2023](https://rive.app/blog/12-best-rive-community-designs-of-2023), [Character rig with blink layer](https://rive.app/marketplace/28370-53642-interactive-character-rig-bones-joysticks-and-data-binding/), [Floating idle](https://rive.app/marketplace/23082-43170-ttgdenn-floating-idle-animation-2025) | Idle ↔ look state machines with an independent blink loop: the exact layering Pip needs. |

**Dribbble and Behance mascot loops**

| Who | Link | What to study |
| --- | --- | --- |
| Hannu Koho for Kallan & Co | [Character animation with Lottie](https://dribbble.com/shots/3394726-Character-animation-with-Lottie) | App mascot loops built for runtime playback. |
| Alexis Doreau / Andre Navarre for Bankin' | [Character animation for mobile app](https://dribbble.com/shots/5910469-Character-animation-for-mobile-app-Lottie-Part-1) | A set of looping in-app character states. |
| Dribbble tag | [Mascot animation](https://dribbble.com/tags/mascot-animation) | Sort by Popular; study idle loops, success and error reactions. |

---

## 8. Sources

Animation principles and acting
- Wikipedia, [Twelve basic principles of animation](https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation)
- J. Lasseter, [Principles of Traditional Animation Applied to 3D Computer Animation](https://www.ics.uci.edu/~fowlkes/class/ics162/exercises/lasseter.pdf), SIGGRAPH 1987
- Animation Mentor, [Why all animators need to master the moving hold](https://animationmentor.com/blog/why-all-animators-need-to-master-the-moving-hold)
- Animation Mentor, [Tutorial: animate blinks and eye movement](https://www.animationmentor.com/blog/tutorial-animate-blinks-eye-movement/)
- Bloop Animation, [How to animate blinks and eye movement](https://www.bloopanimation.com/blinking-animation/)
- School of Motion, [How to animate character takes](https://www.schoolofmotion.com/blog/how-to-animate-character-takes)
- Fiveable, [Timing and spacing for comedic and dramatic effect](https://fiveable.me/2d-animation/unit-10/manipulating-timing-spacing-comedic-dramatic-effect/study-guide/VThqosnSzDJFFmO6)
- Wikipedia, [The Animator's Survival Kit](https://en.wikipedia.org/wiki/The_Animator%27s_Survival_Kit)

Faces and emotion
- [Ekman-based FACS tutorial (arXiv 2307.06396)](https://arxiv.org/pdf/2307.06396)
- [Facial action unit review (PMC4157835)](https://pmc.ncbi.nlm.nih.gov/articles/PMC4157835)
- Kwon et al., [J. R. Soc. Interface 2013 (blink timing)](https://discovery-pp.ucl.ac.uk/1396599/1/J._R._Soc._Interface-2013-Kwon-.pdf)
- [Spontaneous blinking in healthy persons (PubMed 18565090)](https://pubmed.ncbi.nlm.nih.gov/18565090/)

Minimal-face characters
- Wikipedia, [Kirby (character)](https://en.wikipedia.org/wiki/Kirby_(character)); WiKirby, [Kirby's characteristics](https://wikirby.com/wiki/Kirby%27s_characteristics)
- Creative Bloq, [WALL-E: this year's model](https://creativebloq.com/computer-arts/walle-year-s-model-11089200)
- HuffPost / Jim Hill, [How a droopy diaper and a cute rice cooker inspired Baymax](https://www.huffingtonpost.com/entry/how-a-droopy-diaper-and-a_b_6175576.html)
- NPR, [The challenge of Big Hero 6: how to make a huggable robot](https://www.npr.org/blogs/alltechconsidered/2014/11/07/362030044/the-challenge-of-big-hero-6-how-to-make-a-huggable-robot)
- Game Developer, [The Pac-Man Dossier](https://www.gamedeveloper.com/design/the-pac-man-dossier)
- Vice, [Clippy creator interview](https://www.vice.com/en/article/microsoft-clippy-creator-interview-kevin-atteberry)
- Duolingo, [Art meets technology (visemes)](https://blog.duolingo.com/world-character-visemes/)
- Rive, [Duolingo's AI video call brings Lily to life](https://rive.app/blog/duolingo-s-ai-powered-video-call-brings-lily-to-life)
- Nexus Studios, [Headspace case study](https://nexusstudios.com/work/headspace-case-study/); Blake Crosley, [Headspace design analysis](https://blakecrosley.com/guides/design/headspace)
- Nexus Mods, [Junimo scarecrows](https://www.nexusmods.com/stardewvalley/mods/3996)

Josh W. Comeau
- [A friendly introduction to spring physics](https://www.joshwcomeau.com/animation/a-friendly-introduction-to-spring-physics/)
- [Squash and stretch](https://www.joshwcomeau.com/animation/squash-and-stretch/)
- [Springs and bounces in native CSS](https://www.joshwcomeau.com/animation/linear-timing-function/)
- [An interactive guide to keyframe animations](https://www.joshwcomeau.com/animation/keyframe-animations/)
- [An interactive guide to CSS transitions](https://www.joshwcomeau.com/animation/css-transitions/)
- [Partial keyframes](https://www.joshwcomeau.com/animation/partial-keyframes/)
- [Animated pride flags](https://www.joshwcomeau.com/animation/pride-flags/)
- [Building a magical 3D button](https://www.joshwcomeau.com/animation/3d-button/)
- [Boop](https://www.joshwcomeau.com/react/boop/)
- [Animated sparkles in React](https://www.joshwcomeau.com/react/animated-sparkles-in-react/)
- [Whimsical animations](https://www.joshwcomeau.com/blog/whimsical-animations/)
- [Accessible animations: prefers-reduced-motion](https://joshwcomeau.com/react/prefers-reduced-motion/)

Platform motion
- Android, [Customize animations (Compose)](https://developer.android.com/develop/ui/compose/animation/customize); [Spring constants](https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Spring); [ValueAnimator.areAnimatorsEnabled](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())
- Material 3 [easing and duration tokens](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs) (values cross-checked from the [EPA-WG token table](https://unpkg.com/@epa-wg/cem-theme@0.0.14/dist/lib/tokens/cem-timing.xhtml) and [bevy_material_ui](https://docs.rs/crate/bevy_material_ui/latest/source/src/tokens.rs))

Inspiration sources are linked in section 7.

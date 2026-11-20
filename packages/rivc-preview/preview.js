// Pip previewer: plays a compiled .riv with the pinned @rive-app/canvas runtime and builds
// controls from its view model (or legacy state machine inputs).
//
// Time is virtual. This script replaces requestAnimationFrame before rive.js loads, so the
// runtime only sees timestamps we hand it. That gives pause, speed and frame stepping without
// touching the runtime's own play/pause (which resets its clock), and lets golden.mjs advance
// to an exact time in fixed 1/60 s steps.
//
// Query params: ?riv=<url> file to load (default pip.riv, falling back to the spike fixture)
//               ?golden=1  manual clock, no live reload; driven by golden.mjs via window.pipPreview
(() => {
  "use strict";

  const params = new URLSearchParams(location.search);
  const GOLDEN = params.has("golden");
  const FRAME_MS = 1000 / 60;
  const RUNTIME = "vendor/rive-canvas-2.52.0";
  const FALLBACK_RIV = "fixtures/spike-pip.riv";

  // ---- virtual clock -------------------------------------------------------------------------
  const realRaf = window.requestAnimationFrame.bind(window);
  // In golden mode virtual time starts far ahead of performance.now(), so the runtime's
  // first-frame seeding (max(timestamp, performance.now())) always picks our timestamp.
  const clock = { now: GOLDEN ? 1e9 : 0, origin: 0, playing: !GOLDEN, speed: 1, manual: GOLDEN, queue: new Map(), nextId: 1 };
  window.requestAnimationFrame = (cb) => {
    const id = clock.nextId++;
    clock.queue.set(id, cb);
    return id;
  };
  window.cancelAnimationFrame = (id) => clock.queue.delete(id);
  const flush = () => {
    const q = clock.queue;
    clock.queue = new Map();
    q.forEach((cb) => {
      try {
        cb(clock.now);
      } catch (e) {
        console.error(e);
      }
    });
  };
  let lastReal = performance.now();
  const tick = (t) => {
    // Clamp so a backgrounded tab doesn't jump the animation forward when it comes back.
    const dt = Math.min(t - lastReal, 250);
    lastReal = t;
    if (!clock.manual) {
      if (clock.playing) clock.now += dt * clock.speed;
      flush();
      showTime();
    }
    realRaf(tick);
  };
  realRaf(tick);
  const step = (frames) => {
    for (let i = 0; i < frames; i++) {
      clock.now += FRAME_MS;
      flush();
    }
    showTime();
  };

  // ---- DOM -----------------------------------------------------------------------------------
  const $ = (id) => document.getElementById(id);
  const canvas = $("canvas");
  const logEl = $("log");
  const controlsEl = $("controls");
  const setStatus = (text, kind = "") => {
    $("status").textContent = text;
    $("status").className = kind;
  };
  const log = (kind, text) => {
    const line = document.createElement("div");
    line.className = kind;
    const t = ((clock.now - clock.origin) / 1000).toFixed(3).padStart(7);
    line.textContent = `${t}s  ${text}`;
    logEl.appendChild(line);
    while (logEl.childNodes.length > 400) logEl.firstChild.remove();
    logEl.scrollTop = logEl.scrollHeight;
  };
  let seeking = false; // a seek replays from 0 to the target; its events and states aren't logged
  let scrubbing = false; // the user is dragging the timeline
  function showTime() {
    const t = (clock.now - clock.origin) / 1000;
    $("time").textContent = `t = ${t.toFixed(3)} s`;
    if (timeline && !scrubbing && !GOLDEN) {
      const s = timeline.seconds;
      $("scrub").value = timeline.loop && s > 0 ? t % s : Math.min(t, s);
    }
  }
  // The latest event, in the header: stories cue sounds with events, so they should be seen without the log.
  let eventTimer = null;
  function showEvent(text) {
    const e = $("lastEvent");
    e.textContent = `event ${text}`;
    e.classList.add("flash");
    clearTimeout(eventTimer);
    eventTimer = setTimeout(() => e.classList.remove("flash"), 600);
  }

  // Spoken lines: a story fires an event named `say:<line>` where Pip speaks it (the app's runner hands the line to the
  // phone's text-to-speech). Here the browser's speechSynthesis says it, when Voice is on, and the stage captions it.
  const SAY = "say:";
  let captionTimer = null;
  function caption(text) {
    const c = $("caption");
    c.textContent = text;
    c.hidden = !text;
    clearTimeout(captionTimer);
    if (text) captionTimer = setTimeout(() => (c.hidden = true), 900 + 70 * text.length);
  }
  function speak(text) {
    caption(text);
    if (!$("voice")?.checked || !("speechSynthesis" in window)) return;
    const u = new SpeechSynthesisUtterance(text);
    u.pitch = 1.35;
    u.rate = 1.05;
    speechSynthesis.speak(u);
  }

  // The playing artboard's first clip: a story's timeline (a project's main artboard plays it from `Main`).
  function readTimeline() {
    const ab = r?.artboard;
    if (!ab || ab.animationCount() === 0) return null;
    const a = ab.animationByIndex(0);
    const frames = a.enableWorkArea ? a.workEnd - a.workStart : a.duration;
    return { name: a.name, seconds: frames / (a.fps || 60), loop: a.loopValue !== 0 };
  }

  // Fit the canvas to the artboard's shape: a 1080 x 2424 story stands tall, Pip's rig is near square.
  function sizeCanvas() {
    const b = r?.bounds;
    if (!b || document.getElementById("backdrop")?.checked) {
      canvas.style.removeProperty("aspect-ratio");
      canvas.style.removeProperty("width");
      return;
    }
    const w = b.maxX - b.minX, h = b.maxY - b.minY;
    canvas.style.aspectRatio = `${w} / ${h}`;
    canvas.style.width = h / w > 1.4 ? `min(100%, calc(78vh * ${(w / h).toFixed(4)}))` : "min(420px, 100%)";
    fitCanvas();
  }

  function renderArtboards() {
    const sel = $("artboard");
    sel.replaceChildren(
      ...artboards.map((a, i) => {
        const o = document.createElement("option");
        o.value = a.name;
        o.textContent = i === 0 ? `${a.name} (default)` : a.name;
        return o;
      }),
    );
    sel.value = artboardName || artboards[0]?.name || "";
    sel.disabled = artboards.length < 2;
  }
  $("artboard").addEventListener("change", (e) => {
    artboardName = e.target.value === artboards[0]?.name ? null : e.target.value;
    const u = new URL(location.href);
    if (artboardName) u.searchParams.set("artboard", artboardName);
    else u.searchParams.delete("artboard");
    history.replaceState(null, "", u);
    remembered.clear();
    load({ reuse: true }).catch(fail);
  });

  function renderTimeline() {
    const scrub = $("scrub");
    $("timelineRow").hidden = !timeline;
    if (!timeline) return;
    scrub.max = timeline.seconds.toFixed(4);
    scrub.step = (1 / 60).toFixed(5);
    $("timelineName").textContent = `${timeline.name}, ${timeline.seconds.toFixed(2)} s${timeline.loop ? ", loops" : ""}`;
  }

  // Seeking replays the file from t = 0 in fixed 1/60 s steps, so what you see at t is exactly what plays at t
  // (state machines and events have no random access). Only the latest requested time runs.
  let seekTarget = null;
  let seekBusy = false;
  let seekEvent = null; // the last event the replay passed, shown in the header once it lands
  let seekSay = null; // the last spoken line the replay passed, captioned once it lands
  async function seek(t) {
    seekTarget = t;
    if (seekBusy) return;
    seekBusy = true;
    setPlaying(false);
    try {
      while (seekTarget !== null) {
        const target = seekTarget;
        seekTarget = null;
        seeking = true;
        seekEvent = null;
        seekSay = null;
        if ("speechSynthesis" in window) speechSynthesis.cancel();
        try {
          await load({ reuse: true });
          step(Math.round(target * 60));
        } finally {
          seeking = false;
        }
        flush();
      }
      setPlaying(false);
      log("sys", `seek to ${((clock.now - clock.origin) / 1000).toFixed(2)} s` + (seekEvent ? ` (last event ${seekEvent})` : ""));
      $("lastEvent").textContent = seekEvent ? `event ${seekEvent}` : "";
      caption(seekSay ?? "");
    } catch (e) {
      fail(e);
    } finally {
      seekBusy = false;
    }
  }
  $("scrub").addEventListener("input", (e) => {
    scrubbing = true;
    seek(Number(e.target.value));
  });
  $("scrub").addEventListener("change", () => {
    scrubbing = false;
  });

  // ---- runtime loading -----------------------------------------------------------------------
  const runtimeReady = new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = `${RUNTIME}/rive.js`;
    s.onload = () => {
      window.rive.RuntimeLoader.setWasmUrl(`${RUNTIME}/rive.wasm`);
      resolve(window.rive);
    };
    s.onerror = () => reject(new Error("could not load vendored rive.js"));
    document.head.appendChild(s);
  });

  let r = null; // current rive.Rive
  let rivUrl = params.get("riv") || "pip.riv";
  let model = null; // { kind: "vm" | "inputs" | "none", props: [...] }
  const remembered = new Map(); // control values kept across reloads, by name

  async function fetchRiv(url) {
    const res = await fetch(`${url}${url.includes("?") ? "&" : "?"}t=${Date.now()}`, { cache: "no-store" });
    if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
    return new Uint8Array(await res.arrayBuffer());
  }

  // Every artboard in the file with its state machines; the first is the one runtimes open by default.
  function probeContents(rive, buffer) {
    return new Promise((resolve, reject) => {
      const probe = new rive.Rive({
        buffer: buffer.slice().buffer,
        canvas: document.createElement("canvas"),
        autoplay: false,
        onLoad: () => {
          const artboards = probe.contents.artboards.map((a) => ({ name: a.name, stateMachines: a.stateMachines.map((s) => s.name) }));
          probe.cleanup();
          resolve(artboards);
        },
        onLoadError: (e) => reject(new Error(`rive could not load the file: ${e?.data ?? e}`)),
      });
    });
  }

  let artboardName = params.get("artboard"); // null: the file's first (default) artboard
  let artboards = [];
  let cached = null; // { buffer, shown } of the last fetch, so a seek restarts without refetching
  let timeline = null; // { name, seconds } of the playing artboard's first clip

  async function load({ reuse = false } = {}) {
    const rive = await runtimeReady;
    let buffer;
    let shown = rivUrl;
    if (reuse && cached) {
      ({ buffer, shown } = cached);
    } else {
      try {
        buffer = await fetchRiv(rivUrl);
      } catch (e) {
        if (params.get("riv")) throw e;
        buffer = await fetchRiv(FALLBACK_RIV);
        shown = `${FALLBACK_RIV} (no pip.riv yet: run serve.sh)`;
      }
      cached = { buffer, shown };
      artboards = await probeContents(rive, buffer);
    }
    // A rebuilt file may have dropped the picked artboard; fall back to the default one.
    if (artboardName && !artboards.some((a) => a.name === artboardName)) artboardName = null;
    const board = artboards.find((a) => a.name === artboardName) || artboards[0];
    if (r) {
      r.cleanup();
      r = null;
    }
    // (No clearing of the frame queue here: the runtime batches all its frame requests behind
    // one requestAnimationFrame call and would wait forever for one we dropped.)
    clock.origin = clock.now;
    showTime();
    $("source").textContent = shown;

    // The state machine has to be named at construction: autoBind only binds the view model to
    // state machines that exist then (one started later with play() never sees view-model
    // changes), and without a name the runtime plays the first linear animation instead.
    const sm = params.get("sm") || board?.stateMachines[0];

    await new Promise((resolve, reject) => {
      const inst = new rive.Rive({
        buffer: buffer.slice().buffer,
        canvas,
        autoplay: true,
        autoBind: true,
        ...(artboardName ? { artboard: artboardName } : {}),
        stateMachines: sm,
        layout: new rive.Layout({ fit: rive.Fit.Contain, alignment: rive.Alignment.Center }),
        onLoad: () => {
          r = inst;
          resolve();
        },
        onLoadError: (e) => reject(new Error(`rive could not load the file: ${e?.data ?? e}`)),
      });
      inst.on(rive.EventType.RiveEvent, (e) => {
        const t = ((clock.now - clock.origin) / 1000).toFixed(2);
        const name = e.data?.name ?? "";
        if (seeking) {
          seekEvent = `${name} @ ${t} s`;
          if (name.startsWith(SAY)) seekSay = name.slice(SAY.length);
          return;
        }
        if (name.startsWith(SAY) && !GOLDEN) speak(name.slice(SAY.length));
        log("ev", `event ${e.data?.name} ${JSON.stringify(e.data?.properties ?? {})}`);
        showEvent(`${e.data?.name} @ ${t} s`);
      });
      inst.on(rive.EventType.StateChange, (e) => {
        if (!seeking) log("sys", `state → ${[].concat(e.data).filter(Boolean).join(", ")}`);
      });
    });
    fitCanvas();
    // Let the runtime draw its first frames before t = 0. It seeds its clock from the first
    // timestamp it sees, so these advance nothing; waiting for them (rather than flushing a
    // fixed number of times) keeps goldens from depending on when it queued its first frame.
    for (let i = 0; i < 50 && (r.frameCount ?? 2) < 2; i++) {
      flush();
      if ((r.frameCount ?? 2) < 2) await new Promise((res) => setTimeout(res, 0));
    }
    clock.origin = clock.now;

    timeline = readTimeline();
    if (!GOLDEN) {
      sizeCanvas();
      renderArtboards();
      renderTimeline();
    }
    if (!seeking) {
      log(
        "sys",
        `loaded ${shown}: artboard ${r.activeArtboard} (of ${artboards.length}), state machines [${r.stateMachineNames}], ` +
          `playing ${sm ?? `animations [${r.playingAnimationNames}]`}` +
          (timeline ? `, timeline ${timeline.name} ${timeline.seconds.toFixed(2)} s` : ""),
      );
    }
    buildModel(rive, sm);
    renderControls();
    restoreValues();
    setStatus("playing", "ok");
    return r;
  }

  // model.props holds view-model properties (group "vm") followed by the playing state machine's
  // legacy inputs (group "input"). A file normally drives its machine through one or the other;
  // showing both makes a file that mixes them obvious.
  function buildModel(rive, sm) {
    const props = [];
    const vmi = r.viewModelInstance;
    if (vmi) {
      for (const { name, type } of vmi.properties) {
        const p = { group: "vm", name, type: String(type) };
        if (p.type === "number") p.handle = vmi.number(name);
        else if (p.type === "boolean") p.handle = vmi.boolean(name);
        else if (p.type === "trigger") p.handle = vmi.trigger(name);
        else if (p.type === "enumType") p.handle = vmi.enum(name);
        else if (p.type === "string") p.handle = vmi.string(name);
        if (p.handle && p.type !== "trigger") {
          p.handle.on(() => log("vm", `${name} = ${JSON.stringify(p.handle.value)}`));
        } else if (p.handle) {
          p.handle.on(() => log("vm", `${name} fired`));
        }
        props.push(p);
      }
    }
    const T = rive.StateMachineInputType;
    for (const input of sm ? r.stateMachineInputs(sm) || [] : []) {
      props.push({
        group: "input",
        name: input.name,
        type: input.type === T.Number ? "number" : input.type === T.Boolean ? "boolean" : "trigger",
        handle: input,
      });
    }
    const hasVm = props.some((p) => p.group === "vm");
    const hasInputs = props.some((p) => p.group === "input");
    model = {
      kind: hasVm ? "vm" : hasInputs ? "inputs" : "none",
      vmName: vmi ? r.viewModelInstance?.viewModelName : null,
      sm,
      props,
    };
  }

  // Unified accessors over view-model properties and legacy inputs.
  const key = (p) => `${p.group}:${p.name}`;
  const getValue = (p) => p.handle.value;
  const setValue = (p, v) => {
    p.handle.value = v;
    remembered.set(key(p), v);
    if (p.group === "input") log("vm", `input ${p.name} = ${JSON.stringify(v)}`);
  };
  const fire = (p) => {
    if (p.group === "vm") p.handle.trigger();
    else {
      p.handle.fire();
      log("vm", `input ${p.name} fired`);
    }
  };
  // Golden specs name a property plainly ("mood") or with a group ("input:mood").
  // A plain name prefers the view model.
  const findProp = (name) => {
    const props = model?.props || [];
    const p =
      props.find((x) => key(x) === name) ||
      props.find((x) => x.group === "vm" && x.name === name) ||
      props.find((x) => x.name === name);
    if (!p) throw new Error(`no view-model property or input named "${name}"`);
    return p;
  };

  function restoreValues() {
    for (const p of model.props) {
      if (!remembered.has(key(p)) || p.type === "trigger") continue;
      try {
        setValue(p, remembered.get(key(p)));
      } catch {
        /* type changed in the new build */
      }
    }
    renderControls();
  }

  // ---- controls ------------------------------------------------------------------------------
  const el = (tag, attrs = {}, ...kids) => {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
      else if (v !== undefined) n.setAttribute(k, v);
    }
    n.append(...kids);
    return n;
  };
  const ranges = new Map(); // per-property slider bounds, kept across reloads

  function renderControls() {
    $("controlsTitle").textContent = `Controls (state machine ${model.sm ?? "none"})`;
    controlsEl.replaceChildren();
    if (!model.props.length) {
      controlsEl.append(el("span", { class: "muted" }, "No view model or state machine inputs."));
      return;
    }
    let group = null;
    for (const p of model.props) {
      if (p.group !== group) {
        group = p.group;
        controlsEl.append(
          el("div", { class: "group" }, group === "vm" ? `View model ${model.vmName || ""}` : "Legacy state machine inputs"),
        );
      }
      const row = el("div", { class: "row" }, el("label", { class: "name" }, p.name));
      if (!p.handle) {
        row.append(el("span", { class: "muted" }, `(${p.type}: not editable here)`));
      } else if (p.type === "number") {
        const v = getValue(p);
        // Default 0..10 in whole steps (covers enum-like numbers such as mood); the bounds are
        // editable and survive reloads.
        const b = ranges.get(key(p)) || { min: Math.min(0, v), max: Math.max(10, Math.ceil(v * 2)), step: 1 };
        ranges.set(key(p), b);
        const slider = el("input", { type: "range", min: b.min, max: b.max, step: b.step, value: v });
        const num = el("input", { class: "num", type: "number", step: "any", value: v });
        const apply = (x) => {
          if (Number.isNaN(x)) return;
          setValue(p, x);
          slider.value = x;
          num.value = x;
        };
        slider.addEventListener("input", () => apply(Number(slider.value)));
        num.addEventListener("change", () => apply(Number(num.value)));
        const bound = (key) =>
          el("input", {
            class: "bound", type: "number", step: "any", value: b[key], title: key,
            onchange: (e) => {
              b[key] = Number(e.target.value);
              slider.setAttribute(key, b[key]);
            },
          });
        row.append(slider, num);
        controlsEl.append(row);
        controlsEl.append(
          el("div", { class: "row bounds muted" }, "range", bound("min"), "to", bound("max"), "step", bound("step")),
        );
        continue;
      } else if (p.type === "boolean") {
        const cb = el("input", { type: "checkbox" });
        cb.checked = !!getValue(p);
        cb.addEventListener("change", () => setValue(p, cb.checked));
        row.append(cb);
      } else if (p.type === "trigger") {
        row.append(el("button", { onclick: () => fire(p) }, "Fire"));
      } else if (p.type === "enumType") {
        const sel = el("select", {}, ...p.handle.values.map((x) => el("option", { value: x }, x)));
        sel.value = getValue(p);
        sel.addEventListener("change", () => setValue(p, sel.value));
        row.append(sel);
      } else if (p.type === "string") {
        const input = el("input", { type: "text", value: getValue(p) });
        input.addEventListener("change", () => setValue(p, input.value));
        row.append(input);
      }
      controlsEl.append(row);
    }
  }

  // ---- playback UI ---------------------------------------------------------------------------
  const setPlaying = (on) => {
    clock.playing = on;
    $("play").textContent = on ? "Pause" : "Play";
    setStatus(on ? "playing" : "paused", on ? "ok" : "");
  };
  $("play").addEventListener("click", () => setPlaying(!clock.playing));
  $("restart").addEventListener("click", () => load().catch(fail));
  $("speed").addEventListener("input", (e) => {
    clock.speed = Number(e.target.value);
    $("speedOut").textContent = `${clock.speed.toFixed(2)}×`;
  });
  document.querySelectorAll("[data-step]").forEach((b) =>
    b.addEventListener("click", () => {
      setPlaying(false);
      step(Number(b.dataset.step));
    }),
  );
  const backdrop = $("backdrop");
  try {
    backdrop.checked = localStorage.getItem("pip-preview-backdrop") === "1";
  } catch {}
  const applyBackdrop = () => {
    $("stage").classList.toggle("backdrop", backdrop.checked);
    try {
      localStorage.setItem("pip-preview-backdrop", backdrop.checked ? "1" : "0");
    } catch {}
    fitCanvas();
  };
  // Match the drawing surface to the canvas's CSS size and redraw now, so a resize shows up
  // even while paused.
  function fitCanvas() {
    if (!r || GOLDEN) return;
    setTimeout(() => {
      r.resizeDrawingSurfaceToCanvas();
      r.drawFrame();
    }, 0);
  }
  window.addEventListener("resize", fitCanvas);
  backdrop.addEventListener("change", applyBackdrop);
  if (!GOLDEN) fetch("backdrop.png", { method: "HEAD" }).then((res) => {
    if (!res.ok) {
      backdrop.disabled = true;
      backdrop.parentElement.title = "No backdrop: serve.sh maps /backdrop.png to .proof/pip1.png";
    }
  });
  applyBackdrop();

  function fail(e) {
    console.error(e);
    setStatus(String(e.message || e), "bad");
    log("err", String(e.message || e));
  }

  // ---- live reload ---------------------------------------------------------------------------
  // serve.sh rewrites build.json after every compile; server.mjs streams it over SSE. If SSE is
  // unavailable (e.g. served by a plain static server) we poll build.json once a second.
  let buildVersion = null;
  function onBuild(info) {
    if (!info || info.version === undefined) return;
    if (buildVersion === null) {
      buildVersion = info.version;
      if (info.ok === false) setStatus(`build failed: ${info.error}`, "bad");
      return;
    }
    if (info.version === buildVersion) return;
    buildVersion = info.version;
    if (info.ok === false) {
      fail(new Error(`build #${info.version} failed: ${info.error}`));
      return;
    }
    log("sys", `build #${info.version} (${info.changed || "change"}) — reloading`);
    load()
      .then(() => {
        window.pipPreview.lastReload = { version: info.version, at: Date.now() };
        setStatus(`reloaded build #${info.version} at ${new Date().toLocaleTimeString()}`, "ok");
      })
      .catch(fail);
    window.pipPreview.reloads++;
  }
  function watchBuilds() {
    let polling = null;
    const poll = () =>
      fetch(`build.json?t=${Date.now()}`, { cache: "no-store" })
        .then((res) => (res.ok ? res.json() : null))
        .then(onBuild)
        .catch(() => {});
    if (window.EventSource) {
      const es = new EventSource("events");
      es.addEventListener("build", (e) => {
        try {
          onBuild(JSON.parse(e.data));
        } catch {}
      });
      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED && !polling) polling = setInterval(poll, 1000);
      };
      // A static server answers /events with 404, which closes the stream at once.
      setTimeout(() => {
        if (es.readyState === EventSource.CLOSED && !polling) polling = setInterval(poll, 1000);
      }, 1500);
    } else {
      polling = setInterval(poll, 1000);
    }
  }

  // ---- API for golden.mjs --------------------------------------------------------------------
  window.pipPreview = {
    reloads: 0,
    ready: null,
    /** True once the first load (and the ?t= seek, if any) has finished: wait for it before a screenshot. */
    settled: false,
    /** The live rive.Rive instance, for poking at from the console. */
    get rive() {
      return r;
    },
    describe: () => ({
      source: rivUrl,
      kind: model?.kind,
      stateMachines: r?.stateMachineNames,
      props: model?.props.map(({ group, name, type }) => ({ name: `${group}:${name}`, type })),
    }),
    /** Reload the file, apply a golden spec and return the canvas as a PNG data URL. */
    async renderGolden(spec) {
      if (spec.riv) rivUrl = spec.riv;
      remembered.clear();
      await load();
      for (const [name, value] of Object.entries(spec.values || {})) setValue(findProp(name), value);
      const end = Math.round((spec.time ?? 0) * 60);
      const fireAt = Math.round((spec.triggerAt ?? 0) * 60);
      for (let f = 0; f <= end; f++) {
        if (f === fireAt) for (const name of spec.triggers || []) fire(findProp(name));
        if (f < end) step(1);
      }
      // One zero-time frame so the last advance is drawn.
      flush();
      return canvas.toDataURL("image/png");
    },
  };

  // ?t=<seconds> opens paused at that time on the timeline, for screenshots from a script.
  const startAt = GOLDEN ? null : params.get("t");
  window.pipPreview.ready = load()
    .then(() => (startAt !== null && Number.isFinite(Number(startAt)) ? seek(Number(startAt)) : undefined))
    .then(() => {
      window.pipPreview.settled = true;
    })
    .catch((e) => {
      fail(e);
      throw e;
    });
  if (!GOLDEN) watchBuilds();
})();

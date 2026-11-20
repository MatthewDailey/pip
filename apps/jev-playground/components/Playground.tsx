"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Pip from "./Pip";
import { DEFAULT_POLICY, initialPolicyState, stepPolicy, type Level, type PolicyConfig, type PolicyState } from "@/lib/policy";
import { linesToText, parseTranscript } from "@/lib/transcript";
import type { ApproachId, CallContext, DetectorResult } from "@/lib/types";
import type { LabeledTranscript } from "@/data/transcripts";

interface Status {
  jev: { configured: boolean; model: string; mock: boolean };
  llm: { configured: boolean; providers?: { name: string; model: string }[] };
}

interface Track {
  policy: PolicyState;
  last?: DetectorResult;
  history: { n: number; raw: number; smoothed: number; level: Level }[];
}

const ALL: ApproachId[] = ["jev", "keyword", "tj", "llm"];
const NAMES: Record<ApproachId, string> = { jev: "Jev", keyword: "Regex / keywords", tj: "TJ's Pip rules", llm: "LLM classifier" };
const COLORS: Record<ApproachId, string> = { jev: "#7c5cff", keyword: "#ff9f1c", tj: "#2bb673", llm: "#1fb6ff" };
const emptyTracks = (): Record<ApproachId, Track> => ({
  jev: { policy: initialPolicyState(), history: [] },
  keyword: { policy: initialPolicyState(), history: [] },
  llm: { policy: initialPolicyState(), history: [] },
  tj: { policy: initialPolicyState(), history: [] },
});

function Bar({ value, color = "#7c5cff", label, hint }: { value: number; color?: string; label: string; hint?: string }) {
  return (
    <div className="bar-row" title={hint}>
      <span className="bar-label">{label}</span>
      <span className="bar-track">
        <span className="bar-fill" style={{ width: `${Math.round(value * 100)}%`, background: color }} />
      </span>
      <span className="bar-val">{(value * 100).toFixed(0)}%</span>
    </div>
  );
}

function Sparkline({ tracks, enabled, total, cfg }: { tracks: Record<ApproachId, Track>; enabled: ApproachId[]; total: number; cfg: PolicyConfig }) {
  const W = 420, H = 120;
  const x = (n: number) => (total ? (n / total) * W : 0);
  const y = (v: number) => H - v * H;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="spark" preserveAspectRatio="none">
      <line x1="0" x2={W} y1={y(cfg.alertThreshold)} y2={y(cfg.alertThreshold)} className="thr thr-alert" />
      <line x1="0" x2={W} y1={y(cfg.takeoverThreshold)} y2={y(cfg.takeoverThreshold)} className="thr thr-take" />
      {enabled.map((a) => {
        const h = tracks[a].history;
        if (!h.length) return null;
        const pts = [`0,${H}`, ...h.map((p) => `${x(p.n)},${y(p.smoothed)}`)].join(" ");
        return <polyline key={a} points={pts} fill="none" stroke={COLORS[a]} strokeWidth="3" strokeLinejoin="round" />;
      })}
    </svg>
  );
}

export default function Playground({ status, transcripts }: { status: Status; transcripts: LabeledTranscript[] }) {
  const [selected, setSelected] = useState(transcripts[0].id);
  const [text, setText] = useState(linesToText(transcripts[0].lines));
  const [ctxText, setCtxText] = useState(JSON.stringify(transcripts[0].context, null, 2));
  const [speedMs, setSpeedMs] = useState(1800);
  const [every, setEvery] = useState(2);
  const [enabled, setEnabled] = useState<ApproachId[]>(status.llm.configured ? ALL : ["jev", "keyword", "tj"]);
  const [driver, setDriver] = useState<ApproachId>("jev");
  const [cfg, setCfg] = useState<PolicyConfig>(DEFAULT_POLICY);
  const [revealed, setRevealed] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [tracks, setTracks] = useState(emptyTracks);
  const [busy, setBusy] = useState(false);
  const [showState, setShowState] = useState(false);

  const lines = useMemo(() => parseTranscript(text), [text]);
  const context = useMemo<CallContext | undefined>(() => {
    try {
      return JSON.parse(ctxText);
    } catch {
      return undefined;
    }
  }, [ctxText]);
  const meta = transcripts.find((t) => t.id === selected);

  const inFlight = useRef(false);
  const pending = useRef<number | null>(null);
  const cfgRef = useRef(cfg);
  cfgRef.current = cfg;
  const runId = useRef(0);

  const analyze = useCallback(
    async (n: number) => {
      if (n <= 0) return;
      if (inFlight.current) {
        pending.current = n; // coalesce: score the latest transcript once the current call returns
        return;
      }
      inFlight.current = true;
      setBusy(true);
      const myRun = runId.current;
      try {
        const res = await fetch("/api/analyze", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ lines: lines.slice(0, n), context, approaches: enabled }),
        });
        const { results } = (await res.json()) as { results: DetectorResult[] };
        if (myRun !== runId.current) return; // reset happened mid-flight
        setTracks((prev) => {
          const next = { ...prev };
          for (const r of results) {
            const t = prev[r.approach];
            const policy = r.error || r.skipped ? t.policy : stepPolicy(t.policy, r, cfgRef.current);
            next[r.approach] = {
              policy,
              last: r,
              history: r.error || r.skipped ? t.history : [...t.history, { n, raw: r.risk, smoothed: policy.smoothed.risk, level: policy.level }],
            };
          }
          return next;
        });
      } finally {
        inFlight.current = false;
        setBusy(false);
        const p = pending.current;
        pending.current = null;
        if (p !== null && myRun === runId.current) void analyze(p);
      }
    },
    [lines, context, enabled],
  );

  // Simulated live call: reveal one line every speedMs, re-score every N lines.
  useEffect(() => {
    if (!playing) return;
    if (revealed >= lines.length) {
      setPlaying(false);
      return;
    }
    const id = setTimeout(() => setRevealed((r) => r + 1), revealed === 0 ? 300 : speedMs);
    return () => clearTimeout(id);
  }, [playing, revealed, lines.length, speedMs]);

  const lastScored = useRef(0);
  useEffect(() => {
    if (revealed === 0) {
      lastScored.current = 0;
      return;
    }
    if (revealed - lastScored.current >= every || (revealed === lines.length && lastScored.current !== revealed)) {
      lastScored.current = revealed;
      void analyze(revealed);
    }
  }, [revealed, every, lines.length, analyze]);

  const reset = () => {
    runId.current++;
    pending.current = null;
    setPlaying(false);
    setRevealed(0);
    setTracks(emptyTracks());
  };

  const pick = (id: string) => {
    const t = transcripts.find((x) => x.id === id)!;
    setSelected(id);
    setText(linesToText(t.lines));
    setCtxText(JSON.stringify(t.context, null, 2));
    reset();
  };

  // URL params for demos: /?t=<transcript id>&speed=<ms per line>&autoplay=1
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const t = q.get("t");
    if (t && transcripts.some((x) => x.id === t)) pick(t);
    const sp = Number(q.get("speed"));
    if (sp > 0) setSpeedMs(sp);
    if (q.get("autoplay") === "1") setTimeout(() => setPlaying(true), 50);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const drive = tracks[driver];
  const level = drive.policy.level;
  const jev = tracks.jev.last;
  const jevRaw = jev?.raw as { state?: unknown; usage?: unknown; servedModel?: string } | undefined;
  const feedRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    feedRef.current?.scrollTo({ top: feedRef.current.scrollHeight, behavior: "smooth" });
  }, [revealed]);

  const scamTypeLabel = (drive.last?.scamType ?? jev?.scamType ?? "scam").toString().replace(/_/g, " ");

  return (
    <main className={`app level-${level}`}>
      <header className="top">
        <div>
          <h1>Pip <span className="sub">· live fraud-detection playground</span></h1>
          <p className="tag">Jev decision model vs. baselines on a simulated phone call. Detectors measure; Pip&apos;s policy decides.</p>
        </div>
        <div className="badges">
          {status.jev.mock ? (
            <span className="badge mock">MOCK MODE · JEV_API_KEY not set · Jev numbers are simulated</span>
          ) : (
            <span className="badge live">Jev live · {jevRaw?.servedModel ?? status.jev.model}</span>
          )}
          <span className={`badge ${status.llm.configured ? "live" : "off"}`}>LLM baseline {status.llm.configured ? `on · ${status.llm.providers?.map((p) => p.model).join(" → ")}` : "off (no key)"}</span>
        </div>
      </header>

      {level === "takeover" && (
        <div className="takeover">
          <strong>🛡️ Pip intervenes!</strong> Participants muted. Pip says: “Hi, I&apos;m Pip, a call assistant. I&apos;ve paused this call because it
          matches a <b>{scamTypeLabel}</b> scam pattern. Real agencies and banks never ask for gift cards, wires, crypto or codes over the phone.
          Let&apos;s hang up and call your son.” <em>({drive.policy.reason})</em>
        </div>
      )}
      {level === "alert" && <div className="alertbar">📱 Pip texted your trusted contact: “Mom may be on a scam call. Pip is listening.” <em>({drive.policy.reason})</em></div>}

      <section className="grid">
        {/* ── controls ── */}
        <div className="card controls">
          <h2>Call script</h2>
          <select value={selected} onChange={(e) => pick(e.target.value)}>
            {(["clean", "messy"] as const).map((d) => (
              <optgroup key={d} label={d === "clean" ? "Clean (scripted)" : "Messy (realistic speech-to-text)"}>
                {transcripts.filter((t) => (t.dataset ?? "clean") === d).map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.label === "scam" ? "🚩" : "✅"} {t.title.replace("[messy] ", "")}{t.difficulty === "hard" ? " · hard" : ""}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
          {meta && <p className="note">{meta.label.toUpperCase()} · {meta.difficulty} · {meta.note}{meta.tags?.length ? ` [${meta.tags.join(", ")}]` : ""}</p>}
          <label>Transcript (CALLER: / USER: per line, editable)</label>
          <textarea value={text} onChange={(e) => { setText(e.target.value); reset(); }} rows={10} />
          <label>Call context (JSON state)</label>
          <textarea value={ctxText} onChange={(e) => setCtxText(e.target.value)} rows={5} className={context ? "" : "bad"} />

          <div className="buttons">
            <button className="primary" onClick={() => { if (revealed >= lines.length) reset(); setTimeout(() => setPlaying((p) => !p), 0); }}>
              {playing ? "⏸ Pause" : revealed >= lines.length && revealed > 0 ? "↻ Replay" : "▶ Play call"}
            </button>
            <button onClick={() => setRevealed((r) => Math.min(lines.length, r + 1))} disabled={playing}>Step</button>
            <button onClick={() => { setRevealed(lines.length); }} disabled={playing}>Jump to end</button>
            <button onClick={reset}>Reset</button>
          </div>

          <div className="knobs">
            <label>Speed <input type="range" min={300} max={4000} step={100} value={speedMs} onChange={(e) => setSpeedMs(+e.target.value)} /> {(speedMs / 1000).toFixed(1)}s/line</label>
            <label>Re-score every <input type="number" min={1} max={10} value={every} onChange={(e) => setEvery(Math.max(1, +e.target.value))} /> lines</label>
            <div className="checks">
              {ALL.map((a) => (
                <label key={a}>
                  <input type="checkbox" checked={enabled.includes(a)} onChange={(e) => setEnabled((xs) => (e.target.checked ? [...xs, a] : xs.filter((x) => x !== a)))} />
                  {NAMES[a]}
                </label>
              ))}
            </div>
            <label>Pip follows
              <select value={driver} onChange={(e) => setDriver(e.target.value as ApproachId)}>
                {ALL.map((a) => <option key={a} value={a}>{NAMES[a]}</option>)}
              </select>
            </label>
          </div>
          <details>
            <summary>Escalation policy</summary>
            {([
              ["alertThreshold", "Alert contact ≥"],
              ["takeoverThreshold", "Takeover ≥"],
              ["comboThreshold", "Payment+urgency both ≥"],
              ["alphaUp", "EMA rise α"],
              ["alphaDown", "EMA fall α"],
            ] as [keyof PolicyConfig, string][]).map(([k, label]) => (
              <label key={k} className="slider">
                {label}
                <input type="range" min={0.05} max={1} step={0.05} value={cfg[k] as number} onChange={(e) => setCfg({ ...cfg, [k]: +e.target.value })} />
                {(cfg[k] as number).toFixed(2)}
              </label>
            ))}
            <label className="slider">Min updates before takeover
              <input type="number" min={1} max={6} value={cfg.minUpdatesForTakeover} onChange={(e) => setCfg({ ...cfg, minUpdatesForTakeover: +e.target.value })} />
            </label>
          </details>
        </div>

        {/* ── live call ── */}
        <div className="card live">
          <div className="pipwrap">
            <Pip level={level} />
            <div className="meter">
              <div className="meter-title">Smoothed risk ({NAMES[driver]}) {busy && <span className="dot" />}</div>
              <div className="meter-track">
                <div className="meter-fill" style={{ width: `${drive.policy.smoothed.risk * 100}%` }} />
                <div className="meter-raw" style={{ left: `${(drive.last?.risk ?? 0) * 100}%` }} title="latest raw risk" />
                <div className="tick" style={{ left: `${cfg.alertThreshold * 100}%` }}><span>alert</span></div>
                <div className="tick" style={{ left: `${cfg.takeoverThreshold * 100}%` }}><span>takeover</span></div>
              </div>
              <div className="meter-nums">
                <b>{(drive.policy.smoothed.risk * 100).toFixed(0)}%</b> smoothed · raw {((drive.last?.risk ?? 0) * 100).toFixed(0)}% · level <b>{level}</b>
              </div>
              <Sparkline tracks={tracks} enabled={enabled} total={lines.length} cfg={cfg} />
              <div className="legend">{enabled.map((a) => <span key={a}><i style={{ background: COLORS[a] }} />{NAMES[a]}</span>)}</div>
            </div>
          </div>
          <div className="feed" ref={feedRef}>
            {lines.slice(0, revealed).map((l, i) => (
              <div key={i} className={`line ${l.speaker}`}>
                <span className="who">{l.speaker === "caller" ? "Caller" : l.speaker === "user" ? "User" : "Unknown speaker"}</span>
                {l.text}
              </div>
            ))}
            {revealed === 0 && <div className="empty">Press ▶ Play call to stream the transcript line by line.</div>}
            {revealed > 0 && revealed < lines.length && <div className="typing">…</div>}
          </div>
          <div className="progress">line {revealed}/{lines.length}</div>
        </div>

        {/* ── detectors ── */}
        <div className="card results">
          <h2>Jev answers {jev?.mock && <span className="badge mock small">MOCK</span>}</h2>
          {jev?.error && <p className="err">{jev.error}</p>}
          {jev && !jev.error ? (
            <>
              <div className="latency">{jev.latencyMs.toFixed(0)} ms round trip · risk level {jev.riskLevel?.toFixed(2)} / 4</div>
              {Object.entries(jev.signals).map(([k, v]) => <Bar key={k} label={k.replace(/_/g, " ")} value={v} />)}
              <h3>scam_type</h3>
              {Object.entries(jev.scamTypeProbs ?? {})
                .sort((a, b) => b[1] - a[1])
                .map(([k, v]) => <Bar key={k} label={k.replace(/_/g, " ")} value={v} color={k === "none" ? "#5ee08a" : "#ff5d5d"} />)}
              <button className="link" onClick={() => setShowState((s) => !s)}>{showState ? "hide" : "show"} state sent to Jev</button>
              {showState && <pre className="state">{JSON.stringify(jevRaw?.state, null, 2)}</pre>}
            </>
          ) : (
            !jev?.error && <p className="muted">No score yet.</p>
          )}

          <h2>Side by side</h2>
          <div className="compare">
            {enabled.map((a) => {
              const t = tracks[a];
              const r = t.last;
              return (
                <div key={a} className={`cmp level-${t.policy.level}`} style={{ borderColor: COLORS[a] }}>
                  <div className="cmp-head">
                    <b style={{ color: COLORS[a] }}>{r?.label ?? NAMES[a]}</b>
                    <span className={`pill pill-${t.policy.level}`}>{t.policy.level}</span>
                  </div>
                  {r?.skipped ? (
                    <div className="muted">{r.reasons?.[0]}</div>
                  ) : r?.error ? (
                    <div className="err">{r.error}</div>
                  ) : r ? (
                    <>
                      <div className="cmp-nums">
                        raw {(r.risk * 100).toFixed(0)}% · smoothed {(t.policy.smoothed.risk * 100).toFixed(0)}% · pay {(r.payment * 100).toFixed(0)}% · urg {(r.urgency * 100).toFixed(0)}%
                      </div>
                      <div className="cmp-nums">type: {String(r.scamType ?? "–")} · <b>{r.latencyMs < 1 ? r.latencyMs.toFixed(2) : r.latencyMs.toFixed(0)} ms</b></div>
                      {r.reasons?.length ? <div className="reasons">{r.reasons.join(" · ")}</div> : null}
                    </>
                  ) : (
                    <div className="muted">waiting…</div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </section>
      <footer>
        Live API for the Twilio stream service: <code>POST /api/calls/&lt;callId&gt;</code> with <code>{"{ chunks: [{ speaker, text }] }"}</code> → <code>{"{ level, action }"}</code>.
        Eval: <code>npm run eval</code>.
      </footer>
    </main>
  );
}

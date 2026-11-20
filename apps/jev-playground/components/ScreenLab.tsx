"use client";
// Screens tab: pick a dataset screen (or upload one), score it with POST /api/screens, see the verdict and Jev's danger box.
import { useEffect, useMemo, useState } from "react";

export interface LabScreen {
  id: string;
  file: string;
  key: string;
  label: string;
  fraud_type: string;
  expected_intervene: boolean;
  difficulty: string;
  tags: string[];
  a11y_text: string;
  url: string | null;
  app_package: string;
}

type Mode = "image+text" | "image" | "text";
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Assessment = any;

const toB64 = (buf: ArrayBuffer) => {
  let s = "";
  const bytes = new Uint8Array(buf);
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
};
const pct = (x: number) => `${Math.round(x * 100)}%`;

export default function ScreenLab({ screens, hasKey }: { screens: LabScreen[]; hasKey: boolean }) {
  const [id, setId] = useState(screens[0]?.id ?? "");
  const [mode, setMode] = useState<Mode>("image+text");
  const [upload, setUpload] = useState<{ src: string; b64: string; fmt: "png" | "jpg" } | null>(null);
  const [res, setRes] = useState<Assessment | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [showLabel, setShowLabel] = useState(false);
  const s = useMemo(() => screens.find((x) => x.id === id), [screens, id]);
  // Deep link for demos: /screens?id=s20&mode=image&run=1
  const [autorun, setAutorun] = useState(false);
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    if (q.get("id") && screens.some((x) => x.id === q.get("id"))) setId(q.get("id")!);
    if (["image+text", "image", "text"].includes(q.get("mode") ?? "")) setMode(q.get("mode") as Mode);
    if (q.get("run") === "1") setAutorun(true);
  }, [screens]);
  useEffect(() => {
    if (autorun) {
      setAutorun(false);
      void run();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autorun, id]);
  const imgSrc = upload?.src ?? (s ? `/api/screens/img/${s.id}` : "");

  async function run() {
    setBusy(true);
    setErr("");
    setRes(null);
    try {
      let image_base64 = upload?.b64;
      if (!image_base64 && s) image_base64 = toB64(await (await fetch(`/api/screens/img/${s.id}`)).arrayBuffer());
      const body = upload
        ? { image_base64, image_format: upload.fmt, mode: mode === "text" ? "image" : mode, trigger: "upload" }
        : { image_base64, a11y_text: s?.a11y_text, app_package: s?.app_package, url: s?.url, trigger: "playground", mode };
      const r = await fetch("/api/screens", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error ?? `HTTP ${r.status}`);
      setRes(j);
    } catch (e) {
      setErr(String((e as Error).message ?? e));
    } finally {
      setBusy(false);
    }
  }

  const box = res?.verdict?.danger_target;
  return (
    <div className="app">
      <div className="top">
        <div>
          <h1 style={{ margin: 0 }}>Pip · screen check</h1>
          <p className="tag">
            Jev looks at the screenshot + on-screen text and decides <b>whether</b> Pip intervenes (risk ≥ 0.7 and a fake login, payment request or remote-access push). TJ&apos;s layer decides how. <a href="/">← calls playground</a>
          </p>
        </div>
        <div className="badges">
          <span className={`badge ${hasKey ? "live" : "off"}`}>{hasKey ? "Jev live" : "JEV_API_KEY missing"}</span>
        </div>
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "300px 420px 1fr", gap: 16, marginTop: 16, alignItems: "start" }}>
        <div className="card">
          <label>
            Screen
            <select value={id} onChange={(e) => { setId(e.target.value); setUpload(null); setRes(null); }} style={{ width: "100%" }}>
              {screens.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.id} · {x.app_package.split(".").slice(-1)[0]}{x.url ? ` · ${new URL(x.url).hostname}` : ""}
                </option>
              ))}
            </select>
          </label>
          <label style={{ display: "block", marginTop: 10 }}>
            Or upload a screenshot (PNG/JPEG, 280–1568 px per side)
            <input
              type="file"
              accept="image/png,image/jpeg"
              onChange={async (e) => {
                const f = e.target.files?.[0];
                if (!f) return;
                setUpload({ src: URL.createObjectURL(f), b64: toB64(await f.arrayBuffer()), fmt: /jpe?g/.test(f.type) ? "jpg" : "png" });
                setRes(null);
              }}
            />
          </label>
          <label style={{ display: "block", marginTop: 10 }}>
            Mode
            <select value={mode} onChange={(e) => setMode(e.target.value as Mode)}>
              <option value="image+text">image + text</option>
              <option value="image">image only</option>
              <option value="text">text only</option>
            </select>
          </label>
          <div className="buttons">
            <button onClick={run} disabled={busy || !hasKey}>{busy ? "Checking…" : "Check screen"}</button>
          </div>
          {!upload && s && (
            <p className="note">
              <label style={{ display: "inline" }}>
                <input type="checkbox" checked={showLabel} onChange={(e) => setShowLabel(e.target.checked)} /> show label
              </label>
              {showLabel && (
                <span> · {s.label} / {s.fraud_type} · expected intervene: {String(s.expected_intervene)} · {s.difficulty}</span>
              )}
            </p>
          )}
        </div>
        <div className="card" style={{ padding: 10 }}>
          <div style={{ position: "relative", width: 400, maxWidth: "100%", margin: "0 auto" }}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {imgSrc && <img src={imgSrc} alt="screen" style={{ width: "100%", display: "block", borderRadius: 12, border: "1px solid #ddd" }} />}
            {box && (
              <div
                title={`danger_target (confidence ${box.confidence ?? "?"})`}
                style={{
                  position: "absolute",
                  top: `${box.top * 100}%`,
                  left: `${box.left * 100}%`,
                  width: `${(box.right - box.left) * 100}%`,
                  height: `${(box.bottom - box.top) * 100}%`,
                  border: `3px solid ${res.intervene ? "#e02424" : "#f59e0b"}`,
                  background: res.intervene ? "rgba(224,36,36,.15)" : "rgba(245,158,11,.12)",
                  borderRadius: 8,
                }}
              />
            )}
          </div>
        </div>
        <div className="card">
          {err && <p style={{ color: "#c62828" }}>{err}</p>}
          {!res && !err && <p className="note">Pick a screen and press Check screen.</p>}
          {res && (
            <>
              <div className={res.intervene ? "takeover" : "alertbar"} style={{ marginTop: 0, animation: "none", background: res.intervene ? undefined : res.verdict.fraud >= 0.5 ? "#fff0d6" : "#e9fbe9", borderColor: res.verdict.fraud >= 0.5 ? undefined : "#86efac" }}>
                <b>{res.intervene ? "INTERVENE: Pip steps in" : res.verdict.fraud >= 0.5 ? "Suspicious, but no intervention (rule not met)" : "Looks fine"}</b>
                {res.suggested_pip_line && <div style={{ marginTop: 6 }}>🗣️ “{res.suggested_pip_line}”</div>}
              </div>
              <table style={{ width: "100%", marginTop: 12, fontSize: 14 }}>
                <tbody>
                  <tr><td>fraud</td><td><b>{pct(res.verdict.fraud)}</b></td></tr>
                  <tr><td>risk (0.5·fraud + 0.5·score/3)</td><td><b>{res.verdict.risk.toFixed(2)}</b> (score {res.verdict.risk_level} of 3)</td></tr>
                  <tr><td>fraud type</td><td>{res.verdict.fraud_type}</td></tr>
                  {Object.entries(res.verdict.signals as Record<string, number>).map(([k, v]) => (
                    <tr key={k}><td>{k}</td><td>{pct(v)}</td></tr>
                  ))}
                  <tr><td>latency</td><td>{res.latency_ms} ms · {res.model} · {res.mode}</td></tr>
                </tbody>
              </table>
              <details style={{ marginTop: 10 }}>
                <summary>contract JSON (what TJ receives)</summary>
                <pre style={{ fontSize: 11, whiteSpace: "pre-wrap" }}>{JSON.stringify(res, null, 2)}</pre>
              </details>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

"use client";
// Stage view: follows one live call as pip-call pushes its lines to Pip4. Polls Pip4 every 500 ms;
// without a call id it follows whichever call was active most recently, switching when a new one starts.
import { useEffect, useRef, useState } from "react";
import Pip from "@/components/Pip";
import type { CallSession, SessionSummary } from "@/lib/sessions";
import { DEFAULT_POLICY, type Level } from "@/lib/policy";

const POLL_MS = 500;
const LEVEL_TEXT: Record<Level, string> = { calm: "Calm", watch: "Watching", alert: "Alert", takeover: "Taken over" };

export default function LiveCall({ callId }: { callId?: string }) {
  const [followId, setFollowId] = useState<string | undefined>(callId);
  const [session, setSession] = useState<CallSession | null>(null);
  const [error, setError] = useState<string>("");
  const feedRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    async function tick() {
      try {
        let id = callId;
        if (!id) {
          const list = (await (await fetch("/api/calls", { cache: "no-store" })).json()) as { calls: SessionSummary[] };
          id = list.calls[0]?.callId;
        }
        if (stop) return;
        setFollowId(id);
        if (id) {
          const res = await fetch(`/api/calls/${encodeURIComponent(id)}`, { cache: "no-store" });
          if (!stop) setSession(res.ok ? ((await res.json()) as CallSession) : null);
        } else setSession(null);
        setError("");
      } catch (e) {
        if (!stop) setError(String(e));
      }
      if (!stop) timer = setTimeout(tick, POLL_MS);
    }
    tick();
    return () => {
      stop = true;
      clearTimeout(timer);
    };
  }, [callId]);

  const lineCount = session?.lines.length ?? 0;
  useEffect(() => {
    feedRef.current?.scrollTo({ top: feedRef.current.scrollHeight, behavior: "smooth" });
  }, [lineCount]);

  const level: Level = session?.policy.level ?? "calm";
  const smoothed = session?.policy.smoothed.risk ?? 0;
  const last = session?.history[session.history.length - 1]?.result;
  const cfg = session?.config ?? DEFAULT_POLICY;
  // fields the calls route adds on top of CallSession (user-only listening, partial line, demo tripwire)
  const extra = session as (CallSession & {
    listen?: "user" | "both";
    partial?: { text: string } | null;
    tripwire?: { rule: string; level: Level; reason: string } | null;
  }) | null;
  const userOnly = extra?.listen === "user";
  const ctx = session?.context ?? {};
  const callerLabel = [ctx.caller_name ?? ctx.caller_id, ctx.caller_number].filter(Boolean).join(" · ");

  return (
    <div className={`app live-stage level-${level}`}>
      <header className="top">
        <div>
          <h1 style={{ margin: 0 }}>Pip is on the call</h1>
          <p className="tag">
            {followId ? (
              <>
                Call <code>{followId}</code>
                {callId ? "" : " · following the newest call"} · {lineCount} lines
                {userOnly ? " · hearing Mom's side only" : ""}
                {callerLabel ? ` · ${callerLabel}${ctx.caller_in_contacts === true ? " (contact)" : ctx.caller_in_contacts === false ? " (not in contacts)" : ""}` : ""}
              </>
            ) : (
              "Waiting for a call…"
            )}
          </p>
        </div>
        <div className="badges">
          <span className={`badge level-badge level-badge-${level}`}>{LEVEL_TEXT[level]}</span>
          {extra?.tripwire && <span className="badge off">⚡ {extra.tripwire.rule.replace(/_/g, " ")}</span>}
          {last?.mock && <span className="badge mock">Jev mock</span>}
          {error && <span className="badge off">offline</span>}
        </div>
      </header>

      {level === "takeover" && (
        <div className="takeover live-banner">
          <strong>PIP TOOK OVER THE CALL.</strong> The caller is on hold, Mom is muted and hearing Pip&apos;s warning.
          {session?.policy.reason && <em> ({session.policy.reason})</em>}
        </div>
      )}
      {level === "alert" && <div className="alertbar">Pip is worried and is texting the trusted contact.</div>}

      <div className="live-grid">
        <section className="card live-side">
          <Pip level={level} size={260} />
          <div className="meter" style={{ width: "100%", marginTop: 16 }}>
            <div className="meter-title">Scam risk</div>
            <div className="meter-track">
              <div className="meter-fill" style={{ width: `${smoothed * 100}%` }} />
              {last && <div className="meter-raw" style={{ left: `${(last.risk ?? 0) * 100}%` }} title="latest raw risk" />}
              <div className="tick" style={{ left: `${cfg.alertThreshold * 100}%` }}>
                <span>alert</span>
              </div>
              <div className="tick" style={{ left: `${cfg.takeoverThreshold * 100}%` }}>
                <span>takeover</span>
              </div>
            </div>
            <div className="meter-nums">
              smoothed {smoothed.toFixed(2)}
              {last ? ` · latest ${last.risk.toFixed(2)}` : ""}
              {last?.scamType && last.scamType !== "none" ? ` · looks like: ${String(last.scamType).replace(/_/g, " ")}` : ""}
            </div>
          </div>
        </section>

        <section className="card live-feed-card">
          <div className="live-legend">
            {!userOnly && <span className="who-chip caller">Caller</span>}
            <span className="who-chip user">Mom</span>
            {userOnly && <span className="tag">caller not transcribed</span>}
          </div>
          <div className="feed live-feed" ref={feedRef}>
            {!lineCount && !extra?.partial && <div className="empty">No lines yet. They appear here as the call is transcribed.</div>}
            {session?.lines.map((l, i) => (
              <div key={i} className={`line ${l.speaker === "user" ? "user" : "caller"}`}>
                <span className="who">{l.speaker === "user" ? "Mom" : "Caller"}</span>
                {l.text}
              </div>
            ))}
            {extra?.partial?.text && (
              <div className="line user" style={{ opacity: 0.6 }}>
                <span className="who">Mom</span>
                {extra.partial.text}…
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

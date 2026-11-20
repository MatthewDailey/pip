"use client";
// Pip, the personal cyber defender: a little SVG blob whose mood follows the escalation level.
import type { Level } from "@/lib/policy";

const LOOK: Record<Level, { body: string; cheek: string; mouth: string; brow: string; label: string }> = {
  calm: { body: "#5ee08a", cheek: "#ff9db5", mouth: "M86 128 Q100 142 114 128", brow: "", label: "All good!" },
  watch: { body: "#c8e35a", cheek: "#ffb3a0", mouth: "M86 132 Q100 136 114 132", brow: "M72 82 L90 86 M128 82 L110 86", label: "Hmm…" },
  alert: { body: "#ffb347", cheek: "#ff8a7a", mouth: "M86 136 Q100 126 114 136", brow: "M72 86 L90 80 M128 86 L110 80", label: "Uh oh, texting family" },
  takeover: { body: "#ff5d5d", cheek: "#ff3b3b", mouth: "M88 128 Q100 112 112 128 Q100 140 88 128", brow: "M70 88 L92 78 M130 88 L108 78", label: "PIP INTERVENES!" },
};

export default function Pip({ level, size = 200 }: { level: Level; size?: number }) {
  const l = LOOK[level];
  return (
    <div className={`pip pip-${level}`} style={{ width: size }}>
      <svg viewBox="0 0 200 200" width={size} height={size} aria-label={`Pip is ${level}`}>
        {/* shadow */}
        <ellipse cx="100" cy="186" rx="52" ry="8" fill="rgba(0,0,0,.15)" className="pip-shadow" />
        <g className="pip-body">
          {/* antenna */}
          <line x1="100" y1="40" x2="100" y2="20" stroke="#333" strokeWidth="4" strokeLinecap="round" />
          <circle cx="100" cy="16" r="7" fill={level === "takeover" ? "#ff2d2d" : "#ffd84d"} className="pip-bulb" />
          {/* shield-ish body */}
          <path d="M100 40 C150 40 170 70 170 110 C170 155 135 178 100 178 C65 178 30 155 30 110 C30 70 50 40 100 40 Z" fill={l.body} stroke="#2a2a2a" strokeWidth="4" />
          {/* eyes */}
          <ellipse cx="78" cy="102" rx={level === "takeover" ? 11 : 9} ry={level === "takeover" ? 13 : 11} fill="#222" />
          <ellipse cx="122" cy="102" rx={level === "takeover" ? 11 : 9} ry={level === "takeover" ? 13 : 11} fill="#222" />
          <circle cx="81" cy="98" r="3" fill="#fff" />
          <circle cx="125" cy="98" r="3" fill="#fff" />
          {l.brow && <path d={l.brow} stroke="#222" strokeWidth="4" strokeLinecap="round" fill="none" />}
          <ellipse cx="62" cy="124" rx="9" ry="5" fill={l.cheek} opacity=".7" />
          <ellipse cx="138" cy="124" rx="9" ry="5" fill={l.cheek} opacity=".7" />
          <path d={l.mouth} stroke="#222" strokeWidth="4" fill={level === "takeover" ? "#7a1010" : "none"} strokeLinecap="round" />
          {/* sweat drop when nervous */}
          {(level === "watch" || level === "alert") && <path d="M150 70 Q156 82 150 88 Q144 82 150 70 Z" fill="#7cc8ff" className="pip-sweat" />}
        </g>
      </svg>
      <div className="pip-label">{l.label}</div>
    </div>
  );
}

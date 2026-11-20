// Live HTML renders of the screen-dataset mocks, so the emulator browser can open a fake-login
// or remote-access page and Pip can classify the real screen (screenshot + a11y text + URL).
// Visuals follow data/screens/src/build.mjs, but without the fake phone chrome: real Chrome
// supplies the status/address bar. Hostnames in HOST_TO_KEY are rewritten by middleware.ts.

export const HOST_TO_KEY: Record<string, string> = {
  "chase-secure-verify.co": "chase_fake",
  "usps-redelivery-help.com": "usps_fee",
  "security-alert-check.xyz": "virus_popup",
  "phone-cleaner-scan.live": "fake_antivirus",
};

const CSS = `*{box-sizing:border-box}body{margin:0;min-height:100vh;font-family:-apple-system,Roboto,Helvetica,Arial,sans-serif;background:#fff;color:#202124;font-size:17px}
.page{padding:20px 22px 40px}.logo{font-size:32px;font-weight:800;margin:6px 0 14px}.alert{background:#c62828;color:#fff;padding:14px;border-radius:8px;margin:8px 0}
.in{border:1px solid #bbb;border-radius:6px;padding:13px;margin:8px 0;min-height:46px}.ph{color:#999}
.btn{color:#fff;text-align:center;padding:15px;border-radius:8px;font-weight:700;margin-top:14px}
.row{display:flex;gap:8px}.row>*{flex:1}.small{font-size:13px;color:#666}.card{border:1px solid #e0e0e0;border-radius:10px;padding:14px;margin:10px 0}
.appbar{color:#fff;font-size:22px;font-weight:700;padding:16px 18px;margin:-20px -22px 16px}
.urlbar{background:#f1f3f4;margin:-20px -22px 16px;padding:8px 12px 12px}.urlbar div{background:#fff;border-radius:18px;padding:8px 14px;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#202124}`;

const input = (ph: string, v = "") => `<div class="in">${v || `<span class="ph">${ph}</span>`}</div>`;
const btn = (t: string, c = "#1a73e8") => `<div class="btn" style="background:${c}">${t}</div>`;

export const DEMO_PAGES: Record<string, { title: string; url?: string; body: string }> = {
  chase_fake: {
    title: "Sign in – Chase",
    url: "https://chase-secure-verify.co/login",
    body: `<div class="logo" style="color:#117aca">CHASE ◼</div><div class="alert">Your account has been LOCKED due to suspicious activity. Verify within 30 minutes or your funds will be frozen.</div><p>Sign in to restore access</p>${input("Username")}${input("Password")}${input("Card number")}${input("SSN (last 4)")}${btn("Verify now", "#117aca")}`,
  },
  usps_fee: {
    title: "Package on hold – USPS",
    url: "https://usps-redelivery-help.com/fee",
    body: `<div class="logo" style="color:#333366">USPS</div><h3>Package on hold</h3><p>Your package US9514901185421 could not be delivered due to an incomplete address. A redelivery fee of <b>$1.99</b> is required within 24 hours or the package will be returned.</p>${input("Full name")}${input("Card number")}<div class="row">${input("MM/YY")}${input("CVV")}</div>${btn("Pay $1.99 and reschedule", "#333366")}`,
  },
  virus_popup: {
    title: "Security alert",
    url: "https://security-alert-check.xyz/warning",
    body: `<div style="background:#0067b8;color:#fff;padding:18px;border-radius:8px"><b>Windows Defender Security Center</b><p>⚠️ Access to this device has been blocked for security reasons. Trojan Spyware detected. Your banking passwords and photos are at risk.</p><p style="font-size:20px"><b>Call Microsoft Support: 1-844-555-0182 (toll free)</b></p><p>Do not close this window or restart your device.</p></div>${btn("Call now", "#c62828")}`,
  },
  fake_antivirus: {
    title: "Virus found",
    url: "https://phone-cleaner-scan.live/scan",
    body: `<div style="text-align:center"><div style="font-size:48px">⚠️</div><h2 style="color:#c62828">Your phone is infected with (4) viruses!</h2><p>Your Android device is severely damaged by four viruses. Battery and SIM card data will be corrupted in <b>2 minutes</b>.</p><div style="background:#eee;border-radius:8px;height:14px"><div style="background:#c62828;width:78%;height:14px;border-radius:8px"></div></div><p class="small">Scanning… 78%</p>${btn("Remove viruses now", "#c62828")}</div>`,
  },
  anydesk_code: {
    title: "AnyDesk",
    body: `<div class="appbar" style="background:#ef443b">AnyDesk</div><h3>This device</h3><p>Your address</p><div style="font-size:30px;font-weight:700;letter-spacing:2px">482 916 337</div><p>Share this address with the person who wants to connect.</p><div class="card"><b>Incoming session request</b><p>"Amazon Refund Desk" wants to view and control this device.</p>${btn("Accept", "#ef443b")}${btn("Dismiss", "#888")}</div>`,
  },
  teamviewer_allow: {
    title: "TeamViewer QuickSupport",
    body: `<div class="appbar" style="background:#0e8ee9">TeamViewer QuickSupport</div><p>Your ID</p><div style="font-size:28px;font-weight:700">1 284 551 907</div><div class="card"><b>Allow remote control?</b><p>"Bank Fraud Department - Agent Mike" would like to control your device. They will be able to see your screen and use your apps, including banking apps.</p>${btn("Allow", "#0e8ee9")}${btn("Deny", "#888")}</div>`,
  },
  usps_tracking: {
    title: "USPS Tracking",
    url: "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899223197428490",
    body: `<div class="logo" style="color:#333366">USPS Tracking®</div><p>Tracking Number: 9400111899223197428490</p><h3 style="color:#2e7d32">In Transit to Next Facility</h3><p>Expected delivery by <b>Saturday, November 21</b> by 9:00pm</p><div class="card">Arrived at USPS Regional Facility<br><span class="small">SAN FRANCISCO CA DISTRIBUTION CENTER · November 19, 10:42 pm</span></div><div class="card">Shipping Label Created<br><span class="small">OAKLAND, CA · November 18</span></div>`,
  },
  news: {
    title: "Storm brings heavy rain to Bay Area",
    url: "https://apnews.com/article/weather-storm-bay-area",
    body: `<div class="logo">AP</div><h2>Storm brings heavy rain to Bay Area, flood watch through Saturday</h2><p class="small">By the Associated Press · November 20, 2026</p><p>SAN FRANCISCO (AP) — A strong atmospheric river moved into Northern California on Thursday, bringing up to 3 inches of rain to the Bay Area and snow to the Sierra Nevada…</p><p>Officials urged drivers to avoid flooded roads and check on elderly neighbors.</p>`,
  },
  settings: {
    title: "Settings",
    body: `<div class="appbar" style="background:#fff;color:#111;border-bottom:1px solid #eee">Settings</div>${["Network & internet", "Connected devices", "Apps", "Notifications", "Battery · 82%", "Display", "Sound & vibration", "Accessibility", "Security & privacy"].map((s) => `<div class="card">${s}</div>`).join("")}`,
  },
};

export function demoPageHtml(key: string): { html: string; title: string } | null {
  const page = DEMO_PAGES[key];
  if (!page) return null;
  const host = page.url ? page.url.replace(/^https?:\/\//, "") : "";
  const bar = host ? `<div class="urlbar"><div>🔒 ${escapeHtml(host)}</div></div>` : "";
  const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(page.title)}</title><style>${CSS}</style></head><body><div class="page">${bar}${page.body}</div></body></html>`;
  return { html, title: page.title };
}

function escapeHtml(s: string) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

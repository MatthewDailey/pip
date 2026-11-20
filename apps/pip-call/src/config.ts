// Reads and validates pip-call's environment. Missing required variables fail fast, naming each one.
//
// PIP_TWILIO=fake swaps the real Twilio client for RecordingTwilioOps (simulator, tests, local dev
// without an account); in that mode only TWILIO_AUTH_TOKEN is needed (to check fake signatures).
//
// A few variables have two accepted names: the plan's (PIP_LINE_NUMBER, TWILIO_API_KEY, ...) and the
// ones the cloud environment was set up with (TWILIO_PIP_LINE, TWILIO_API_KEY_SID, ...).

export interface Config {
  twilioAccountSid: string;
  twilioAuthToken: string;
  twilioFake: boolean;
  /** API key + secret and TwiML App: signing the Voice SDK access tokens the app fetches from /token */
  twilioApiKeySid?: string;
  twilioApiKeySecret?: string;
  twimlAppSid?: string;
  /** FCM push credential, only needed for incoming calls to the app */
  pushCredentialSid?: string;
  /** shared secret the app sends to /token and /app/calls/:callSid/context (x-pip-app-secret) */
  appSecret?: string;
  pipLineNumber?: string;
  momNumber?: string;
  /** Voice SDK identity of Mom's app: Pip line calls ring the app instead of MOM_NUMBER when set */
  momClientIdentity?: string;
  roboCallerNumber?: string;
  publicBaseUrl: string;
  vercelBypassSecret?: string;
  pip4Url: string;
  /** Vercel protection-bypass secret of the Pip4 deployment (jev-playground previews are protected) */
  pip4BypassSecret?: string;
  /** which Pip4 detector to score with: jev (default) or a baseline such as keyword */
  pip4Approach: string;
  pip4TimeoutMs: number;
  /** skip X-Twilio-Signature checks (never in production) */
  skipSignature: boolean;
  record: boolean;
  /** what Pip does on takeover: "warn" plays the fixed warning to Mom (default), "live" dials Pip's live voice in */
  pipVoice: "warn" | "live";
  /** live voice: OpenAI Realtime over SIP (the key, project and webhook secret must all be from one OpenAI project) */
  openaiApiKey?: string;
  openaiProjectId?: string;
  openaiWebhookSecret?: string;
  realtimeModel: string;
  realtimeVoice: string;
  /** opens the /debug routes (?token= or x-pip-debug-token); TWILIO_AUTH_TOKEN also works in the header */
  debugToken?: string;
}

export class ConfigError extends Error {
  constructor(public missing: string[]) {
    super(`pip-call: missing required environment variable${missing.length > 1 ? "s" : ""}: ${missing.join(", ")} (see .env.example)`);
  }
}

type Env = Record<string, string | undefined>;
const pick = (env: Env, ...names: string[]) => names.map((n) => env[n]).find((v) => v) || undefined;

/** PUBLIC_BASE_URL, or on Vercel the URL Twilio will call: the production domain, else the branch URL. */
function publicBase(env: Env): string | undefined {
  if (env.PUBLIC_BASE_URL) return env.PUBLIC_BASE_URL;
  const host =
    env.VERCEL_ENV === "production" ? env.VERCEL_PROJECT_PRODUCTION_URL : env.VERCEL_BRANCH_URL || env.VERCEL_URL;
  return host ? `https://${host}` : undefined;
}

export function loadConfig(env: Env = process.env): Config {
  const twilioFake = env.PIP_TWILIO === "fake";
  const required: [string, string | undefined][] = twilioFake
    ? [["TWILIO_AUTH_TOKEN", env.TWILIO_AUTH_TOKEN]]
    : [
        ["TWILIO_ACCOUNT_SID", env.TWILIO_ACCOUNT_SID],
        ["TWILIO_AUTH_TOKEN", env.TWILIO_AUTH_TOKEN],
        ["PIP_LINE_NUMBER", pick(env, "PIP_LINE_NUMBER", "TWILIO_PIP_LINE")],
        ["PUBLIC_BASE_URL", publicBase(env)],
      ];
  const missing = required.filter(([, v]) => !v).map(([k]) => k);
  if (missing.length) throw new ConfigError(missing);
  return {
    twilioAccountSid: env.TWILIO_ACCOUNT_SID ?? "",
    twilioAuthToken: env.TWILIO_AUTH_TOKEN!,
    twilioFake,
    twilioApiKeySid: pick(env, "TWILIO_API_KEY", "TWILIO_API_KEY_SID"),
    twilioApiKeySecret: pick(env, "TWILIO_API_SECRET", "TWILIO_API_KEY_SECRET"),
    twimlAppSid: env.TWILIO_TWIML_APP_SID || undefined,
    pushCredentialSid: env.TWILIO_PUSH_CREDENTIAL_SID || undefined,
    appSecret: env.PIP_APP_SECRET || undefined,
    pipLineNumber: pick(env, "PIP_LINE_NUMBER", "TWILIO_PIP_LINE"),
    momNumber: env.MOM_NUMBER || undefined,
    momClientIdentity: env.MOM_CLIENT_IDENTITY || undefined,
    roboCallerNumber: pick(env, "ROBO_CALLER_NUMBER", "TWILIO_ROBO_CALLER"),
    publicBaseUrl: (publicBase(env) || `http://localhost:${env.PORT || 3200}`).replace(/\/$/, ""),
    vercelBypassSecret: env.VERCEL_AUTOMATION_BYPASS_SECRET || undefined,
    pip4Url: (env.PIP4_URL || "http://localhost:3100").replace(/\/$/, ""),
    pip4BypassSecret: env.PIP4_BYPASS_SECRET || undefined,
    pip4Approach: env.PIP4_APPROACH || "jev",
    pip4TimeoutMs: Number(env.PIP4_TIMEOUT_MS || 5000),
    skipSignature: env.PIP_SKIP_SIGNATURE === "1",
    record: env.PIP_RECORD === "1",
    pipVoice: env.PIP_VOICE === "live" ? "live" : "warn",
    openaiApiKey: env.OPENAI_API_KEY || undefined,
    openaiProjectId: env.OPENAI_PROJECT_ID || undefined,
    openaiWebhookSecret: env.OPENAI_WEBHOOK_SECRET || undefined,
    realtimeModel: env.PIP_REALTIME_MODEL || "gpt-realtime",
    realtimeVoice: env.PIP_REALTIME_VOICE || "cedar",
    debugToken: env.DEBUG_TOKEN || undefined,
  };
}

let cached: Config | undefined;
export function config(): Config {
  return (cached ??= loadConfig());
}
/** tests */
export function resetConfig() {
  cached = undefined;
}

/** Live voice is used only when it is switched on and fully configured; otherwise the fixed warning plays. */
export function liveVoiceReady(cfg: Config = config()) {
  return cfg.pipVoice === "live" && !!cfg.openaiApiKey && !!cfg.openaiProjectId && !!cfg.openaiWebhookSecret;
}

/** Absolute URL Twilio should call, with the Vercel protection-bypass parameter on preview deployments. */
export function webhookUrl(path: string, cfg: Config = config()) {
  const u = new URL(path, cfg.publicBaseUrl + "/");
  if (cfg.vercelBypassSecret) u.searchParams.set("x-vercel-protection-bypass", cfg.vercelBypassSecret);
  return u.toString();
}

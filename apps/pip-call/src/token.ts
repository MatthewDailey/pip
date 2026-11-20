// Twilio Voice SDK access tokens for the Elder Guard app: identity = Mom's account, one Voice grant
// whose outgoing calls run the TwiML App (whose voice URL is /twiml/voice), valid one hour.
import twilio from "twilio";
import type { Config } from "./config";

export const TOKEN_TTL_S = 3600;

export class TokenConfigError extends Error {}

/** Voice SDK identities: letters, digits and a few separators, at most 121 characters. */
export function validIdentity(id: string) {
  return /^[A-Za-z0-9_.@+-]{1,121}$/.test(id);
}

export function mintVoiceToken(cfg: Config, identity: string): string {
  const missing = [
    ["TWILIO_ACCOUNT_SID", cfg.twilioAccountSid],
    ["TWILIO_API_KEY_SID", cfg.twilioApiKeySid],
    ["TWILIO_API_KEY_SECRET", cfg.twilioApiKeySecret],
    ["TWILIO_TWIML_APP_SID", cfg.twimlAppSid],
  ]
    .filter(([, v]) => !v)
    .map(([k]) => k);
  if (missing.length) throw new TokenConfigError(`missing ${missing.join(", ")}`);
  const { AccessToken } = twilio.jwt;
  const token = new AccessToken(cfg.twilioAccountSid, cfg.twilioApiKeySid!, cfg.twilioApiKeySecret!, {
    identity,
    ttl: TOKEN_TTL_S,
  });
  token.addGrant(
    new AccessToken.VoiceGrant({
      outgoingApplicationSid: cfg.twimlAppSid,
      incomingAllow: true,
      ...(cfg.pushCredentialSid ? { pushCredentialSid: cfg.pushCredentialSid } : {}),
    }),
  );
  return token.toJwt();
}

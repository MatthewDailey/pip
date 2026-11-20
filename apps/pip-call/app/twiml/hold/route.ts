// Hold music for the caller during a takeover (holdUrl). Twilio may GET or POST it.
export const dynamic = "force-dynamic";

const twiml = `<?xml version="1.0" encoding="UTF-8"?>
<Response><Play loop="0">http://com.twilio.sounds.music.s3.amazonaws.com/MARKOVICHAMP-Borghestral.mp3</Play></Response>`;

const reply = () => new Response(twiml, { headers: { "content-type": "text/xml" } });
export const GET = reply;
export const POST = reply;

// The warning only Mom hears during a takeover (announceUrl on her participant).
export const dynamic = "force-dynamic";

const twiml = `<?xml version="1.0" encoding="UTF-8"?>
<Response><Say voice="Polly.Joanna">This is Pip. This call looks like a scam. I have put the caller on hold. Please do not share any numbers or buy gift cards. Hang up, and call your family on a number you know.</Say></Response>`;

const reply = () => new Response(twiml, { headers: { "content-type": "text/xml" } });
export const GET = reply;
export const POST = reply;

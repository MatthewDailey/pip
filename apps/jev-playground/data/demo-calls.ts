// Demo calls for user-only listening: only the protected user's (Margaret's) own voice is transcribed on her phone.
// `pnpm demo:call` replays these in real time (with partial chunks) against /api/calls/:id so /live shows them.
import type { CallContext } from "../lib/types";

export interface DemoLine {
  /** seconds since the call started */
  t: number;
  text: string;
}

export interface DemoCall {
  id: string;
  title: string;
  label: "scam" | "benign";
  /** what Pip should do at most */
  expect: "takeover" | "alert_ok" | "calm";
  context: CallContext;
  lines: DemoLine[];
}

const PROFILE = "Margaret, 81, widowed, lives alone; trusted contact is her daughter Karen";

export const DEMO_CALLS: DemoCall[] = [
  {
    id: "demo-ssa-target",
    title: "Fake Social Security agent walks Margaret through buying Target gift cards",
    label: "scam",
    expect: "takeover",
    context: {
      direction: "inbound",
      caller_number: "+12025550147",
      caller_name: "SOCIAL SEC ADMIN",
      caller_in_contacts: false,
      local_time: "Friday 2:41 PM",
      user_profile: PROFILE,
    },
    lines: [
      { t: 1, text: "Hello?" },
      { t: 5, text: "Yes, this is Margaret." },
      { t: 10, text: "Social Security? Oh my goodness, what's wrong?" },
      { t: 17, text: "My number was used in Texas? I've never even been to Texas." },
      { t: 25, text: "Arrested? No, no, I haven't done anything wrong." },
      { t: 32, text: "Okay. Okay. What do I need to do to fix it?" },
      { t: 40, text: "Protect my money how? You want me to go to the store?" },
      { t: 47, text: "Okay, which store, Target? There's one on Route 9." },
      { t: 56, text: "No, I won't tell my daughter. You said not to tell anyone." },
      { t: 66, text: "I'm at the Target now. The gift cards are up by the front, yes." },
      { t: 74, text: "Five hundred dollars on each one, four of them. Okay." },
      { t: 83, text: "The cashier asked what they were for. I said they're a present, like you told me." },
      { t: 92, text: "Okay, I'm back in the car. Let me scratch off the back." },
      { t: 99, text: "Okay, the first one, it's four one one one, seven two oh nine..." },
      { t: 108, text: "And the PIN is three three eight five." },
    ],
  },
  {
    id: "demo-birthday-card",
    title: "Granddaughter Emily (saved contact) — Margaret plans a Target gift card for her birthday",
    label: "benign",
    expect: "calm",
    context: {
      direction: "inbound",
      caller_number: "+16175550123",
      caller_name: "Emily (granddaughter)",
      caller_in_contacts: true,
      local_time: "Sunday 11:05 AM",
      user_profile: PROFILE,
    },
    lines: [
      { t: 1, text: "Hi sweetheart! Happy almost birthday!" },
      { t: 6, text: "Twenty two, I can't believe it. Where does the time go." },
      { t: 13, text: "I was thinking of getting you a Target gift card, is that alright?" },
      { t: 20, text: "Fifty dollars? Or would you rather have a hundred?" },
      { t: 27, text: "Okay, I'll put it in a birthday card and mail it on Monday." },
      { t: 34, text: "Your mom gave me your new address, the one on Maple Street?" },
      { t: 41, text: "Okay honey, I love you too. Tell your mom I said hi. Bye bye." },
    ],
  },
  {
    id: "demo-store-hours",
    title: "Margaret calls her local Target to ask about hours and gift cards",
    label: "benign",
    expect: "calm",
    context: {
      direction: "outbound",
      caller_number: "+15085550199",
      caller_name: "Target Route 9",
      caller_in_contacts: false,
      local_time: "Saturday 9:12 AM",
      user_profile: PROFILE,
    },
    lines: [
      { t: 1, text: "Hi, is this the Target on Route 9?" },
      { t: 6, text: "What time are you open until today?" },
      { t: 12, text: "Nine o'clock, okay. And do you sell gift cards at the front registers?" },
      { t: 20, text: "Can I put any amount on them, like twenty five dollars?" },
      { t: 27, text: "Wonderful. And is the pharmacy open on Sunday?" },
      { t: 33, text: "Ten to six. Okay, thank you dear." },
    ],
  },
  {
    id: "demo-church-collection",
    title: "Friend from church (new number, not saved) collecting for a retirement gift card",
    label: "benign",
    expect: "alert_ok",
    context: {
      direction: "inbound",
      caller_number: "+15085550134",
      caller_name: "WIRELESS CALLER",
      caller_in_contacts: false,
      local_time: "Thursday 6:30 PM",
      user_profile: PROFILE,
    },
    lines: [
      { t: 1, text: "Hello? Oh, Linda! I didn't recognize the number." },
      { t: 7, text: "You got a new phone, good for you." },
      { t: 13, text: "Pastor Jim's retirement? Oh, that's lovely." },
      { t: 20, text: "Everyone's chipping in for a gift card? Sure, count me in." },
      { t: 27, text: "I'll bring twenty dollars in an envelope on Sunday." },
      { t: 33, text: "Okay, see you at the ten o'clock service. Bye now." },
    ],
  },
];

// ~20 labeled, scripted call transcripts for the playground and `npm run eval`.
// Written for this project (not real calls). Mix of classic scams, scams that never say a number
// or the words "gift card", and tricky benign calls that share vocabulary with scams.
import type { CallContext, ScamType, TranscriptLine } from "../lib/types";

export interface LabeledTranscript {
  id: string;
  title: string;
  label: "scam" | "benign";
  scamType: ScamType;
  /** why it is in the set */
  note: string;
  context: CallContext;
  lines: TranscriptLine[];
  /** "clean" = scripted, well-punctuated; "messy" = looks like real streaming STT */
  dataset?: "clean" | "messy";
  difficulty?: "easy" | "medium" | "hard";
  /** free-form tags for per-tag reporting (e.g. late-onset, mistranscription, hard-negative) */
  tags?: string[];
  /** 0-based index of the first line where the scam actually starts (for late-onset calls) */
  scamStartsAt?: number;
}

const c = (text: string): TranscriptLine => ({ speaker: "caller", text });
const u = (text: string): TranscriptLine => ({ speaker: "user", text });
const unknownInbound = (extra: Partial<CallContext> = {}): CallContext => ({
  direction: "inbound",
  caller_in_contacts: false,
  caller_id: "Unknown",
  local_time: "14:10",
  user_profile: "78-year-old, lives alone",
  ...extra,
});

export const TRANSCRIPTS: LabeledTranscript[] = [
  // ───────────── SCAMS ─────────────
  {
    id: "irs-gift-cards",
    title: "IRS warrant → Target gift cards",
    label: "scam",
    scamType: "government",
    note: "Classic: authority + threat + secrecy + gift card numbers read aloud.",
    context: unknownInbound({ caller_id: "202-555-0143" }),
    lines: [
      c("Hello, this is Officer Mark Daniels with the Internal Revenue Service, badge number 4471."),
      u("Oh. Hello? Is something wrong?"),
      c("Ma'am, our records show you owe back taxes from 2023 and a warrant has been issued for your arrest."),
      u("An arrest? I always pay my taxes, my son helps me with them."),
      c("I understand, but this is very serious. Local police will be at your home within the hour unless we resolve it today."),
      u("Oh my goodness. What do I need to do?"),
      c("You can settle the balance right now. Do not hang up and do not discuss this with your son, it is a confidential federal matter."),
      u("Okay, okay. How do I pay?"),
      c("Go to the Target on your street and purchase four Target gift cards for five hundred dollars each."),
      u("Gift cards? For the IRS?"),
      c("Yes, it is the fastest way to post the payment. When you have them, scratch the back and read me the numbers."),
      u("I'm in the car now. The first one says 6 0 4 2 9 1 8 8 3 7 1 2..."),
    ],
  },
  {
    id: "grandparent-bail",
    title: "Grandson in jail, needs bail (no numbers)",
    label: "scam",
    scamType: "family_emergency",
    note: "No digits, no 'gift card': emotional pretext + secrecy + cash courier.",
    context: unknownInbound({ local_time: "21:40" }),
    lines: [
      c("Grandma? It's me... I'm in really big trouble."),
      u("Danny? Is that you? You sound different."),
      c("Yeah, I have a cold and I hit my nose in the accident. I got into a car accident and they arrested me."),
      u("Oh no, sweetheart, are you hurt?"),
      c("I'm okay but I'm in jail. Please don't tell Mom and Dad, they'll kill me. My lawyer can explain."),
      c("Hello ma'am, this is Robert Klein, I'm Daniel's public defender. The judge set bail and we need to post it tonight."),
      u("How much is it? I don't know how this works."),
      c("It has to be cash. A court courier will come by your house in an hour to collect it in an envelope. Please keep this quiet, there's a gag order."),
      u("I have some money at home, I could go to the bank too."),
      c("If the bank asks, just tell them it's for home repairs. Don't mention Daniel, it could hurt his case."),
    ],
  },
  {
    id: "tech-support-anydesk",
    title: "Microsoft tech support → remote access",
    label: "scam",
    scamType: "tech_support",
    note: "Remote-access install + fake infection + payment for 'protection'.",
    context: unknownInbound({ caller_id: "MICROSOFT" }),
    lines: [
      c("Hello, I am calling from Windows technical department. Your computer is sending us error messages."),
      u("My computer? I just use it for email."),
      c("Yes ma'am, hackers have installed a virus and they are stealing your information right now."),
      u("Oh dear. What should I do?"),
      c("Please turn on the computer. I will help you. Go to the website any desk dot com and download the app."),
      u("Okay, it's downloading. It's asking me something."),
      c("Click allow and read me the nine digit address that is showing on your screen."),
      u("It says three four one, eight eight two, nine oh five."),
      c("Thank you. I am now connected. I see many hackers. We must install the security firewall, it costs two ninety nine for lifetime protection."),
      u("Can I pay with my credit card?"),
    ],
  },
  {
    id: "bank-fraud-otp",
    title: "Fake bank fraud dept → card number + code",
    label: "scam",
    scamType: "bank_impersonation",
    note: "Sounds like a real bank call but asks for full card number and the texted code.",
    context: unknownInbound({ caller_id: "CHASE BANK" }),
    lines: [
      c("Hi, this is Jessica from the Chase fraud department. We detected a suspicious purchase of nine hundred dollars at a Best Buy in Ohio."),
      u("Ohio? I didn't buy anything in Ohio."),
      c("No problem, we'll block it. First I need to verify you. Can you read me the full card number on the front of your card?"),
      u("Sure, it's 4147 2023 8812 0094."),
      c("Thank you. And the expiration and the three digits on the back?"),
      u("Oh nine twenty eight, and 7 1 3."),
      c("Perfect. We just texted you a verification code to cancel the charge. Please read it to me right away before the transaction goes through."),
      u("It says 5 8 2 9 1 4. It also says don't share this code with anyone."),
      c("That's just standard, I'm with the bank so it's fine. Stay on the line while I process the reversal."),
    ],
  },
  {
    id: "bank-safe-account-wire",
    title: "Bank 'safe account' wire transfer",
    label: "scam",
    scamType: "bank_impersonation",
    note: "Move savings to a 'safe account' + lie to the teller.",
    context: unknownInbound({ caller_id: "800-555-0199" }),
    lines: [
      c("This is the security department at your bank. Your account has been compromised by an employee at your branch."),
      u("An employee? That's terrible."),
      c("We are investigating, so you cannot speak to anyone at the branch. To protect your savings we need to move them to a secure federal account today."),
      u("How do I do that?"),
      c("Go to the branch and request a wire transfer. I will give you the routing details for the protected account."),
      u("Won't they ask me why?"),
      c("If they ask, say it's for a family member buying a house. Do not mention this call or the employee will be tipped off."),
      u("Alright. How much should I move?"),
      c("Everything in savings. Once the investigation is over in two weeks we move it back with interest."),
    ],
  },
  {
    id: "prize-pch",
    title: "Publishers Clearing House prize fees",
    label: "scam",
    scamType: "prize_lottery",
    note: "Prize + upfront fee.",
    context: unknownInbound(),
    lines: [
      c("Congratulations! This is David from Publishers Clearing House. You've won two point five million dollars and a new Mercedes!"),
      u("Really? I don't remember entering."),
      c("Your entry was automatic from a magazine subscription. The prize patrol is scheduled to come to your house Friday."),
      u("Oh how wonderful!"),
      c("Before we deliver we just need you to cover the federal processing fee and insurance, it's only nineteen hundred dollars."),
      u("Can you take it out of the prize?"),
      c("Unfortunately no, by law it must be paid in advance. You can pay with Walmart gift cards or by sending cash overnight."),
      u("Let me think about it."),
      c("The offer expires today at five o'clock, ma'am, and you shouldn't tell anyone until the official announcement."),
    ],
  },
  {
    id: "romance-crypto",
    title: "Online boyfriend stuck at customs → bitcoin ATM",
    label: "scam",
    scamType: "romance",
    note: "Known contact (met online) but classic romance-scam money ask.",
    context: { direction: "inbound", caller_in_contacts: true, caller_id: "James", local_time: "19:15", user_profile: "70-year-old widow" },
    lines: [
      c("My darling, I've missed your voice so much. I finally finished the contract on the oil rig."),
      u("James! I can't wait to finally meet you in person."),
      c("Me too, my love. But there is a problem. Customs is holding my gold payment and they won't release it without a fee."),
      u("Oh no. How much is the fee?"),
      c("Eight thousand dollars. My bank accounts are frozen while I'm overseas. I'll pay you back the moment I land."),
      u("That's a lot of money, James."),
      c("I know, sweetheart. The agent says the fastest way is a bitcoin machine. There's one at the gas station near you. I'll walk you through it."),
      u("I've never used one of those."),
      c("It's easy. Please don't tell your daughter, she never liked me and she won't understand."),
    ],
  },
  {
    id: "medicare-ssn",
    title: "Medicare card replacement → SSN",
    label: "scam",
    scamType: "government",
    note: "Identity theft, no money requested; calm tone.",
    context: unknownInbound(),
    lines: [
      c("Good afternoon, I'm calling from Medicare. We're issuing new plastic Medicare cards with a chip to all seniors this month."),
      u("Oh, I didn't hear about that."),
      c("Yes, the old paper cards will stop working on the first. I just need to verify a few things to mail yours."),
      u("Okay, what do you need?"),
      c("Can you confirm your full name and date of birth?"),
      u("Margaret Ellen Hughes, March fourth, nineteen forty six."),
      c("Thank you Margaret. And to link the new card I'll need your Social Security number and your current Medicare number."),
      u("Is it safe to give that over the phone?"),
      c("Of course, this is a recorded government line. If we don't verify today your benefits may be interrupted."),
    ],
  },
  {
    id: "utility-shutoff",
    title: "Power company: shutoff in 30 minutes",
    label: "scam",
    scamType: "other_scam",
    note: "Utility impersonation with deadline and odd payment method.",
    context: unknownInbound({ caller_id: "Unknown", local_time: "16:50" }),
    lines: [
      c("This is the disconnection department at the electric company. Your account is past due and a technician is on the way to shut off your power."),
      u("Past due? I pay automatically every month."),
      c("Our system shows the last two payments were rejected. Service will be disconnected in thirty minutes."),
      u("Please don't, I need my oxygen machine."),
      c("I can stop the technician if you make an immediate payment of four hundred eighty dollars."),
      u("Can I pay on the website?"),
      c("The website is down for maintenance. You'll need to send it by Zelle or pick up a prepaid card at the pharmacy and call me back on this number."),
    ],
  },
  {
    id: "amazon-refund-overpay",
    title: "Amazon refund 'overpayment' → send it back",
    label: "scam",
    scamType: "tech_support",
    note: "Refund scam with remote access and a fake overpayment guilt trip.",
    context: unknownInbound({ caller_id: "Amazon Security" }),
    lines: [
      c("Hello, this is Amazon customer security. An iPhone order for one thousand two hundred dollars was placed on your account."),
      u("I didn't order an iPhone!"),
      c("We can refund you. I'll connect to your computer to process the refund, please open the app I'm texting you."),
      u("Okay, it's open. I can see you moving my mouse."),
      c("Please log in to your online banking so I can deposit the refund."),
      u("Okay I'm logged in."),
      c("Oh no, I accidentally typed an extra zero. I refunded you twelve thousand dollars instead of twelve hundred. I could lose my job."),
      u("Oh dear, I don't want you to lose your job. How do I give it back?"),
      c("Our system can only accept the difference in Google Play cards. Please go to the store now and don't hang up, keep me on the line."),
    ],
  },
  {
    id: "virtual-kidnapping",
    title: "Virtual kidnapping ransom (no gift cards)",
    label: "scam",
    scamType: "family_emergency",
    note: "Terror + stay on the line + wire; never names a gift card.",
    context: unknownInbound({ local_time: "11:05" }),
    lines: [
      c("Mom! Mom, help me, please!"),
      u("Sarah? Sarah, what's happening?"),
      c("Listen to me carefully. We have your daughter. If you hang up or call the police, you will never see her again."),
      u("Please don't hurt her. What do you want?"),
      c("You will stay on this phone with me the whole time. You will go to your bank and send money through Western Union."),
      u("How much? I'll do anything."),
      c("Fifteen thousand. If anyone at the bank asks, you are sending it to a cousin. If you say anything else, she pays for it."),
    ],
  },
  {
    id: "ssa-apple-cards-subtle",
    title: "Social Security suspended → 'the cards by the register' (subtle)",
    label: "scam",
    scamType: "government",
    note: "Avoids trigger words: says 'those Apple cards on the rack' and 'the codes'. Tests semantic detection.",
    context: unknownInbound({ caller_id: "SSA" }),
    lines: [
      c("This is the Social Security office. Your number has been linked to drug trafficking in Texas and has been suspended."),
      u("Suspended? I get my checks from Social Security!"),
      c("To keep your benefits active, we will move your funds into a temporary protected status. You'll need to make a security deposit."),
      u("How would I make a deposit?"),
      c("Go to the CVS near you. On the rack by the register there are Apple cards. Pick up five of the two hundred dollar ones."),
      u("And then mail them?"),
      c("No, just call me back from the parking lot and read me the codes on them. And don't speak to the cashier about why, they're not authorized."),
    ],
  },

  // ───────────── BENIGN (tricky) ─────────────
  {
    id: "real-bank-alert",
    title: "Real bank fraud alert (verifies, never asks for numbers)",
    label: "benign",
    scamType: "none",
    note: "Legit: asks yes/no about a charge, explicitly refuses PIN/codes, tells user to call the number on the card.",
    context: { direction: "inbound", caller_in_contacts: false, caller_id: "CHASE", local_time: "10:20", user_profile: "78-year-old, lives alone" },
    lines: [
      c("Hi, this is the Chase fraud prevention team calling about recent activity on your card ending in 0094."),
      c("Before we continue, please know we will never ask for your PIN, password, or a code we text you."),
      u("Okay, thank you for telling me."),
      c("Did you make a purchase of forty two dollars at Kroger this morning?"),
      u("Yes, that was me, groceries."),
      c("Great. And a charge of nine dollars ninety nine at a streaming service?"),
      u("Yes, that's my television thing."),
      c("Perfect, everything looks fine. If you ever want to double check that a call is really from us, hang up and call the number on the back of your card."),
      u("I will. Thank you so much."),
    ],
  },
  {
    id: "birthday-gift-card",
    title: "Son & mom: birthday gift card for grandkid",
    label: "benign",
    scamType: "none",
    note: "Says 'gift card', 'Target', 'read me the code', and has digits, but it's family logistics.",
    context: { direction: "outbound", caller_in_contacts: true, caller_id: "Suvir (son)", local_time: "18:30", user_profile: "78-year-old" },
    lines: [
      u("Hi honey, I'm at Target. What should I get Emma for her birthday?"),
      c("Honestly, Mom, she'd love a gift card. She's saving up for art supplies."),
      u("Okay, they have gift cards right here by the register. Is fifty dollars enough?"),
      c("Fifty is perfect. You could put it in a card with a note."),
      u("I bought it. Should I read you the number so you can put it in her online account?"),
      c("No need, just give it to her at the party Saturday. Keep the receipt in case."),
      u("Okay. What time is the party again?"),
      c("Two o'clock at our place. I'll pick you up at one thirty."),
    ],
  },
  {
    id: "pharmacy-refill",
    title: "Pharmacy refill reminder",
    label: "benign",
    scamType: "none",
    note: "Asks for date of birth to verify, which is normal for a pharmacy.",
    context: { direction: "inbound", caller_in_contacts: true, caller_id: "CVS Pharmacy", local_time: "11:00", user_profile: "78-year-old" },
    lines: [
      c("Hi, this is Linda from the CVS pharmacy on Main Street. Your blood pressure prescription is ready for pickup."),
      u("Oh good, I was running low."),
      c("Just to confirm I have the right person, can you tell me your date of birth?"),
      u("March fourth, forty six."),
      c("Thank you. Your copay is five dollars, you can pay when you pick it up. We're open until nine."),
      u("I'll come by this afternoon. Thank you, Linda."),
    ],
  },
  {
    id: "doctor-appointment",
    title: "Doctor's office appointment reschedule",
    label: "benign",
    scamType: "none",
    note: "Routine logistics.",
    context: { direction: "inbound", caller_in_contacts: true, caller_id: "Dr. Patel's Office", local_time: "09:15", user_profile: "78-year-old" },
    lines: [
      c("Good morning, this is Dr. Patel's office calling about your appointment on Thursday."),
      u("Yes, at ten o'clock?"),
      c("The doctor has a conflict. Could we move you to Friday at eleven instead?"),
      u("Friday works. My daughter will drive me."),
      c("Wonderful. Please bring your insurance card and a list of your medications."),
      u("Will do. See you Friday."),
    ],
  },
  {
    id: "friend-urgent-ride",
    title: "Friend urgently needs a ride to the hospital",
    label: "benign",
    scamType: "none",
    note: "Real urgency and emotion, but no money and a known contact.",
    context: { direction: "inbound", caller_in_contacts: true, caller_id: "Barbara (neighbor)", local_time: "07:45", user_profile: "78-year-old" },
    lines: [
      c("Ruth, it's Barbara. I'm so sorry to call this early, Harold fell in the bathroom."),
      u("Oh no! Is he okay?"),
      c("He's conscious but his hip hurts badly. The ambulance is coming but I need to follow them to the hospital right now and my car won't start."),
      u("I'll come get you right away. Give me five minutes."),
      c("Thank you, you're a lifesaver. Please hurry."),
      u("I'm grabbing my keys now. Don't worry, we'll get there."),
    ],
  },
  {
    id: "user-pays-credit-card-bill",
    title: "User calls card company to pay bill (reads card number)",
    label: "benign",
    scamType: "none",
    note: "Contains a full card number and payment, but the USER initiated the call to the number on her card.",
    context: { direction: "outbound", caller_in_contacts: false, caller_id: "1-800-555-0177", local_time: "13:00", user_profile: "78-year-old" },
    lines: [
      c("Thank you for calling Visa card services. How can I help you today?"),
      u("Hi, I'd like to pay my bill over the phone. I got the statement in the mail."),
      c("Of course. Can you please enter or say the card number you're calling about?"),
      u("It's 4147 2023 8812 0094."),
      c("Thank you. Your statement balance is two hundred twelve dollars. Would you like to pay it from your checking account on file?"),
      u("Yes please, the whole balance."),
      c("Done. You'll get a confirmation letter. Anything else?"),
      u("No, that's all. Thank you."),
    ],
  },
  {
    id: "contractor-deposit",
    title: "Roofer discusses deposit for scheduled job",
    label: "benign",
    scamType: "none",
    note: "Money and a deadline, but a known local contractor and normal terms (check, invoice).",
    context: { direction: "inbound", caller_in_contacts: true, caller_id: "Miller Roofing", local_time: "15:30", user_profile: "78-year-old" },
    lines: [
      c("Hi, it's Tom from Miller Roofing following up on the estimate we did last week."),
      u("Yes, my son and I looked it over. We'd like to go ahead."),
      c("Great. We can start the week of the twelfth. We ask for a ten percent deposit to order materials."),
      u("Can I pay by check?"),
      c("Check is perfect. I'll email your son the contract and invoice, and you can mail the check or give it to the crew the first day."),
      u("That sounds fine. Thank you, Tom."),
    ],
  },
  {
    id: "daughter-house-closing-wire",
    title: "Daughter discussing wiring house down payment",
    label: "benign",
    scamType: "none",
    note: "Wire transfer + deadline, but it's family discussing a real closing and verifying with the title company.",
    context: { direction: "outbound", caller_in_contacts: true, caller_id: "Priya (daughter)", local_time: "20:00", user_profile: "78-year-old" },
    lines: [
      u("Hi sweetie, how's the house stuff going?"),
      c("Stressful! Closing is Friday and we have to wire the down payment to the title company by Thursday."),
      u("Do you need any help with that?"),
      c("No, we're okay, thanks Mom. Our agent told us to call the title company at the number on their website to confirm the wire instructions, because there are scams."),
      u("Good, I read about those in the paper. Be careful."),
      c("We will. You should come see it after we move in, you'll love the garden."),
    ],
  },
];

for (const t of TRANSCRIPTS) {
  t.dataset ??= "clean";
  t.difficulty ??= t.id.includes("subtle") || t.label === "benign" ? "medium" : "easy";
  t.tags ??= t.label === "benign" ? ["hard-negative"] : [];
}

export const CLEAN_TRANSCRIPTS = TRANSCRIPTS;
export const TRANSCRIPT_BY_ID = Object.fromEntries(TRANSCRIPTS.map((t) => [t.id, t]));

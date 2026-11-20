// /live?call=<callId>: the stage view of a live call (or the most recently active call when omitted).
import LiveCall from "@/components/LiveCall";

export const dynamic = "force-dynamic";
export const metadata = { title: "Pip · live call" };

export default async function LivePage({ searchParams }: { searchParams: Promise<{ call?: string }> }) {
  const { call } = await searchParams;
  return <LiveCall callId={call} />;
}

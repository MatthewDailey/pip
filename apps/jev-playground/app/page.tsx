import Playground from "@/components/Playground";
import { status } from "@/lib/detectors";
import { TRANSCRIPTS } from "@/data/transcripts";
import { MESSY_TRANSCRIPTS } from "@/data/transcripts-messy";

export const dynamic = "force-dynamic";

export default function Page() {
  return <Playground status={status()} transcripts={[...TRANSCRIPTS, ...MESSY_TRANSCRIPTS]} />;
}

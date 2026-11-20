import ScreenLab, { type LabScreen } from "@/components/ScreenLab";
import data from "@/data/screens/screens.json";

export const dynamic = "force-dynamic";

export default function ScreensPage() {
  const screens = (data.screens as LabScreen[]).map((s) => ({ ...s }));
  return <ScreenLab screens={screens} hasKey={Boolean(process.env.JEV_API_KEY)} />;
}

// POST /api/screens/classify: device-facing alias of POST /api/screens.
// The Android app posts screenshot + accessibility text here; the lab UI can keep using /api/screens.
export { POST } from "../route";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
